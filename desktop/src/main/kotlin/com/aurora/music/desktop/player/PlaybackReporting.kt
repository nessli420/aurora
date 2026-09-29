package com.aurora.music.desktop.player

import com.aurora.music.data.PlaybackReport
import com.aurora.music.data.PlaybackReportState
import com.aurora.music.data.PlaybackReportTarget
import com.aurora.music.desktop.audio.EngineEvent
import com.aurora.music.desktop.audio.EnginePhase
import com.aurora.music.desktop.audio.EngineState
import com.aurora.music.desktop.audio.TransitionReason
import com.aurora.music.model.Song
import com.aurora.music.playback.PlaybackReportSnapshot
import com.aurora.music.playback.PlaybackReportTracker

internal class PlaybackReporting(
    targetFor: (Song) -> PlaybackReportTarget?,
    submit: (PlaybackReportTarget, PlaybackReport) -> Unit,
    private val allowed: () -> Boolean,
) {
    private val tracker = PlaybackReportTracker(targetFor, submit)
    private var tracked: Long? = null
    private var ending: Pair<Long, Long>? = null

    fun transition(event: EngineEvent.Transition, state: EngineState, nowMs: Long, wallMs: Long = System.currentTimeMillis()) {
        val from = event.from
        ending = if (from != null && event.endPositionMs != null) from.uid to event.endPositionMs else null
        sample(state, nowMs, wallMs, repeat = event.reason == TransitionReason.REPEAT && event.to?.uid == state.current?.uid)
    }

    fun discontinuity(event: EngineEvent.Discontinuity, state: EngineState, nowMs: Long, wallMs: Long = System.currentTimeMillis()) =
        sample(state, nowMs, wallMs, seekFrom = event.fromMs.takeIf { event.entry.uid == tracked && state.current?.uid == tracked })

    fun sample(state: EngineState, nowMs: Long, wallMs: Long = System.currentTimeMillis(), repeat: Boolean = false, seekFrom: Long? = null) {
        val entry = state.current
        // a new queue entry is a new occurrence even when it holds the same song
        val newOccurrence = entry != null && tracked != null && (entry.uid != tracked || repeat)
        ending = ending?.takeIf { it.first == tracked }
        val endingPosition = if (newOccurrence) ending?.second else seekFrom
        tracker.update(entry?.let { snapshot(state, it.song) }, nowMs, wallMs, allowed(),
            seekFrom != null && !newOccurrence, newOccurrence, endingPosition)
        if (newOccurrence) ending = null
        tracked = entry?.uid
    }

    fun close(state: EngineState, nowMs: Long) {
        sample(state, nowMs)
        tracker.finish(nowMs, countListen = allowed())
    }

    private fun snapshot(state: EngineState, song: Song): PlaybackReportSnapshot {
        val duration = state.durationMs.takeIf { it > 0 } ?: (song.durationSec * 1_000L)
        val reportState = when {
            state.phase == EnginePhase.IDLE || state.phase == EnginePhase.ENDED -> PlaybackReportState.STOPPED
            state.isPlaying -> PlaybackReportState.PLAYING
            state.playWhenReady && state.phase == EnginePhase.BUFFERING -> PlaybackReportState.BUFFERING
            else -> PlaybackReportState.PAUSED
        }
        return PlaybackReportSnapshot(song, state.positionMs, duration, reportState, state.speed)
    }
}
