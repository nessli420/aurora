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

/** A song available offline: metadata + local file paths. */
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
    // Nullable because Gson injects null (not the Kotlin default) when an older saved record
    // predates these fields — see toSong()'s null-coalescing.
    val suffix: String? = "",
    val bitrateKbps: Int = 0,
    val sampleRateHz: Int = 0,
    val bitDepth: Int = 0,
    // Which server this was downloaded from (its base URL). Lets the Library "Downloaded" view
    // scope to the active server while online; offline mode shows every server's downloads.
    val serverId: String? = "",
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
    )
}

sealed interface DownloadState {
    data object Queued : DownloadState
    data class Downloading(val progress: Float) : DownloadState
    data object Done : DownloadState
    data object Failed : DownloadState
}

/** An album/playlist the user explicitly downloaded as a unit. */
data class DownloadedCollection(
    val id: String,
    val kind: String,      // "album" | "playlist"
    val title: String,
    val subtitle: String,
    val coverPath: String,
    val trackIds: List<String>,
    val serverId: String? = "",   // server base URL this collection came from
)

/**
 * Downloads songs (audio + cover) to app storage for offline playback, with a persistent
 * index and live per-song progress. Albums/playlists download by passing their full track list.
 */
class DownloadManager(
    context: Context,
    // Resolves a server audio URL for (songId, maxBitrate, lossless) from the active backend.
    private val streamUrlProvider: (String, Int, Boolean) -> String? = { _, _, _ -> null },
    private val downloadBitrateProvider: () -> Int = { 0 },
    // The active server's base URL, stamped onto each download so it can be scoped per-server.
    private val currentServerIdProvider: () -> String = { "" },
    // Resolves an `aurora-yt://` sentinel (Spotify) to a real, fetchable audio URL. Null otherwise.
    private val resolveSentinel: (String) -> String? = { null },
) {

    private val dir = File(context.filesDir, "downloads").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val collectionsFile = File(dir, "collections.json")
    private val gson = Gson()
    private val http = OkHttpClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _downloads = MutableStateFlow(loadIndex())
    val downloads: StateFlow<Map<String, DownloadedSong>> = _downloads.asStateFlow()

    private val _collections = MutableStateFlow(loadCollections())
    val collections: StateFlow<List<DownloadedCollection>> = _collections.asStateFlow()

    /** Record an album/playlist as a downloaded collection and download its tracks. */
    fun downloadCollection(id: String, kind: String, title: String, subtitle: String, coverUrl: String, songs: List<Song>) {
        scope.launch {
            val coverFile = File(dir, "col_$id.jpg")
            runCatching { if (coverUrl.isNotBlank()) downloadTo(coverUrl, coverFile) {} }
            val collection = DownloadedCollection(id, kind, title, subtitle, if (coverFile.exists()) coverFile.absolutePath else "", songs.map { it.id }, currentServerIdProvider())
            _collections.update { (it.filterNot { c -> c.id == id }) + collection }
            saveCollections()
        }
        downloadAll(songs)
    }

    fun removeCollection(id: String) {
        val col = _collections.value.firstOrNull { it.id == id } ?: return
        col.trackIds.forEach { removeDownload(it) }
        runCatching { if (col.coverPath.isNotBlank()) File(col.coverPath).delete() }
        _collections.update { it.filterNot { c -> c.id == id } }
        saveCollections()
    }

    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states.asStateFlow()

    fun isDownloaded(id: String): Boolean = _downloads.value.containsKey(id)
    fun get(id: String): DownloadedSong? = _downloads.value[id]

    fun downloadSong(song: Song) {
        if (isDownloaded(song.id) || _states.value[song.id] is DownloadState.Downloading) return
        setState(song.id, DownloadState.Queued)
        scope.launch { doDownload(song) }
    }

    fun downloadAll(songs: List<Song>) = songs.forEach { downloadSong(it) }

    fun removeDownload(id: String) {
        val d = _downloads.value[id] ?: return
        runCatching { File(d.audioPath).delete() }
        runCatching { if (d.coverPath.isNotBlank()) File(d.coverPath).delete() }
        _downloads.update { it - id }
        _states.update { it - id }
        saveIndex()
    }

    fun clearAll() {
        _downloads.value.keys.toList().forEach { removeDownload(it) }
    }

    fun totalBytes(): Long = _downloads.value.values.sumOf {
        runCatching { File(it.audioPath).length() + (if (it.coverPath.isNotBlank()) File(it.coverPath).length() else 0L) }.getOrDefault(0L)
    }

    private suspend fun doDownload(song: Song) {
        try {
            setState(song.id, DownloadState.Downloading(0f))
            val audioFile = File(dir, "${song.id}.audio")
            // Build the audio URL at the chosen download quality (0 = lossless/original).
            val bitrate = downloadBitrateProvider()
            val provided = streamUrlProvider(song.id, bitrate, bitrate == 0) ?: song.streamUrl
            // Spotify tracks carry an `aurora-yt://` sentinel — resolve it to a real YouTube audio
            // URL (using the song's full sentinel, which holds the search query) before fetching.
            val audioUrl = if (provided.startsWith("aurora-yt://")) {
                val sentinel = if (song.streamUrl.startsWith("aurora-yt://")) song.streamUrl else provided
                resolveSentinel(sentinel) ?: throw IOException("No stream found for this track")
            } else provided
            downloadTo(audioUrl, audioFile) { p -> setState(song.id, DownloadState.Downloading(p)) }
            val coverFile = File(dir, "${song.id}.jpg")
            runCatching { if (song.artworkUrl.isNotBlank()) downloadTo(song.artworkUrl, coverFile) {} }
            val entry = DownloadedSong(
                id = song.id, title = song.title, artist = song.artist, album = song.album,
                albumId = song.albumId, artistId = song.artistId, durationSec = song.durationSec,
                audioPath = audioFile.absolutePath,
                coverPath = if (coverFile.exists()) coverFile.absolutePath else "",
                // Original-quality download keeps the source codec; a transcoded one is MP3.
                suffix = if (bitrate == 0) song.suffix else "mp3",
                bitrateKbps = if (bitrate == 0) song.bitrateKbps else bitrate,
                sampleRateHz = song.sampleRateHz,
                bitDepth = song.bitDepth,
                serverId = currentServerIdProvider(),
            )
            _downloads.update { it + (song.id to entry) }
            saveIndex()
            setState(song.id, DownloadState.Done)
        } catch (e: Exception) {
            runCatching { File(dir, "${song.id}.audio").delete() }
            setState(song.id, DownloadState.Failed)
        }
    }

    private fun downloadTo(url: String, file: File, onProgress: (Float) -> Unit) {
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
        // Drop entries whose audio file vanished.
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
