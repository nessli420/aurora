package com.aurora.music.playback

import com.aurora.music.data.PlaybackReport
import com.aurora.music.data.PlaybackReportEvent
import com.aurora.music.data.PlaybackReportState
import com.aurora.music.data.PlaybackReportTarget
import com.aurora.music.data.PlaybackSourceIdentity
import com.aurora.music.model.Song
import org.junit.Assert.*
import org.junit.Test

class PlaybackReportTrackerTest {
    private val song = Song("101", "Track", "Artist", "Album", "", 180)

    private class Harness {
        var now = 0L
        var source = "first"
        var resolves = 0
        var supported = true
        val reports = mutableListOf<PlaybackReport>()
        val destinations = mutableListOf<PlaybackReportTarget>()
        val created = mutableListOf<PlaybackReportTarget>()
        private var occurrence = 0
        val tracker = PlaybackReportTracker(
            targetFor = {
                resolves++
                if (supported) PlaybackReportTarget(it.copy(title = source)) {}.also(created::add) else null
            },
            emit = { target, report -> destinations += target; reports += report },
            newSessionId = { "session-${++occurrence}" },
        )
        fun update(snapshot: PlaybackReportSnapshot?, elapsed: Long = 0, allowed: Boolean = true,
            discontinuity: Boolean = false, newOccurrence: Boolean = false, endingPosition: Long? = null) {
            now += elapsed
            tracker.update(snapshot, now, 1_750_000_000_000 + now, allowed, discontinuity, newOccurrence, endingPosition)
        }
        fun events() = reports.map { it.event }
    }

    private fun snapshot(song: Song = this.song, position: Long = 0,
        state: PlaybackReportState = PlaybackReportState.PLAYING, duration: Long = song.durationSec * 1000L,
        rate: Float = 1f) = PlaybackReportSnapshot(song, position, duration, state, rate)

    @Test fun startsOnlyWhenPlaybackActuallyStarts() {
        val harness = Harness()
        harness.update(snapshot(state = PlaybackReportState.BUFFERING))
        harness.update(snapshot(state = PlaybackReportState.PAUSED), elapsed = 20_000)
        assertTrue(harness.reports.isEmpty())
        assertEquals(0, harness.resolves)
        harness.update(snapshot(position = 15_000), elapsed = 1000)
        assertEquals(listOf(PlaybackReportEvent.START), harness.events())
        assertEquals(15_000, harness.reports.single().positionMs)
        assertEquals(0, harness.reports.single().listenedMs)
        assertEquals(1_750_000_021_000, harness.reports.single().startedAtMs)
    }

    @Test fun seekingToTheEndDoesNotCountAsListening() {
        val harness = Harness()
        harness.update(snapshot())
        harness.update(snapshot(position = 179_000), discontinuity = true)
        repeat(29) { harness.update(snapshot(position = 179_000), elapsed = 1000) }
        assertFalse(harness.events().contains(PlaybackReportEvent.SCROBBLE))
        harness.update(snapshot(position = 179_000), elapsed = 1000)
        val scrobble = harness.reports.single { it.event == PlaybackReportEvent.SCROBBLE }
        assertEquals(30_000, scrobble.listenedMs)
        assertEquals(179_000, scrobble.positionMs)
    }

    @Test fun seekingBackDoesNotEraseActualListening() {
        val harness = Harness()
        harness.update(snapshot())
        repeat(20) { harness.update(snapshot(position = (it + 1) * 1000L), elapsed = 1000) }
        harness.update(snapshot(position = 0), discontinuity = true)
        repeat(10) { harness.update(snapshot(position = (it + 1) * 1000L), elapsed = 1000) }
        assertEquals(30_000, harness.reports.single { it.event == PlaybackReportEvent.SCROBBLE }.listenedMs)
    }

    @Test fun pauseAndBufferingDoNotAccumulateListenTime() {
        val harness = Harness()
        harness.update(snapshot())
        harness.update(snapshot(position = 1000), elapsed = 1000)
        harness.update(snapshot(position = 2000, state = PlaybackReportState.PAUSED), elapsed = 1000)
        repeat(60) { harness.update(snapshot(position = 2000, state = PlaybackReportState.PAUSED), elapsed = 1000) }
        harness.update(snapshot(position = 2000, state = PlaybackReportState.BUFFERING), elapsed = 1000)
        repeat(60) { harness.update(snapshot(position = 2000, state = PlaybackReportState.BUFFERING), elapsed = 1000) }
        harness.update(snapshot(position = 2000), elapsed = 1000)
        harness.update(snapshot(position = 3000), elapsed = 1000, discontinuity = true)
        assertEquals(3000, harness.reports.last().listenedMs)
        assertFalse(harness.events().contains(PlaybackReportEvent.SCROBBLE))
    }

