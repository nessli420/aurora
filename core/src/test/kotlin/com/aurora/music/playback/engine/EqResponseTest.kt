package com.aurora.music.playback.engine

import com.aurora.music.data.AudioPrefs
import com.aurora.music.data.ParamBand
import com.aurora.music.data.ProcessingRack
import com.aurora.music.data.ProcessingRackNode
import com.aurora.music.data.RackNodeKind
import com.aurora.music.data.RackEqChannel
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

class EqResponseTest {
    private fun node(bands: List<ParamBand> = emptyList(), wet: Float = 1f, bypass: Boolean = false,
        graphic: List<Float> = emptyList(), layout: Int = 0) = ProcessingRackNode(
        UUID.nameUUIDFromBytes("eq-response-test".toByteArray()).toString(), "Response", RackNodeKind.EQ,
        bypass, wet, AudioPrefs(dspGraphicBands = graphic, dspGraphicLayout = layout, dspParametric = bands))

    @Test fun channelResponseMatchesSelectedProcessingAndUnselectedDryChannel() {
        val left = node(listOf(ParamBand(1_000f, 6f, 1f))).copy(eqChannel = RackEqChannel.LEFT)
        val affected = EqResponseCalculator.calculateNodeAtFrequencies(left, 48_000, doubleArrayOf(1000.0))
        assertEquals(6.0, affected.magnitudeDb.single(), 1e-9)
        val untouched = EqResponseCalculator.calculateNodeAtFrequencies(left, 48_000, doubleArrayOf(1000.0), RackEqChannel.RIGHT)
        assertEquals(0.0, untouched.magnitudeDb.single(), 0.0)
        assertEquals(0.0, untouched.phaseDegrees.single(), 0.0)
        assertEquals(0.0, untouched.groupDelayMs.single(), 0.0)
        assertTrue(untouched.scope.contains("passes through unchanged"))
        assertThrows(IllegalArgumentException::class.java) { EqResponseCalculator.calculateNode(left, 48_000, channel = RackEqChannel.BOTH) }
    }

    @Test fun emptyBypassedAndFullyDryEqAreUnityAtEverySupportedReferenceRate() {
        for (rate in intArrayOf(44_100, 48_000, 96_000)) {
            for (n in listOf(node(), node(listOf(ParamBand(700f, 12f, 4f)), bypass = true),
                node(listOf(ParamBand(700f, 12f, 4f)), wet = 0f))) {
                val response = EqResponseCalculator.calculateNode(n, rate)
                assertEquals(rate, response.sampleRate)
                assertEquals(512, response.frequenciesHz.size)
                response.magnitudeDb.forEach { assertEquals(0.0, it, 1e-12) }
                response.phaseDegrees.forEach { assertEquals(0.0, it, 1e-12) }
                response.groupDelayMs.forEach { assertEquals(0.0, it, 1e-12) }
                assertTrue(response.scope.contains("excludes the rest of the rack"))
            }
        }
    }

    @Test fun logGridBoundsStayBelowNyquistAndInvalidDomainsAreRejected() {
        for (rate in intArrayOf(8_000, 32_000, 44_100, 48_000, 96_000, 768_000)) {
            val response = EqResponseCalculator.calculateNode(node(), rate, 32)
            assertEquals(20.0, response.minFrequencyHz, 0.0)
            assertEquals(response.maxFrequencyHz, response.frequenciesHz.last(), 0.0)
            assertTrue(response.maxFrequencyHz < response.nyquistFrequencyHz)
            assertTrue(response.maxFrequencyHz <= 20_000.0)
            val ratio = response.frequenciesHz[1] / response.frequenciesHz[0]
            for (i in 1 until response.frequenciesHz.size) assertEquals(ratio,
                response.frequenciesHz[i] / response.frequenciesHz[i - 1], 1e-12)
        }
        assertThrows(IllegalArgumentException::class.java) { EqResponseCalculator.calculateNode(node(), 7999) }
        assertThrows(IllegalArgumentException::class.java) { EqResponseCalculator.calculateNode(node(), 48_000, 31) }
        assertThrows(IllegalArgumentException::class.java) { EqResponseCalculator.calculateNode(node(), 48_000, 4097) }
        assertThrows(IllegalArgumentException::class.java) {
            EqResponseCalculator.calculateNode(node().copy(kind = RackNodeKind.LEGACY_DSP), 48_000)
        }
    }

