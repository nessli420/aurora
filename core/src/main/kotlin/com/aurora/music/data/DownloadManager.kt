package com.aurora.music.data

import com.aurora.music.data.remote.AuroraHttp
import com.aurora.music.model.Song
import com.aurora.music.util.accentArgbFor
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

data class DownloadedSong(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: String,
    val artistId: String,
    val durationSec: Int,
    val audioPath: String,
    val coverPath: String,
    // nullable because gson injects null not the kotlin default for fields missing from older records
    val suffix: String? = "",
    val bitrateKbps: Int = 0,
    val sampleRateHz: Int = 0,
    val bitDepth: Int = 0,
    val serverId: String? = "",
    val playbackSource: PlaybackSourceIdentity? = null,
    val genre: String? = null,
    // offline liked songs: saved from liked songs, or known to be liked when saved or since
    val liked: Boolean? = null,
) {
    fun toSong(fileUri: (String) -> String): Song = Song(
        id = id,
        title = title,
        artist = artist,
        album = album,
        liked = liked == true,
        artworkUrl = if (coverPath.isNotBlank()) fileUri(coverPath) else "",
        durationSec = durationSec,
        accentArgb = accentArgbFor(id),
        streamUrl = fileUri(audioPath),
        albumId = albumId,
        artistId = artistId,
        suffix = suffix ?: "",
        bitrateKbps = bitrateKbps,
        sampleRateHz = sampleRateHz,
        bitDepth = bitDepth,
        genre = genre.orEmpty(),
        playbackSource = (playbackSource ?: PlaybackSourceIdentity()).copy(source = com.aurora.music.data.rules.RuleSource.DOWNLOAD),
    )
}

sealed interface DownloadState {
    data object Queued : DownloadState
    data class Downloading(val progress: Float) : DownloadState
    data object Done : DownloadState
    data object Failed : DownloadState
}

data class DownloadedCollection(
    val id: String,
    val kind: String,
    val title: String,
    val subtitle: String,
    val coverPath: String,
    val trackIds: List<String>,
    val serverId: String? = "",
    val playbackCollection: PlaybackCollectionIdentity? = null,
    val sourceProviderId: String? = null,
)

internal data class DownloadOwner(val providerId: String?, val serverId: String) {
    fun matches(other: DownloadOwner): Boolean =
        if (!providerId.isNullOrBlank() || !other.providerId.isNullOrBlank())
            !providerId.isNullOrBlank() && providerId == other.providerId
        else serverId.isNotBlank() && serverId == other.serverId
}

