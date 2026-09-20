package com.aurora.music.playback.network

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class MediaGrant internal constructor(
    val token: String,
    val expiresAtMillis: Long,
) {
    val path: String get() = "/media/$token"
    fun url(host: String, port: Int): String = URI("http", null, host, port, path, null, null).toASCIIString()
    override fun toString(): String = "MediaGrant(expiresAtMillis=$expiresAtMillis)"
}

data class MediaTransferStats(val requests: Long, val bytesServed: Long, val activeGrants: Int)

class ScopedMediaServer(
    private val clock: () -> Long = System::currentTimeMillis,
) : Closeable {
    private data class Entry(
        val file: File,
        val mimeType: String,
        val expires: Long,
        val length: Long,
        val modified: Long,
        val allowedClient: InetAddress?,
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private val requests = AtomicLong()
    private val bytes = AtomicLong()
    private val random = SecureRandom()
    private val server = BoundedHttpServer(::handle, maxBodyBytes = 0, requestTimeoutMillis = 120_000)
    val port: Int get() = server.port
    val stats: MediaTransferStats get() {
        removeExpired()
        return MediaTransferStats(requests.get(), bytes.get(), entries.size)
    }

    fun start(bindAddress: InetAddress = InetAddress.getByName("0.0.0.0"), port: Int = 0): Int =
        server.start(bindAddress, port)

    @Synchronized fun grant(
        file: File,
        mimeType: String,
        lifetimeMillis: Long = 30 * 60 * 1_000L,
        allowedClient: InetAddress? = null,
    ): MediaGrant {
        require(lifetimeMillis in 1_000..4 * 60 * 60 * 1_000L) { "Invalid media lifetime." }
        require(mimeType.matches(Regex("audio/[A-Za-z0-9.+-]+(?:;[A-Za-z0-9= -]+)*"))) { "Unsupported media type." }
        val canonical = file.canonicalFile
        require(canonical.isFile && canonical.canRead() && canonical.length() > 0) { "Audio file unavailable." }
        removeExpired()
        check(entries.size < 100) { "Too many shared tracks." }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        val expires = clock() + lifetimeMillis
        entries[token] = Entry(canonical, mimeType, expires, canonical.length(), canonical.lastModified(), allowedClient)
        return MediaGrant(token, expires)
    }

    fun revoke(token: String) { entries.remove(token) }
    fun revokeAll() { entries.clear() }

    private fun removeExpired() {
        val now = clock()
        entries.entries.removeAll { it.value.expires <= now }
    }

    private fun handle(request: HttpRequest): HttpResponse {
        val common = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
            "Access-Control-Allow-Headers" to "Range, Content-Type, GetContentFeatures.DLNA.ORG, TransferMode.DLNA.ORG",
            "Access-Control-Expose-Headers" to "Content-Length, Content-Range, Accept-Ranges, Content-Type",
            "Accept-Ranges" to "bytes",
        )
        if (!request.path.matches(Regex("/media/[A-Za-z0-9_-]{43}"))) return HttpResponse(404, common)
        val token = request.path.substringAfterLast('/')
        val entry = entries[token] ?: return HttpResponse(404, common)
        if (entry.expires <= clock()) {
            entries.remove(token, entry)
            return HttpResponse(410, common)
        }
        if (entry.allowedClient != null && entry.allowedClient != request.remoteAddress) return HttpResponse(403, common)
        if (request.method == "OPTIONS") return HttpResponse(204, common)
        if (request.method !in setOf("GET", "HEAD")) return HttpResponse(405, common + ("Allow" to "GET, HEAD, OPTIONS"))
        if (!entry.file.isFile || entry.file.length() != entry.length || entry.file.lastModified() != entry.modified) {
            entries.remove(token, entry)
            return HttpResponse(410, common)
        }
        val rangeText = request.headers["range"]
        val range = if (rangeText == null) 0L..(entry.length - 1) else parseRange(rangeText, entry.length)
            ?: return HttpResponse(416, common + ("Content-Range" to "bytes */${entry.length}"))
        val length = range.last - range.first + 1
        val headers = common.toMutableMap().apply {
            put("Content-Type", entry.mimeType)
            put("transferMode.dlna.org", "Streaming")
            put("contentFeatures.dlna.org", "DLNA.ORG_OP=01;DLNA.ORG_CI=1;DLNA.ORG_FLAGS=01700000000000000000000000000000")
            if (rangeText != null) put("Content-Range", "bytes ${range.first}-${range.last}/${entry.length}")
        }
        requests.incrementAndGet()
        return HttpResponse(if (rangeText == null) 200 else 206, headers, bodyLength = length, writeBody = { output ->
            RandomAccessFile(entry.file, "r").use { input ->
                input.seek(range.first)
                var remaining = length
                val buffer = ByteArray(32 * 1_024)
                while (remaining > 0) {
                    if (entries[token] !== entry || entry.expires <= clock()) throw IOException("Media access expired.")
                    val read = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    if (read < 0) throw IOException("Audio file changed.")
                    output.write(buffer, 0, read)
                    bytes.addAndGet(read.toLong())
                    remaining -= read
                }
            }
        })
    }

    override fun close() {
        revokeAll()
        server.close()
    }

    companion object {
        internal fun parseRange(value: String, length: Long): LongRange? {
            if (length <= 0 || !value.startsWith("bytes=")) return null
            val match = Regex("bytes=([0-9]*)-([0-9]*)").matchEntire(value) ?: return null
            val first = match.groupValues[1]
            val last = match.groupValues[2]
            if (first.isEmpty()) {
                val suffix = last.toLongOrNull()?.takeIf { it > 0 } ?: return null
                return maxOf(0, length - suffix)..(length - 1)
            }
            val start = first.toLongOrNull()?.takeIf { it in 0 until length } ?: return null
            val end = if (last.isEmpty()) length - 1 else last.toLongOrNull()?.coerceAtMost(length - 1) ?: return null
            return if (end >= start) start..end else null
        }
    }
}