    @Test fun sixtyFourthBandAndWetMixHaveTheKnownCenterGain() {
        for (rate in intArrayOf(44_100, 48_000, 96_000)) {
            val eq = node(List(64) { ParamBand(1000f, if (it == 63) 12f else 0f, 1f) }, wet = .25f)
            val response = EqResponseCalculator.calculateNodeAtFrequencies(eq, rate, doubleArrayOf(1000.0))
            assertEquals(20.0 * log10(.75 + .25 * 10.0.pow(12.0 / 20.0)), response.magnitudeDb.single(), 1e-9)
            assertEquals(0.0, response.phaseDegrees.single(), 1e-8)
        }
    }

    @Test fun analyticDelayUnwrapAndDryMixGroupDelayHaveIndependentKnownAnswers() {
        val delayTwo = arrayOf(BiquadCoefficients(0.0, 0.0, 1.0, 0.0, 0.0))
        for (rate in intArrayOf(44_100, 48_000, 96_000)) {
            val frequencies = DoubleArray(65) { it * rate / 128.0 }
            val pure = EqResponseCalculator.calculateCoefficients(delayTwo, rate, frequencies)
            for (i in frequencies.indices) {
                assertEquals(0.0, pure.magnitudeDb[i], 1e-12)
                assertEquals(-720.0 * frequencies[i] / rate, pure.phaseDegrees[i], 1e-10)
                assertEquals(2000.0 / rate, pure.groupDelayMs[i], 1e-12)
            }
            val half = EqResponseCalculator.calculateCoefficients(delayTwo, rate,
                doubleArrayOf(0.0, rate / 8.0, rate / 4.0, rate * 3.0 / 8.0), wet = .5)
            assertEquals(0.0, half.magnitudeDb[0], 0.0)
            assertEquals(20 * log10(cos(PI / 4)), half.magnitudeDb[1], 1e-12)
            assertEquals(1000.0 / rate, half.groupDelayMs[0], 1e-12)
            assertEquals(1000.0 / rate, half.groupDelayMs[1], 1e-12)
            assertEquals(EqResponseCalculator.MAGNITUDE_FLOOR_DB, half.magnitudeDb[2], 0.0)
            assertTrue(half.phaseDegrees[2].isNaN()); assertTrue(half.groupDelayMs[2].isNaN())
            assertTrue(half.phaseDegrees[3].isFinite()); assertTrue(half.groupDelayMs[3].isFinite())
        }
    }

    @Test fun zeroAndDeepNullResponsesExposeUndefinedPhaseWithoutInfinities() {
        for (coefficient in doubleArrayOf(0.0, 1e-10)) {
            val response = EqResponseCalculator.calculateCoefficients(
                arrayOf(BiquadCoefficients(coefficient, 0.0, 0.0, 0.0, 0.0)), 48_000, doubleArrayOf(20.0, 1000.0, 20_000.0))
            response.magnitudeDb.forEach { assertEquals(-180.0, it, 0.0) }
            response.phaseDegrees.forEach { assertTrue(it.isNaN()) }
            response.groupDelayMs.forEach { assertTrue(it.isNaN()) }
            assertEquals(-120.0, response.phaseUndefinedBelowDb, 0.0)
        }
    }

    @Test fun aboveNyquistFiltersMatchThePlaybackBypassAtTheActualSampleRate() {
        val eq = node(listOf(ParamBand(24_000f, 12f, .5f)))
        for (rate in intArrayOf(44_100, 48_000)) {
            val response = EqResponseCalculator.calculateNode(eq, rate)
            response.magnitudeDb.forEach { assertEquals(0.0, it, 1e-12) }
            response.groupDelayMs.forEach { assertEquals(0.0, it, 1e-12) }
        }
        val highRate = EqResponseCalculator.calculateNodeAtFrequencies(eq, 96_000, doubleArrayOf(24_000.0))
        assertEquals(12.0, highRate.magnitudeDb.single(), 1e-10)
    }

