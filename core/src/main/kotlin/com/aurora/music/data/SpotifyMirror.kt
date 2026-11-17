package com.aurora.music.data

import kotlin.math.abs

sealed interface SpotifyMirrorCommand {
    data class Play(val trackId: String, val positionMs: Long) : SpotifyMirrorCommand
    data class Seek(val positionMs: Long) : SpotifyMirrorCommand
    data object Pause : SpotifyMirrorCommand
    data object Resume : SpotifyMirrorCommand
    data object Stop : SpotifyMirrorCommand
}

// keeps spotify's muted web player copy of a song in step with aurora's play reports
class SpotifyMirrorFollower(private val seekSlackMs: Long = 5_000) {
    private var sessionId: String? = null
    private var playing = false
    private var positionMs = 0L
    private var atMs = 0L

    fun follow(report: PlaybackReport, nowMs: Long, enabled: Boolean): List<SpotifyMirrorCommand> {
        if (!enabled) return if (sessionId != null) listOf(SpotifyMirrorCommand.Stop).also { sessionId = null } else emptyList()
        if (report.event == PlaybackReportEvent.START) {
            sessionId = report.sessionId
            mark(report, nowMs)
            return listOf(SpotifyMirrorCommand.Play(report.song.id, report.positionMs))
        }
        if (report.sessionId != sessionId) return emptyList()
        return when (report.event) {
            PlaybackReportEvent.STOP -> listOf(SpotifyMirrorCommand.Stop).also { sessionId = null }
            PlaybackReportEvent.PROGRESS -> {
                val expected = if (playing) positionMs + (nowMs - atMs) else positionMs
                val wasPlaying = playing
                mark(report, nowMs)
                buildList {
                    if (wasPlaying && !playing) add(SpotifyMirrorCommand.Pause)
                    if (abs(report.positionMs - expected) > seekSlackMs) add(SpotifyMirrorCommand.Seek(report.positionMs))
                    if (!wasPlaying && playing) add(SpotifyMirrorCommand.Resume)
                }
            }
            else -> emptyList()
        }
    }

    private fun mark(report: PlaybackReport, nowMs: Long) {
        // a buffering aurora still means play: the web copy pausing for it would only lag further
        playing = report.state != PlaybackReportState.PAUSED
        positionMs = report.positionMs
        atMs = nowMs
    }
}
