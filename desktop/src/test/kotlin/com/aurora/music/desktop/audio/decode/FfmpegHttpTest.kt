package com.aurora.music.desktop.audio.decode

import com.sun.net.httpserver.Headers
import org.bytedeco.ffmpeg.global.avutil.AVERROR_HTTP_NOT_FOUND
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.abs

class FfmpegHttpTest {
    @Test fun requestsCarryTheConfiguredHeadersAndUserAgent() {
        val mp3 = TestAssets.file("gapless.mp3").readBytes()
        val requests = CopyOnWriteArrayList<Headers>()
        TestServer { exchange ->
            requests += exchange.requestHeaders
            exchange.sendResponseHeaders(200, mp3.size.toLong())
            exchange.responseBody.write(mp3)
        }.use { server ->
            val http = HttpOptions(headers = mapOf("X-Aurora-Test" to "desktop", "Authorization" to "Bearer abc"), userAgent = "AuroraTest/1.0")
            FfmpegDecoder.open("${server.base}/rest/stream.view?id=1&t=token", http).use { decoder ->
                assertEquals("mp3", decoder.info.codec)
                val samples = decodeAll(decoder)
                assertEquals(10_001 * 2, samples.size)
                val reference = TestAssets.reference("gapless-mp3.pcm")
                assertTrue(samples.indices.all { abs(toPcm16(samples[it]) - reference[it]) <= 1 })
            }
        }
        assertTrue(requests.isNotEmpty())
        for (headers in requests) {
            assertEquals("desktop", headers.getFirst("X-Aurora-Test"))
            assertEquals("Bearer abc", headers.getFirst("Authorization"))
            assertEquals("AuroraTest/1.0", headers.getFirst("User-Agent"))
        }
    }

    @Test fun interruptCancelsAStalledNetworkRead() {
        val wav = wavBytes(48_000, 2, 16, 960_000) { f, c -> (f + c) % 1000 }
        val sent = CountDownLatch(1)
        val release = CountDownLatch(1)
        TestServer { exchange ->
            exchange.sendResponseHeaders(200, wav.size.toLong())
            exchange.responseBody.write(wav, 0, 1536 * 1024)
            exchange.responseBody.flush()
            sent.countDown()
            release.await(30, TimeUnit.SECONDS)
        }.use { server ->
            val cancel = AtomicBoolean()
            val decoded = AtomicLong()
            val failure = AtomicReference<Throwable>()
            val worker = thread {
                try {
                    FfmpegDecoder.open("${server.base}/slow.wav", HttpOptions(timeoutMs = 60_000), cancel).use { decoder ->
                        val block = DoubleArray(2048)
                        while (true) decoded.addAndGet(decoder.read(block).takeIf { it >= 0 }?.toLong() ?: break)
                    }
                } catch (t: Throwable) {
                    failure.set(t)
                }
            }
            try {
                assertTrue(sent.await(10, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (decoded.get() == 0L && System.nanoTime() < deadline) Thread.sleep(20)
                Thread.sleep(500)
                val stalled = decoded.get()
                Thread.sleep(300)
                assertTrue("$stalled ${failure.get()}", stalled in 1 until 960_000)
                assertEquals(stalled, decoded.get())
                assertTrue(worker.isAlive)
                val started = System.nanoTime()
                cancel.set(true)
                worker.join(5_000)
                assertFalse(worker.isAlive)
                assertTrue("${failure.get()}", failure.get() is DecoderInterruptedException)
                assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2))
            } finally {
                release.countDown()
                cancel.set(true)
                worker.join(5_000)
            }
        }
    }

