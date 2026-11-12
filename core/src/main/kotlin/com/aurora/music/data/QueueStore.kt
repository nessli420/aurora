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
    // ties a position entry to its full write; older builds drop it
    val revision: Long? = null,
    // ids of the songs autoplay added, so they keep their heading and still leave when it's turned off
    val autoplay: List<String>? = null,
    // shuffle with autoplay, and the picks it mixed in among your songs
    val smartShuffle: Boolean? = null,
    val smartPicks: List<String>? = null,
)

// per-account so queue survives swipe-away and server switch writes coalesced by periodic flush
class QueueStore(filesDir: File, private val persist: (File, ByteArray) -> Unit = ::persistBackupFileAtomically) {
    private val file = File(filesDir, "queue_state.json")
    // index and position saved alone since the last full write, so playback doesn't rewrite every account's queue
    private val positionsFile = File(filesDir, "queue_positions.json")
    private val gson = Gson()
    private val lock = Any()
    // serializing a big queue takes long, so writes queue here instead of blocking save() on the ui thread
    private val writeLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val positions = HashMap<String, SavedQueue>()
    // parsed off main, every access waits on the lazy so a write can't replace an unread file
    private val map: MutableMap<String, SavedQueue> by lazy { load() }
    @Volatile private var dirty = false
    @Volatile private var positionDirty = false
    private var pendingFlush: Job? = null

    init { scope.launch { map.size } }

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

    private fun load(): MutableMap<String, SavedQueue> = read(file).also { loaded ->
        read(positionsFile).forEach { (key, at) ->
            loaded[key]?.takeIf { at.revision != null && it.revision == at.revision }
                ?.let { loaded[key] = it.copy(currentIndex = at.currentIndex, positionSec = at.positionSec); positions[key] = at }
        }
    }

    private fun read(from: File): MutableMap<String, SavedQueue> = runCatching {
        if (!from.exists()) HashMap()
        else gson.fromJson<MutableMap<String, SavedQueue>>(from.readText(), object : TypeToken<MutableMap<String, SavedQueue>>() {}.type) ?: HashMap()
    }.getOrDefault(HashMap())

    fun get(accountKey: String): SavedQueue? = synchronized(lock) { map[accountKey]?.copy(revision = null) }

    fun save(accountKey: String, queue: SavedQueue) {
        if (accountKey.isBlank()) return
        synchronized(lock) {
            val saved = queue.withoutSpeech()
            val old = map[accountKey]
            // the list on disk is this same instance, so only index and position moved
            if (!dirty && old != null && old.revision != null && old.tracks === saved.tracks && old.shuffle == saved.shuffle && old.repeat == saved.repeat && old.shuffleOrder == saved.shuffleOrder && old.autoplay == saved.autoplay &&
                old.smartShuffle == saved.smartShuffle && old.smartPicks == saved.smartPicks) {
                map[accountKey] = saved.copy(revision = old.revision)
                positions[accountKey] = SavedQueue(tracks = null, currentIndex = saved.currentIndex, positionSec = saved.positionSec, revision = old.revision)
                positionDirty = true
            } else { map[accountKey] = saved; dirty = true }
            scheduleFlush()
        }
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
        synchronized(lock) { if (map.remove(accountKey) != null) { positions.remove(accountKey); dirty = true; scheduleFlush() } }
    }

    fun requestFlush() { scope.launch { flushNow() } }

    /** Await an atomic account restore/removal without letting an older queued flush overwrite it. */
    suspend fun restoreAccount(accountKey: String, queue: SavedQueue?) = withContext(Dispatchers.IO) {
        require(accountKey.isNotBlank())
        synchronized(writeLock) {
            synchronized(lock) {
                val restored = HashMap(map)
                if (queue == null) restored.remove(accountKey) else restored[accountKey] = queue
                val stamped = stamped(restored)
                persist(file, gson.toJson(stamped).toByteArray(Charsets.UTF_8))
                positionsFile.delete()
                // Publish only after the durable replacement succeeds, preserving every other account.
                map.clear(); map.putAll(stamped); dirty = false
                positions.clear(); positionDirty = false
            }
        }
    }

    // a leftover position file no longer matches a new full write
    private fun stamped(queues: Map<String, SavedQueue>): Map<String, SavedQueue> {
        val revision = kotlin.random.Random.nextLong()
        return queues.mapValues { it.value.copy(revision = revision) }
    }

    fun flushNow() {
        // Snapshots are taken in write order, so the last write always holds the newest state. A failed write stays dirty.
        synchronized(writeLock) {
            val (full, moved) = synchronized(lock) {
                if (!dirty && !positionDirty) return
                val snapshot = if (dirty) stamped(map).also { map.putAll(it); positions.clear() } else HashMap(positions)
                (dirty to snapshot).also { dirty = false; positionDirty = false }
            }
            runCatching {
                persist(if (full) file else positionsFile, gson.toJson(moved).toByteArray(Charsets.UTF_8))
                if (full) positionsFile.delete()
            }.onFailure { synchronized(lock) { if (full) dirty = true else positionDirty = true; scheduleFlush() } }
        }
    }
}
