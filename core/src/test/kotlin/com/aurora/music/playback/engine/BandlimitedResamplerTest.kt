package com.aurora.music.playback.engine

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class BandlimitedResamplerTest {
    @Test fun streamingSplitsResetAndFullDurationMatchOfflineConversion() {
        val input = DoubleArray(6003) { sin(it * .173) * .4 }
        for ((source, target) in listOf(44_100 to 48_000, 96_000 to 44_100, 48_000 to 192_000)) {
            val expected = BandlimitedResampler.resample(input, source, target)
            val converter = BandlimitedResampler(source, target, 1)
            repeat(2) {
                val result = ArrayList<Double>()
                val output = DoubleArray(137)
                fun drain() {
                    while (true) { val n = converter.readOutput(output, 0, output.size); if (n == 0) break; repeat(n) { result += output[it] } }
                }
                var offset = 0
                while (offset < input.size) { offset += converter.queueInput(input, offset, minOf(37, input.size - offset)); drain() }
                converter.queueEndOfInput(); drain()
                assertTrue(converter.isEnded)
                assertArrayEquals(expected, result.toDoubleArray(), 0.0)
                assertEquals((input.size * target.toDouble() / source).roundToInt(), result.size)
                converter.reset()
            }
        }
    }

    @Test fun passbandAmplitudeAndStopbandAliasingMeetMeasuredBounds() {
        fun amplitude(frequency: Double, source: Int, target: Int): Double {
            val input = DoubleArray(source / 2) { sin(2 * PI * frequency * it / source) }
            val result = BandlimitedResampler.resample(input, source, target)
            return sqrt(result.copyOfRange(1000, result.size - 1000).map { it * it }.average() * 2)
        }
        for (frequency in listOf(100.0, 1000.0, 10_000.0, 18_000.0)) {
            assertEquals("44.1 to 48 kHz, $frequency Hz", 1.0, amplitude(frequency, 44_100, 48_000), .003)
        }
        assertTrue("30 kHz must not alias when reducing to 48 kHz", amplitude(30_000.0, 96_000, 48_000) < .0002)
        assertTrue("40 kHz must not alias when reducing to 44.1 kHz", amplitude(40_000.0, 96_000, 44_100) < .0002)
    }

    @Test fun impulseConversionPreservesDcGainAndReportsIntegerAlignment() {
        for ((source, target) in listOf(48_000 to 96_000, 48_000 to 44_100, 96_000 to 48_000)) {
            val result = BandlimitedResampler.resampleImpulse(doubleArrayOf(1.0, -.2, .1), source, target)
            assertEquals(.9, result.sum(), 4e-5)
            val padding = BandlimitedResampler.impulsePadding(source, target)
            assertEquals(0, padding.toLong().times(target).rem(source).toInt())
            assertEquals(padding.toLong() * target / source, BandlimitedResampler.impulseDelayFrames(source, target).toLong())
        }
    }

    @Test fun capacityBackpressureRejectsExcessWithoutOverwritingHistory() {
        val converter = BandlimitedResampler(48_000, 96_000, 1)
        val input = DoubleArray(20_000) { .25 }
        val accepted = converter.queueInput(input, 0, input.size)
        assertTrue(accepted in 1 until input.size)
        assertEquals(0, converter.queueInput(input, accepted, input.size - accepted))
        val output = DoubleArray(1000)
        assertEquals(1000, converter.readOutput(output, 0, output.size))
        assertEquals(.25, output[999], 1e-10)
        assertTrue(converter.queueInput(input, accepted, input.size - accepted) > 0)
    }
}
