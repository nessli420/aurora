package com.aurora.music.data

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

data class Share(val label: String, val fraction: Float, val minutes: Long)

data class ZoneSummary(val zone: String, val minutes: Long, val fraction: Float, val topGenre: String?, val topArtist: String?, val peakHour: Int?)

enum class InsightBadge { NIGHT_LISTENER, MORNING_LISTENER, IN_ORDER_LISTENER, SHUFFLER, EXPLORER, REPEAT_HEAVY, LOSSLESS, COMPLETIONIST }

// value is a percentage, except for explorer where it counts new artists
data class Badge(val kind: InsightBadge, val value: Int)

data class InsightSlice(
    val minutes: Long,
    val plays: Int,
    val genres: List<Share>,
    val topArtist: String?,
    val outputs: List<Share>,
    val zones: List<Share>,
    val averageSessionMinutes: Int,
)

data class InsightsReport(
    val minutes: Long,
    val plays: Int,
    val artists: Int,
    val newArtists: Int,
    val heatmap: List<List<Long>>,
    val peakHour: Int?,
    val sessions: Int,
    val averageSessionMinutes: Int,
    val longestSessionMinutes: Int,
    val completion: Float?,
    val skips: Float?,
    val shuffle: Float?,
    val repeats: Float?,
    val inOrder: Float?,
    val genres: List<Share>,
    val transitions: List<Pair<String, String>>,
    val outputs: List<Share>,
    val lossless: Float?,
    val hiRes: Float?,
    val autoEq: Float?,
    val convolution: Float?,
    val replayGain: Float?,
    val bitPerfect: Float?,
    val crossfeed: Float?,
    val formats: List<Share>,
    val zones: List<ZoneSummary>,
    val badges: List<Badge>,
) {
    val empty: Boolean get() = plays == 0
}

object Insights {
    private const val SESSION_GAP_MS = 30 * 60_000L
    private const val WEEK_MS = 7 * 24 * 3_600_000L

    fun counts(play: DetailedPlay): Boolean {
        val duration = play.track.durationSec.takeIf { it > 0 } ?: 60
        return play.listenedMs >= minOf(30_000L, duration * 500L)
    }

    fun artistKey(name: String?): String = name.orEmpty().trim().lowercase(Locale.ROOT)

    fun genreOf(play: DetailedPlay): String? = play.context.genre?.let(GenreText::split)?.firstOrNull()
        ?.split(';', ',', '/')?.firstOrNull()?.trim()?.takeIf(String::isNotEmpty)

    fun dayAndHour(millis: Long, zone: ZoneId): Pair<Int, Int> {
        val time = Instant.ofEpochMilli(millis).atZone(zone)
        return (time.dayOfWeek.value - 1) to time.hour
    }

