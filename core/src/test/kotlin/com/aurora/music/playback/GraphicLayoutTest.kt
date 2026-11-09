package com.aurora.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphicLayoutTest {
    @Test fun customLayoutsSpanTheAudibleRangeWithDistinctRisingBands() {
        for (count in DspCoeffBuilder.MIN_GRAPHIC..DspCoeffBuilder.MAX_GRAPHIC) {
            val layout = DspCoeffBuilder.customLayout(count)
            assertEquals(count, layout.freqs.size)
            assertEquals(20f, layout.freqs.first(), 0f)
            assertEquals(20_000f, layout.freqs.last(), 0f)
            assertTrue("$count bands rise", layout.freqs.toList().zipWithNext().all { (a, b) -> b > a })
            assertTrue(layout.q > 0f)
        }
    }

    @Test fun customQTracksTheFixedIsoLayouts() {
        // the fixed 10/31-band layouts use Q 1.41 and 4.32; even spacing over the same range lands close
        assertEquals(1.41f, DspCoeffBuilder.customLayout(10).q, 0.15f)
        assertEquals(4.32f, DspCoeffBuilder.customLayout(31).q, 0.1f)
    }

    @Test fun customCountsOutsideTheRangeAreClamped() {
        assertEquals(DspCoeffBuilder.MIN_GRAPHIC, DspCoeffBuilder.customLayout(1).freqs.size)
        assertEquals(DspCoeffBuilder.MAX_GRAPHIC, DspCoeffBuilder.customLayout(99).freqs.size)
    }

    @Test fun customLayoutSizeComesFromTheSavedGains() {
        assertEquals(7, DspCoeffBuilder.graphicLayout(DspCoeffBuilder.CUSTOM_LAYOUT, List(7) { 0f }).freqs.size)
        assertEquals(DspCoeffBuilder.GRAPHIC_BANDS, DspCoeffBuilder.graphicLayout(DspCoeffBuilder.CUSTOM_LAYOUT, emptyList()).freqs.size)
        assertEquals(15, DspCoeffBuilder.graphicLayout(1, List(7) { 0f }).freqs.size)
        assertEquals(10, DspCoeffBuilder.graphicLayout(42, emptyList()).freqs.size)
    }
}
