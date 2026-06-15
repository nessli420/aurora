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
)

data class RankedItem(val id: String, val name: String, val subtitle: String, val artworkUrl: String, val count: Int)

class PlayHistoryStore(context: Context) {

    private val file = File(context.filesDir, "play_history.json")
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _history = MutableStateFlow(load())
    val history: StateFlow<List<PlayEvent>> = _history.asStateFlow()

    fun record(song: Song, timestamp: Long) {
        if (song.id.isEmpty()) return
        val event = PlayEvent(song.id, song.title, song.artist, song.album, song.albumId, song.artistId, song.artworkUrl, song.durationSec, timestamp)
        // drop immediate duplicate re-fire within 10s
        val last = _history.value.firstOrNull()
        if (last != null && last.songId == song.id && timestamp - last.timestamp < 10_000) return
        _history.update { (listOf(event) + it).take(MAX) }
        scope.launch { save() }
    }

    fun clear() {
        _history.value = emptyList()
        scope.launch { runCatching { file.delete() } }
    }

    fun snapshot(): List<PlayEvent> = _history.value
    fun restore(events: List<PlayEvent>) {
        _history.value = events.take(MAX)
        scope.launch { save() }
    }

    fun totalPlays(): Int = _history.value.size
    fun totalMinutes(): Long = _history.value.sumOf { it.durationSec.toLong() } / 60

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

    private fun MutableStateFlow<List<PlayEvent>>.update(block: (List<PlayEvent>) -> List<PlayEvent>) { value = block(value) }

    private fun load(): List<PlayEvent> = runCatching {
        if (!file.exists()) return@runCatching emptyList<PlayEvent>()
        val type = object : TypeToken<List<PlayEvent>>() {}.type
        gson.fromJson<List<PlayEvent>>(file.readText(), type) ?: emptyList()
    }.getOrDefault(emptyList())

    private fun save() = runCatching { file.writeText(gson.toJson(_history.value)) }

    companion object { const val MAX = 3000 }
}