class DownloadManager(
    dir: File,
    val fileUri: (String) -> String,
    private val openStream: (String) -> InputStream?,
    private val streamUrlProvider: (String, Int, Boolean) -> String? = { _, _, _ -> null },
    private val downloadBitrateProvider: () -> Int = { 0 },
    private val currentServerIdProvider: () -> String = { "" },
    private val resolveSentinel: (String) -> String? = { null },
    private val playbackSourceProvider: (Song) -> PlaybackSourceIdentity? = { it.playbackSource },
    private val playbackCollectionProvider: (String, String, String) -> PlaybackCollectionIdentity? = { _, _, _ -> null },
    private val copyExtension: ((String, File, (Float) -> Unit) -> Unit)? = null,
    private val copyCached: ((String, File, (Float) -> Unit) -> Unit)? = null,
    private val downloadUrlResolverProvider: () -> (suspend (String, Int, Boolean) -> String)? = { null },
) {

    private data class DownloadRequest(val serverId: String, val url: String, val bitrate: Int, val extension: Boolean, val cached: Boolean,
        val resolveUrl: (suspend (String, Int, Boolean) -> String)?)

    private val dir = dir.apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val collectionsFile = File(dir, "collections.json")
    private val gson = Gson()
    private val http get() = AuroraHttp.base
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingSongs = mutableMapOf<String, DownloadOwner>()
    private val pendingCollections = mutableMapOf<String, DownloadOwner>()
    private val downloadGate = Semaphore(PARALLEL_DOWNLOADS)

    // parsed off main, every reader and writer waits on the lazy so nothing sees an empty index
    private val _downloads by lazy { MutableStateFlow(loadIndex()) }
    val downloads: StateFlow<Map<String, DownloadedSong>> get() = _downloads

    private val _collections by lazy { MutableStateFlow(loadCollections()) }
    val collections: StateFlow<List<DownloadedCollection>> get() = _collections

    private val indexWriter = ConflatedWrite(indexFile) { _downloads.value.values.toList() }
    private val collectionsWriter = ConflatedWrite(collectionsFile) { _collections.value }

    private class OriginalIndex(val source: Map<String, DownloadedSong>, val byOriginal: Map<String, List<DownloadedSong>>)
    @Volatile private var originalIndex: OriginalIndex? = null

    @Synchronized
    fun downloadCollection(id: String, kind: String, title: String, subtitle: String, coverUrl: String, songs: List<Song>): Boolean {
        val mismatched = songs.filter(::hasSourceMismatch)
        if (mismatched.isNotEmpty()) {
            mismatched.forEach { setState(it.id, DownloadState.Failed) }
            return false
        }
        val identity = playbackCollectionProvider(kind, id, title)
        val serverId = currentServerIdProvider()
        val providerId = playbackSourceProvider(Song(id, "", "", "", "", 0))?.providerId
            ?: songs.mapNotNull { sourceIdentity(it)?.providerId }.distinct().singleOrNull()
        val owner = DownloadOwner(providerId, serverId)
        val existing = _collections.value.firstOrNull { it.id == id }
        if (existing != null && !owner.matches(DownloadOwner(existing.sourceProviderId, existing.serverId.orEmpty())) &&
            (identity?.id == null || identity.id != existing.playbackCollection?.id)) return false
        pendingCollections[id]?.let { return owner.matches(it) }
        val conflicts = songs.filter { song ->
            val songOwner = DownloadOwner(sourceIdentity(song)?.providerId, serverId)
            _downloads.value[song.id]?.let { !songOwner.matches(DownloadOwner(it.playbackSource?.providerId, it.serverId.orEmpty())) } == true ||
                pendingSongs[song.id]?.let { !songOwner.matches(it) } == true
        }
        if (conflicts.isNotEmpty()) {
            conflicts.forEach { setState(it.id, DownloadState.Failed) }
            return false
        }
        pendingCollections[id] = owner
        scope.launch {
            try {
                val source = DownloadSource.of(songs.firstNotNullOfOrNull { sourceIdentity(it)?.providerLabel }, serverId)
                val coverFile = File(folderFor(source), "col_${fileStem(serverId, id)}.jpg")
                runCatching { if (coverUrl.isNotBlank()) downloadTo(coverUrl, coverFile) {} }
                val collection = DownloadedCollection(id, kind, title, subtitle, if (coverFile.exists()) coverFile.absolutePath else "", songs.map { it.id }, serverId, identity, providerId)
                _collections.update { (it.filterNot { c -> c.id == id }) + collection }
                saveCollections()
            } finally {
                synchronized(this@DownloadManager) { pendingCollections.remove(id) }
            }
        }
        downloadAll(if (kind == "liked") songs.map { it.copy(liked = true) } else songs)
        return true
    }

    // likes learned online reach downloads so offline liked songs stay current
    fun markLiked(ids: Collection<String>, liked: Boolean) {
        if (ids.isEmpty()) return
        val wanted = ids.toSet()
        var changed = false
        synchronized(this) {
            _downloads.update { current ->
                current.mapValues { (id, entry) ->
                    if (id in wanted && (entry.liked == true) != liked) { changed = true; entry.copy(liked = liked) } else entry
                }
            }
        }
        if (changed) saveIndex()
    }

    fun removeCollection(id: String) {
        val col = _collections.value.firstOrNull { it.id == id } ?: return
        removeCollection(col)
    }

    @Synchronized
    fun removeCollection(col: DownloadedCollection, canRemoveTrack: (DownloadedSong) -> Boolean = { true }): Boolean {
        if (_collections.value.none { it == col }) return false
        removeEntries(col.trackIds.mapNotNull(::get).filter(canRemoveTrack))
        runCatching { if (col.coverPath.isNotBlank()) File(col.coverPath).delete() }
        _collections.update { it.filterNot { c -> c == col } }
        saveCollections()
        return true
    }

    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states.asStateFlow()

    init {
        scope.launch { _downloads.value; _collections.value; moveIntoFolders(); dropMissingFiles() }
    }

    // downloads saved before per-source folders move into theirs once; an entry only changes after its file moved
    private fun moveIntoFolders() {
        fun move(path: String, target: File): String? {
            val file = File(path)
            if (path.isBlank() || file.parentFile?.absolutePath != dir.absolutePath) return null
            val moved = File(target, file.name)
            return moved.absolutePath.takeIf { file.renameTo(moved) }
        }
        val songs = _downloads.value.values.mapNotNull { entry ->
            val target = folderFor(DownloadSource.of(entry))
            val audio = move(entry.audioPath, target) ?: return@mapNotNull null
            entry to entry.copy(audioPath = audio, coverPath = move(entry.coverPath, target) ?: entry.coverPath)
        }
        if (songs.isNotEmpty()) {
            synchronized(this) { _downloads.update { current -> current + songs.filter { (old, _) -> current[old.id] === old }.associate { it.second.id to it.second } } }
            saveIndex()
        }
        val byId = _downloads.value
        val covers = _collections.value.mapNotNull { col ->
            val source = col.trackIds.firstNotNullOfOrNull { byId[it] }?.let(DownloadSource::of) ?: DownloadSource.of(null, col.serverId)
            move(col.coverPath, folderFor(source))?.let { col to col.copy(coverPath = it) }
        }
        if (covers.isNotEmpty()) {
            synchronized(this) { _collections.update { current -> current.map { c -> covers.firstOrNull { it.first === c }?.second ?: c } } }
            saveCollections()
        }
    }

    fun isDownloaded(id: String): Boolean = _downloads.value.containsKey(id)
    fun get(id: String): DownloadedSong? = _downloads.value[id]

    // also matches merged-namespaced keys downloads keyed by wrapped id but localize looks up raw id
    fun getByOriginalId(originalId: String, providerId: String? = null): DownloadedSong? =
        _downloads.value[originalId]?.takeIf { providerId == null || it.playbackSource?.providerId == providerId }
            ?: byOriginalId()[originalId]?.firstOrNull { providerId == null || it.playbackSource?.providerId == providerId }

    private fun byOriginalId(): Map<String, List<DownloadedSong>> {
        val current = _downloads.value
        originalIndex?.takeIf { it.source === current }?.let { return it.byOriginal }
        return current.values.groupBy { stripMergeNamespace(it.id) }.also { originalIndex = OriginalIndex(current, it) }
    }

    private fun copiedSource(song: Song): Boolean =
        song.streamUrl.startsWith("aurora-extension:") || song.streamUrl.startsWith("aurora-cache:")

    private fun sourceIdentity(song: Song): PlaybackSourceIdentity? = if (copiedSource(song))
        song.playbackSource ?: playbackSourceProvider(song)
    else playbackSourceProvider(song.copy(playbackSource = null, streamUrl = "")) ?: song.playbackSource

    private fun hasSourceMismatch(song: Song): Boolean {
        if (copiedSource(song)) return false
        val selectedProvider = song.playbackSource?.providerId ?: return false
        val currentProvider = playbackSourceProvider(song.copy(playbackSource = null, streamUrl = ""))?.providerId ?: return false
        return selectedProvider != currentProvider
    }

    @Synchronized
    fun downloadSong(song: Song): Boolean {
        if (hasSourceMismatch(song)) { com.aurora.music.util.AppLog.w("Downloads", "source mismatch for a download"); setState(song.id, DownloadState.Failed); return false }
        val identity = sourceIdentity(song)
        val serverId = currentServerIdProvider()
        val owner = DownloadOwner(identity?.providerId, serverId)
        _downloads.value[song.id]?.let { existing ->
            val matches = owner.matches(DownloadOwner(existing.playbackSource?.providerId, existing.serverId.orEmpty()))
            setState(song.id, if (matches) DownloadState.Done else DownloadState.Failed)
            return matches
        }
        pendingSongs[song.id]?.let { pending ->
            if (!owner.matches(pending)) { setState(song.id, DownloadState.Failed); return false }
            return true
        }
        val request = runCatching {
            val extension = song.streamUrl.startsWith("aurora-extension:")
            val cached = song.streamUrl.startsWith("aurora-cache:")
            val bitrate = if (extension || cached) 0 else downloadBitrateProvider()
            DownloadRequest(
                if (extension) "extension://${URI(song.streamUrl).host}" else serverId,
                if (extension || cached) song.streamUrl else streamUrlProvider(song.id, bitrate, bitrate == 0)?.takeIf { it.isNotBlank() } ?: song.streamUrl,
                bitrate, extension, cached,
                if (extension || cached) null else downloadUrlResolverProvider(),
            )
        }.getOrElse { setState(song.id, DownloadState.Failed); return false }
        pendingSongs[song.id] = owner
        setState(song.id, DownloadState.Queued)
        scope.launch { downloadGate.withPermit { doDownload(song.copy(playbackSource = identity), request) } }
        return true
    }

    fun downloadAll(songs: List<Song>): Boolean = songs.map { downloadSong(it) }.all { it }

    @Synchronized
    fun removeDownload(id: String) {
        val d = _downloads.value[id] ?: return
        runCatching { File(d.audioPath).delete() }
        runCatching { if (d.coverPath.isNotBlank()) File(d.coverPath).delete() }
        _downloads.update { it - id }
        _states.update { it - id }
        saveIndex()
    }

    @Synchronized
    fun removeDownload(download: DownloadedSong): Boolean {
        if (_downloads.value[download.id] != download) return false
        removeDownload(download.id)
        return true
    }

    @Synchronized
    fun removeDownloads(ids: Collection<String>) {
        val current = _downloads.value
        removeEntries(ids.mapNotNull { current[it] })
    }

    @Synchronized
    private fun removeEntries(entries: List<DownloadedSong>) {
        val doomed = entries.filter { _downloads.value[it.id] == it }.distinctBy { it.id }
        if (doomed.isEmpty()) return
        doomed.forEach { d ->
            runCatching { File(d.audioPath).delete() }
            runCatching { if (d.coverPath.isNotBlank()) File(d.coverPath).delete() }
        }
        val ids = doomed.mapTo(HashSet()) { it.id }
        _downloads.update { it - ids }
        _states.update { it - ids }
        saveIndex()
    }

    fun clearAll() {
        removeDownloads(_downloads.value.keys.toList())
    }

    fun totalBytes(): Long = _downloads.value.values.sumOf {
        runCatching { File(it.audioPath).length() + (if (it.coverPath.isNotBlank()) File(it.coverPath).length() else 0L) }.getOrDefault(0L)
    }

    private fun folderFor(source: DownloadSource) = File(dir, source.folder).apply { mkdirs() }

    private suspend fun doDownload(song: Song, request: DownloadRequest) {
        val stem = fileStem(request.serverId, song.id)
        val folder = folderFor(DownloadSource.of(song.playbackSource?.providerLabel, request.serverId))
        try {
            setState(song.id, DownloadState.Downloading(0f))
            val audioFile = File(folder, "$stem.audio")
            // resolve aurora-yt sentinel via the songs full sentinel which holds the search query
            val resolvedUrl = request.resolveUrl?.invoke(song.id, request.bitrate, request.bitrate == 0)
                ?.takeIf { it.isNotBlank() } ?: request.url
            val audioUrl = if (resolvedUrl.startsWith("aurora-yt://")) {
                val sentinel = if (song.streamUrl.startsWith("aurora-yt://")) song.streamUrl else resolvedUrl
                resolveSentinel(sentinel) ?: throw IOException("No stream found for this track")
            } else resolvedUrl
            val progress = progressReporter(song.id)
            val hls = !request.cached && !request.extension && HlsAudioDownload.isPlaylist(audioUrl)
            if (hls) HlsAudioDownload.download(http, audioUrl, audioFile, File(folder, "$stem.part"), progress)
            else if (request.cached) requireNotNull(copyCached) { "Cached audio is unavailable." }(audioUrl, audioFile, progress)
            else if (request.extension) requireNotNull(copyExtension) { "Extension downloads are unavailable." }(audioUrl, audioFile, progress)
            else downloadTo(audioUrl, audioFile, progress)
            val coverFile = File(folder, "$stem.jpg")
            runCatching { if (!request.cached && song.artworkUrl.isNotBlank()) downloadTo(song.artworkUrl, coverFile) {} }
            val entry = DownloadedSong(
                id = song.id, title = song.title, artist = song.artist, album = song.album,
                albumId = song.albumId, artistId = song.artistId, durationSec = song.durationSec,
                audioPath = audioFile.absolutePath,
                coverPath = if (coverFile.exists()) coverFile.absolutePath else "",
                // the stitched stream is aac in mp4, and the player reads a bare m4a as lossless alac
                suffix = if (hls) "aac" else if (request.bitrate == 0) song.suffix else "mp3",
                bitrateKbps = if (request.bitrate == 0) song.bitrateKbps else request.bitrate,
                sampleRateHz = song.sampleRateHz,
                bitDepth = song.bitDepth,
                serverId = request.serverId,
                playbackSource = song.playbackSource,
                genre = song.genre,
                liked = song.liked,
            )
            _downloads.update { it + (song.id to entry) }
            saveIndex()
            setState(song.id, DownloadState.Done)
        } catch (e: Exception) {
            com.aurora.music.util.AppLog.w("Downloads", "download failed: ${e.message}")
            runCatching { File(folder, "$stem.audio").delete(); File(folder, "$stem.part").delete() }
            setState(song.id, DownloadState.Failed)
        } finally {
            synchronized(this) { pendingSongs.remove(song.id) }
        }
    }

    private fun fileStem(serverId: String, id: String): String = sha256Hex("$serverId\u0000$id")

    private fun progressReporter(id: String): (Float) -> Unit {
        var last = 0f
        var lastAt = System.nanoTime()
        return { p ->
            val now = System.nanoTime()
            if ((p >= 1f && last < 1f) || (kotlin.math.abs(p - last) >= 0.01f && now - lastAt >= PROGRESS_INTERVAL_NS)) {
                last = p
                lastAt = now
                setState(id, DownloadState.Downloading(p))
            }
        }
    }

    private fun downloadTo(url: String, file: File, onProgress: (Float) -> Unit) {
        if (url.startsWith("content://") || url.startsWith("file://")) {
            val input = openStream(url) ?: throw IOException("Missing cover")
            input.use { source -> file.outputStream().use { source.copyTo(it) } }
            onProgress(1f)
            return
        }
        if (RangedDownload.isThrottled(url)) return RangedDownload.fetch(http, url, file, onProgress)
        val request = Request.Builder().url(url).build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("empty body")
            val total = body.contentLength()
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var readTotal = 0L
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        output.write(buf, 0, n)
                        readTotal += n
                        if (total > 0) onProgress((readTotal.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
    }

    private fun setState(id: String, state: DownloadState) = _states.update { it + (id to state) }

    // missing files are pruned in the background so startup skips a stat per download
    private fun loadIndex(): Map<String, DownloadedSong> = runCatching {
        if (!indexFile.exists()) return@runCatching emptyMap<String, DownloadedSong>()
        val type = object : TypeToken<List<DownloadedSong>>() {}.type
        val list: List<DownloadedSong> = gson.fromJson(indexFile.readText(), type) ?: emptyList()
        list.associateBy { it.id }
    }.getOrDefault(emptyMap())

    private fun dropMissingFiles() {
        val stale = _downloads.value.values.filterNot { File(it.audioPath).exists() }.associateBy { it.id }
        if (stale.isEmpty()) return
        synchronized(this) { _downloads.update { current -> current.filterNot { (id, entry) -> stale[id] === entry } } }
    }

    private fun saveIndex() = indexWriter.request()

    private fun loadCollections(): List<DownloadedCollection> = runCatching {
        if (!collectionsFile.exists()) return@runCatching emptyList<DownloadedCollection>()
        val type = object : TypeToken<List<DownloadedCollection>>() {}.type
        gson.fromJson<List<DownloadedCollection>>(collectionsFile.readText(), type) ?: emptyList()
    }.getOrDefault(emptyList())

    private fun saveCollections() = collectionsWriter.request()

    private inner class ConflatedWrite(private val file: File, private val content: () -> Any) {
        private val pending = AtomicBoolean(false)

        fun request() {
            if (pending.compareAndSet(false, true)) scope.launch { write() }
        }

        private fun write() {
            synchronized(this) {
                pending.set(false)
                runCatching { persistBackupFileAtomically(file, gson.toJson(content()).toByteArray(Charsets.UTF_8)) }
            }
        }
    }

    private companion object {
        const val PARALLEL_DOWNLOADS = 3
        const val PROGRESS_INTERVAL_NS = 250_000_000L
    }
}
