package com.aurora.music.data

import com.aurora.music.model.Song

enum class PlaybackReportEvent { START, PROGRESS, STOP, SCROBBLE }
enum class PlaybackReportState { PLAYING, PAUSED, BUFFERING, STOPPED }

data class PlaybackReport(
    val song: Song,
    val sessionId: String,
    val event: PlaybackReportEvent,
    val state: PlaybackReportState,
    val positionMs: Long,
    val durationMs: Long,
    val listenedMs: Long,
    val startedAtMs: Long,
    val playbackRate: Float = 1f,
)

class PlaybackReportTarget(val song: Song, val send: suspend (PlaybackReport) -> Unit)
