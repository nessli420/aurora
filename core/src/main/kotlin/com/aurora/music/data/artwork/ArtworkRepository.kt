package com.aurora.music.data.artwork

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

interface ArtworkImages {
    fun size(bytes: ByteArray): Pair<Int, Int>?
    fun isPlaceholder(bytes: ByteArray): Boolean
}

class ArtworkRepository(
    private val root: File,
    private val openStream: (String) -> InputStream?,
    private val images: ArtworkImages,
    private val offline: () -> Boolean,
    private val enabled: suspend () -> Boolean,
    private val separators: suspend () -> com.aurora.music.data.ArtistSeparators,
    private val client: CoverArtClient = CoverArtClient(),
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build(),
) {
    private val gate = Semaphore(3)
    // lookups hold gate through the 1.1 s musicbrainz limit, server covers must not queue behind them
    private val serverGate = Semaphore(6)
    private val locks = Array(64) { Mutex() }
    private val matchLocks = Array(64) { Mutex() }
    private val storage = Any()
    private var generation = 0
    private var savesSinceTrim = 0
    private val _bytes = MutableStateFlow(0L)
    val bytes = _bytes.asStateFlow()
    init { CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { synchronized(storage) { trim() } } }

    suspend fun <T> open(request: ArtworkRequest, read: (File) -> T): T = withContext(Dispatchers.IO) {
        val key = request.key
        locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            val image = File(root, "$key.img")
            val miss = File(root, "$key.miss")
            val match = File(root, "match-${request.matchKey}.img")
            val serverArt = request.original?.takeIf(ArtworkUrls::isServerArt)
            val version = synchronized(storage) { generation }
            // A newly embedded local cover takes precedence over an older metadata match.
            val original = request.original?.takeIf { it.isNotBlank() && serverArt == null }?.let { uri ->
                try { openStream(uri)?.use(::readImage) }
                catch (_: FileNotFoundException) { null }
                catch (_: SecurityException) { null }
            }
            if (original != null) return@withLock saveAndOpen(image, original, version, read)
            synchronized(storage) {
                val freshUntil = runCatching { File(root, "$key.fresh").readText().toLong() }.getOrDefault(0L)
                if (File(root, "$key.placeholder").isFile && match.isFile) {
                    return@withLock saveAndOpen(image, match.readBytes(), version, read)
                }
                if (image.isFile && (serverArt == null || offline() || freshUntil > System.currentTimeMillis())) {
                    image.setLastModified(System.currentTimeMillis())
                    // trim goes oldest first, an evicted sidecar would force a re-download of a cover in use
                    File(root, "$key.fresh").setLastModified(System.currentTimeMillis())
                    return@withLock read(image)
                }
            }
            val supplied = if (serverArt != null && !offline()) {
                try { serverGate.withPermit { fetchImage(serverArt) } }
                catch (error: IOException) {
                    synchronized(storage) {
                        if (image.isFile) return@withLock read(image)
                    }
                    throw error
                }
            } else null
            if (supplied != null && !images.isPlaceholder(supplied)) return@withLock saveAndOpen(image, supplied, version, read, SERVER_ART_TTL)
            synchronized(storage) {
                if (match.isFile) return@withLock saveAndOpen(image, match.readBytes(), version, read)
            }
            if (offline() || !enabled()) {
                if (supplied != null) return@withLock saveAndOpen(image, supplied, version, read, RETRY_TTL, placeholder = true)
                throw FileNotFoundException("Artwork lookup unavailable")
            }
            synchronized(storage) {
                if (miss.isFile && System.currentTimeMillis() - miss.lastModified() < MISS_TTL) throw FileNotFoundException("No matched artwork")
            }
            gate.withPermit {
                // Album and track cover IDs can differ even when their metadata is identical.
                matchLocks[(request.matchKey.hashCode() and Int.MAX_VALUE) % matchLocks.size].withLock lookup@{
                    synchronized(storage) {
                        if (match.isFile) return@lookup saveAndOpen(image, match.readBytes(), version, read)
                    }
                    try {
                        val candidates = client.candidates(request, separators())
                        for (url in candidates) {
                            if (offline()) throw FileNotFoundException("Offline")
                            val data = try { fetchImage(url) } catch (_: IOException) {
                                delay(750)
                                fetchImage(url)
                            }
                            if (data != null) {
                                saveAndOpen(match, data, version, {})
                                return@lookup saveAndOpen(image, data, version, read)
                            }
                        }
                    } catch (error: IOException) {
                        // Keep the server image usable during a provider outage; retry shortly.
                        if (supplied != null) return@lookup saveAndOpen(image, supplied, version, read, RETRY_TTL, placeholder = true)
                        throw error
                    }
                    if (supplied != null) return@lookup saveAndOpen(image, supplied, version, read, placeholder = true)
                    synchronized(storage) {
                        if (version == generation) {
                            root.mkdirs()
                            miss.writeText("")
                            trim()
                        }
                    }
                    throw FileNotFoundException("No matching cover art")
                }
            }
        }
    }

    private fun fetchImage(url: String): ByteArray? =
        http.newCall(Request.Builder().url(url).header("User-Agent", CoverArtClient.USER_AGENT).build()).execute().use { response ->
            when {
                response.code == 404 -> null
                !response.isSuccessful -> throw IOException("Artwork HTTP ${response.code}")
                else -> response.body?.byteStream()?.use(::readImage)
            }
        }

    private fun readImage(input: InputStream): ByteArray? {
        val bytes = input.readBytesLimited(MAX_IMAGE_BYTES) ?: return null
        val (width, height) = images.size(bytes) ?: return null
        return bytes.takeIf { width in 1..12000 && height in 1..12000 }
    }

    private fun <T> saveAndOpen(file: File, data: ByteArray, version: Int, read: (File) -> T, ttl: Long = MISS_TTL, placeholder: Boolean = false): T = synchronized(storage) {
        if (version != generation) throw FileNotFoundException("Artwork cache cleared")
        root.mkdirs()
        val temporary = File(root, file.name + ".tmp")
        val replaced = file.length()
        try {
            temporary.writeBytes(data)
            // renameTo can't replace an existing file on windows
            try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { temporary.delete() }
        _bytes.value += data.size - replaced
        File(root, file.nameWithoutExtension + ".miss").delete()
        File(root, file.nameWithoutExtension + ".fresh").writeText((System.currentTimeMillis() + ttl).toString())
        File(root, file.nameWithoutExtension + ".placeholder").let { if (placeholder) it.writeText("") else it.delete() }
        val opened = read(file)
        // a full trim stats the whole folder under this lock, so not on every save
        if (_bytes.value > LIMIT_BYTES || ++savesSinceTrim >= SAVES_PER_TRIM) {
            savesSinceTrim = 0
            trim(keep = file)
        }
        opened
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        synchronized(storage) {
            generation++
            root.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
            _bytes.value = root.listFiles()?.sumOf { it.length() } ?: 0L
        }
    }

    private fun trim(keep: File? = null) {
        val files = root.listFiles()?.filter { it.isFile }?.map { it to it.lastModified() }?.sortedBy { it.second }?.map { it.first }.orEmpty()
        var bytes = files.sumOf { it.length() }
        var count = files.size
        // going below the limit keeps a full cache from trimming again on the very next save
        val target = if (bytes > LIMIT_BYTES) LIMIT_BYTES / 8 * 7 else LIMIT_BYTES
        for (file in files) {
            if (file == keep) continue
            if (bytes <= target && count <= 2500) break
            val size = file.length()
            if (file.delete()) { bytes -= size; count-- }
        }
        _bytes.value = bytes
    }

    companion object {
        const val LIMIT_BYTES = 64L * 1024 * 1024
        private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
        private const val MISS_TTL = 24L * 60 * 60 * 1000
        private const val RETRY_TTL = 60L * 1000
        private const val SERVER_ART_TTL = 30L * 24 * 60 * 60 * 1000
        private const val SAVES_PER_TRIM = 32
    }
}

internal fun InputStream.readBytesLimited(limit: Int): ByteArray? {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val count = read(buffer)
        if (count == -1) return output.toByteArray()
        if (output.size() + count > limit) return null
        output.write(buffer, 0, count)
    }
}