    fun build(plays: List<DetailedPlay>, firstArtistPlay: Map<String, Long>, from: Long, zone: ZoneId = ZoneId.systemDefault()): InsightsReport {
        val sorted = plays.sortedBy { it.startedAt }
        val counted = sorted.filter(::counts)
        val totalMs = sorted.sumOf { it.listenedMs }
        val heatmap = List(7) { LongArray(24) }
        sorted.forEach { p -> val (day, hour) = dayAndHour(p.startedAt, zone); heatmap[day][hour] += p.listenedMs }
        val byHour = LongArray(24) { h -> heatmap.sumOf { it[h] } }
        val sessions = sessions(sorted)
        val finished = sorted.filter { it.ending != null && !it.imported }
        val artistKeys = counted.map { artistKey(it.track.artist) }.filter(String::isNotEmpty).toSet()
        val newArtists = artistKeys.count { (firstArtistPlay[it] ?: Long.MAX_VALUE) >= from }
        val shuffleKnown = counted.filter { it.context.shuffle != null }
        val report = InsightsReport(
            minutes = totalMs / 60_000,
            plays = counted.size,
            artists = artistKeys.size,
            newArtists = newArtists,
            heatmap = heatmap.map { it.toList() },
            peakHour = byHour.indices.maxByOrNull { byHour[it] }?.takeIf { byHour[it] > 0 },
            sessions = sessions.size,
            averageSessionMinutes = if (sessions.isEmpty()) 0 else (sessions.average() / 60_000).roundToInt(),
            longestSessionMinutes = ((sessions.maxOrNull() ?: 0L) / 60_000).toInt(),
            completion = finished.fraction { it.ending == PlayEnding.COMPLETED },
            skips = finished.fraction { it.ending == PlayEnding.SKIPPED },
            shuffle = shuffleKnown.fraction { it.context.shuffle == true },
            repeats = repeats(counted),
            inOrder = shuffleKnown.fraction { it.context.shuffle == false && it.context.playlist == null },
            genres = shares(sorted) { genreOf(it) }.take(6),
            transitions = transitions(sorted),
            outputs = shares(sorted) { it.context.output },
            lossless = sorted.timeShare({ it.context.lossless }) { it },
            hiRes = sorted.timeShare({ it.context.sampleRateHz }) { rate -> rate > 48_000 },
            autoEq = sorted.timeShare({ it.context.autoEq }) { it },
            convolution = sorted.timeShare({ it.context.convolution }) { it },
            replayGain = sorted.timeShare({ it.context.replayGain }) { it != 0 },
            bitPerfect = sorted.timeShare({ it.context.bitPerfect }) { it },
            crossfeed = sorted.timeShare({ it.context.crossfeed }) { it },
            formats = shares(sorted) { formatName(it.context.codec) }.take(5),
            zones = zones(sorted, zone),
            badges = emptyList(),
        )
        return report.copy(badges = badges(report, sorted, counted.size, finished.size, zone, totalMs))
    }

    fun slice(plays: List<DetailedPlay>, zone: ZoneId = ZoneId.systemDefault(), keep: (day: Int, hour: Int) -> Boolean): InsightSlice {
        val chosen = plays.filter { val (day, hour) = dayAndHour(it.startedAt, zone); keep(day, hour) }.sortedBy { it.startedAt }
        val sessions = sessions(chosen)
        return InsightSlice(
            minutes = chosen.sumOf { it.listenedMs } / 60_000,
            plays = chosen.count(::counts),
            genres = shares(chosen) { genreOf(it) }.take(3),
            topArtist = topBy(chosen) { it.track.artist.takeIf(String::isNotBlank) },
            outputs = shares(chosen) { it.context.output },
            zones = shares(chosen) { it.context.zone },
            averageSessionMinutes = if (sessions.isEmpty()) 0 else (sessions.average() / 60_000).roundToInt(),
        )
    }

    fun formatName(codec: String?): String? {
        val c = codec?.lowercase(Locale.ROOT)?.takeIf(String::isNotBlank) ?: return null
        return when {
            "flac" in c -> "FLAC"
            "alac" in c -> "ALAC"
            "dsd" in c || "dsf" in c || "dff" in c -> "DSD"
            "opus" in c -> "Opus"
            "vorbis" in c || c == "ogg" -> "Vorbis"
            "mp4a" in c || "aac" in c -> "AAC"
            "mpeg" in c || "mp3" in c -> "MP3"
            "raw" in c || "pcm" in c || "wav" in c || "aiff" in c -> "PCM"
            else -> c.substringAfterLast('/').uppercase(Locale.ROOT)
        }
    }

    private fun sessions(sorted: List<DetailedPlay>): List<Long> {
        val out = mutableListOf<Long>()
        var end = Long.MIN_VALUE
        sorted.forEach { p ->
            if (out.isEmpty() || p.startedAt - end > SESSION_GAP_MS) out += 0L
            out[out.lastIndex] += p.listenedMs
            end = maxOf(end, p.endedAt)
        }
        return out
    }

    private fun repeats(counted: List<DetailedPlay>): Float? {
        if (counted.isEmpty()) return null
        val last = HashMap<String, Long>()
        var repeated = 0
        counted.forEach { p ->
            last[p.track.songId]?.let { if (p.startedAt - it <= WEEK_MS) repeated++ }
            last[p.track.songId] = p.startedAt
        }
        return repeated.toFloat() / counted.size
    }

