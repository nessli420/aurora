package com.aurora.music.desktop.player

import com.aurora.music.data.ListeningAccumulator
import com.aurora.music.data.PlayHistoryStore
import com.aurora.music.desktop.audio.EngineState
import com.aurora.music.model.Song

internal class ListeningHistory(playHistory: PlayHistoryStore) {
    private val accumulator = ListeningAccumulator(playHistory)

    var allowed: Boolean
        get() = accumulator.allowed
        set(value) { accumulator.allowed = value }

    fun track(state: EngineState, nowMs: Long) {
        val current = state.current?.song
        val song = current?.let { if (it.durationSec > 0) it else it.copy(durationSec = (state.durationMs.coerceAtLeast(0) / 1000).toInt()) }
            ?: Song("", "", "", "", "", 0)
        accumulator.track(song, state.isPlaying, state.positionMs, nowMs)
    }

    fun finish() = accumulator.finish()
}
