package com.aurora.music.desktop.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeTaperTest {
    @Test fun endpointsAreExact() {
        assertEquals(0f, volumeGain(0f), 0f)
        assertEquals(1f, volumeGain(1f), 0f)
        assertTrue(volumeGain(1f) == 1f)
    }

    @Test fun halfwayIsAnEighthOfFullScale() {
        assertEquals(0.125f, volumeGain(0.5f), 0f)
        assertEquals(0.027f, volumeGain(0.3f), 1e-6f)
    }

    @Test fun isStrictlyIncreasingAcrossTheSlider() {
        var previous = volumeGain(0f)
        for (step in 1..1000) {
            val gain = volumeGain(step / 1000f)
            assertTrue("step $step", gain > previous)
            previous = gain
        }
    }

    @Test fun clampsOutOfRangePositions() {
        assertEquals(0f, volumeGain(-0.4f), 0f)
        assertEquals(1f, volumeGain(1.7f), 0f)
        assertEquals(0f, volumeGain(Float.NaN), 0f)
    }
}
