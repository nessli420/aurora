package com.aurora.music.desktop.audio

import com.aurora.music.data.PlaybackSourceIdentity
import com.aurora.music.data.SettingsStore
import com.aurora.music.data.cache.AudioCachePrefs
import com.aurora.music.data.isRadio
import com.aurora.music.data.rules.RuleSource
import com.aurora.music.desktop.platform.desktopFileUri
import com.aurora.music.model.Song
import com.aurora.music.util.AppLog
import com.google.gson.Gson
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

data class AudioCacheStatus(val bytes: Long = 0, val tracks: Int = 0, val ready: Boolean = false, val failed: Boolean = false)

// streamed songs pass through a loopback proxy that downloads each one once; ffmpeg reads the download as it
// fills, and a finished file plays straight from disk next time, offline included
class AudioCache(
    private val root: File,
    http: OkHttpClient,
    private val settings: SettingsStore?,
    private val offline: () -> Boolean = { false },
    private val limitOf: (AudioCachePrefs) -> Long = { it.limitBytes },
) : AutoCloseable {
    private val http = http.newBuilder().retryOnConnectionFailure(true).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val generation = AtomicLong()
    private val downloads = ConcurrentHashMap<String, Download>()
    private val byToken = ConcurrentHashMap<String, Download>()
    private val readers = ConcurrentHashMap<String, Int>()
    @Volatile private var prefs = AudioCachePrefs()
    @Volatile private var server: HttpServer? = null
    private val mutableStatus = MutableStateFlow(AudioCacheStatus())
    val status: StateFlow<AudioCacheStatus> = mutableStatus.asStateFlow()
    private val mutableSongs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = mutableSongs.asStateFlow()

    init {
        scope.launch {
            try {
                root.mkdirs()
                // a download cut short by a crash never got its record, so its audio is only a leftover
                root.listFiles { f -> f.extension == AUDIO && !File(root, f.nameWithoutExtension + ".$RECORD").isFile }?.forEach { it.delete() }
                refresh()
                settings?.audioCachePrefs?.collect { configure(it) }
            } catch (e: Exception) {
                AppLog.w(TAG, "Audio cache unavailable", e)
                mutableStatus.value = AudioCacheStatus(failed = true)
            }
        }
    }

    suspend fun configure(value: AudioCachePrefs) = withContext(Dispatchers.IO) {
        prefs = value
        if (!value.enabled) downloads.values.forEach { it.cancel() }
        evict()
        refresh()
    }

    fun resolve(song: Song, upstream: () -> ResolvedStream): ResolvedStream {
        if (song.streamUrl.startsWith("$SCHEME://")) {
            val key = song.streamUrl.substringAfter("://")
            return complete(key)?.let { ResolvedStream(desktopFileUri(it.path)) } ?: throw IOException("Cached audio is unavailable")
        }
        val key = keyFor(song) ?: return upstream()
        complete(key)?.let { file ->
            touch(key)
            return ResolvedStream(desktopFileUri(file.path))
        }
        val stream = upstream()
        if (!prefs.enabled || offline() || song.durationSec <= 0 || song.isRadio() || !progressive(stream.url)) return stream
        if (root.usableSpace < FREE_SPACE_RESERVE) return stream
        val download = downloads.compute(key) { _, existing -> existing?.takeIf { !it.finished } ?: Download(key, stream, Record.of(song)) }!!
        byToken[download.token] = download
        download.start()
        return ResolvedStream("http://127.0.0.1:${port()}/a/${download.token}")
    }

    // a song already on disk, for work like analysis that should neither stream nor fill the cache
    fun local(song: Song): ResolvedStream? = keyFor(song)?.let(::complete)?.let { ResolvedStream(desktopFileUri(it.path)) }

    // a track whose youtube match changed must not keep playing the old video's audio. The change usually comes while
    // the wrong song is still streaming in, so its download is stopped before it can finish into the cache, and the
    // record goes even when a player holds the audio open: without it the entry no longer counts as cached, and the
    // orphaned audio is swept on the next start
    fun forget(songId: String): kotlinx.coroutines.Job = scope.launch {
        val id = com.aurora.music.data.stripMergeNamespace(songId)
        fun same(recordId: String?) = recordId?.let { com.aurora.music.data.stripMergeNamespace(it) } == id
        downloads.values.filter { same(it.songId) }.forEach { it.cancel() }
        records().filter { (_, record) -> same(record.id) }.forEach { (key, _) ->
            File(root, "$key.$RECORD").delete()
            if (readers[key] == null) File(root, "$key.$AUDIO").delete()
        }
        refresh()
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        generation.incrementAndGet()
        downloads.values.forEach { it.cancel() }
        root.listFiles()?.forEach { file -> if (readers[file.nameWithoutExtension] == null) file.delete() }
        refresh()
    }

    // promotes a finished cache entry to an explicit download without fetching it again
    fun copyTo(uri: String, file: File, progress: (Float) -> Unit) {
        val source = complete(uri.substringAfter("://")) ?: throw IOException("Cached audio is unavailable")
        val length = source.length()
        var copied = 0L
        source.inputStream().use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    copied += count
                    progress((copied.toFloat() / length).coerceIn(0f, 1f))
                }
            }
        }
        if (copied != length) throw IOException("Cached audio is incomplete")
    }

    override fun close() {
        downloads.values.forEach { it.cancel() }
        server?.stop(0)
        scope.cancel()
    }

    private fun complete(key: String): File? {
        val audio = File(root, "$key.$AUDIO")
        val record = File(root, "$key.$RECORD")
        if (!audio.isFile || !record.isFile) return null
        val length = runCatching { gson.fromJson(record.readText(), Record::class.java).length }.getOrNull() ?: return null
        return audio.takeIf { it.length() == length && length > 0 }
    }

    private fun touch(key: String) {
        File(root, "$key.$RECORD").setLastModified(System.currentTimeMillis())
    }

    private fun records(): List<Pair<String, Record>> = root.listFiles { f -> f.extension == RECORD }.orEmpty().mapNotNull { file ->
        val key = file.nameWithoutExtension
        val record = runCatching { gson.fromJson(file.readText(), Record::class.java) }.getOrNull() ?: return@mapNotNull null
        if (complete(key) == null) null else key to record
    }

    private fun refresh() {
        val entries = records()
        mutableSongs.value = entries.mapNotNull { (key, record) -> record.toSong(key) }.sortedBy { it.title }
        mutableStatus.value = AudioCacheStatus(entries.sumOf { it.second.length }, entries.size, ready = true)
    }

    // least recently played goes first; a file someone is still reading is left for next time
    private fun evict() {
        val entries = records().sortedBy { File(root, "${it.first}.$RECORD").lastModified() }.toMutableList()
        var total = entries.sumOf { it.second.length }
        while (total > limitOf(prefs) && entries.isNotEmpty()) {
            val (key, record) = entries.removeAt(0)
            if (readers[key] != null) continue
            File(root, "$key.$RECORD").delete()
            File(root, "$key.$AUDIO").delete()
            total -= record.length
        }
    }

    @Synchronized private fun port(): Int {
        server?.let { return it.address.port }
        val created = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        created.createContext("/a/") { exchange -> serve(exchange) }
        created.executor = Executors.newCachedThreadPool { runnable -> Thread(runnable, "aurora-cache").apply { isDaemon = true } }
        created.start()
        server = created
        return created.address.port
    }

    private fun serve(exchange: HttpExchange) {
        exchange.use {
            val download = byToken[exchange.requestURI.path.substringAfter("/a/")] ?: return exchange.sendResponseHeaders(404, -1)
            val range = exchange.requestHeaders.getFirst("Range")
            val start = range?.let { RANGE.find(it)?.groupValues?.get(1)?.toLongOrNull() } ?: 0L
            val end = range?.let { RANGE.find(it)?.groupValues?.get(2)?.takeIf(String::isNotEmpty)?.toLongOrNull() }
            try {
                if (!download.awaitLength() || !download.covers(start)) return passthrough(exchange, download, range)
                readers.merge(download.key, 1, Int::plus)
                try { fromFile(exchange, download, start, end, range != null) } finally { readers.computeIfPresent(download.key) { _, n -> (n - 1).takeIf { it > 0 } } }
            } catch (_: IOException) {
                // the player hung up, usually to seek; the download carries on regardless
            }
        }
    }

    private fun fromFile(exchange: HttpExchange, download: Download, start: Long, requestedEnd: Long?, ranged: Boolean) {
        val total = download.length
        val last = (requestedEnd ?: (total - 1)).coerceAtMost(total - 1)
        if (start > last) {
            exchange.responseHeaders.add("Content-Range", "bytes */$total")
            return exchange.sendResponseHeaders(416, -1)
        }
        exchange.responseHeaders.add("Accept-Ranges", "bytes")
        download.contentType?.let { exchange.responseHeaders.add("Content-Type", it) }
        if (ranged) exchange.responseHeaders.add("Content-Range", "bytes $start-$last/$total")
        exchange.sendResponseHeaders(if (ranged) 206 else 200, last - start + 1)
        RandomAccessFile(download.file, "r").use { file ->
            var position = start
            val buffer = ByteArray(64 * 1024)
            while (position <= last) {
                val available = download.awaitBeyond(position)
                if (available <= position) throw IOException("Download stopped")
                file.seek(position)
                val count = file.read(buffer, 0, minOf(buffer.size.toLong(), available - position, last - position + 1).toInt())
                if (count <= 0) throw IOException("Download stopped")
                exchange.responseBody.write(buffer, 0, count)
                position += count
            }
        }
    }

    // a seek far past the download, or a download that failed, goes straight to the source
    private fun passthrough(exchange: HttpExchange, download: Download, range: String?) {
        val request = download.request(range)
        http.newCall(request).execute().use { response ->
            listOf("Content-Type", "Content-Range", "Accept-Ranges").forEach { name -> response.header(name)?.let { exchange.responseHeaders.add(name, it) } }
            val body = response.body
            val length = body?.contentLength() ?: -1L
            exchange.sendResponseHeaders(response.code, if (length < 0) 0 else if (length == 0L) -1 else length)
            body?.byteStream()?.copyTo(exchange.responseBody, 64 * 1024)
        }
    }

    private inner class Download(val key: String, private val stream: ResolvedStream, private val record: Record) {
        val token: String = UUID.randomUUID().toString()
        val file = File(root, "$key.$AUDIO")
        val songId: String? get() = record.id
        private val lock = Object()
        private val epoch = generation.get()
        @Volatile var length = -1L
            private set
        @Volatile var contentType: String? = null
            private set
        @Volatile private var written = 0L
        @Volatile private var failed = false
        @Volatile private var cancelled = false
        @Volatile var finished = false
            private set
        @Volatile private var started = false

        fun request(range: String?): Request = Request.Builder().url(stream.url).apply {
            stream.headers.forEach { (name, value) -> header(name, value) }
            stream.userAgent?.let { header("User-Agent", it) }
            range?.let { header("Range", it) }
        }.build()

        @Synchronized fun start() {
            if (started) return
            started = true
            Thread({ run() }, "aurora-cache-download").apply { isDaemon = true }.start()
        }

        fun cancel() {
            cancelled = true
            signal()
        }

        fun awaitLength(): Boolean = synchronized(lock) {
            val deadline = System.currentTimeMillis() + 20_000
            while (length < 0 && !failed && !cancelled && System.currentTimeMillis() < deadline) lock.wait(250)
            length > 0 && !failed
        }

        fun covers(position: Long): Boolean = !failed && !cancelled && position < length && position <= written + LOOKAHEAD

        fun awaitBeyond(position: Long): Long = synchronized(lock) {
            while (written <= position && !failed && !cancelled && !finished) lock.wait(250)
            if (failed || cancelled) minOf(written, position) else written
        }

        private fun signal() = synchronized(lock) { lock.notifyAll() }

        private fun run() {
            try {
                RandomAccessFile(file, "rw").use { out ->
                    out.setLength(0)
                    var position = 0L
                    val buffer = ByteArray(64 * 1024)
                    while (!cancelled && (length < 0 || position < length)) {
                        val request = request("bytes=$position-${position + CHUNK - 1}")
                        http.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                            val body = response.body ?: throw IOException("Empty response")
                            if (length < 0) {
                                contentType = response.header("Content-Type")
                                val total = if (response.code == 206) response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                                    else body.contentLength().takeIf { it > 0 }
                                if (total == null || total <= 0 || total > limitOf(prefs)) throw IOException("Not cacheable")
                                length = total
                                signal()
                            } else if (response.code != 206) throw IOException("Range ignored mid download")
                            body.byteStream().use { input ->
                                while (!cancelled) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    out.write(buffer, 0, count)
                                    position += count
                                    written = position
                                    signal()
                                }
                            }
                        }
                    }
                    if (cancelled || position != length) throw IOException("Incomplete")
                }
                if (epoch != generation.get() || !prefs.enabled) throw IOException("Cache cleared")
                File(root, "$key.$RECORD").writeText(gson.toJson(record.copy(length = length)))
                finished = true
                signal()
                evict()
                refresh()
            } catch (e: Exception) {
                failed = true
                signal()
                if (!cancelled) AppLog.d(TAG, "Not caching $key: ${e.message}")
                if (readers[key] == null) file.delete()
            } finally {
                downloads.remove(key, this)
            }
        }
    }

    private data class Record(
        val id: String? = null, val title: String? = null, val artist: String? = null, val album: String? = null,
        val albumId: String? = null, val artistId: String? = null, val artwork: String? = null, val duration: Int? = null,
        val suffix: String? = null, val provider: String? = null, val providerLabel: String? = null, val sourceAlbum: String? = null,
        val trackGain: Float? = null, val albumGain: Float? = null, val length: Long = 0,
    ) {
        fun toSong(key: String): Song? {
            if (id.isNullOrBlank() || title.isNullOrBlank() || (duration ?: 0) <= 0) return null
            return Song(id, title, artist.orEmpty(), album.orEmpty(), artwork.orEmpty(), duration ?: 0,
                streamUrl = "$SCHEME://$key", albumId = albumId.orEmpty(), artistId = artistId.orEmpty(),
                suffix = suffix.orEmpty(), replayGainTrack = trackGain ?: 0f, replayGainAlbum = albumGain ?: 0f,
                playbackSource = PlaybackSourceIdentity(provider, providerLabel, RuleSource.STREAM, sourceAlbum))
        }

        companion object {
            fun of(song: Song) = Record(song.id, song.title, song.artist, song.album, song.albumId, song.artistId, song.artworkUrl,
                song.durationSec, song.suffix, song.playbackSource?.providerId, song.playbackSource?.providerLabel,
                song.playbackSource?.albumId, song.replayGainTrack, song.replayGainAlbum)
        }
    }

    companion object {
        const val SCHEME = "aurora-cache"
        private const val TAG = "AudioCache"
        private const val AUDIO = "audio"
        private const val RECORD = "json"
        private const val CHUNK = 4L * 1024 * 1024
        private const val LOOKAHEAD = 2L * 1024 * 1024
        private const val FREE_SPACE_RESERVE = 64 * 1024 * 1024L
        private val RANGE = Regex("bytes=(\\d+)-(\\d*)")

        private fun progressive(url: String): Boolean {
            val lower = url.lowercase()
            return (lower.startsWith("http://") || lower.startsWith("https://")) && ".m3u8" !in lower && ".mpd" !in lower
        }

        // the account and resource identity, never the url, which can carry credentials
        internal fun keyFor(song: Song): String? {
            if (song.id.isBlank() || song.streamUrl.startsWith("file:")) return null
            return MessageDigest.getInstance("SHA-256").digest("${song.playbackSource?.providerId.orEmpty()}\u0000${song.id}".toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }
}
