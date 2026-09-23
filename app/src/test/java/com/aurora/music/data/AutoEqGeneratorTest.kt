package com.aurora.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoEqGeneratorTest {
    @Test fun combinedBoostGetsEnoughHeadroomBeyondOldTwentyDbCap() {
        val band = ParamBand(1000f, 12f, 1f)
        assertEquals(-24f, AutoEqGenerator.headroomPreamp(listOf(band, band)) ?: 0f, .05f)
    }

    @Test fun correctionsBeyondStoredPreampRangeAreRejected() {
        val band = ParamBand(1000f, 12f, 1f)
        assertNull(AutoEqGenerator.headroomPreamp(List(6) { band }))
    }
}