    @Test fun qualifiesOncePerOccurrenceUsingElapsedRatherThanPlaybackSpeed() {
        val harness = Harness()
        harness.update(snapshot(rate = 2f))
        repeat(75) { harness.update(snapshot(position = (it + 1) * 2000L, rate = 2f), elapsed = 1000) }
        harness.update(snapshot(position = 150_000, state = PlaybackReportState.STOPPED, rate = 2f))
        val scrobble = harness.reports.single { it.event == PlaybackReportEvent.SCROBBLE }
        assertEquals(30_000, scrobble.listenedMs)
        assertEquals(2f, scrobble.playbackRate, 0f)
        assertEquals(75_000, harness.reports.last().listenedMs)
    }

    @Test fun repeatedTrackOccurrencesStopThenStartWithDistinctSessions() {
        val harness = Harness()
        harness.update(snapshot())
        repeat(30) { harness.update(snapshot(position = (it + 1) * 1000L), elapsed = 1000) }
        val boundary = harness.reports.size
        harness.update(snapshot(), newOccurrence = true)
        assertEquals(listOf(PlaybackReportEvent.STOP, PlaybackReportEvent.START), harness.events().drop(boundary))
        assertEquals("session-1", harness.reports[boundary].sessionId)
        assertEquals("session-2", harness.reports[boundary + 1].sessionId)
        assertEquals(0, harness.reports.last().listenedMs)
        repeat(30) { harness.update(snapshot(position = (it + 1) * 1000L), elapsed = 1000) }
        assertEquals(listOf("session-1", "session-2"), harness.reports
            .filter { it.event == PlaybackReportEvent.SCROBBLE }.map { it.sessionId })
    }

    @Test fun stopsOldTrackAtItsPinnedProviderBeforeResolvingTheNextTrack() {
        val harness = Harness()
        harness.update(snapshot())
        val firstTarget = harness.created.single()
        harness.source = "second"
        harness.update(snapshot(position = 12_000), discontinuity = true)
        assertSame(firstTarget, harness.destinations.last())
        assertEquals("first", harness.reports.last().song.title)
        val boundary = harness.reports.size
        harness.update(snapshot(song.copy(id = "202")), elapsed = 1000)
        assertEquals(listOf(PlaybackReportEvent.STOP, PlaybackReportEvent.START), harness.events().drop(boundary))
        assertSame(firstTarget, harness.destinations[boundary])
        assertSame(harness.created.last(), harness.destinations[boundary + 1])
        assertEquals("101", harness.reports[boundary].song.id)
        assertEquals(12_000, harness.reports[boundary].positionMs)
        assertEquals("second", harness.reports[boundary + 1].song.title)
        assertEquals(2, harness.resolves)
    }

    @Test fun sameRawIdFromAnotherProviderStartsANewOccurrence() {
        val harness = Harness()
        harness.update(snapshot(song.copy(playbackSource = PlaybackSourceIdentity(providerId = "first"))))
        harness.update(snapshot(song.copy(playbackSource = PlaybackSourceIdentity(providerId = "second"))))
        assertEquals(listOf(PlaybackReportEvent.START, PlaybackReportEvent.STOP, PlaybackReportEvent.START), harness.events())
        assertEquals("session-2", harness.reports.last().sessionId)
    }

    @Test fun sourceRecordingChangesRestartEvenWhenVisibleIdAndProviderStayTheSame() {
        val harness = Harness()
        harness.update(snapshot(song.copy(playbackSource = PlaybackSourceIdentity(providerId = "first", songId = "video-one"))))
        harness.update(snapshot(song.copy(playbackSource = PlaybackSourceIdentity(providerId = "first", songId = "video-two"))))
        assertEquals(listOf(PlaybackReportEvent.START, PlaybackReportEvent.STOP, PlaybackReportEvent.START), harness.events())
        assertEquals("video-one", harness.reports[1].song.playbackSource?.songId)
        assertEquals("video-two", harness.reports[2].song.playbackSource?.songId)
        assertEquals("session-2", harness.reports.last().sessionId)
    }

    @Test fun seekingReportsTheHeardIntervalThenTheNewPositionWithoutDoubleCounting() {
        val harness = Harness()
        harness.update(snapshot())
        repeat(4) { harness.update(snapshot(position = (it + 1) * 1000L), elapsed = 1000) }
        harness.update(snapshot(position = 90_000), elapsed = 1000, discontinuity = true, endingPosition = 5000)
        assertEquals(listOf(PlaybackReportEvent.START, PlaybackReportEvent.PROGRESS, PlaybackReportEvent.PROGRESS), harness.events())
        assertEquals(listOf(5000L, 90_000L), harness.reports.drop(1).map { it.positionMs })
        assertEquals(listOf(5000L, 5000L), harness.reports.drop(1).map { it.listenedMs })
        assertEquals(listOf("session-1", "session-1"), harness.reports.drop(1).map { it.sessionId })
        harness.update(snapshot(position = 91_000), elapsed = 1000, discontinuity = true)
        assertEquals(6000, harness.reports.last().listenedMs)
    }

