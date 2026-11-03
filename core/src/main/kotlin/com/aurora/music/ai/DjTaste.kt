package com.aurora.music.ai

import com.aurora.music.data.PlayEvent
import com.aurora.music.model.Song
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

enum class DjSegment { REPEAT, HOUR, PAST, NEW, LIKED, ARTIST, MIX }

data class DjSet(val segment: DjSegment, val artist: String?, val songs: List<Song>)

class DjSession(val plan: List<DjSegment> = listOf(DjSegment.REPEAT) + (DjSegment.entries - DjSegment.REPEAT - DjSegment.MIX).shuffled()) {
    var next = 0
    val placed = LinkedHashSet<String>()
    val artists = HashSet<String>()
    private val tracks = HashSet<String>()

    private fun key(song: Song) = "${DjKeywords.norm(song.artist)}|${DjKeywords.norm(song.title)}"

    fun heard(song: Song) = key(song) in tracks

    fun mark(songs: List<Song>) = songs.forEach { placed += it.id; tracks += key(it) }
}

internal class DjTaste(events: List<PlayEvent>, now: Long, offsetMs: Long = TimeZone.getDefault().getOffset(now).toLong()) {
    val plays = HashMap<String, Int>()
    val repeat = HashMap<String, Int>()
    val hour = HashMap<String, Int>()
    val past = HashMap<String, Int>()
    val justHeard = HashSet<String>()
    val artists: List<String>

    init {
        val last = HashMap<String, Long>()
        val older = HashMap<String, Int>()
        val recentArtists = HashMap<String, Int>()
        val allArtists = HashMap<String, Int>()
        fun hourOf(time: Long) = Math.floorMod(time + offsetMs, DAY) / HOUR
        val nowHour = hourOf(now)
        for (e in events) {
            val id = e.songId
            if (id.isEmpty() || id.startsWith("radio:") || id.startsWith("podcast:") || skipped(e)) continue
            val age = now - e.timestamp
            plays.merge(id, 1, Int::plus)
            last.merge(id, e.timestamp, ::maxOf)
            if (age < 4 * HOUR) justHeard += id
            if (age < 30 * DAY) repeat.merge(id, 1, Int::plus)
            if (age >= 60 * DAY) older.merge(id, 1, Int::plus)
            val apart = abs(hourOf(e.timestamp) - nowHour)
            if (min(apart, 24 - apart) * HOUR <= HOUR) hour.merge(id, 1, Int::plus)
            val artist = e.artist.substringBefore(",").substringBefore(" feat").trim()
            if (artist.isNotEmpty()) {
                allArtists.merge(artist, 1, Int::plus)
                if (age < 45 * DAY) recentArtists.merge(artist, 1, Int::plus)
            }
        }
        repeat.values.removeAll { it < 2 }
        hour.values.removeAll { it < 2 }
        older.forEach { (id, count) -> if (count >= 2 && now - (last[id] ?: now) >= 60 * DAY) past[id] = count }
        artists = (recentArtists.entries.sortedByDescending { it.value } + allArtists.entries.sortedByDescending { it.value }).map { it.key }.distinct()
    }

    // a quick skip says nothing good about a track
    private fun skipped(e: PlayEvent) = e.listenedMs?.let { it < 30_000 && it < e.durationSec * 500L } == true

    companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR

        fun <K> sample(weights: Map<K, Int>, n: Int, random: Random): List<K> = weights.entries
            .map { it.key to random.nextDouble().pow(1.0 / it.value) }
            .sortedByDescending { it.second }.take(n).map { it.first }
    }
}
