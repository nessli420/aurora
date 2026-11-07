package com.aurora.music.data

import com.aurora.music.model.Song
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

data class PlayEvent(
    val songId: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: String,
    val artistId: String,
    val artworkUrl: String,
    val durationSec: Int,
    val timestamp: Long,
    val listenedMs: Long? = null,
)

data class RankedItem(val id: String, val name: String, val subtitle: String, val artworkUrl: String, val count: Int)

class PlayHistoryStore(filesDir: File) {

    private val file = File(filesDir, "play_history.json")
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var revision = 0L
    private var savedRevision = 0L
    private var listeningKey = ""
    private var listeningStamp = 0L
    private var lastSave = System.nanoTime()
    private var quietSave: Job? = null

    fun endListeningSession() {
        val changed = synchronized(lock) {
            val active = listeningKey.isNotEmpty()
            listeningKey = ""
            if (active) { quietSave?.cancel(); quietSave = null; lastSave = System.nanoTime() }
            active
        }
        if (changed) scope.launch { save() }
    }

    fun recordListening(song: Song, elapsedMs: Long, timestamp: Long = System.currentTimeMillis()) {
        if (song.id.isBlank() || elapsedMs <= 0) return
        awaitLoaded()
        val safety = synchronized(lock) {
            val day = java.time.Instant.ofEpochMilli(timestamp).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            val key = "${song.id}:$day"
            val events = _history.value
            val old = events.firstOrNull()
            if (listeningKey != key || old?.timestamp != listeningStamp || old.songId != song.id) {
                listeningKey = key
                listeningStamp = timestamp
                val e = PlayEvent(song.id, song.title, song.artist, song.album, song.albumId, song.artistId, song.artworkUrl, song.durationSec, timestamp, elapsedMs)
                _history.value = prepend(events, e)
            } else {
                _history.value = withHead(events, old.copy(listenedMs = (old.listenedMs ?: 0) + elapsedMs))
            }
            revision++
            quietSave?.cancel()
            quietSave = null
            val now = System.nanoTime()
            if (now - lastSave >= SAFETY_SAVE_NS) { lastSave = now; true }
            else { quietSave = scope.launch { delay(QUIET_SAVE_MS); save() }; false }
        }
        if (safety) scope.launch { save() }
    }

    class BackupRollback internal constructor(internal val previous: List<PlayEvent>, internal val revision: Long)

    private val loaded = CountDownLatch(1)
    private val _history = MutableStateFlow<List<PlayEvent>>(emptyList())
    val history: StateFlow<List<PlayEvent>> = _history.asStateFlow()

    init {
        thread(name = "play-history-load", isDaemon = true) {
            try {
                val events = load()
                synchronized(lock) { _history.value = events }
            } finally {
                loaded.countDown()
            }
        }
    }

    private fun awaitLoaded() {
        if (loaded.count > 0L) loaded.await()
    }

    fun record(song: Song, timestamp: Long) {
        if (song.id.isEmpty()) return
        val event = PlayEvent(song.id, song.title, song.artist, song.album, song.albumId, song.artistId, song.artworkUrl, song.durationSec, timestamp)
        awaitLoaded()
        synchronized(lock) {
            // drop immediate duplicate re-fire within 10s
            val last = _history.value.firstOrNull()
            if (last != null && last.songId == song.id && timestamp - last.timestamp < 10_000) return
            revision++
            _history.value = prepend(_history.value, event)
        }
        scope.launch { save() }
    }

    fun clear() {
        awaitLoaded()
        synchronized(lock) { revision++; _history.value = emptyList() }
        // Persist the current state when this work runs: a queued delete must never erase a later restore.
        scope.launch { save() }
    }

    fun snapshot(): List<PlayEvent> {
        awaitLoaded()
        return _history.value
    }

    fun restore(events: List<PlayEvent>) {
        awaitLoaded()
        synchronized(lock) { revision++; _history.value = events.take(MAX) }
        scope.launch { save() }
    }

    suspend fun restoreBackup(events: List<PlayEvent>) {
        replaceBackup(events)
    }

