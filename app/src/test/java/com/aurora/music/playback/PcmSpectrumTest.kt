package com.aurora.music.playback

import androidx.media3.common.C
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class PcmSpectrumTest {
    private fun feed(tap: PcmSpectrumTap, gain: Double, start: Long = 1_000_000, frames: Int = 2048) {
        repeat(frames) { i ->
            val sample = gain * sin(2 * PI * 64 * i / 2048)
            tap.observe(sample, -sample, 48_000, if (start == C.TIME_UNSET) start else start + i * 1_000_000L / 48_000)
        }
    }

    @Test fun alignedAntiphaseStereoRetainsPowerAndMeasuresAttenuation() {
        val before = PcmSpectrumTap(); val after = PcmSpectrumTap()
        feed(before, .5); feed(after, .25)
        val result = requireNotNull(PcmSpectrumAnalyzer.aligned(before.windows(), after.windows()))
        assertEquals(20 * log10(.5), result.beforeDb[64].toDouble(), .0001)
        assertEquals(20 * log10(.25), result.afterDb[64].toDouble(), .0001)
        assertTrue(result.beforeDb[70] < -100f)
    }

    @Test fun mismatchedMissingPartialAndResetWindowsNeverOverlay() {
        val before = PcmSpectrumTap(); val after = PcmSpectrumTap()
        feed(before, .5); feed(after, .25, 2_000_000)
        assertNull(PcmSpectrumAnalyzer.aligned(before.windows(), after.windows()))
        after.reset(); feed(after, .25, C.TIME_UNSET)
        assertTrue(after.windows().isEmpty())
        after.reset(); feed(after, .25, frames = 2047)
        assertTrue(after.windows().isEmpty())
        before.reset(); assertTrue(before.windows().isEmpty())
    }

    @Test fun boundedHistoryRetainsOnlyCompleteRecentWindows() {
        val tap = PcmSpectrumTap()
        feed(tap, .5, frames = 2048 * 30 + 1)
        assertEquals(7, tap.windows().size)
        assertTrue(tap.windows().all { it.left.size == 2048 && it.timeUs > 1_500_000 })
    }
}
