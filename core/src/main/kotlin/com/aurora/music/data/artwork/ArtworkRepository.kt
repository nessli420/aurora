package com.aurora.music.data.artwork

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
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
import java.util.concurrent.TimeUnit

class ArtworkRepository internal constructor(
    context: Context,
    private val offline: () -> Boolean,
    private val enabled: suspend () -> Boolean,
    private val separators: suspend () -> com.aurora.music.data.ArtistSeparators,
    private val client: CoverArtClient = CoverArtClient(),
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build(),
    private val root: File = File(context.cacheDir, "metadata-artwork"),
) {
    private val resolver = context.applicationContext.contentResolver
    private val gate = Semaphore(3)
    private val locks = Array(64) { Mutex() }
    private val matchLocks = Array(64) { Mutex() }
    private val storage = Any()
    private var generation = 0
    private val _bytes = MutableStateFlow(0L)
    val bytes = _bytes.asStateFlow()
    init { CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { synchronized(storage) { trim() } } }

    internal suspend fun open(request: ArtworkRequest): ParcelFileDescriptor = withContext(Dispatchers.IO) {
        val key = request.key
        locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            val image = File(root, "$key.img")
            val miss = File(root, "$key.miss")
            val match = File(root, "match-${request.matchKey}.img")
            val serverArt = request.original?.takeIf(ArtworkUrls::isServerArt)
            val version = synchronized(storage) { generation }
            // A newly embedded local cover takes precedence over an older metadata match.
            val original = request.original?.takeIf { it.isNotBlank() && serverArt == null }?.let { uri ->
                try { resolver.openInputStream(Uri.parse(uri))?.use(::readImage) }
                catch (_: FileNotFoundException) { null }
                catch (_: SecurityException) { null }
            }
            if (original != null) return@withLock saveAndOpen(image, original, version)
            synchronized(storage) {
                val freshUntil = runCatching { File(root, "$key.fresh").readText().toLong() }.getOrDefault(0L)
                if (File(root, "$key.placeholder").isFile && match.isFile) {
                    return@withLock saveAndOpen(image, match.readBytes(), version)
                }
                if (image.isFile && (serverArt == null || offline() || freshUntil > System.currentTimeMillis())) {
                    image.setLastModified(System.currentTimeMillis())
                    return@withLock ParcelFileDescriptor.open(image, ParcelFileDescriptor.MODE_READ_ONLY)
                }
            }
            val supplied = if (serverArt != null && !offline()) {
                try { gate.withPermit { fetchImage(serverArt) } }
                catch (error: IOException) {
                    synchronized(storage) {
                        if (image.isFile) return@withLock ParcelFileDescriptor.open(image, ParcelFileDescriptor.MODE_READ_ONLY)
                    }
                    throw error
                }
            } else null
            if (supplied != null && !NavidromePlaceholder.matches(supplied)) return@withLock saveAndOpen(image, supplied, version)
            synchronized(storage) {
                if (match.isFile) return@withLock saveAndOpen(image, match.readBytes(), version)
            }
            if (offline() || !enabled()) {
                if (supplied != null) return@withLock saveAndOpen(image, supplied, version, RETRY_TTL, placeholder = true)
                throw FileNotFoundException("Artwork lookup unavailable")
            }
            synchronized(storage) {
                if (miss.isFile && System.currentTimeMillis() - miss.lastModified() < MISS_TTL) throw FileNotFoundException("No matched artwork")
            }
            gate.withPermit {
                // Album and track cover IDs can differ even when their metadata is identical.
                matchLocks[(request.matchKey.hashCode() and Int.MAX_VALUE) % matchLocks.size].withLock lookup@{
                    synchronized(storage) {
                        if (match.isFile) return@lookup saveAndOpen(image, match.readBytes(), version)
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
                                saveAndOpen(match, data, version).close()
                                return@lookup saveAndOpen(image, data, version)
                            }
                        }
                    } catch (error: IOException) {
                        // Keep the server image usable during a provider outage; retry shortly.
                        if (supplied != null) return@lookup saveAndOpen(image, supplied, version, RETRY_TTL, placeholder = true)
                        throw error
                    }
                    if (supplied != null) return@lookup saveAndOpen(image, supplied, version, placeholder = true)
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
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bytes.takeIf { bounds.outWidth in 1..12000 && bounds.outHeight in 1..12000 }
    }

    private fun saveAndOpen(file: File, data: ByteArray, version: Int, ttl: Long = MISS_TTL, placeholder: Boolean = false): ParcelFileDescriptor = synchronized(storage) {
        if (version != generation) throw FileNotFoundException("Artwork cache cleared")
        root.mkdirs()
        val temporary = File(root, file.name + ".tmp")
        try {
            temporary.writeBytes(data)
            if (!temporary.renameTo(file)) throw IOException("Cannot save cover")
        } finally { temporary.delete() }
        File(root, file.nameWithoutExtension + ".miss").delete()
        File(root, file.nameWithoutExtension + ".fresh").writeText((System.currentTimeMillis() + ttl).toString())
        File(root, file.nameWithoutExtension + ".placeholder").let { if (placeholder) it.writeText("") else it.delete() }
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        trim(keep = file)
        descriptor
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        synchronized(storage) {
            generation++
            root.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
            _bytes.value = root.listFiles()?.sumOf { it.length() } ?: 0L
        }
    }

    private fun trim(keep: File? = null) {
        val files = root.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() }.orEmpty()
        var bytes = files.sumOf { it.length() }
        var count = files.size
        for (file in files) {
            if (file == keep) continue
            if (bytes <= LIMIT_BYTES && count <= 2500) break
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
