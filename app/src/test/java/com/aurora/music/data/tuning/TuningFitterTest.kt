package com.aurora.music.data.tuning

import com.aurora.music.data.AudioPrefs
import com.aurora.music.data.ProcessingRackNode
import com.aurora.music.data.RackNodeKind
import com.aurora.music.playback.engine.EqResponseCalculator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

class TuningFitterTest {
    private fun config(rate: Int = 48_000, budget: Int = 6) = TuningFitConfig(sampleRate = rate, bandBudget = budget,
        normalization = TuningNormalization.NONE, smoothingOctaves = 0.0)

    private fun curve(name: String = "Left", min: Double = 20.0, max: Double = 20_000.0, phase: Boolean = false,
        magnitude: (Double) -> Double): MeasurementCurve {
        val points = List(2048) { index ->
            val frequency = when (index) { 0 -> min; 2047 -> max; else -> min * exp(ln(max / min) * index / 2047) }
            FrequencyResponsePoint(frequency, magnitude(frequency), if (phase) -450.0 + index else null)
        }
        val source = points.joinToString("\n") { "${it.frequencyHz} ${it.magnitudeDb}" + (it.phaseDegrees?.let { value -> " $value" } ?: "") }
        return MeasurementCurve(UUID.randomUUID().toString(), name, points, source)
    }

    private fun project(left: MeasurementCurve? = curve { 0.0 }, right: MeasurementCurve? = null,
        settings: TuningFitConfig = config(), target: MeasurementCurve? = null) = TuningProject(
        UUID.randomUUID().toString(), "Synthetic fixture", left, right, target, config = settings)

    // An independent closed-form RBJ peak response, without normalized production coefficients.
    private fun peak(frequency: Double, center: Double, gain: Double, q: Double, rate: Int): Double {
        val a = 10.0.pow(gain / 40.0)
        val omega = 2 * PI * frequency / rate
        val centerOmega = 2 * PI * center / rate
        val alpha = sin(centerOmega) / (2 * q)
        val real = cos(omega) - cos(centerOmega)
        val imaginary = alpha * sin(omega)
        return 10 * log10((real * real + imaginary * imaginary * a * a) /
            (real * real + imaginary * imaginary / (a * a)))
    }

    private fun actual(fit: TuningFitResult, channel: TuningChannelFit, frequencies: DoubleArray = fit.frequenciesHz.toDoubleArray()): DoubleArray {
        val node = ProcessingRackNode(UUID.randomUUID().toString(), "Generated EQ", RackNodeKind.EQ,
            audio = AudioPrefs(dspParametric = channel.bands))
        return EqResponseCalculator.calculateNodeAtFrequencies(node, fit.config.sampleRate, frequencies).magnitudeDb
    }

    @Test fun knownPeaksImproveAtAllReferenceRatesAndReportedResponseMatchesPlayback() = runBlocking<Unit> {
        for (rate in intArrayOf(44_100, 48_000, 96_000)) {
            val input = project(curve(phase = true) { peak(it, 950.0, 6.0, 1.4, rate) }, settings = config(rate, 4))
            val original = input.measurementLeft!!.points.toList()
            val fit = TuningFitter.fit(input)
            val channel = fit.channels.single()
            assertTrue("rate $rate: ${fit.errorBeforeDb} -> ${fit.errorAfterDb}", fit.errorAfterDb < fit.errorBeforeDb * .3)
            assertTrue(channel.bands.isNotEmpty())
            val playback = actual(fit, channel)
            for (index in playback.indices) {
                assertEquals(playback[index], channel.fittedDb[index], 1e-6)
                assertEquals(channel.measuredDb[index] + playback[index], channel.predictedDb[index], 1e-6)
                assertEquals(channel.targetDb[index] - channel.measuredDb[index], channel.correctionDb[index], 0.0)
            }
            assertEquals(original, input.measurementLeft!!.points)
            assertTrue(input.measurementLeft!!.points.all { it.phaseDegrees != null })
            TuningFitter.verifyGeneratedFit(input, fit)
        }
    }

