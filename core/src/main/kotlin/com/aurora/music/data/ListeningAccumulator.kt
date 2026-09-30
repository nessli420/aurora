package com.aurora.music.data

import com.aurora.music.model.Song
import java.time.LocalDate
import java.time.ZoneId

class ListeningAccumulator(private val playHistory: PlayHistoryStore) {
    var allowed = false

    var song: Song? = null
        private set
    private var tick = 0L
    private var position = 0L
    private var wasPlaying = false
    private var pendingMs = 0L
    private var day = LocalDate.now()

    // a null song means no active player so only the clock advances
    fun track(current: Song?, playing: Boolean, positionMs: Long, nowMs: Long) {
        val elapsed = (nowMs - tick).coerceIn(0L, 2000L)
        tick = nowMs
        current ?: return
        val id = current.id
        val today = LocalDate.now()
        val newSession = id != song?.id || (positionMs < position && positionMs < 1500 && position > 5000) || today != day
        if (newSession || !playing || !allowed) flush()
        if (newSession || !allowed) playHistory.endListeningSession()
        if (!newSession && wasPlaying && playing && allowed && id.isNotBlank() &&
            !id.startsWith("radio:") && !id.startsWith("podcast:") && !id.startsWith("aurora-mix:")) {
            pendingMs += elapsed
            if (pendingMs >= 5000) flush()
        }
        song = current
        position = positionMs
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
