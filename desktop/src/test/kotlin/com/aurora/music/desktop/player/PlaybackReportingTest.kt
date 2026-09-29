package com.aurora.music.desktop.player

import com.aurora.music.data.PlaybackReport
import com.aurora.music.data.PlaybackReportEvent
import com.aurora.music.data.PlaybackReportEvent.PROGRESS
import com.aurora.music.data.PlaybackReportEvent.SCROBBLE
import com.aurora.music.data.PlaybackReportEvent.START
import com.aurora.music.data.PlaybackReportEvent.STOP
import com.aurora.music.data.PlaybackReportState
import com.aurora.music.data.PlaybackReportTarget
import com.aurora.music.desktop.audio.EngineEvent
import com.aurora.music.desktop.audio.EnginePhase
import com.aurora.music.desktop.audio.EngineState
import com.aurora.music.desktop.audio.QueueEntry
import com.aurora.music.desktop.audio.TransitionReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackReportingTest {
    private val reports = mutableListOf<PlaybackReport>()
    private var allowed = true
    private val reporting = PlaybackReporting({ song -> PlaybackReportTarget(song) {} }, { _, report -> reports += report }, { allowed })
    private val a = QueueEntry(1, song("a"))
    private val b = QueueEntry(2, song("b"))

    private fun state(entry: QueueEntry?, positionMs: Long, playing: Boolean = true, phase: EnginePhase = EnginePhase.READY) =
        EngineState(listOf(a, b), listOf(a, b).indexOf(entry), positionMs, 200_000, playing, phase)

    private fun events(id: String) = reports.filter { it.song.id == id }.map { it.event }

    private fun play(entry: QueueEntry, fromMs: Long, toMs: Long, startNow: Long): Long {
        var now = startNow
        for (position in fromMs..toMs step 1_000) {
            reporting.sample(state(entry, position), now)
            now += 1_000
        }
        return now
    }

    @Test fun reportsAPlayFromStartThroughScrobbleToStopAtTheTransition() {
        var now = play(a, 0, 12_000, 0)
        reporting.sample(state(a, 12_000, playing = false), now)
        now += 5_000
        reporting.sample(state(a, 12_000), now)
        now = play(a, 13_000, 40_000, now + 1_000)
        reporting.transition(EngineEvent.Transition(a, b, TransitionReason.AUTO, 199_000), state(b, 0), now)

        assertEquals(listOf(START, PROGRESS, PROGRESS, PROGRESS, PROGRESS, SCROBBLE, PROGRESS, STOP), events("a"))
        assertEquals(PlaybackReportState.PAUSED, reports.filter { it.song.id == "a" }[2].state)
        assertTrue(reports.single { it.song.id == "a" && it.event == SCROBBLE }.listenedMs >= 30_000)
        assertEquals(199_000L, reports.last { it.song.id == "a" }.positionMs)
        assertEquals(listOf(START), events("b"))
    }

    @Test fun anEarlyStateForTheNextEntryIsNotReportedTwice() {
        val now = play(a, 0, 3_000, 0)
        reporting.sample(state(b, 100), now)
        reporting.transition(EngineEvent.Transition(a, b, TransitionReason.AUTO, 200_000), state(b, 150), now + 50)
        reporting.sample(state(b, 1_200), now + 1_100)
        assertEquals(listOf(START, STOP), events("a"))
        assertEquals(listOf(START), events("b"))
        assertEquals(3_000L, reports.single { it.event == STOP }.positionMs)
    }

    @Test fun repeatOneStartsANewSessionForTheSameEntry() {
        val now = play(a, 0, 5_000, 0)
        reporting.sample(state(a, 0), now)
        reporting.transition(EngineEvent.Transition(a, a, TransitionReason.REPEAT, 200_000), state(a, 50), now + 50)
        assertEquals(listOf(START, STOP, START), events("a"))
        val stop = reports.single { it.event == STOP }
        assertEquals(200_000L, stop.positionMs)
        assertNotEquals(reports.first().sessionId, reports.last().sessionId)
    }

    @Test fun seeksReportTheHeardPositionBeforeTheJump() {
        val now = play(a, 0, 3_000, 0)
        reporting.discontinuity(EngineEvent.Discontinuity(a, 3_200, 120_000), state(a, 120_000), now)
        val progress = reports.filter { it.event == PROGRESS }.map { it.positionMs }
        assertEquals(listOf(3_200L, 120_000L), progress)
    }

    @Test fun stoppingOrDisallowingEndsTheSession() {
        var now = play(a, 0, 3_000, 0)
        reporting.sample(state(a, 3_000, playing = false, phase = EnginePhase.IDLE), now)
        assertEquals(listOf(START, STOP), events("a"))
        now = play(b, 0, 2_000, now + 1_000)
        allowed = false
        reporting.sample(state(b, 3_000), now)
        reporting.sample(state(b, 4_000), now + 1_000)
        assertEquals(listOf(START, STOP), events("b"))
        assertEquals(PlaybackReportEvent.STOP, reports.last().event)
    }
}
