package com.aurora.music.playback

import com.aurora.music.data.PlaybackReport
import com.aurora.music.data.PlaybackReportEvent
import com.aurora.music.data.PlaybackReportState
import com.aurora.music.data.PlaybackReportTarget
import com.aurora.music.model.Song
import java.util.UUID

internal data class PlaybackReportSnapshot(
    val song: Song,
    val positionMs: Long,
    val durationMs: Long,
    val state: PlaybackReportState,
    val playbackRate: Float = 1f,
)

internal class PlaybackReportTracker(
    private val targetFor: (Song) -> PlaybackReportTarget?,
    private val emit: (PlaybackReportTarget, PlaybackReport) -> Unit,
    private val newSessionId: () -> String = { UUID.randomUUID().toString() },
) {
    private var target: PlaybackReportTarget? = null
    private var snapshot: PlaybackReportSnapshot? = null
    private var sessionId = ""
    private var startedAt = 0L
    private var tick = 0L
    private var lastReport = 0L
    private var listened = 0L
    private var submitted = false

    fun update(current: PlaybackReportSnapshot?, nowMs: Long, wallTimeMs: Long, allowed: Boolean,
        discontinuity: Boolean = false, newOccurrence: Boolean = false, endingPositionMs: Long? = null) {
        val previous = snapshot
        accrue(nowMs)
        val changed = previous != null && (current == null || newOccurrence ||
            current.song.id != previous.song.id || current.song.playbackSource?.providerId != previous.song.playbackSource?.providerId ||
            current.song.playbackSource?.songId != previous.song.playbackSource?.songId)
        if (!allowed || changed || current?.state == PlaybackReportState.STOPPED) {
            if (changed && endingPositionMs != null) snapshot = previous?.copy(positionMs = endingPositionMs)
            if (allowed && !changed && current != null) snapshot = current.copy(positionMs = endingPositionMs ?: current.positionMs)
            finish(nowMs, countListen = allowed)
        }
        if (!allowed || current == null || current.state == PlaybackReportState.STOPPED) return
        if (target == null) {
            if (current.state != PlaybackReportState.PLAYING) return
            val resolved = targetFor(current.song) ?: return
            target = resolved
            snapshot = current
            sessionId = newSessionId()
            startedAt = wallTimeMs
            listened = 0
            submitted = false
            lastReport = nowMs
            send(PlaybackReportEvent.START)
            return
        }
        if (discontinuity && endingPositionMs != null) {
            snapshot = previous?.copy(positionMs = endingPositionMs)
            send(PlaybackReportEvent.PROGRESS)
        }
        snapshot = current
        submitIfQualified()
        if (previous?.state != current.state || previous?.playbackRate != current.playbackRate || discontinuity || nowMs - lastReport >= 10_000) {
            send(PlaybackReportEvent.PROGRESS)
            lastReport = nowMs
        }
    }

    fun finish(nowMs: Long, countListen: Boolean = true) {
        accrue(nowMs)
        if (countListen) submitIfQualified()
        send(PlaybackReportEvent.STOP)
        target = null
        snapshot = null
        listened = 0
        tick = nowMs
    }

    private fun accrue(nowMs: Long) {
        if (snapshot?.state == PlaybackReportState.PLAYING) {
            listened += (nowMs - tick).coerceIn(0, 2_000)
        }
        tick = nowMs
    }

    private fun submitIfQualified() {
        val duration = snapshot?.durationMs ?: return
        val required = if (duration > 0) minOf(30_000, (duration / 2).coerceAtLeast(1_000)) else 30_000
        if (!submitted && listened >= required) {
            submitted = true
            send(PlaybackReportEvent.SCROBBLE)
        }
    }

    private fun send(event: PlaybackReportEvent) {
        val destination = target ?: return
        val current = snapshot ?: return
        emit(destination, PlaybackReport(destination.song, sessionId, event,
            if (event == PlaybackReportEvent.STOP) PlaybackReportState.STOPPED else current.state,
            current.positionMs.coerceAtLeast(0), current.durationMs.coerceAtLeast(0), listened, startedAt, current.playbackRate))
    }
}
