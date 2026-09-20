package com.aurora.music.playback

import com.aurora.music.model.Song
import com.aurora.music.viewmodel.PlayerUiState
import org.junit.Assert.*
import org.junit.Test

class PlayerTimelineTest {
    private val video = Song("video", "Music video", "Artist", "", "", 0)

    @Test fun missingCatalogDurationDoesNotMeanLive() {
        val state = PlayerUiState(current = video)
        assertFalse(state.isLive)
        assertEquals(0, state.durationSec)
    }

    @Test fun vodUsesDecodedDurationForProgressAndSeeking() {
        val state = PlayerUiState(current = video, timelineDurationSec = 240, positionSec = 60f)
        assertFalse(state.isLive)
        assertEquals(240, state.durationSec)
        assertEquals(0.25f, state.progress, 0.0001f)
    }

    @Test fun liveDvrWindowDoesNotBecomeSongDuration() {
        val state = PlayerUiState(current = video, timelineDurationSec = 14_400, isLive = true)
        assertEquals(0, state.durationSec)
        assertEquals(0f, state.progress)
    }
}