    @Test fun interruptCancelsAStalledOpen() {
        val wav = wavBytes(48_000, 2, 16, 960_000) { f, c -> (f + c) % 1000 }
        for (headerBytes in listOf(0, 64 * 1024)) {
            val release = CountDownLatch(1)
            val requested = CountDownLatch(1)
            TestServer { exchange ->
                if (headerBytes > 0) {
                    exchange.sendResponseHeaders(200, wav.size.toLong())
                    exchange.responseBody.write(wav, 0, headerBytes)
                    exchange.responseBody.flush()
                }
                requested.countDown()
                release.await(30, TimeUnit.SECONDS)
            }.use { server ->
                val cancel = AtomicBoolean()
                val failure = AtomicReference<Throwable>()
                val worker = thread {
                    try {
                        FfmpegDecoder.open("${server.base}/hang.wav", HttpOptions(timeoutMs = 60_000), cancel).close()
                    } catch (t: Throwable) {
                        failure.set(t)
                    }
                }
                try {
                    assertTrue(requested.await(10, TimeUnit.SECONDS))
                    Thread.sleep(300)
                    assertTrue(worker.isAlive)
                    cancel.set(true)
                    worker.join(5_000)
                    assertFalse(worker.isAlive)
                    assertTrue("$headerBytes ${failure.get()}", failure.get() is DecoderInterruptedException)
                } finally {
                    release.countDown()
                    cancel.set(true)
                    worker.join(5_000)
                }
            }
        }
    }

    @Test fun manyConcurrentOpensKeepTheirOwnInterrupt() {
        val count = 16
        val release = CountDownLatch(1)
        val requested = CountDownLatch(count)
        TestServer { requested.countDown(); release.await(30, TimeUnit.SECONDS) }.use { server ->
            val cancels = List(count) { AtomicBoolean() }
            val failures = List(count) { AtomicReference<Throwable>() }
            val workers = List(count) { i ->
                thread {
                    try {
                        FfmpegDecoder.open("${server.base}/hang-$i.wav", HttpOptions(timeoutMs = 60_000), cancels[i]).close()
                    } catch (t: Throwable) {
                        failures[i].set(t)
                    }
                }
            }
            try {
                assertTrue(requested.await(10, TimeUnit.SECONDS))
                val (first, second) = workers.indices.partition { it % 2 == 0 }
                first.forEach { cancels[it].set(true) }
                first.forEach { workers[it].join(5_000) }
                assertTrue(first.none { workers[it].isAlive })
                assertTrue(second.all { workers[it].isAlive })
                second.forEach { cancels[it].set(true) }
                second.forEach { workers[it].join(5_000) }
                assertTrue(workers.none { it.isAlive })
                assertTrue(failures.map { it.get() }.toString(), failures.all { it.get() is DecoderInterruptedException })
            } finally {
                release.countDown()
                cancels.forEach { it.set(true) }
                workers.forEach { it.join(5_000) }
            }
        }
    }

    @Test fun httpErrorsKeepTheCodeAndHideTheQuery() {
        TestServer { exchange -> exchange.sendResponseHeaders(404, -1) }.use { server ->
            val failure = runCatching { FfmpegDecoder.open("${server.base}/missing.flac?api_key=secret-token") }.exceptionOrNull()
            assertTrue("$failure", failure is DecoderException)
            assertEquals(AVERROR_HTTP_NOT_FOUND, (failure as DecoderException).code)
            assertFalse(failure.message!!.contains("secret-token"))
            assertTrue(failure.message!!, failure.message!!.contains("/missing.flac"))
        }
    }

    @Test fun aTruncatedResponseFailsInsteadOfEndingEarly() {
        val wav = wavBytes(48_000, 2, 16, 960_000) { f, c -> (f + c) % 1000 }
        TestServer { exchange ->
            exchange.sendResponseHeaders(200, wav.size.toLong())
            exchange.responseBody.write(wav, 0, wav.size / 2)
        }.use { server ->
            FfmpegDecoder.open("${server.base}/cut.wav", HttpOptions(reconnect = false)).use { decoder ->
                val block = DoubleArray(4096)
                var decoded = 0L
                val failure = runCatching { while (true) decoded += decoder.read(block).takeIf { it >= 0 } ?: break }.exceptionOrNull()
                assertTrue("$failure after $decoded", failure is DecoderException && failure !is DecoderInterruptedException)
                assertTrue(decoded in 1 until 960_000)
            }
        }
    }
}
