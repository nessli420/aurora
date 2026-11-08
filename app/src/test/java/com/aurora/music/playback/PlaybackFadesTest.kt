package com.aurora.music.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackFadesTest {
    @Test fun replayGainIsLinearAndNeverBoosts() {
        assertEquals(1f, PlaybackFades.replayGainLinear(0f), 0f)
        assertEquals(0.5f, PlaybackFades.replayGainLinear(-6.0206f), 1e-4f)
        assertEquals(1f, PlaybackFades.replayGainLinear(6f), 0f)
        assertEquals(0.1f, PlaybackFades.replayGainLinear(-40f), 0f)
        for (db in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(1f, PlaybackFades.replayGainLinear(db), 0f)
        }
    }

    @Test fun fadeWindowIsAtMostHalfTheTrackAtPlaybackSpeed() {
        assertEquals(8_000L, PlaybackFades.fadeWindowMs(8_000, 300_000, 1f))
        assertEquals(5_000L, PlaybackFades.fadeWindowMs(8_000, 10_000, 1f))
        assertEquals(2_500L, PlaybackFades.fadeWindowMs(8_000, 10_000, 2f))
    }

    @Test fun remainingTimeFollowsPlaybackSpeed() {
        assertEquals(5_000L, PlaybackFades.remainingMs(200_000, 190_000, 2f))
    }

    @Test fun nativeTailShapeFollowsTheCurve() {
        assertEquals(1, PlaybackFades.nativeTailShape("LINEAR"))
        assertEquals(2, PlaybackFades.nativeTailShape("POWER"))
        assertEquals(0, PlaybackFades.nativeTailShape("SMOOTH"))
        assertEquals(0, PlaybackFades.nativeTailShape("unknown"))
    }

    @Test fun rampProgressIsClampedAndToleratesAZeroLength() {
        assertEquals(0f, PlaybackFades.rampProgress(1_000, 1_000, 2_000), 0f)
        assertEquals(0.5f, PlaybackFades.rampProgress(2_000, 1_000, 2_000), 0f)
        assertEquals(1f, PlaybackFades.rampProgress(9_000, 1_000, 2_000), 0f)
        assertEquals(0f, PlaybackFades.rampProgress(500, 1_000, 2_000), 0f)
        assertEquals(1f, PlaybackFades.rampProgress(1_001, 1_000, 0), 0f)
    }
}