    suspend fun replaceBackup(events: List<PlayEvent>): BackupRollback = withContext(Dispatchers.IO) {
        awaitLoaded()
        val restored = events.take(MAX)
        val bytes = gson.toJson(restored, EVENTS_TYPE).toByteArray(Charsets.UTF_8)
        synchronized(lock) {
            val previous = _history.value
            persistBackupFileAtomically(file, bytes)
            val committed = ++revision
            savedRevision = committed
            _history.value = restored
            BackupRollback(previous, committed)
        }
    }

    suspend fun rollbackBackup(token: BackupRollback): Boolean = withContext(Dispatchers.IO) {
        awaitLoaded()
        synchronized(lock) {
            if (revision != token.revision) {
                persistBackupFileAtomically(file, gson.toJson(_history.value, EVENTS_TYPE).toByteArray(Charsets.UTF_8))
                savedRevision = revision
                false
            } else {
                persistBackupFileAtomically(file, gson.toJson(token.previous, EVENTS_TYPE).toByteArray(Charsets.UTF_8))
                savedRevision = ++revision
                _history.value = token.previous
                true
            }
        }
    }

    fun totalPlays(): Int = snapshot().count { it.qualifiesAsPlay }
    fun totalMinutes(): Long = snapshot().sumOf { it.listeningMillis } / 60_000

    fun since(millis: Long): List<PlayEvent> = snapshot().filter { it.timestamp >= millis }

    fun topArtists(events: List<PlayEvent>, limit: Int = 20): List<RankedItem> =
        events.filter { it.artistId.isNotBlank() || it.artist.isNotBlank() }
            .groupBy { it.artistId.ifBlank { it.artist } }
            .map { (key, list) -> RankedItem(list.first().artistId, list.first().artist, "${list.size} plays", list.first().artworkUrl, list.size) }
            .sortedByDescending { it.count }.take(limit)

    fun topSongs(events: List<PlayEvent>, limit: Int = 30): List<RankedItem> =
        events.groupBy { it.songId }
            .map { (_, list) -> RankedItem(list.first().songId, list.first().title, "${list.first().artist} · ${list.size} plays", list.first().artworkUrl, list.size) }
            .sortedByDescending { it.count }.take(limit)

    fun topAlbums(events: List<PlayEvent>, limit: Int = 20): List<RankedItem> =
        events.filter { it.albumId.isNotBlank() || it.album.isNotBlank() }
            .groupBy { it.albumId.ifBlank { it.album } }
            .map { (_, list) -> RankedItem(list.first().albumId, list.first().album, "${list.first().artist} · ${list.size} plays", list.first().artworkUrl, list.size) }
            .sortedByDescending { it.count }.take(limit)

    fun playsByHour(events: List<PlayEvent>): IntArray {
        val out = IntArray(24)
        val cal = java.util.Calendar.getInstance()
        for (e in events) { cal.timeInMillis = e.timestamp; out[cal.get(java.util.Calendar.HOUR_OF_DAY)]++ }
        return out
    }

    fun streak(): Pair<Int, Int> {
        val days = snapshot().map { localDay(it.timestamp) }.toSortedSet().toList()
        if (days.isEmpty()) return 0 to 0
        var longest = 1; var run = 1
        for (i in 1 until days.size) {
            if (days[i] == days[i - 1] + 1) { run++; if (run > longest) longest = run } else run = 1
        }
        val today = localDay(System.currentTimeMillis())
        val daySet = days.toHashSet()
        var current = 0
        var d = when { daySet.contains(today) -> today; daySet.contains(today - 1) -> today - 1; else -> Long.MIN_VALUE }
        while (d != Long.MIN_VALUE && daySet.contains(d)) { current++; d-- }
        return current to longest
    }

    private fun localDay(ts: Long): Long {
        val offset = java.util.TimeZone.getDefault().getOffset(ts)
        return (ts + offset) / 86_400_000L
    }

