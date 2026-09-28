package com.aurora.music.data.ir

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class MinimumPhaseImpulseTest {
    @Test fun reflectedZeroMatchesAnalyticalMinimumPhaseAndPreservesMagnitude() {
        val input = DoubleArray(128).apply { this[0] = .25; this[1] = 1.0 }
        val result = MinimumPhaseImpulse.convert(input)
        assertEquals(1.0, result[0], 1e-10)
        assertEquals(.25, result[1], 1e-10)
        assertTrue(result.drop(2).all { abs(it) < 1e-10 })
        for (bin in 0..100) {
            val w = PI * bin / 100
            fun magnitude(samples: DoubleArray): Double = hypot(samples.indices.sumOf { samples[it] * cos(it * w) }, samples.indices.sumOf { samples[it] * sin(it * w) })
            assertEquals(magnitude(input), magnitude(result), 1e-9)
        }
    }
    @Test fun delayIsRemovedAndSilenceRemainsFinite() {
        val delayed = DoubleArray(256).apply { this[77] = .5 }
        val converted = MinimumPhaseImpulse.convert(delayed)
        assertEquals(.5, converted[0], 1e-12)
        assertTrue(converted.drop(1).all { abs(it) < 1e-12 })
        assertArrayEquals(DoubleArray(40), MinimumPhaseImpulse.convert(DoubleArray(40)), 0.0)
    }

    @Test fun negativeDcAndZeroDcPathsKeepTheirPolarity() {
        val reflected = DoubleArray(128).apply { this[0] = .25; this[1] = -1.0 }
        val minimum = MinimumPhaseImpulse.convert(reflected)
        assertEquals(-1.0, minimum[0], 1e-10)
        assertEquals(.25, minimum[1], 1e-10)
        assertTrue(minimum.drop(2).all { abs(it) < 1e-10 })
        val zeroDc = DoubleArray(128).apply { this[0] = 1.0; this[1] = -1.0 }
        val positive = MinimumPhaseImpulse.convert(zeroDc)
        val negative = MinimumPhaseImpulse.convert(DoubleArray(zeroDc.size) { -zeroDc[it] })
        assertTrue(positive[0] > 0.0)
        assertTrue(negative[0] < 0.0)
        assertArrayEquals(DoubleArray(positive.size) { -positive[it] }, negative, 0.0)
    }
}