    @Test fun alreadyMatchingFlatResponseUsesNoFiltersAndNoPreamp() = runBlocking<Unit> {
        val input = project(settings = config(budget = 64))
        val fit = TuningFitter.fit(input)
        assertTrue(fit.channels.single().bands.isEmpty())
        assertEquals(0.0, fit.errorBeforeDb, 0.0)
        assertEquals(0.0, fit.errorAfterDb, 0.0)
        assertEquals(0f, fit.preampDb)
        assertTrue(fit.channels.single().fittedDb.all { it == 0.0 })
        TuningFitter.verifyGeneratedFit(input, fit)
    }

    @Test fun independentChannelsRetainTheirDifferentCorrectionsAndSplitOneTotalBudget() = runBlocking<Unit> {
        val input = project(curve { peak(it, 600.0, 6.0, 1.4, 48_000) },
            curve("Right") { peak(it, 3000.0, -5.0, 1.4, 48_000) }, config(budget = 5))
        val fit = TuningFitter.fit(input)
        assertEquals(listOf(TuningFitChannel.LEFT, TuningFitChannel.RIGHT), fit.channels.map { it.channel })
        assertTrue(fit.channels[0].bands.size <= 3)
        assertTrue(fit.channels[1].bands.size <= 2)
        assertTrue(fit.channels.sumOf { it.bands.size } <= 5)
        fit.channels.forEach { assertTrue(it.errorAfterDb < it.errorBeforeDb * .4) }
        assertTrue(fit.channels[0].bands.any { it.gainDb < 0 })
        assertTrue(fit.channels[1].bands.any { it.gainDb > 0 })
        assertTrue(fit.preampDb <= -fit.peakBoostDb - .49)
        TuningFitter.verifyGeneratedFit(input, fit)
        val onlyOneBand = TuningFitter.fit(input.copy(config = config(budget = 1)))
        assertTrue(onlyOneBand.channels[1].bands.isEmpty())
        assertEquals(onlyOneBand.channels[1].errorBeforeDb, onlyOneBand.channels[1].errorAfterDb, 0.0)
    }

