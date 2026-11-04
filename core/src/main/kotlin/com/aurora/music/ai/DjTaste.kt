package com.aurora.music.ai

import com.aurora.music.data.PlayEvent
import com.aurora.music.model.Song
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

enum class DjSegment { REPEAT, HOUR, PAST, NEW, LIKED, ARTIST, BECAUSE, DEEP, MIX }

data class DjSet(val segment: DjSegment, val artist: String?, val songs: List<Song>)

internal fun djTrackKey(artist: String, title: String) = "${DjKeywords.norm(artist)}|${DjKeywords.norm(title)}"

internal fun djPrimaryArtist(artist: String) = artist.substringBefore(",").substringBefore(" feat").trim()

class DjSession(val plan: List<DjSegment> = (DjSegment.entries - DjSegment.MIX).shuffled()) {
    var next = 0
    var sets = 0
    val placed = LinkedHashSet<String>()
    val artists = HashSet<String>()
    private val tracks = HashSet<String>()

    private fun key(song: Song) = djTrackKey(song.artist, song.title)

    fun heard(song: Song) = key(song) in tracks

    fun mark(songs: List<Song>) = songs.forEach { placed += it.id; tracks += key(it) }
}

internal class DjTaste(events: List<PlayEvent>, now: Long, offsetMs: Long = TimeZone.getDefault().getOffset(now).toLong()) {
    val plays = HashMap<String, Int>()
    val repeat = HashMap<String, Int>()
    val hour = HashMap<String, Int>()
    val past = HashMap<String, Int>()
    val justHeard = HashSet<String>()
    val lately = HashSet<String>()
    val keys = HashMap<String, String>()
    val artists: List<String>
    val stats: DjStats?

    init {
        var weekMs = 0L
        val titles = HashMap<String, String>()
        val last = HashMap<String, Long>()
        val older = HashMap<String, Int>()
        val recentArtists = HashMap<String, Int>()
        val allArtists = HashMap<String, Int>()
        val weekArtists = HashMap<String, Int>()
        val monthArtists = HashMap<String, Int>()
        val firstHeard = HashMap<String, Long>()
        val days = HashSet<Long>()
        var oldest = now
        fun hourOf(time: Long) = Math.floorMod(time + offsetMs, DAY) / HOUR
        val nowHour = hourOf(now)
        for (e in events) {
            val id = e.songId
            if (id.isEmpty() || id.startsWith("radio:") || id.startsWith("podcast:") || skipped(e)) continue
            val age = now - e.timestamp
            plays.merge(id, 1, Int::plus)
            last.merge(id, e.timestamp, ::maxOf)
            if (id !in keys) keys[id] = djTrackKey(e.artist, e.title)
            days += Math.floorDiv(e.timestamp + offsetMs, DAY)
            oldest = minOf(oldest, e.timestamp)
            if (age < 4 * HOUR) justHeard += id
            if (age < 14 * DAY) lately += id
            if (age < 7 * DAY) weekMs += e.listenedMs ?: (e.durationSec * 1000L)
            titles.putIfAbsent(id, e.title)
            if (age < 30 * DAY) repeat.merge(id, 1, Int::plus)
            if (age >= PAST_AGE) older.merge(id, 1, Int::plus)
            val apart = abs(hourOf(e.timestamp) - nowHour)
            if (min(apart, 24 - apart) * HOUR <= HOUR) hour.merge(id, 1, Int::plus)
            val artist = djPrimaryArtist(e.artist)
            if (artist.isNotEmpty()) {
                allArtists.merge(artist, 1, Int::plus)
                firstHeard.merge(artist, e.timestamp, ::minOf)
                if (age < 45 * DAY) recentArtists.merge(artist, 1, Int::plus)
                if (age < 30 * DAY) monthArtists.merge(artist, 1, Int::plus)
                if (age < 7 * DAY) weekArtists.merge(artist, 1, Int::plus)
            }
        }
        repeat.values.removeAll { it < 2 }
        hour.values.removeAll { it < 2 }
        older.forEach { (id, count) -> if (now - (last[id] ?: now) >= PAST_AGE) past[id] = count }
        artists = (recentArtists.entries.sortedByDescending { it.value } + allArtists.entries.sortedByDescending { it.value }).map { it.key }.distinct()
        val top = repeat.maxByOrNull { it.value }
        val hours = (weekMs / HOUR).toInt()
        val today = Math.floorDiv(now + offsetMs, DAY)
        val from = if (today in days) today else today - 1
        val streak = generateSequence(from) { it - 1 }.takeWhile { it in days }.count()
        // everything looks new until there's a history to compare against
        val fresh = if (now - oldest < 60 * DAY) null else monthArtists.filter { (a, n) -> n >= 3 && now - (firstHeard[a] ?: 0L) < 30 * DAY }.maxByOrNull { it.value }?.key
        stats = if (hours < 2 && (top?.value ?: 0) < 3) null else DjStats(hours, weekArtists.maxByOrNull { it.value }?.key, top?.let { titles[it.key] }, top?.value ?: 0,
            weekArtists.size, streak, fresh, monthArtists.maxByOrNull { it.value }?.key)
    }

    // a quick skip says nothing good about a track
    private fun skipped(e: PlayEvent) = e.listenedMs?.let { it < 30_000 && it < e.durationSec * 500L } == true

    companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
        const val PAST_AGE = 45 * DAY

        fun <K> sample(weights: Map<K, Int>, n: Int, random: Random): List<K> = weights.entries
            // the root keeps favourites ahead without letting them take every slot
            .map { it.key to random.nextDouble().pow(1.0 / sqrt(it.value.toDouble())) }
            .sortedByDescending { it.second }.take(n).map { it.first }
    }
}
