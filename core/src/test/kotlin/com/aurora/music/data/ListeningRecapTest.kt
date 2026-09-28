package com.aurora.music.data

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ListeningRecapTest {
    private val zone = ZoneId.of("Europe/Amsterdam")
    private fun event(date: String, id: String = "1", title: String = "Song", artist: String = "Artist", album: String = "Album", ms: Long? = 60_000): PlayEvent =
        PlayEvent(id, title, artist, album, "album-$id", "artist-$id", "", 240, ZonedDateTime.parse(date).toInstant().toEpochMilli(), ms)

    @Test fun calendarWindowsIncludeLeapDayAndStartWeeksOnMonday() {
        val leap = RecapWindow.containing(RecapPeriod.MONTH, LocalDate.of(2024, 2, 29))
        assertEquals(LocalDate.of(2024, 2, 1), leap.start)
        assertEquals(LocalDate.of(2024, 3, 1), leap.end)
        assertEquals(LocalDate.of(2024, 1, 1), leap.move(-1).start)
        assertEquals(LocalDate.of(2025, 1, 1), RecapWindow.containing(RecapPeriod.YEAR, leap.start).end)
        assertEquals(LocalDate.of(2023, 12, 25), RecapWindow.containing(RecapPeriod.WEEK, LocalDate.of(2023, 12, 31)).start)
    }
    @Test fun localMidnightAndDstUseExclusiveCalendarBounds() {
        val window = RecapWindow.containing(RecapPeriod.DAY, LocalDate.of(2026, 3, 29))
        val data = listOf(event("2026-03-28T23:59:59+01:00"), event("2026-03-29T00:00:00+01:00"), event("2026-03-29T23:59:59+02:00"), event("2026-03-30T00:00:00+02:00"))
        assertEquals(2, ListeningRecaps.build(data, window, zone).plays)
        assertEquals(2L, ListeningRecaps.build(data, window, zone).minutes)
    }
    @Test fun actualTimeDoesNotCountEntireSongAndLegacyTimeIsLabelled() {
        val e = event("2026-09-20T10:00:00+02:00", ms = 45_000)
        val window = RecapWindow.containing(RecapPeriod.DAY, LocalDate.of(2026, 9, 20))
        val actual = ListeningRecaps.build(listOf(e), window, zone)
        assertEquals(45_000L, actual.millis); assertFalse(actual.estimated)
        val mixed = ListeningRecaps.build(listOf(e, e.copy(songId = "2", listenedMs = null)), window, zone)
        assertEquals(285_000L, mixed.millis); assertTrue(mixed.estimated)
    }
    @Test fun sourceCopiesCombineButSameNamedAlbumsFromDifferentArtistsDoNot() {
        val e = event("2026-09-20T10:00:00+02:00")
        val recap = ListeningRecaps.build(listOf(e, e.copy(songId = "local-copy", artistId = "local-artist"), e.copy(songId = "other", artist = "Someone else")), RecapWindow.containing(RecapPeriod.MONTH, LocalDate.of(2026, 9, 1)), zone)
        assertEquals(2, recap.songs.size); assertEquals(2, recap.albums.size)
        assertEquals(2, recap.artists.first().plays); assertEquals(120_000L, recap.songs.first().millis)
    }
    @Test fun briefSkipsContributeTimeWithoutCountingAsAPlay() {
        val e = event("2026-09-20T10:00:00+02:00", ms = 2000)
        val recap = ListeningRecaps.build(listOf(e), RecapWindow.containing(RecapPeriod.DAY, LocalDate.of(2026, 9, 20)), zone)
        assertEquals(0, recap.plays); assertEquals(2000L, recap.millis)
    }
    @Test fun notificationsOnlyIncludeFinishedPeriodsWithHistoryAndNoDuplicates() {
        val e = event("2026-09-20T10:00:00+02:00")
        val available = ListeningRecaps.available(listOf(e, e), LocalDate.of(2026, 9, 21), zone)
        assertEquals(setOf(RecapPeriod.DAY, RecapPeriod.WEEK), available.map { it.period }.toSet())
        assertEquals(2, available.size)
        assertTrue(ListeningRecaps.available(emptyList(), LocalDate.now(), zone).isEmpty())
    }
    @Test fun artistsAreNotLimitedToTheVisibleRankingCount() {
        val data = (1..40).map { event("2026-09-20T10:00:00+02:00", id = "$it", artist = "Artist $it") }
        assertEquals(40, ListeningRecaps.build(data, RecapWindow.containing(RecapPeriod.DAY, LocalDate.of(2026, 9, 20)), zone).artists.size)
    }
}