    private fun transitions(sorted: List<DetailedPlay>): List<Pair<String, String>> {
        val pairs = HashMap<Pair<String, String>, Int>()
        sorted.zipWithNext().forEach { (a, b) ->
            if (b.startedAt - a.endedAt > SESSION_GAP_MS) return@forEach
            val from = genreOf(a) ?: return@forEach
            val to = genreOf(b) ?: return@forEach
            if (!from.equals(to, ignoreCase = true)) pairs.merge(from to to, 1, Int::plus)
        }
        return pairs.entries.filter { it.value >= 2 }.sortedByDescending { it.value }.take(3).map { it.key }
    }

    private fun zones(sorted: List<DetailedPlay>, zone: ZoneId): List<ZoneSummary> {
        val placed = sorted.filter { it.context.zone != null }
        val total = placed.sumOf { it.listenedMs }.takeIf { it > 0 } ?: return emptyList()
        return placed.groupBy { it.context.zone!! }.map { (name, list) ->
            val hours = LongArray(24)
            list.forEach { hours[dayAndHour(it.startedAt, zone).second] += it.listenedMs }
            val ms = list.sumOf { it.listenedMs }
            ZoneSummary(name, ms / 60_000, ms.toFloat() / total, topBy(list) { genreOf(it) }, topBy(list) { it.track.artist.takeIf(String::isNotBlank) },
                hours.indices.maxByOrNull { hours[it] }?.takeIf { hours[it] > 0 })
        }.sortedByDescending { it.minutes }
    }

    private fun badges(report: InsightsReport, sorted: List<DetailedPlay>, counted: Int, finished: Int, zone: ZoneId, totalMs: Long): List<Badge> {
        if (counted < 10 || totalMs <= 0) return emptyList()
        fun share(hours: (Int) -> Boolean) = sorted.filter { hours(dayAndHour(it.startedAt, zone).second) }.sumOf { it.listenedMs }.toFloat() / totalMs
        fun pct(v: Float) = (v * 100).roundToInt()
        val night = share { it >= 20 || it < 4 }
        val morning = share { it in 5..9 }
        return buildList {
            if (night >= .5f) add(Badge(InsightBadge.NIGHT_LISTENER, pct(night)))
            if (morning >= .35f) add(Badge(InsightBadge.MORNING_LISTENER, pct(morning)))
            report.inOrder?.takeIf { counted >= 20 && it >= .6f }?.let { add(Badge(InsightBadge.IN_ORDER_LISTENER, pct(it))) }
            report.shuffle?.takeIf { counted >= 20 && it >= .6f }?.let { add(Badge(InsightBadge.SHUFFLER, pct(it))) }
            if (report.newArtists >= 15) add(Badge(InsightBadge.EXPLORER, report.newArtists))
            report.repeats?.takeIf { it >= .15f }?.let { add(Badge(InsightBadge.REPEAT_HEAVY, pct(it))) }
            report.lossless?.takeIf { it >= .7f }?.let { add(Badge(InsightBadge.LOSSLESS, pct(it))) }
            report.completion?.takeIf { finished >= 20 && it >= .8f }?.let { add(Badge(InsightBadge.COMPLETIONIST, pct(it))) }
        }
    }

    private fun <T> List<T>.fraction(test: (T) -> Boolean): Float? = if (isEmpty()) null else count(test).toFloat() / size

    private fun <V : Any> List<DetailedPlay>.timeShare(value: (DetailedPlay) -> V?, test: (V) -> Boolean): Float? {
        val known = filter { value(it) != null }
        val total = known.sumOf { it.listenedMs }.takeIf { it > 0 } ?: return null
        return known.filter { test(value(it)!!) }.sumOf { it.listenedMs }.toFloat() / total
    }

    private fun shares(plays: List<DetailedPlay>, label: (DetailedPlay) -> String?): List<Share> {
        val grouped = HashMap<String, Long>()
        plays.forEach { p -> label(p)?.let { grouped.merge(it, p.listenedMs, Long::plus) } }
        val total = grouped.values.sum().takeIf { it > 0 } ?: return emptyList()
        return grouped.entries.sortedByDescending { it.value }.map { Share(it.key, it.value.toFloat() / total, it.value / 60_000) }
    }

    private fun topBy(plays: List<DetailedPlay>, label: (DetailedPlay) -> String?): String? = shares(plays, label).firstOrNull()?.label
}
