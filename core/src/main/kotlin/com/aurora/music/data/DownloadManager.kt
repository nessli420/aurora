package com.aurora.music.data

import android.content.Context
import android.net.Uri
import com.aurora.music.model.Song
import com.aurora.music.util.accentFor
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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

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
) {
    fun toSong(): Song = Song(
        id = id,
        title = title,
        artist = artist,
        album = album,
        artworkUrl = if (coverPath.isNotBlank()) Uri.fromFile(File(coverPath)).toString() else "",
        durationSec = durationSec,
        accent = accentFor(id),
        streamUrl = Uri.fromFile(File(audioPath)).toString(),
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
    context: Context,
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

    private val contentResolver = context.applicationContext.contentResolver
    private val dir = File(context.filesDir, "downloads").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val collectionsFile = File(dir, "collections.json")
    private val gson = Gson()
    private val http = OkHttpClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingSongs = mutableMapOf<String, DownloadOwner>()
    private val pendingCollections = mutableMapOf<String, DownloadOwner>()

    private val _downloads = MutableStateFlow(loadIndex())
    val downloads: StateFlow<Map<String, DownloadedSong>> = _downloads.asStateFlow()

    private val _collections = MutableStateFlow(loadCollections())
    val collections: StateFlow<List<DownloadedCollection>> = _collections.asStateFlow()

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
                val coverFile = File(dir, "col_${fileStem(serverId, id)}.jpg")
                runCatching { if (coverUrl.isNotBlank()) downloadTo(coverUrl, coverFile) {} }
                val collection = DownloadedCollection(id, kind, title, subtitle, if (coverFile.exists()) coverFile.absolutePath else "", songs.map { it.id }, serverId, identity, providerId)
                _collections.update { (it.filterNot { c -> c.id == id }) + collection }
                saveCollections()
            } finally {
                synchronized(this@DownloadManager) { pendingCollections.remove(id) }
            }
        }
        downloadAll(songs)
        return true
    }

    fun removeCollection(id: String) {
        val col = _collections.value.firstOrNull { it.id == id } ?: return
        removeCollection(col)
    }

    @Synchronized
    fun removeCollection(col: DownloadedCollection, canRemoveTrack: (DownloadedSong) -> Boolean = { true }): Boolean {
        if (_collections.value.none { it == col }) return false
        col.trackIds.mapNotNull(::get).filter(canRemoveTrack).forEach { removeDownload(it) }
        runCatching { if (col.coverPath.isNotBlank()) File(col.coverPath).delete() }
        _collections.update { it.filterNot { c -> c == col } }
        saveCollections()
        return true
    }

    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states.asStateFlow()

    fun isDownloaded(id: String): Boolean = _downloads.value.containsKey(id)
    fun get(id: String): DownloadedSong? = _downloads.value[id]

    // also matches merged-namespaced keys downloads keyed by wrapped id but localize looks up raw id
    fun getByOriginalId(originalId: String, providerId: String? = null): DownloadedSong? =
        _downloads.value[originalId]?.takeIf { providerId == null || it.playbackSource?.providerId == providerId }
            ?: _downloads.value.values.firstOrNull {
                stripMergeNamespace(it.id) == originalId && (providerId == null || it.playbackSource?.providerId == providerId)
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
        if (hasSourceMismatch(song)) { setState(song.id, DownloadState.Failed); return false }
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
                if (extension) "extension://${android.net.Uri.parse(song.streamUrl).host}" else serverId,
                if (extension || cached) song.streamUrl else streamUrlProvider(song.id, bitrate, bitrate == 0)?.takeIf { it.isNotBlank() } ?: song.streamUrl,
                bitrate, extension, cached,
                if (extension || cached) null else downloadUrlResolverProvider(),
            )
        }.getOrElse { setState(song.id, DownloadState.Failed); return false }
        pendingSongs[song.id] = owner
        setState(song.id, DownloadState.Queued)
        scope.launch { doDownload(song.copy(playbackSource = identity), request) }
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

    fun clearAll() {
        _downloads.value.keys.toList().forEach { removeDownload(it) }
    }

    fun totalBytes(): Long = _downloads.value.values.sumOf {
        runCatching { File(it.audioPath).length() + (if (it.coverPath.isNotBlank()) File(it.coverPath).length() else 0L) }.getOrDefault(0L)
    }

    private suspend fun doDownload(song: Song, request: DownloadRequest) {
        val stem = fileStem(request.serverId, song.id)
        try {
            setState(song.id, DownloadState.Downloading(0f))
            val audioFile = File(dir, "$stem.audio")
            // resolve aurora-yt sentinel via the songs full sentinel which holds the search query
            val resolvedUrl = request.resolveUrl?.invoke(song.id, request.bitrate, request.bitrate == 0)
                ?.takeIf { it.isNotBlank() } ?: request.url
            val audioUrl = if (resolvedUrl.startsWith("aurora-yt://")) {
                val sentinel = if (song.streamUrl.startsWith("aurora-yt://")) song.streamUrl else resolvedUrl
                resolveSentinel(sentinel) ?: throw IOException("No stream found for this track")
            } else resolvedUrl
            if (request.cached) requireNotNull(copyCached) { "Cached audio is unavailable." }(audioUrl, audioFile) { p -> setState(song.id, DownloadState.Downloading(p)) }
            else if (request.extension) requireNotNull(copyExtension) { "Extension downloads are unavailable." }(audioUrl, audioFile) { p -> setState(song.id, DownloadState.Downloading(p)) }
            else downloadTo(audioUrl, audioFile) { p -> setState(song.id, DownloadState.Downloading(p)) }
            val coverFile = File(dir, "$stem.jpg")
            runCatching { if (!request.cached && song.artworkUrl.isNotBlank()) downloadTo(song.artworkUrl, coverFile) {} }
            val entry = DownloadedSong(
                id = song.id, title = song.title, artist = song.artist, album = song.album,
                albumId = song.albumId, artistId = song.artistId, durationSec = song.durationSec,
                audioPath = audioFile.absolutePath,
                coverPath = if (coverFile.exists()) coverFile.absolutePath else "",
                suffix = if (request.bitrate == 0) song.suffix else "mp3",
                bitrateKbps = if (request.bitrate == 0) song.bitrateKbps else request.bitrate,
                sampleRateHz = song.sampleRateHz,
                bitDepth = song.bitDepth,
                serverId = request.serverId,
                playbackSource = song.playbackSource,
                genre = song.genre,
            )
            _downloads.update { it + (song.id to entry) }
            saveIndex()
            setState(song.id, DownloadState.Done)
        } catch (e: Exception) {
            runCatching { File(dir, "$stem.audio").delete() }
            setState(song.id, DownloadState.Failed)
        } finally {
            synchronized(this) { pendingSongs.remove(song.id) }
        }
    }

    private fun fileStem(serverId: String, id: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$serverId\u0000$id".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun downloadTo(url: String, file: File, onProgress: (Float) -> Unit) {
        if (url.startsWith("content://") || url.startsWith("file://")) {
            val input = contentResolver.openInputStream(Uri.parse(url)) ?: throw IOException("Missing cover")
            input.use { source -> file.outputStream().use { source.copyTo(it) } }
            onProgress(1f)
            return
        }
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

    private fun loadIndex(): Map<String, DownloadedSong> = runCatching {
        if (!indexFile.exists()) return@runCatching emptyMap<String, DownloadedSong>()
        val type = object : TypeToken<List<DownloadedSong>>() {}.type
        val list: List<DownloadedSong> = gson.fromJson(indexFile.readText(), type) ?: emptyList()
        list.filter { File(it.audioPath).exists() }.associateBy { it.id }
    }.getOrDefault(emptyMap())

    private fun saveIndex() = runCatching {
        indexFile.writeText(gson.toJson(_downloads.value.values.toList()))
    }

    private fun loadCollections(): List<DownloadedCollection> = runCatching {
        if (!collectionsFile.exists()) return@runCatching emptyList<DownloadedCollection>()
        val type = object : TypeToken<List<DownloadedCollection>>() {}.type
        gson.fromJson<List<DownloadedCollection>>(collectionsFile.readText(), type) ?: emptyList()
    }.getOrDefault(emptyList())

    private fun saveCollections() = runCatching {
        collectionsFile.writeText(gson.toJson(_collections.value))
    }
}
