package com.aurora.music.data

import com.aurora.music.data.remote.YouTubeMusicClient
import com.aurora.music.data.remote.YouTubeMusicTransport
import com.aurora.music.data.remote.json
import com.aurora.music.data.remote.obj
import com.aurora.music.data.remote.string
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.security.SecureRandom

internal class YouTubeMusicPlaybackTracker(private val api: YouTubeMusicTransport) {
    private data class ListeningSession(
        val videoId: String,
        val playbackUrl: String,
        val watchtimeUrl: String,
        val nonce: String,
        var previous: PlaybackReport,
        var started: Boolean = false,
    )

    private val sessions = linkedMapOf<String, ListeningSession>()
    private val lock = Mutex()
    private val random = SecureRandom()

    suspend fun report(report: PlaybackReport) = lock.withLock {
        if (!report.song.id.matches(Regex("[A-Za-z0-9_-]{11}")) || report.sessionId.isBlank()) return@withLock
        var active = sessions[report.sessionId]
        if (active != null && active.videoId != report.song.id) return@withLock
        if (active?.started == true && report.event == PlaybackReportEvent.START) return@withLock
        if (active == null) {
            if (report.event !in setOf(PlaybackReportEvent.START, PlaybackReportEvent.PROGRESS) ||
                report.state != PlaybackReportState.PLAYING) return@withLock
            val tracking = api.request("player", json("videoId" to report.song.id)).obj("playbackTracking")
            val playbackUrl = tracking.obj("videostatsPlaybackUrl").string("baseUrl")
            val watchtimeUrl = tracking.obj("videostatsWatchtimeUrl").string("baseUrl")
            if (playbackUrl.isBlank() || watchtimeUrl.isBlank()) {
                throw IOException("YouTube Music did not provide playback reporting for this track.")
            }
            val playback = YouTubeMusicClient.validatedTrackingUrl(playbackUrl)
            val watchtime = YouTubeMusicClient.validatedTrackingUrl(watchtimeUrl)
            require(playback.encodedPath == "/api/stats/playback" && watchtime.encodedPath == "/api/stats/watchtime")
            val nonce = CharArray(16) { NONCE_ALPHABET[random.nextInt(NONCE_ALPHABET.length)] }.concatToString()
            active = ListeningSession(report.song.id, playback.toString(), watchtime.toString(), nonce, report)
            if (sessions.size >= 16) sessions.remove(sessions.keys.first())
            sessions[report.sessionId] = active
        }
        try {
            if (!active.started) {
                api.trackPlayback(active.playbackUrl, parameters(active, report))
                active.started = true
            }
            val previous = active.previous
            val advance = (position(report) - position(previous)).coerceAtLeast(0)
            val elapsed = (report.listenedMs - previous.listenedMs).coerceAtLeast(0)
            val rate = previous.playbackRate.takeIf { it.isFinite() && it > 0f } ?: 1f
            val heard = (elapsed * rate.toDouble()).coerceAtMost(Long.MAX_VALUE.toDouble()).toLong()
            val continuous = previous.state == PlaybackReportState.PLAYING &&
                advance <= heard + 1_500 && position(report) >= position(previous)
            val played = if (continuous) minOf(advance, heard) else 0
            if (played > 0 || report.state != previous.state || report.event == PlaybackReportEvent.STOP) {
                val end = position(report)
                val parameters = parameters(active, report).toMutableMap().apply {
                    put("st", seconds(end - played))
                    put("et", seconds(end))
                    if (rate != 1f) put("rate", rate.toString())
                    if (report.event == PlaybackReportEvent.STOP) put("final", "1")
                }
                api.trackPlayback(active.watchtimeUrl, parameters)
            }
            active.previous = report
        } finally {
            if (report.event == PlaybackReportEvent.STOP) sessions.remove(report.sessionId)
        }
    }

    private fun parameters(active: ListeningSession, report: PlaybackReport) = mutableMapOf(
        "cpn" to active.nonce,
        "ver" to "2",
        "el" to "detailpage",
        "ns" to "yt",
        "cmt" to seconds(position(report)),
        "rt" to seconds((System.currentTimeMillis() - report.startedAtMs).coerceAtLeast(0)),
        "state" to when (report.state) {
            PlaybackReportState.PLAYING -> "playing"
            PlaybackReportState.PAUSED -> "paused"
            PlaybackReportState.BUFFERING -> "buffering"
            PlaybackReportState.STOPPED -> "ended"
        },
    )

    private fun position(report: PlaybackReport): Long = report.positionMs.coerceAtLeast(0).let {
        if (report.durationMs > 0) it.coerceAtMost(report.durationMs) else it
    }

    private fun seconds(value: Long) = "${value / 1000}.${(value % 1000).toString().padStart(3, '0')}"

    private companion object {
        const val NONCE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    }
}
