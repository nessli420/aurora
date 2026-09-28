package com.aurora.music.playback.engine

import com.aurora.music.data.*
import com.aurora.music.playback.DspBand
import com.aurora.music.playback.DspCoeffBuilder
import com.aurora.music.playback.DspParams
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class ExpandedFilterTest {
    private fun coefficients(type: FilterType, order: Int = type.orders.first(), rate: Int = 48_000,
        frequency: Float = 1000f, gain: Float = 0f, q: Float = .70710677f) =
        PrecisionDspCoeffBuilder.cascade(DspBand(frequency, gain, q, type.code, order = order,
            coefficients = if (type == FilterType.CUSTOM_BIQUAD) listOf(1.0, 0.0, 0.0, 0.0, 0.0) else null), rate).toTypedArray()

    private fun magnitude(c: Array<BiquadCoefficients>, frequency: Double, rate: Int = 48_000): Double =
        EqResponseCalculator.calculateCoefficients(c, rate, doubleArrayOf(frequency)).magnitudeDb.single()

    @Test fun butterworthMatchesBilinearAnalogReferenceAcrossOrdersAndSampleRates() {
        for (rate in listOf(44_100, 48_000, 96_000, 192_000)) for (order in listOf(2, 4, 6, 8)) {
            for (high in listOf(false, true)) {
                val c = coefficients(if (high) FilterType.BUTTERWORTH_HIGH_PASS else FilterType.BUTTERWORTH_LOW_PASS, order, rate)
                for (f in listOf(100.0, 500.0, 1000.0, 2000.0, 8000.0)) {
                    val ratio = tan(PI * f / rate) / tan(PI * 1000 / rate)
                    val expected = -10 * log10(1 + (if (high) 1 / ratio else ratio).pow(2 * order))
                    assertEquals("$rate/$order/$high/$f", expected, magnitude(c, f, rate), 1e-7)
                }
            }
        }
    }

    @Test fun linkwitzRileyHasSixDecibelCrossoverAndComplementaryComplexSum() {
        for (order in listOf(4, 8)) for (rate in listOf(48_000, 96_000)) {
            val low = coefficients(FilterType.LINKWITZ_RILEY_LOW_PASS, order, rate)
            val high = coefficients(FilterType.LINKWITZ_RILEY_HIGH_PASS, order, rate)
            assertEquals(-6.020599913, magnitude(low, 1000.0, rate), 1e-7)
            assertEquals(-6.020599913, magnitude(high, 1000.0, rate), 1e-7)
            val frequencies = doubleArrayOf(200.0, 500.0, 1000.0, 2000.0, 4000.0)
            val l = EqResponseCalculator.calculateCoefficients(low, rate, frequencies)
            val r = EqResponseCalculator.calculateCoefficients(high, rate, frequencies)
            for (i in frequencies.indices) {
                val lm = 10.0.pow(l.magnitudeDb[i] / 20); val rm = 10.0.pow(r.magnitudeDb[i] / 20)
                val lp = l.phaseDegrees[i] * PI / 180; val rp = r.phaseDegrees[i] * PI / 180
                assertEquals(1.0, hypot(lm * cos(lp) + rm * cos(rp), lm * sin(lp) + rm * sin(rp)), 1e-8)
            }
        }
    }

    @Test fun bandpassNotchAllpassAndTiltMatchTheirReferenceProperties() {
        assertEquals(0.0, magnitude(coefficients(FilterType.BAND_PASS), 1000.0), 1e-10)
        assertTrue(magnitude(coefficients(FilterType.NOTCH), 1000.0) < -120)
        val allpass = EqResponseCalculator.calculateCoefficients(coefficients(FilterType.ALL_PASS), 48_000,
            doubleArrayOf(20.0, 100.0, 1000.0, 10000.0, 20000.0))
        allpass.magnitudeDb.forEach { assertEquals(0.0, it, 1e-10) }
        assertTrue(allpass.groupDelayMs[2] > 0)
        val tilt = coefficients(FilterType.TILT, gain = 12f)
        assertEquals(-6.0, magnitude(tilt, 0.0), 1e-8)
        assertEquals(6.0, magnitude(tilt, 24000.0), 1e-8)
        assertEquals(0.0, magnitude(tilt, 1000.0), 1e-8)
    }

    @Test fun filtersRemainFiniteStableAtSupportedExtremesAndNyquistBypasses() {
        for (type in FilterType.entries) for (order in type.orders) for (rate in listOf(8000, 48000, 192000, 768000)) {
            for (frequency in listOf(10f, 1000f, 24000f)) for (q in listOf(.1f, 100f)) {
                val c = coefficients(type, order, rate, frequency, if (type.hasGain) 30f else 0f, q)
                c.forEach {
                    assertTrue(listOf(it.b0, it.b1, it.b2, it.a1, it.a2).all(Double::isFinite))
                    assertTrue(abs(it.a2) < 1.0 && 1 + it.a1 + it.a2 > 0 && 1 - it.a1 + it.a2 > 0)
                }
                if (frequency >= rate / 2 && type != FilterType.CUSTOM_BIQUAD) assertEquals(listOf(BiquadCoefficients.IDENTITY), c.toList())
            }
        }
    }

    @Test fun bypassIsIdentityAndLegacyBankMatchesDedicatedCascadeForEveryFilter() {
        for (type in FilterType.entries) {
            val band = ParamBand(1000f, if (type.hasGain) -3f else 0f, .8f, type.code, order = type.orders.last(),
                coefficients = if (type == FilterType.CUSTOM_BIQUAD) listOf(.5, .25, 0.0, -.2, .1) else null)
            val p = DspParams(graphic = floatArrayOf(), parametric = listOf(DspBand.from(band)))
            val dedicated = ProductionEqCoefficients.build(AudioPrefs(dspGraphicBands = emptyList(), dspParametric = listOf(band)), 48000)
            val legacy = PrecisionDspCoeffBuilder.build(p, 48000)
            dedicated.forEachIndexed { i, c -> assertEquals(c, legacy.filter(DspCoeffBuilder.MAX_GRAPHIC + i)) }
            val bypass = PrecisionDspCoeffBuilder.cascade(DspBand.from(band.copy(enabled = false)), 48000)
            assertEquals(listOf(BiquadCoefficients.IDENTITY), bypass)
        }
    }

    @Test fun customBiquadUsesExactEnteredCoefficientsAtEveryActiveRate() {
        val values = listOf(.5, .25, 0.0, -.2, .1)
        val b = DspBand(24000f, 0f, 1f, FilterType.CUSTOM_BIQUAD.code, coefficients = values)
        val expected = BiquadCoefficients(.5, .25, 0.0, -.2, .1)
        for (rate in listOf(8000, 48000, 96000, 192000)) {
            assertEquals(listOf(expected), PrecisionDspCoeffBuilder.cascade(b, rate))
            assertEquals(20 * log10(.75 / .9), magnitude(arrayOf(expected), 0.0, rate), 1e-10)
        }
        val at48 = magnitude(arrayOf(expected), 1000.0, 48000)
        val at96 = magnitude(arrayOf(expected), 2000.0, 96000)
        assertEquals(at48, at96, 1e-12)
    }
}
