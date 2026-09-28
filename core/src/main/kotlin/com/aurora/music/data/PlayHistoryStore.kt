package com.aurora.music.data

import android.content.Context
import com.aurora.music.model.Song
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

class PlayHistoryStore(context: Context) {

    private val file = File(context.filesDir, "play_history.json")
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var revision = 0L
    private var listeningKey = ""
    private var listeningStamp = 0L
    private var lastSave = 0L

    fun endListeningSession() {
        val changed = synchronized(lock) { val active = listeningKey.isNotEmpty(); listeningKey = ""; active }
        if (changed) scope.launch { save() }
    }

    fun recordListening(song: Song, elapsedMs: Long, timestamp: Long = System.currentTimeMillis()) {
        if (song.id.isBlank() || elapsedMs <= 0) return
        synchronized(lock) {
            val day = java.time.Instant.ofEpochMilli(timestamp).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            val key = "${song.id}:$day"
            val old = _history.value.firstOrNull()
            if (listeningKey != key || old?.timestamp != listeningStamp || old.songId != song.id) {
                listeningKey = key
                listeningStamp = timestamp
                val e = PlayEvent(song.id, song.title, song.artist, song.album, song.albumId, song.artistId, song.artworkUrl, song.durationSec, timestamp, elapsedMs)
                _history.value = listOf(e) + _history.value.take(MAX - 1)
            } else {
                _history.value = listOf(old.copy(listenedMs = (old.listenedMs ?: 0) + elapsedMs)) + _history.value.drop(1)
            }
            revision++
        }
        if (timestamp - lastSave >= 15_000) { lastSave = timestamp; scope.launch { save() } }
    }

    internal class BackupRollback internal constructor(internal val previous: List<PlayEvent>, internal val revision: Long)

    private val _history = MutableStateFlow(load())
    val history: StateFlow<List<PlayEvent>> = _history.asStateFlow()

    fun record(song: Song, timestamp: Long) {
        if (song.id.isEmpty()) return
        val event = PlayEvent(song.id, song.title, song.artist, song.album, song.albumId, song.artistId, song.artworkUrl, song.durationSec, timestamp)
        synchronized(lock) {
            // drop immediate duplicate re-fire within 10s
            val last = _history.value.firstOrNull()
            if (last != null && last.songId == song.id && timestamp - last.timestamp < 10_000) return
            revision++
            _history.value = (listOf(event) + _history.value).take(MAX)
        }
        scope.launch { save() }
    }

    fun clear() {
        synchronized(lock) { revision++; _history.value = emptyList() }
        // Persist the current state when this work runs: a queued delete must never erase a later restore.
        scope.launch { save() }
    }

    fun snapshot(): List<PlayEvent> = _history.value
    fun restore(events: List<PlayEvent>) {
        synchronized(lock) { revision++; _history.value = events.take(MAX) }
        scope.launch { save() }
    }

    internal suspend fun restoreBackup(events: List<PlayEvent>) {
        replaceBackup(events)
    }

    internal suspend fun replaceBackup(events: List<PlayEvent>): BackupRollback = withContext(Dispatchers.IO) {
        val restored = events.take(MAX)
        val bytes = gson.toJson(restored).toByteArray(Charsets.UTF_8)
        synchronized(lock) {
            val previous = _history.value
            persistBackupFileAtomically(file, bytes)
            val committed = ++revision
            _history.value = restored
            BackupRollback(previous, committed)
        }
    }

    internal suspend fun rollbackBackup(token: BackupRollback): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (revision != token.revision) {
                persistBackupFileAtomically(file, gson.toJson(_history.value).toByteArray(Charsets.UTF_8))
                false
            } else {
                persistBackupFileAtomically(file, gson.toJson(token.previous).toByteArray(Charsets.UTF_8))
                revision++
                _history.value = token.previous
                true
            }
        }
    }

    fun totalPlays(): Int = _history.value.count { it.qualifiesAsPlay }
    fun totalMinutes(): Long = _history.value.sumOf { it.listeningMillis } / 60_000

    fun since(millis: Long): List<PlayEvent> = _history.value.filter { it.timestamp >= millis }

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
        val days = _history.value.map { localDay(it.timestamp) }.toSortedSet().toList()
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
        val type = object : TypeToken<List<PlayEvent>>() {}.type
        gson.fromJson<List<PlayEvent>>(file.readText(), type) ?: emptyList()
    }.getOrDefault(emptyList())

    private fun save() {
        val (version, snapshot) = synchronized(lock) { revision to _history.value }
        val bytes = gson.toJson(snapshot).toByteArray(Charsets.UTF_8)
        synchronized(lock) {
            // A queued save must not overwrite a later clear or backup restore.
            if (revision == version) runCatching { persistBackupFileAtomically(file, bytes) }
        }
    }

    companion object { const val MAX = 250_000 }
}