    @Test fun linkedAverageIsExplicitAndDoesNotReplaceEitherRawChannel() = runBlocking<Unit> {
        val left = curve { peak(it, 1000.0, 6.0, 1.0, 48_000) }
        val right = curve("Right") { -peak(it, 1000.0, 6.0, 1.0, 48_000) }
        val input = project(left, right, config().copy(channelMode = TuningChannelMode.LINKED_AVERAGE))
        val fit = TuningFitter.fit(input)
        assertEquals(TuningFitChannel.LINKED_AVERAGE, fit.channels.single().channel)
        assertTrue(fit.channels.single().bands.isEmpty())
        assertEquals(0.0, fit.errorBeforeDb, 1e-10)
        assertEquals(left, input.measurementLeft)
        assertEquals(right, input.measurementRight)
        assertTrue(fit.notes.any { it.contains("individual channel errors can differ") })
        TuningFitter.verifyGeneratedFit(input, fit)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { TuningFitter.fit(input.copy(measurementRight = null)) } }
    }

    @Test fun sharedNormalizationKeepsChannelLevelDifferenceAndTargetGetsItsOwnOffset() = runBlocking<Unit> {
        val input = project(curve { 83.0 }, curve("Right") { 77.0 }, config().copy(
            normalization = TuningNormalization.MATCH_MEAN_200_2000, maxBoostDb = 0.0, maxCutDb = 0.0), curve("Target") { 92.0 })
        val fit = TuningFitter.fit(input)
        assertEquals(80.0, fit.measuredNormalizationDb, 1e-10)
        assertEquals(92.0, fit.targetNormalizationDb, 1e-10)
        assertTrue(fit.channels[0].measuredDb.all { abs(it - 3.0) < 1e-10 })
        assertTrue(fit.channels[1].measuredDb.all { abs(it + 3.0) < 1e-10 })
        fit.channels.forEach { assertTrue(it.targetDb.all { value -> abs(value) < 1e-10 }) }
        assertEquals(3.0, fit.errorBeforeDb, 1e-10)
        assertEquals(fit.errorBeforeDb, fit.errorAfterDb, 0.0)
        val none = TuningFitter.fit(input.copy(config = input.config.copy(normalization = TuningNormalization.NONE)))
        assertEquals(0.0, none.measuredNormalizationDb, 0.0)
        assertEquals(0.0, none.targetNormalizationDb, 0.0)
        assertEquals(83.0, none.channels[0].measuredDb.first(), 0.0)
        TuningFitter.verifyGeneratedFit(input, fit)
    }

    @Test fun coverageUsesMeasurementTargetAndNyquistIntersectionWithoutExtrapolation() = runBlocking<Unit> {
        val input = project(curve(min = 30.0, max = 12_000.0) { 0.0 }, curve("Right", 70.0, 16_000.0) { 0.0 },
            config(16_000), curve("Target", 100.0, 10_000.0) { 0.0 })
        val fit = TuningFitter.fit(input)
        assertEquals(100.0, fit.minFrequencyHz, 0.0)
        assertTrue(fit.maxFrequencyHz < 8000.0)
        assertTrue(fit.frequenciesHz.all { it in 100.0..8000.0 })
        assertEquals(fit.minFrequencyHz, fit.frequenciesHz.first(), 0.0)
        assertEquals(fit.maxFrequencyHz, fit.frequenciesHz.last(), 0.0)
        TuningFitter.verifyGeneratedFit(input, fit)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { TuningFitter.fit(input.copy(
            target = curve("No overlap", 15_000.0, 20_000.0) { 0.0 })) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { TuningFitter.fit(project(
            curve(min = 4000.0) { 0.0 }, settings = config().copy(normalization = TuningNormalization.MATCH_MEAN_200_2000))) } }
    }

    @Test fun combinedBoundsAndConservativePreampComeFromActualFinalResponse() = runBlocking<Unit> {
        val input = project(curve { -peak(it, 400.0, 12.0, 1.0, 48_000) + peak(it, 2200.0, 14.0, 2.0, 48_000) },
            settings = config(budget = 8).copy(maxBoostDb = 3.0, maxCutDb = 5.0, minQ = .3, maxQ = 4.0))
        val fit = TuningFitter.fit(input)
        val frequencies = DoubleArray(4096) { 1.0 * exp(ln(23_999.0) * it / 4095) }
        val response = actual(fit, fit.channels.single(), frequencies)
        assertTrue(response.max() <= 3.001)
        assertTrue(response.min() >= -5.001)
        assertTrue(fit.errorAfterDb <= fit.errorBeforeDb)
        assertTrue(fit.preampDb.toDouble() + response.max() <= -.49)
        fit.channels.single().bands.forEach {
            assertTrue(it.freqHz >= 20 && it.freqHz <= 20_000)
            assertTrue(it.q >= .3 - 1e-6 && it.q <= 4 + 1e-6)
            assertTrue(it.gainDb >= -5 - 1e-6 && it.gainDb <= 3 + 1e-6)
            assertEquals(0, it.type)
        }
        TuningFitter.verifyGeneratedFit(input, fit)
    }

    @Test fun incompatibleConstraintsRetainZeroCorrectionInsteadOfWorseningError() = runBlocking<Unit> {
        val input = project(curve { -peak(it, 500.0, 8.0, 1.0, 48_000) }, settings = config().copy(maxBoostDb = 0.0))
        val fit = TuningFitter.fit(input)
        assertTrue(fit.channels.single().bands.isEmpty())
        assertEquals(fit.errorBeforeDb, fit.errorAfterDb, 0.0)
        assertTrue(fit.notes.any { it.contains("zero correction") })
        TuningFitter.verifyGeneratedFit(input, fit)
    }

    @Test fun budgetsAboveTwelveCanProduceMoreThanTwelveIndependentPeakFilters() = runBlocking<Unit> {
        val input = project(curve { 3 * sin(ln(it / 20.0) / ln(1000.0) * 2 * PI * 13) },
            settings = config(budget = 16).copy(maxBoostDb = 12.0, maxCutDb = 12.0, maxQ = 40.0))
        val fit = TuningFitter.fit(input)
        assertTrue("Used ${fit.channels.single().bands.size} bands", fit.channels.single().bands.size > 12)
        assertTrue(fit.channels.single().bands.size <= 16)
        assertTrue(fit.errorAfterDb < fit.errorBeforeDb)
        TuningFitter.verifyGeneratedFit(input, fit)
    }

    @Test fun exactDecimalQBoundsRemainSavableAfterFloatQuantization() = runBlocking<Unit> {
        val input = project(curve { peak(it, 800.0, 4.0, .1, 48_000) },
            settings = config(budget = 1).copy(minQ = .1, maxQ = .1))
        val fit = TuningFitter.fit(input)
        assertTrue(fit.channels.single().bands.isNotEmpty())
        assertEquals(.1f, fit.channels.single().bands.single().q)
        val saved = input.copy(generatedFit = fit)
        assertEquals(saved, TuningProjectCodec.decodeProject(TuningProjectCodec.encodeProject(saved)).getOrThrow())
    }

    @Test fun cancellationStopsAnInProgressBoundedFitWithoutProducingAResult() {
        val input = project(curve { 6 * sin(ln(it)) }, settings = config(budget = 64))
        val job = Job()
        var progressCalls = 0
        assertThrows(CancellationException::class.java) {
            runBlocking { withContext(job) { TuningFitter.fit(input) { _, _ -> progressCalls++; job.cancel() } } }
        }
        assertEquals(1, progressCalls)
        assertNull(input.generatedFit)
    }

    @Test fun forgedArraysMetricsBandsPreampOrInputsCannotPassPureVerification() = runBlocking<Unit> {
        val input = project(curve { -peak(it, 1000.0, 5.0, 1.0, 48_000) }, settings = config(budget = 2))
        val fit = TuningFitter.fit(input)
        TuningFitter.verifyGeneratedFit(input, fit)
        val channel = fit.channels.single()
        val forged = listOf(
            fit.copy(preampDb = 0f), fit.copy(peakBoostDb = 0.0), fit.copy(errorAfterDb = fit.errorAfterDb + 1.0),
            fit.copy(measuredNormalizationDb = 10.0),
            fit.copy(channels = listOf(channel.copy(bands = channel.bands.map { it.copy(gainDb = -it.gainDb) }))),
            fit.copy(channels = listOf(channel.copy(measuredDb = channel.measuredDb.map { it + 1.0 }))),
            fit.copy(channels = listOf(channel.copy(fittedDb = channel.fittedDb.map { it + 1.0 }))),
            fit.copy(channels = listOf(channel.copy(predictedDb = channel.predictedDb.map { it + 1.0 }))),
            fit.copy(frequenciesHz = fit.frequenciesHz.map { it * 1.01 }),
        )
        forged.forEach { candidate -> assertThrows(IllegalArgumentException::class.java) { TuningFitter.verifyGeneratedFit(input, candidate) } }
        assertThrows(IllegalArgumentException::class.java) { TuningFitter.verifyGeneratedFit(input.copy(
            measurementLeft = curve { 0.0 }), fit) }
    }

    @Test fun invalidPublicFitInputsUseTheSameLimitsAsProjectPersistence() {
        val input = project()
        val invalid = listOf(config().copy(bandBudget = 0), config().copy(bandBudget = 65),
            config().copy(maxBoostDb = 25.0), config().copy(smoothingOctaves = 1.5),
            config().copy(minQ = .01), config().copy(maxQ = Double.NaN), config().copy(sampleRate = 7999))
        invalid.forEach { settings ->
            assertThrows(IllegalArgumentException::class.java) { TuningProjectCodec.validateConfig(settings) }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { TuningFitter.fit(input, settings) } }
        }
    }
}
