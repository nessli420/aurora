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
import kotlinx.coroutines.withContext
import java.io.File

// no compose color gson cant round-trip it strings nullable per gson rule
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
    val genre: String? = "",
) {
    fun toSong(): Song = Song(
        id = id ?: "", title = title ?: "", artist = artist ?: "", album = album ?: "",
        artworkUrl = artworkUrl ?: "", durationSec = durationSec, liked = liked, explicit = explicit,
        accent = accentFor(id ?: ""), streamUrl = streamUrl ?: "", albumId = albumId ?: "", artistId = artistId ?: "",
        suffix = suffix ?: "", bitrateKbps = bitrateKbps, sampleRateHz = sampleRateHz, bitDepth = bitDepth,
        replayGainTrack = replayGainTrack, replayGainAlbum = replayGainAlbum, path = path ?: "", genre = genre ?: "",
    )
}

fun Song.toSavedTrack(): SavedTrack = SavedTrack(
    id = id, title = title, artist = artist, album = album, artworkUrl = artworkUrl, streamUrl = streamUrl,
    albumId = albumId, artistId = artistId, suffix = suffix, path = path, durationSec = durationSec,
    bitrateKbps = bitrateKbps, sampleRateHz = sampleRateHz, bitDepth = bitDepth,
    replayGainTrack = replayGainTrack, replayGainAlbum = replayGainAlbum, liked = liked, explicit = explicit,
    genre = genre,
)

data class SavedQueue(
    val tracks: List<SavedTrack>? = emptyList(),
    val currentIndex: Int = 0,
    val positionSec: Int = 0,
    val shuffle: Boolean = false,
    val repeat: Int = 0,           // 0 off 1 all 2 one
)

// per-account so queue survives swipe-away and server switch writes coalesced by periodic flush
class QueueStore internal constructor(context: Context, private val persist: (File, ByteArray) -> Unit) {
    constructor(context: Context) : this(context, ::persistBackupFileAtomically)
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

    fun requestFlush() { scope.launch { flushNow() } }

    /** Await an atomic account restore/removal without letting an older queued flush overwrite it. */
    internal suspend fun restoreAccount(accountKey: String, queue: SavedQueue?) = withContext(Dispatchers.IO) {
        require(accountKey.isNotBlank())
        synchronized(lock) {
            val restored = HashMap(map)
            if (queue == null) restored.remove(accountKey) else restored[accountKey] = queue
            persist(file, gson.toJson(restored).toByteArray(Charsets.UTF_8))
            // Publish only after the durable replacement succeeds, preserving every other account.
            map.clear(); map.putAll(restored); dirty = false
        }
    }

    private fun flushNow() = synchronized(lock) {
        if (!dirty) return@synchronized
        // Serialize the write as well as the snapshot. A failed ordinary write stays dirty.
        runCatching {
            persist(file, gson.toJson(map).toByteArray(Charsets.UTF_8))
            dirty = false
        }
    }
}
