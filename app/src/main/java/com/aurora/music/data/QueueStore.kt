package com.aurora.music.data

import android.content.Context
import com.aurora.music.model.Song
import com.aurora.music.util.accentFor
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * A persisted queue track. A trimmed [Song] (no Compose Color — Gson can't round-trip it) carrying
 * everything needed to rebuild a playable MediaItem. Strings are nullable per the project's Gson
 * rule (missing keys come back null, not the Kotlin default).
 */
data class SavedTrack(
    val id: String? = "",
    val title: String? = "",
    val artist: String? = "",
    val album: String? = "",
    val artworkUrl: String? = "",
    val streamUrl: String? = "",
    val albumId: String? = "",
    val artistId: String? = "",
    val suffix: String? = "",
    val path: String? = "",
    val durationSec: Int = 0,
    val bitrateKbps: Int = 0,
    val sampleRateHz: Int = 0,
    val bitDepth: Int = 0,
    val replayGainTrack: Float = 0f,
    val replayGainAlbum: Float = 0f,
    val liked: Boolean = false,
    val explicit: Boolean = false,
) {
    fun toSong(): Song = Song(
        id = id ?: "", title = title ?: "", artist = artist ?: "", album = album ?: "",
        artworkUrl = artworkUrl ?: "", durationSec = durationSec, liked = liked, explicit = explicit,
        accent = accentFor(id ?: ""), streamUrl = streamUrl ?: "", albumId = albumId ?: "", artistId = artistId ?: "",
        suffix = suffix ?: "", bitrateKbps = bitrateKbps, sampleRateHz = sampleRateHz, bitDepth = bitDepth,
        replayGainTrack = replayGainTrack, replayGainAlbum = replayGainAlbum, path = path ?: "",
    )
}

fun Song.toSavedTrack(): SavedTrack = SavedTrack(
    id = id, title = title, artist = artist, album = album, artworkUrl = artworkUrl, streamUrl = streamUrl,
    albumId = albumId, artistId = artistId, suffix = suffix, path = path, durationSec = durationSec,
    bitrateKbps = bitrateKbps, sampleRateHz = sampleRateHz, bitDepth = bitDepth,
    replayGainTrack = replayGainTrack, replayGainAlbum = replayGainAlbum, liked = liked, explicit = explicit,
)

/** A persisted playback session: the queue, where we are in it, and the playback modes. */
data class SavedQueue(
    val tracks: List<SavedTrack>? = emptyList(),
    val currentIndex: Int = 0,
    val positionSec: Int = 0,
    val shuffle: Boolean = false,
    val repeat: Int = 0,           // 0 = off, 1 = all, 2 = one
)

/**
 * Persists the playback queue **per account** so it survives a swipe-away (the service is torn down
 * in onTaskRemoved) and a server switch / logout-and-back. Keyed by the same account key as
 * [AppContainer.currentAccountKey]. Writes are coalesced by a periodic flush so the hot position
 * updates don't hammer the disk.
 */
class QueueStore(context: Context) {
    private val file = File(context.filesDir, "queue_state.json")
    private val gson = Gson()
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val map: MutableMap<String, SavedQueue> = load()
    @Volatile private var dirty = false

    init {
        scope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(3000)
                if (dirty) flushNow()
            }
        }
    }

    private fun load(): MutableMap<String, SavedQueue> = runCatching {
        if (!file.exists()) HashMap()
        else gson.fromJson<MutableMap<String, SavedQueue>>(file.readText(), object : TypeToken<MutableMap<String, SavedQueue>>() {}.type) ?: HashMap()
    }.getOrDefault(HashMap())

    fun get(accountKey: String): SavedQueue? = synchronized(lock) { map[accountKey] }

    fun save(accountKey: String, queue: SavedQueue) {
        if (accountKey.isBlank()) return
        synchronized(lock) { map[accountKey] = queue; dirty = true }
    }

    fun clear(accountKey: String) {
        if (accountKey.isBlank()) return
        synchronized(lock) { if (map.remove(accountKey) != null) dirty = true }
    }

    /** Write to disk now (e.g. just before switching accounts or on app close). */
    fun requestFlush() { scope.launch { flushNow() } }

    private fun flushNow() {
        val snapshot = synchronized(lock) { dirty = false; HashMap(map) }
        runCatching { file.writeText(gson.toJson(snapshot)) }
    }
}