    @Test fun privacyOptOutStopsPresenceWithoutSubmittingAQualifiedListen() {
        val harness = Harness()
        harness.update(snapshot())
        repeat(29) { harness.update(snapshot(position = (it + 1) * 1000L), elapsed = 1000) }
        harness.update(snapshot(position = 30_000), elapsed = 1000, allowed = false)
        assertEquals(PlaybackReportEvent.STOP, harness.reports.last().event)
        assertFalse(harness.events().contains(PlaybackReportEvent.SCROBBLE))
        val count = harness.reports.size
        repeat(30) { harness.update(snapshot(position = 40_000), elapsed = 1000, allowed = false) }
        assertEquals(count, harness.reports.size)
        harness.update(snapshot(position = 50_000))
        assertEquals(PlaybackReportEvent.START, harness.reports.last().event)
        assertEquals("session-2", harness.reports.last().sessionId)
        assertEquals(0, harness.reports.last().listenedMs)
    }

    @Test fun shortTracksQualifyAtHalfDurationAndStopUsesFinalPosition() {
        val harness = Harness()
        harness.update(snapshot(duration = 4000))
        harness.update(snapshot(position = 1000, duration = 4000), elapsed = 1000)
        assertFalse(harness.events().contains(PlaybackReportEvent.SCROBBLE))
        harness.update(snapshot(position = 2000, duration = 4000, state = PlaybackReportState.STOPPED), elapsed = 1000)
        assertEquals(listOf(PlaybackReportEvent.START, PlaybackReportEvent.SCROBBLE, PlaybackReportEvent.STOP), harness.events())
        assertEquals(2000, harness.reports.last().positionMs)
        assertEquals(PlaybackReportState.STOPPED, harness.reports.last().state)
        assertEquals(2000, harness.reports.last().listenedMs)
    }

    @Test fun unknownDurationUsesThirtySecondsInsteadOfImmediateScrobbling() {
        val harness = Harness()
        harness.update(snapshot(duration = 0))
        repeat(29) { harness.update(snapshot(duration = 0), elapsed = 1000) }
        assertFalse(harness.events().contains(PlaybackReportEvent.SCROBBLE))
        harness.update(snapshot(duration = 0), elapsed = 1000)
        assertEquals(1, harness.events().count { it == PlaybackReportEvent.SCROBBLE })
    }

    @Test fun sendsProgressAtTenSecondsAndImmediatelyOnStateChangesOrSeeks() {
        val harness = Harness()
        harness.update(snapshot())
        repeat(9) { harness.update(snapshot(position = (it + 1) * 1000L), elapsed = 1000) }
        assertEquals(listOf(PlaybackReportEvent.START), harness.events())
        harness.update(snapshot(position = 10_000), elapsed = 1000)
        harness.update(snapshot(position = 20_000), discontinuity = true)
        harness.update(snapshot(position = 20_000, state = PlaybackReportState.PAUSED))
        assertEquals(listOf(PlaybackReportEvent.START, PlaybackReportEvent.PROGRESS,
            PlaybackReportEvent.PROGRESS, PlaybackReportEvent.PROGRESS), harness.events())
    }

    @Test fun finishAccountsForTheFinalIntervalAndNeverDoubleStops() {
        val harness = Harness()
        harness.update(snapshot(duration = 2000))
        harness.tracker.finish(1000)
        assertEquals(listOf(PlaybackReportEvent.START, PlaybackReportEvent.SCROBBLE, PlaybackReportEvent.STOP), harness.events())
        assertEquals(1000, harness.reports.last().listenedMs)
        harness.tracker.finish(2000)
        assertEquals(3, harness.reports.size)
    }

    @Test fun playbackRateChangesReportImmediatelyWithoutInflatingListenTime() {
        val harness = Harness()
        harness.update(snapshot())
        harness.update(snapshot(position = 1000, rate = 1.5f), elapsed = 1000)
        assertEquals(listOf(PlaybackReportEvent.START, PlaybackReportEvent.PROGRESS), harness.events())
        assertEquals(1.5f, harness.reports.last().playbackRate, 0f)
        assertEquals(1000, harness.reports.last().listenedMs)
        harness.update(snapshot(position = 2000, rate = 1.5f), elapsed = 1000)
        assertEquals(2, harness.reports.size)
    }

    @Test fun unsupportedSourcesAndEmptyQueuesEmitNothing() {
        val harness = Harness().apply { supported = false }
        harness.update(null)
        harness.update(snapshot())
        harness.update(snapshot(), elapsed = 1000)
        harness.update(null, elapsed = 1000)
        harness.tracker.finish(3000)
        assertTrue(harness.reports.isEmpty())
    }
}
