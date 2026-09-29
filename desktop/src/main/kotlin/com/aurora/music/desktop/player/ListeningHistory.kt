package com.aurora.music.desktop.player

import com.aurora.music.data.PlayHistoryStore
import com.aurora.music.desktop.audio.EngineState
import com.aurora.music.model.Song
import java.time.LocalDate
import java.time.ZoneId

internal class ListeningHistory(private val playHistory: PlayHistoryStore) {
    var allowed = false

    private var tick = 0L
    private var song: Song? = null
    private var position = 0L
    private var wasPlaying = false
    private var pendingMs = 0L
    private var day = LocalDate.now()

    fun track(state: EngineState, nowMs: Long) {
        val elapsed = (nowMs - tick).coerceIn(0L, 2000L)
        tick = nowMs
        val current = state.current?.song
        val id = current?.id.orEmpty()
        val playing = state.isPlaying
        val currentPosition = state.positionMs
        val today = LocalDate.now()
        val newSession = id != song?.id || (currentPosition < position && currentPosition < 1500 && position > 5000) || today != day
        if (newSession || !playing || !allowed) flush()
        if (newSession || !allowed) playHistory.endListeningSession()
        if (!newSession && wasPlaying && playing && allowed && id.isNotBlank() &&
            !id.startsWith("radio:") && !id.startsWith("podcast:") && !id.startsWith("aurora-mix:")) {
            pendingMs += elapsed
            if (pendingMs >= 5000) flush()
        }
        song = current?.let { if (it.durationSec > 0) it else it.copy(durationSec = (state.durationMs.coerceAtLeast(0) / 1000).toInt()) }
            ?: Song(id, "", "", "", "", 0)
        position = currentPosition
        wasPlaying = playing
        day = today
    }

    fun finish() {
        flush()
        playHistory.endListeningSession()
    }

    private fun flush() {
        val previous = song
        if (previous != null && pendingMs > 0) {
            playHistory.recordListening(previous, pendingMs,
                if (day == LocalDate.now()) System.currentTimeMillis()
                else day.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1)
        }
        pendingMs = 0
    }
}
