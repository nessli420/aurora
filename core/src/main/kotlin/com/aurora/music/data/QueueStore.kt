package com.aurora.music.data

import com.aurora.music.model.Song
import com.aurora.music.util.accentArgbFor
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
    val playbackSource: PlaybackSourceIdentity? = null,
    val playbackCollection: PlaybackCollectionIdentity? = null,
) {
    fun toSong(): Song = Song(
        id = id ?: "", title = title ?: "", artist = artist ?: "", album = album ?: "",
        artworkUrl = artworkUrl ?: "", durationSec = durationSec, liked = liked, explicit = explicit,
        accentArgb = accentArgbFor(id ?: ""), streamUrl = streamUrl ?: "", albumId = albumId ?: "", artistId = artistId ?: "",
        suffix = suffix ?: "", bitrateKbps = bitrateKbps, sampleRateHz = sampleRateHz, bitDepth = bitDepth,
        replayGainTrack = replayGainTrack, replayGainAlbum = replayGainAlbum, path = path ?: "", genre = genre ?: "",
        playbackSource = playbackSource, playbackCollection = playbackCollection,
    )
}

fun Song.toSavedTrack(): SavedTrack = SavedTrack(
    id = id, title = title, artist = artist, album = album, artworkUrl = artworkUrl, streamUrl = streamUrl,
    albumId = albumId, artistId = artistId, suffix = suffix, path = path, durationSec = durationSec,
    bitrateKbps = bitrateKbps, sampleRateHz = sampleRateHz, bitDepth = bitDepth,
    replayGainTrack = replayGainTrack, replayGainAlbum = replayGainAlbum, liked = liked, explicit = explicit,
    genre = genre,
    playbackSource = playbackSource, playbackCollection = playbackCollection,
)

data class SavedQueue(
    val tracks: List<SavedTrack>? = emptyList(),
    val currentIndex: Int = 0,
    val positionSec: Int = 0,
    val shuffle: Boolean = false,
    val repeat: Int = 0,           // 0 off 1 all 2 one
    val shuffleOrder: List<String>? = null,
)

// per-account so queue survives swipe-away and server switch writes coalesced by periodic flush
class QueueStore(filesDir: File, private val persist: (File, ByteArray) -> Unit = ::persistBackupFileAtomically) {
    private val file = File(filesDir, "queue_state.json")
    private val gson = Gson()
    private val lock = Any()
    // serializing a big queue takes long, so writes queue here instead of blocking save() on the ui thread
    private val writeLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val map: MutableMap<String, SavedQueue> = load()
    @Volatile private var dirty = false
    private var pendingFlush: Job? = null

    // one delayed flush per burst of saves instead of a forever-running poll
    private fun scheduleFlush() {
        if (pendingFlush?.isActive == true) return
        pendingFlush = scope.launch {
            delay(3000)
            // clear first so a save landing during the write schedules its own flush
            synchronized(lock) { pendingFlush = null }
            flushNow()
        }
    }

    private fun load(): MutableMap<String, SavedQueue> = runCatching {
        if (!file.exists()) HashMap()
        else gson.fromJson<MutableMap<String, SavedQueue>>(file.readText(), object : TypeToken<MutableMap<String, SavedQueue>>() {}.type) ?: HashMap()
    }.getOrDefault(HashMap())

    fun get(accountKey: String): SavedQueue? = synchronized(lock) { map[accountKey] }

    fun save(accountKey: String, queue: SavedQueue) {
        if (accountKey.isBlank()) return
        synchronized(lock) { map[accountKey] = queue.withoutSpeech(); dirty = true; scheduleFlush() }
    }

    // dj speech is a throwaway file, a restored queue carries on from the music around it
    private fun SavedQueue.withoutSpeech(): SavedQueue {
        val all = tracks.orEmpty()
        fun SavedTrack.speech() = id.orEmpty().startsWith(com.aurora.music.ai.DJ_SPEECH_PREFIX)
        if (all.none { it.speech() }) return this
        return copy(
            tracks = all.filterNot { it.speech() },
            currentIndex = (currentIndex - all.take(currentIndex).count { it.speech() }).coerceAtLeast(0),
            positionSec = if (all.getOrNull(currentIndex)?.speech() == true) 0 else positionSec,
            shuffleOrder = shuffleOrder?.filterNot { it.startsWith(com.aurora.music.ai.DJ_SPEECH_PREFIX) },
        )
    }

    fun clear(accountKey: String) {
        if (accountKey.isBlank()) return
        synchronized(lock) { if (map.remove(accountKey) != null) { dirty = true; scheduleFlush() } }
    }

    fun requestFlush() { scope.launch { flushNow() } }

    /** Await an atomic account restore/removal without letting an older queued flush overwrite it. */
    suspend fun restoreAccount(accountKey: String, queue: SavedQueue?) = withContext(Dispatchers.IO) {
        require(accountKey.isNotBlank())
        synchronized(writeLock) {
            synchronized(lock) {
                val restored = HashMap(map)
                if (queue == null) restored.remove(accountKey) else restored[accountKey] = queue
                persist(file, gson.toJson(restored).toByteArray(Charsets.UTF_8))
                // Publish only after the durable replacement succeeds, preserving every other account.
                map.clear(); map.putAll(restored); dirty = false
            }
        }
    }

    fun flushNow() {
        // Snapshots are taken in write order, so the last write always holds the newest state. A failed write stays dirty.
        synchronized(writeLock) {
            val snapshot = synchronized(lock) {
                if (!dirty) return
                dirty = false
                HashMap(map)
            }
            runCatching { persist(file, gson.toJson(snapshot).toByteArray(Charsets.UTF_8)) }
                .onFailure { synchronized(lock) { dirty = true; scheduleFlush() } }
        }
    }
}
