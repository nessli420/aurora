package com.aurora.music.data

import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class RecapPeriod(val label: String) { DAY("Daily"), WEEK("Weekly"), MONTH("Monthly"), YEAR("Yearly"), ALL("All time") }

data class RecapWindow(val period: RecapPeriod, val start: LocalDate, val end: LocalDate) {
    val key: String get() = "${period.name}:$start"
    val label: String get() = when (period) {
        RecapPeriod.DAY -> start.format(DateTimeFormatter.ofPattern("d MMM yyyy"))
        RecapPeriod.WEEK -> "${start.format(DateTimeFormatter.ofPattern("d MMM"))} – ${end.minusDays(1).format(DateTimeFormatter.ofPattern("d MMM yyyy"))}"
        RecapPeriod.MONTH -> start.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
        RecapPeriod.YEAR -> start.year.toString()
        RecapPeriod.ALL -> "All time"
    }
    fun move(amount: Long): RecapWindow = containing(period, when (period) {
        RecapPeriod.DAY -> start.plusDays(amount)
        RecapPeriod.WEEK -> start.plusWeeks(amount)
        RecapPeriod.MONTH -> start.plusMonths(amount)
        RecapPeriod.YEAR -> start.plusYears(amount)
        RecapPeriod.ALL -> start
    })
    companion object {
        fun containing(period: RecapPeriod, date: LocalDate): RecapWindow {
            val start = when (period) {
                RecapPeriod.DAY -> date
                RecapPeriod.WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                RecapPeriod.MONTH -> date.withDayOfMonth(1)
                RecapPeriod.YEAR -> date.withDayOfYear(1)
                RecapPeriod.ALL -> LocalDate.of(1970, 1, 1)
            }
            val end = when (period) {
                RecapPeriod.DAY -> start.plusDays(1)
                RecapPeriod.WEEK -> start.plusWeeks(1)
                RecapPeriod.MONTH -> start.plusMonths(1)
                RecapPeriod.YEAR -> start.plusYears(1)
                RecapPeriod.ALL -> LocalDate.now().plusDays(1)
            }
            return RecapWindow(period, start, end)
        }
    }
}

data class RecapRank(val id: String, val name: String, val artist: String, val artwork: String, val plays: Int, val millis: Long)
data class ListeningRecap(val window: RecapWindow, val plays: Int, val millis: Long,
    val artists: List<RecapRank>, val songs: List<RecapRank>, val albums: List<RecapRank>,
    val activeDays: Int, val busiestDay: LocalDate?, val hourly: List<Long>, val estimated: Boolean,
    val dailyMillis: Map<LocalDate, Long>) {
    val minutes: Long get() = millis / 60_000
}

object ListeningRecaps {
    private fun norm(s: String) = s.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    fun build(history: List<PlayEvent>, window: RecapWindow, zone: ZoneId = ZoneId.systemDefault()): ListeningRecap {
        val start = window.start.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = window.end.atStartOfDay(zone).toInstant().toEpochMilli()
        val events = history.filter { it.timestamp >= start && it.timestamp < end }
        fun rank(kind: Int): List<RecapRank> = events.filter { kind != 2 || it.album.isNotBlank() }.groupBy {
            when (kind) { 0 -> norm(it.artist); 1 -> norm(it.artist) + "|" + norm(it.title); else -> norm(it.artist) + "|" + norm(it.album) }
        }.values.map { list ->
            val e = list.first()
            RecapRank(when (kind) { 0 -> e.artistId; 1 -> e.songId; else -> e.albumId },
                when (kind) { 0 -> e.artist; 1 -> e.title; else -> e.album }, e.artist, e.artworkUrl,
                list.count { it.qualifiesAsPlay }, list.sumOf { it.listeningMillis })
        }.sortedWith(compareByDescending<RecapRank> { it.plays }.thenByDescending { it.millis }.thenBy { it.name })
        val days = events.groupBy { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
        val hourly = MutableList(24) { 0L }
        events.forEach { hourly[Instant.ofEpochMilli(it.timestamp).atZone(zone).hour] += it.listeningMillis }
        return ListeningRecap(window, events.count { it.qualifiesAsPlay }, events.sumOf { it.listeningMillis }, rank(0), rank(1), rank(2),
            days.size, days.maxByOrNull { it.value.sumOf { e -> e.listeningMillis } }?.key, hourly, events.any { it.listenedMs == null },
            days.mapValues { it.value.sumOf { e -> e.listeningMillis } })
    }

    fun available(history: List<PlayEvent>, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): List<RecapWindow> =
        history.map { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }.distinct()
            .flatMap { date -> RecapPeriod.entries.filter { it != RecapPeriod.ALL }.map { RecapWindow.containing(it, date) } }
            .distinctBy { it.key }.filter { !it.end.isAfter(today) }.sortedWith(compareByDescending<RecapWindow> { it.end }.thenByDescending { it.period.ordinal })
}

val PlayEvent.listeningMillis: Long get() = listenedMs?.coerceAtLeast(0) ?: durationSec.coerceAtLeast(0) * 1000L
val PlayEvent.qualifiesAsPlay: Boolean get() = listenedMs == null || listeningMillis >= minOf(30_000L, (durationSec.takeIf { it > 0 } ?: 60) * 500L)
