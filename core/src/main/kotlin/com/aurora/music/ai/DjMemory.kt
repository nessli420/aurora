package com.aurora.music.ai

import com.aurora.music.data.persistBackupFileAtomically
import com.aurora.music.model.Song
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import kotlin.random.Random

// what the dj has already done, so every session doesn't open with the same tracks and lines
class DjMemory(private val file: File?, private val now: () -> Long = System::currentTimeMillis) {
    private class Saved(
        @SerializedName("tracks") val tracks: Map<String, Long>? = null,
        @SerializedName("artists") val artists: Map<String, Long>? = null,
        @SerializedName("facts") val facts: Map<String, Long>? = null,
        @SerializedName("openings") val openings: List<String>? = null,
        @SerializedName("turn") val turn: Int? = null,
    )

    private companion object {
        const val DAY = 86_400_000L
        const val TRACK_REST = 3 * DAY
        const val TRACK_STALE = 14 * DAY
        const val KEEP = 30 * DAY
        const val MAX_TRACKS = 2000
        const val ARTIST_REST = 2 * DAY
        const val FACT_GAP = 3_600_000L
        const val FACT_REST = 3 * DAY
    }

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val tracks = HashMap<String, Long>()
    private val artists = HashMap<String, Long>()
    private val facts = HashMap<String, Long>()
    private val openings = ArrayList<String>()
    private var turn = 0
    private var loaded = false

    private fun load() {
        if (loaded) return
        loaded = true
        val saved = runCatching { file?.takeIf { it.exists() }?.readText()?.let { gson.fromJson(it, Saved::class.java) } }.getOrNull() ?: return
        saved.tracks?.let(tracks::putAll)
        saved.artists?.let(artists::putAll)
        saved.facts?.let(facts::putAll)
        saved.openings?.let(openings::addAll)
        turn = saved.turn ?: 0
    }

    private fun save() {
        val target = file ?: return
        // callers are on the main thread, and it shouldn't wait on this monitor for the json either
        scope.launch {
            val saved = synchronized(this@DjMemory) { Saved(HashMap(tracks), HashMap(artists), HashMap(facts), ArrayList(openings), turn) }
            runCatching { persistBackupFileAtomically(target, gson.toJson(saved).toByteArray()) }
        }
    }

    @Synchronized fun resting(key: String): Boolean {
        load()
        return now() - (tracks[key] ?: 0L) < TRACK_REST
    }

    // tracks it played days ago come back only after everything it hasn't touched in a while
    @Synchronized fun freshFirst(songs: List<Song>): List<Song> {
        load()
        val at = now()
        val placed = songs.map { it to (tracks[djTrackKey(it.artist, it.title)] ?: 0L) }.filter { at - it.second >= TRACK_REST }
        val (stale, warm) = placed.partition { at - it.second >= TRACK_STALE }
        return stale.map { it.first } + warm.sortedBy { it.second }.map { it.first }
    }

    @Synchronized fun placed(songs: List<Song>) {
        load()
        val at = now()
        songs.forEach { tracks[djTrackKey(it.artist, it.title)] = at }
        tracks.values.removeAll { at - it > KEEP }
        if (tracks.size > MAX_TRACKS) tracks.entries.sortedBy { it.value }.take(tracks.size - MAX_TRACKS).forEach { tracks.remove(it.key) }
        save()
    }

    @Synchronized fun artistResting(name: String): Boolean {
        load()
        return now() - (artists[name] ?: 0L) < ARTIST_REST
    }

    @Synchronized fun usedArtist(name: String) {
        load()
        artists[name] = now()
        artists.values.removeAll { now() - it > KEEP }
        save()
    }

    @Synchronized fun plan(random: Random): List<DjSegment> {
        load()
        val recent = openings.takeLast(3)
        return (DjSegment.entries - DjSegment.MIX).shuffled(random).sortedBy { it.name in recent }
    }

    @Synchronized fun opened(segment: DjSegment) {
        load()
        openings += segment.name
        while (openings.size > 6) openings.removeAt(0)
        save()
    }

    // a stat is news once: never twice within days, and not every time the dj starts
    @Synchronized fun nextFact(stats: DjStats?, random: Random = Random.Default): DjFact? {
        load()
        val at = now()
        if (stats == null || at - (facts.values.maxOrNull() ?: 0L) < FACT_GAP) return null
        val fact = stats.facts().filter { at - (facts[it.name] ?: 0L) >= FACT_REST }.randomOrNull(random) ?: return null
        facts[fact.name] = at
        save()
        return fact
    }

    @Synchronized fun nextTurn(): Int {
        load()
        turn = (turn + 1) and 0xFFFF
        save()
        return turn
    }

    @Synchronized fun clear() {
        load()
        tracks.clear(); artists.clear(); facts.clear(); openings.clear(); turn = 0
        save()
    }
}