    @Test fun responseMatchesDftAndTimeWeightedDerivativeOfTheProductionImpulse() {
        val bands = List(64) { index -> when (index) {
            0 -> ParamBand(350f, -3f, .8f)
            31 -> ParamBand(6000f, 2f, .7f, 2)
            63 -> ParamBand(1200f, 5f, 1.3f)
            else -> ParamBand(1000f, 0f, 1f)
        } }
        val eq = node(bands, wet = .65f, graphic = List(10) { if (it == 4) -1.5f else 0f })
        for (rate in intArrayOf(44_100, 48_000, 96_000)) {
            val response = EqResponseCalculator.calculateNode(eq, rate, 32)
            val source = DoubleArray(32_768).apply { this[0] = 1.0 }
            val impulse = process(eq, rate, source)
            for (i in response.frequenciesHz.indices) {
                val omega = 2 * PI * response.frequenciesHz[i] / rate
                var real = 0.0; var imaginary = 0.0
                var derivativeReal = 0.0; var derivativeImaginary = 0.0
                for (n in impulse.indices) {
                    val c = cos(omega * n); val s = sin(omega * n)
                    real += impulse[n] * c; imaginary -= impulse[n] * s
                    derivativeReal -= n * impulse[n] * s; derivativeImaginary -= n * impulse[n] * c
                }
                assertEquals(20 * log10(hypot(real, imaginary)), response.magnitudeDb[i], 1e-6)
                assertEquals(0.0, phaseDifference(atan2(imaginary, real) * 180 / PI, response.phaseDegrees[i]), 1e-5)
                val measuredDelay = -(derivativeImaginary * real - derivativeReal * imaginary) /
                    (real * real + imaginary * imaginary) * 1000 / rate
                assertEquals(measuredDelay, response.groupDelayMs[i], 1e-5)
            }
        }
    }

    @Test fun steppedSineSweepMatchesProductionAmplitudeAndPhaseIncludingWetMix() {
        val eq = node(listOf(ParamBand(500f, 6f, .9f), ParamBand(6000f, -4f, 1.4f)), wet = .4f)
        val period = 8192
        for (rate in intArrayOf(44_100, 48_000, 96_000)) {
            for (bin in intArrayOf(17, 83, 389, 997, 1801)) {
                val frequency = bin.toDouble() * rate / period
                val omega = 2 * PI * bin / period
                val input = DoubleArray(period * 2) { sin(omega * it) * .1 }
                val output = process(eq, rate, input)
                var sine = 0.0; var cosine = 0.0
                for (i in period until output.size) {
                    sine += output[i] * sin(omega * i); cosine += output[i] * cos(omega * i)
                }
                sine *= 2.0 / period / .1; cosine *= 2.0 / period / .1
                val calculated = EqResponseCalculator.calculateNodeAtFrequencies(eq, rate, doubleArrayOf(frequency))
                assertEquals(20 * log10(hypot(sine, cosine)), calculated.magnitudeDb.single(), 1e-6)
                assertEquals(0.0, phaseDifference(atan2(cosine, sine) * 180 / PI, calculated.phaseDegrees.single()), 1e-5)
            }
        }
    }

    private fun process(eq: ProcessingRackNode, rate: Int, monoInput: DoubleArray): DoubleArray {
        val graph = ProductionSerialRack.compile(ProcessingRack(enabled = true, nodes = listOf(eq)), rate, null)
        val block = AudioBlock(AudioStreamFormat(rate, ChannelLayout.STEREO), 256)
        val result = DoubleArray(monoInput.size)
        var inputPosition = 0; var outputPosition = 0
        fun drain() {
            while (true) {
                val output = graph.getOutput() ?: return
                repeat(output.frameCount) { index ->
                    result[outputPosition++] = output.samples[index * 2]
                    assertEquals(output.samples[index * 2], output.samples[index * 2 + 1], 0.0)
                }
            }
        }
        while (inputPosition < monoInput.size) {
            val frames = minOf(256, monoInput.size - inputPosition)
            block.begin(frames)
            repeat(frames) { index ->
                block.samples[index * 2] = monoInput[inputPosition + index]
                block.samples[index * 2 + 1] = monoInput[inputPosition + index]
            }
            assertTrue(graph.queueInput(block)); drain(); inputPosition += frames
        }
        graph.queueEndOfStream(); drain()
        assertTrue(graph.isEnded); assertEquals(monoInput.size, outputPosition)
        return result
    }

    private fun phaseDifference(first: Double, second: Double): Double {
        var difference = first - second
        while (difference > 180) difference -= 360
        while (difference < -180) difference += 360
        return abs(difference)
    }
}