    private fun load(): List<PlayEvent> = runCatching {
        if (!file.exists()) return@runCatching emptyList<PlayEvent>()
        // pooled while parsing because copy() throws on a gson null, and a failed load wipes the history
        val strings = gson.getAdapter(String::class.java)
        val pool = HashMap<String, String>()
        val pooling = GsonBuilder().registerTypeAdapter(String::class.java, object : TypeAdapter<String>() {
            override fun read(reader: JsonReader): String? = strings.read(reader)?.let { value -> pool.getOrPut(value) { value } }
            override fun write(out: JsonWriter, value: String?) = strings.write(out, value)
        }).create()
        pooling.fromJson<List<PlayEvent>>(file.readText(), EVENTS_TYPE)?.map(::withoutBrowsePrefix) ?: emptyList()
    }.getOrDefault(emptyList())

    // android auto and the library browser used to record their "song_" media ids instead of the song id
    // every string is passed in because copy() throws on a gson null
    private fun withoutBrowsePrefix(event: PlayEvent): PlayEvent =
        if ((event.songId as String?)?.startsWith(BROWSE_PREFIX) == true) event.copy(songId = event.songId.removePrefix(BROWSE_PREFIX),
            title = (event.title as String?).orEmpty(), artist = (event.artist as String?).orEmpty(), album = (event.album as String?).orEmpty(),
            albumId = (event.albumId as String?).orEmpty(), artistId = (event.artistId as String?).orEmpty(),
            artworkUrl = (event.artworkUrl as String?).orEmpty())
        else event

    fun flushNow() {
        synchronized(lock) {
            if (revision == savedRevision) return
            runCatching {
                persistBackupFileAtomically(file, gson.toJson(_history.value, EVENTS_TYPE).toByteArray(Charsets.UTF_8))
                savedRevision = revision
            }
        }
    }

    private fun save() {
        val (version, snapshot) = synchronized(lock) {
            if (revision == savedRevision) return
            revision to _history.value
        }
        val bytes = gson.toJson(snapshot, EVENTS_TYPE).toByteArray(Charsets.UTF_8)
        synchronized(lock) {
            // A queued save must not overwrite a later clear or backup restore.
            if (revision == version && savedRevision != version) runCatching {
                persistBackupFileAtomically(file, bytes)
                savedRevision = version
            }
        }
    }

    // newest events sit in a short array over a shared base so ticks never copy the whole history
    private class Events(val head: Array<PlayEvent>, val base: List<PlayEvent>, val from: Int) : AbstractList<PlayEvent>(), RandomAccess {
        override val size: Int = head.size + base.size - from
        override fun get(index: Int): PlayEvent {
            if (index < 0 || index >= size) throw IndexOutOfBoundsException("Index $index out of bounds for size $size")
            return if (index < head.size) head[index] else base[from + index - head.size]
        }
    }

    private fun prepend(events: List<PlayEvent>, event: PlayEvent): List<PlayEvent> = when {
        events.size >= MAX || (events is Events && events.head.size >= HEAD_LIMIT) ->
            ArrayList<PlayEvent>(minOf(events.size + 1, MAX)).apply {
                add(event)
                for (i in 0 until minOf(events.size, MAX - 1)) add(events[i])
            }
        events is Events -> Events(Array(events.head.size + 1) { if (it == 0) event else events.head[it - 1] }, events.base, events.from)
        else -> Events(arrayOf(event), randomAccess(events), 0)
    }

    private fun withHead(events: List<PlayEvent>, event: PlayEvent): List<PlayEvent> =
        if (events is Events) Events(events.head.copyOf().also { it[0] = event }, events.base, events.from)
        else Events(arrayOf(event), randomAccess(events), 1)

    private fun randomAccess(events: List<PlayEvent>): List<PlayEvent> = if (events is RandomAccess) events else ArrayList(events)

    companion object {
        const val MAX = 250_000
        private const val BROWSE_PREFIX = "song_"
        private const val HEAD_LIMIT = 64
        private const val QUIET_SAVE_MS = 15_000L
        private const val SAFETY_SAVE_NS = 300_000_000_000L
        private val EVENTS_TYPE = object : TypeToken<List<PlayEvent>>() {}.type
    }
}
