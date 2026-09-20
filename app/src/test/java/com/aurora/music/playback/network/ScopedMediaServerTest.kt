package com.aurora.music.playback.network

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ScopedMediaServerTest {
    private val loopback = InetAddress.getByName("127.0.0.1")

    @Test fun grantServesOnlyItsFileAndRevocationStopsAccess() {
        val file = audioFile()
        try {
            ScopedMediaServer().use { server ->
                server.start(loopback)
                val grant = server.grant(file, "audio/wav")
                assertFalse(grant.toString().contains(grant.token))
                assertEquals(200, request(server.port, grant.path).status)
                assertArrayEquals(file.readBytes(), request(server.port, grant.path).body)
                assertEquals(404, request(server.port, "/media/../${file.name}").status)
                assertEquals(404, request(server.port, grant.path + "?token=other").status)
                assertEquals(404, request(server.port, "/${file.absolutePath}").status)
                server.revoke(grant.token)
                assertEquals(404, request(server.port, grant.path).status)
                assertTrue(file.exists())
            }
        } finally { file.delete() }
    }

    @Test fun rangesHeadCorsAndMetricsAreConsistent() {
        val file = audioFile()
        try {
            ScopedMediaServer().use { server ->
                server.start(loopback)
                val grant = server.grant(file, "audio/wav")
                val head = request(server.port, grant.path, "HEAD")
                assertEquals(200, head.status)
                assertEquals("256", head.headers["content-length"])
                assertTrue(head.body.isEmpty())
                val range = request(server.port, grant.path, headers = mapOf("Range" to "bytes=10-19"))
                assertEquals(206, range.status)
                assertEquals("bytes 10-19/256", range.headers["content-range"])
                assertArrayEquals(file.readBytes().sliceArray(10..19), range.body)
                assertEquals("*", range.headers["access-control-allow-origin"])
                assertEquals(204, request(server.port, grant.path, "OPTIONS").status)
                assertEquals(405, request(server.port, grant.path, "PUT").status)
                val suffix = request(server.port, grant.path, headers = mapOf("Range" to "bytes=-3"))
                assertArrayEquals(file.readBytes().takeLast(3).toByteArray(), suffix.body)
                assertEquals(13L, server.stats.bytesServed)
                assertEquals(3L, server.stats.requests)
                assertEquals(1, server.stats.activeGrants)
            }
        } finally { file.delete() }
    }

    @Test fun invalidOrMultipleRangesFailWithoutFallingBackToWholeFile() {
        val file = audioFile()
        try {
            ScopedMediaServer().use { server ->
                server.start(loopback)
                val grant = server.grant(file, "audio/wav")
                listOf("bytes=999-", "bytes=9-2", "bytes=0-1,4-5", "bytes=-0", "bytes=999999999999999999999-").forEach {
                    val result = request(server.port, grant.path, headers = mapOf("Range" to it))
                    assertEquals(it, 416, result.status)
                    assertEquals("bytes */256", result.headers["content-range"])
                    assertTrue(result.body.isEmpty())
                }
                assertEquals(0L, server.stats.bytesServed)
            }
        } finally { file.delete() }
    }

    @Test fun expiryAudienceAndChangedFileAreRejected() {
        var time = 1_000L
        val file = audioFile()
        try {
            ScopedMediaServer { time }.use { server ->
                server.start(loopback)
                val expires = server.grant(file, "audio/wav", lifetimeMillis = 1_000)
                val audience = server.grant(file, "audio/wav", allowedClient = InetAddress.getByName("192.168.1.2"))
                assertEquals(403, request(server.port, audience.path).status)
                time = 2_001
                assertEquals(410, request(server.port, expires.path).status)
                val changed = server.grant(file, "audio/wav")
                file.appendBytes(byteArrayOf(1))
                assertEquals(410, request(server.port, changed.path).status)
            }
        } finally { file.delete() }
    }

    @Test fun wholeQueueGrantsAreBoundedAndReusableAfterRevocation() {
        val file = audioFile()
        try {
            ScopedMediaServer().use { server ->
                val grants = (0 until 100).map { server.grant(file, "audio/wav") }
                assertEquals(100, server.stats.activeGrants)
                assertThrows(IllegalStateException::class.java) { server.grant(file, "audio/wav") }
                server.revoke(grants.first().token)
                server.grant(file, "audio/wav")
                assertEquals(100, server.stats.activeGrants)
                server.revokeAll()
                assertEquals(0, server.stats.activeGrants)
            }
        } finally { file.delete() }
    }

    @Test fun listenerBoundsRequestsAndCanRestart() {
        var calls = 0
        BoundedHttpServer(handler = { calls++; HttpResponse(200, body = it.body) }, maxBodyBytes = 3).use { server ->
            server.start(loopback)
            val oversized = raw(server.port, "POST / HTTP/1.1\r\nContent-Length: 4\r\n\r\n1234")
            assertEquals(413, oversized.status)
            assertEquals(400, raw(server.port, "POST / HTTP/1.1\r\nContent-Length: 2\r\nContent-Length: 1\r\n\r\n12").status)
            assertEquals(400, raw(server.port, "POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n").status)
            assertEquals(431, raw(server.port, "GET / HTTP/1.1\r\nX-Large: ${"a".repeat(17_000)}\r\n\r\n").status)
            assertEquals(0, calls)
            assertArrayEquals("abc".toByteArray(), raw(server.port, "POST / HTTP/1.1\r\nContent-Length: 3\r\n\r\nabc").body)
            server.close()
            assertEquals(0, server.port)
            server.start(loopback)
            assertEquals(200, request(server.port, "/").status)
        }
    }

    @Test fun busyListenerDoesNotQueueUnboundedConnections() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        BoundedHttpServer(handler = {
            entered.countDown()
            release.await(3, TimeUnit.SECONDS)
            HttpResponse(200)
        }, maxConcurrentRequests = 1).use { server ->
            server.start(loopback)
            Socket(loopback, server.port).use { occupied ->
                occupied.getOutputStream().write("GET / HTTP/1.1\r\n\r\n".toByteArray())
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                Socket(loopback, server.port).use { rejected ->
                    rejected.soTimeout = 1_000
                    assertEquals(-1, rejected.getInputStream().read())
                }
                release.countDown()
            }
        }
    }

    private fun audioFile(): File = File.createTempFile("aurora-network-", ".wav").apply {
        writeBytes(ByteArray(256) { it.toByte() })
    }

    private data class Result(val status: Int, val headers: Map<String, String>, val body: ByteArray)
    private fun request(port: Int, path: String, method: String = "GET", headers: Map<String, String> = emptyMap()): Result =
        raw(port, "$method $path HTTP/1.1\r\nHost: localhost\r\n" + headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } + "\r\n")

    private fun raw(port: Int, request: String): Result = Socket(loopback, port).use { socket ->
        socket.soTimeout = 2_000
        socket.getOutputStream().write(request.toByteArray())
        val bytes = socket.getInputStream().readBytes()
        val boundary = bytes.indices.first { index -> index + 3 < bytes.size &&
            bytes[index] == 13.toByte() && bytes[index + 1] == 10.toByte() &&
            bytes[index + 2] == 13.toByte() && bytes[index + 3] == 10.toByte() }
        val lines = bytes.copyOfRange(0, boundary).toString(Charsets.US_ASCII).split("\r\n")
        Result(lines.first().split(' ')[1].toInt(), lines.drop(1).associate {
            it.substringBefore(':').lowercase() to it.substringAfter(':').trim()
        }, bytes.copyOfRange(boundary + 4, bytes.size))
    }
}
