package com.aurora.music.playback.engine

import com.aurora.music.playback.DspBand
import com.aurora.music.playback.DspCoeffBuilder
import com.aurora.music.playback.DspParams
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*
import kotlin.random.Random

class PrecisionEffectsKernelTest {
    private val format = AudioStreamFormat(48_000, ChannelLayout.STEREO)
    // Out-of-band graphic slot isolates each effect from even identity-filter rounding noise.
    private val neutral = DspParams(limiterEnabled = false, graphicFreqs = floatArrayOf(24_000f))

    @Test fun peakingAndShelvesHaveTheirAnalyticFrequencyResponsesInDouble() {
        for (gain in listOf(-12.0, 6.0, 18.0)) {
            val peak = PrecisionDspCoeffBuilder.band(0, 1_000.0, gain, 1.7, 48_000)
            assertEquals(gain, magnitudeDb(peak, 1_000.0), 1e-10)
            val low = PrecisionDspCoeffBuilder.band(1, 100.0, gain, 0.707, 48_000)
            assertEquals(gain, magnitudeDb(low, 0.0), 1e-8)
            assertEquals(0.0, magnitudeDb(low, 24_000.0), 1e-12)
            val high = PrecisionDspCoeffBuilder.band(2, 8_000.0, gain, 0.707, 48_000)
            assertEquals(0.0, magnitudeDb(high, 0.0), 1e-12)
            assertEquals(gain, magnitudeDb(high, 24_000.0), 1e-10)
        }
        val precise = PrecisionDspCoeffBuilder.band(0, 31.0, 4.0, 4.32, 192_000)
        assertNotEquals(precise.b0.toFloat().toDouble(), precise.b0, 0.0)
        assertEquals(BiquadCoefficients.IDENTITY, PrecisionDspCoeffBuilder.band(0, 24_000.0, 6.0, 1.0, 48_000))
        assertEquals(0.0, magnitudeDb(PrecisionDspCoeffBuilder.band(1, 100.0, 0.0, 1.0, 48_000), 1_000.0), 1e-12)
    }

    @Test fun preparedBankMatchesLegacySlotOrderAndRemainsIndependentOfMutablePreferences() {
        val p = richParameters()
        for (rate in listOf(8_000, 44_100, 48_000, 96_000, 192_000)) {
            val old = DspCoeffBuilder.build(p, rate)
            val precise = PrecisionDspCoeffBuilder.build(p, rate)
            assertEquals(old.nBiquads, precise.nBiquads)
            repeat(old.nBiquads) { i ->
                val b = precise.filter(i)
                assertArrayEquals(doubleArrayOf(old.b0[i].toDouble(), old.b1[i].toDouble(), old.b2[i].toDouble(),
                    old.a1[i].toDouble(), old.a2[i].toDouble()), doubleArrayOf(b.b0, b.b1, b.b2, b.a1, b.a2), 2e-6)
            }
            assertEquals(old.delayL, precise.delayL); assertEquals(old.delayR, precise.delayR)
            assertEquals(old.crossfeedDelay, precise.crossfeedDelay)
        }
        val frozen = PrecisionDspCoeffBuilder.build(p, format.sampleRate)
        val original = frozen.filter(0)
        p.graphic[0] = 12f; p.graphicFreqs[0] = 6_000f
        assertEquals(original, frozen.filter(0))
    }

    @Test fun quietSamplesAndHeadroomAreNotQuantizedOrClippedInsideTheEffectsKernel() {
        val quiet = 2.0.pow(-80)
        val input = doubleArrayOf(quiet, quiet, 1.0 + 2.0.pow(-40), 1.0 + 2.0.pow(-40), -3.0, 3.0)
        val result = process(input, neutral)
        input.indices.forEach { assertEquals(input[it], result[it], abs(input[it]) * 1e-14) }
    }

    @Test fun gainBalanceTrimAndWidthPreserveTheirExistingOrder() {
        val p = neutral.copy(preampDb = -3f, balance = 0.3f, width = 1.6f, trimLeftDb = 2f, trimRightDb = -4f)
        val left = 0.2 * 10.0.pow(-3.0 / 20.0) * (1.0 - 0.3f.toDouble()) * 10.0.pow(2.0 / 20.0)
        val right = -0.4 * 10.0.pow(-3.0 / 20.0) * 10.0.pow(-4.0 / 20.0)
        val mid = (left + right) * 0.5; val side = (left - right) * 0.5 * 1.6f.toDouble()
        assertArrayEquals(doubleArrayOf(mid + side, mid - side), process(doubleArrayOf(0.2, -0.4), p), 1e-16)
    }

    @Test fun saturationPreservesTheExistingAsymmetryAndDcComponent() {
        val drive = 0.4f.toDouble(); val k = 1.0 + 5.0 * drive
        val input = doubleArrayOf(0.0, 0.0, 0.5, -0.5)
        val expected = input.map { x -> val s = tanh(k * x) / k; s + drive * 0.2 * (s * s - 0.33) }.toDoubleArray()
        assertArrayEquals(expected, process(input, neutral.copy(saturation = 0.4f)), 1e-16)
        assertTrue(expected[0] < 0.0)
    }

    @Test fun crossfeedIsDelayedFilteredAndCrossesToOnlyTheOppositeChannel() {
        val coefficients = PrecisionDspCoeffBuilder.build(neutral.copy(crossfeed = 1f), 48_000)
        val input = DoubleArray(64 * 2).apply { this[0] = 1.0 }
        val output = process(input, neutral.copy(crossfeed = 1f), 7)
        repeat(64) { frame ->
            assertEquals(if (frame == 0) 1.0 else 0.0, output[frame * 2], 0.0)
            val expected = if (frame < coefficients.crossfeedDelay) 0.0 else
                0.5 * (1.0 - coefficients.crossfeedLpfA) * coefficients.crossfeedLpfA.pow(frame - coefficients.crossfeedDelay)
            assertEquals(expected, output[frame * 2 + 1], 2e-17)
        }
    }

    @Test fun compressorLinksStereoAndUsesTheExistingAttackEnvelope() {
        val p = neutral.copy(compEnabled = true, compThreshDb = -12f, compRatio = 4f)
        val threshold = 10.0.pow(-12.0 / 20.0)
        val desired = (0.8 / threshold).pow(1.0 / 4.0 - 1.0)
        val attack = exp(-1.0 / (0.010 * 48_000))
        val input = DoubleArray(2_000 * 2) { if (it % 2 == 0) 0.8 else -0.2 }
        val result = process(input, p, 31)
        for (frame in listOf(0, 1, 999, 1_999)) {
            val gain = desired + (1.0 - desired) * attack.pow(frame + 1)
            assertEquals(0.8 * gain, result[frame * 2], 2e-14)
            assertEquals(-0.2 * gain, result[frame * 2 + 1], 1e-14)
        }
    }

    @Test fun limiterRetainsItsAttackAndReleaseRatherThanClaimingABrickWall() {
        val p = neutral.copy(limiterEnabled = true, limiterCeilingDb = -6f)
        val ceiling = 10.0.pow(-6.0 / 20.0)
        val attack = exp(-1.0 / (0.002 * 48_000))
        val initialGain = ceiling + (1.0 - ceiling) * attack
        val result = process(doubleArrayOf(1.0, -0.5, 0.1, -0.05), p)
        assertEquals(initialGain, result[0], 1e-15)
        assertEquals(-0.5 * initialGain, result[1], 1e-15)
        val released = 1.0 + (initialGain - 1.0) * exp(-1.0 / (0.10 * 48_000))
        assertEquals(0.1 * released, result[2], 1e-15)
        assertTrue(result[0] > ceiling)
    }

    @Test fun channelDelaysAreIndependentAndUseLegacyIntegerFrameRounding() {
        val p = neutral.copy(delayLeftMs = 1f, delayRightMs = 2f, trimLeftDb = -6f)
        val input = DoubleArray(120 * 2).apply { this[0] = 1.0; this[1] = -1.0 }
        val result = process(input, p, 13)
        repeat(120) { frame ->
            assertEquals(if (frame == 48) 10.0.pow(-6.0 / 20.0) else 0.0, result[frame * 2], 1e-16)
            assertEquals(if (frame == 96) -1.0 else 0.0, result[frame * 2 + 1], 0.0)
        }
    }

    @Test fun liveGainUpdatesSmoothAcrossBlockBoundariesAndResetStartsAtTheNewTarget() {
        val kernel = PrecisionEffectsKernel(format)
        val block = AudioBlock(format, 4)
        block.begin(1); block.samples[0] = 1.0; block.samples[1] = 1.0
        kernel.process(block, PrecisionDspCoeffBuilder.build(neutral, 48_000))
        val changed = PrecisionDspCoeffBuilder.build(neutral.copy(preampDb = -12f), 48_000)
        val target = 10.0.pow(-12.0 / 20.0)
        repeat(4) { frame ->
            block.samples[0] = 1.0; block.samples[1] = 1.0
            kernel.process(block, changed)
            assertEquals(target + (1.0 - target) * exp(-(frame + 1) / (0.005 * 48_000)), block.samples[0], 1e-15)
        }
        kernel.reset(); block.samples[0] = 1.0; block.samples[1] = 1.0
        kernel.process(block, changed)
        assertEquals(target, block.samples[0], 0.0)
    }

    @Test fun returningAGraphicBandToZeroKeepsItsExistingRecursiveHistory() {
        val p = neutral.copy(graphicFreqs = floatArrayOf(1_000f), graphic = floatArrayOf(6f))
        val boosted = PrecisionDspCoeffBuilder.build(p, 48_000)
        val flat = PrecisionDspCoeffBuilder.build(p.copy(graphic = floatArrayOf(0f)), 48_000)
        val kernel = PrecisionEffectsKernel(format)
        val block = AudioBlock(format, 1)
        block.begin(1); block.samples[0] = 0.5; block.samples[1] = 0.0
        kernel.process(block, boosted)
        val firstOutput = boosted.filter(0).b0 * 0.5
        block.samples.fill(0.0)
        kernel.process(block, flat)
        val expected = flat.filter(0).b1 * 0.5 - flat.filter(0).a1 * firstOutput
        assertEquals(expected, block.samples[0], 1e-16)
        assertTrue(abs(expected) > 0.001)
        assertEquals(0.0, block.samples[1], 0.0)
    }

    @Test fun fullEffectsStateIsExactlyInvariantToCallbackPartitioning() {
        val random = Random(71069)
        val input = DoubleArray(8_192 * 2) { random.nextDouble(-0.3, 0.3) }
        val p = richParameters()
        val reference = process(input, p, 8_192)
        for (chunk in listOf(1, 7, 256, 1_023)) assertArrayEquals("chunk $chunk", reference, process(input, p, chunk), 0.0)
    }

    @Test fun resetClearsAllFilterCrossfeedAndDelayHistories() {
        val p = richParameters().copy(saturation = 0f)
        val c = PrecisionDspCoeffBuilder.build(p, 48_000)
        val kernel = PrecisionEffectsKernel(format)
        val block = AudioBlock(format, 512)
        block.begin(512); block.samples[0] = 0.8; block.samples[1] = -0.3
        kernel.process(block, c)
        kernel.reset(); block.samples.fill(0.0)
        kernel.process(block, c)
        assertTrue(block.samples.all { it == 0.0 })
        assertEquals(SamplePrecision.FLOAT_64, kernel.capabilities.statePrecision)
        assertNull(kernel.capabilities.tailFrames)
    }

    @Test fun fullEffectsPortHasBoundedDifferenceFromLegacyFloat() {
        val random = Random(29491)
        val input = DoubleArray(12_000 * 2) { (random.nextInt(-8_192, 8_193) / 32_768f).toDouble() }
        val p = richParameters()
        val legacy = LegacyFloatEffectsReference.process(input, p, 48_000)
        val precise = process(input, p, 256)
        var squaredError = 0.0; var maximum = 0.0
        for (i in precise.indices) { val error = abs(precise[i] - legacy[i]); maximum = max(maximum, error); squaredError += error * error }
        val rms = sqrt(squaredError / precise.size)
        // This 31-band fixture includes 20 Hz / Q 4.32 poles: the old Float recurrence and
        // coefficient rounding dominate the difference. Measured peak 4.25644e-4, RMS 1.45571e-4.
        // The independent transposed-form test below separately verifies the new Double recurrence;
        // non-EQ effects have a much tighter comparison and must not inherit this EQ allowance.
        assertTrue("peak difference $maximum", maximum < 5e-4)
        assertTrue("RMS difference $rms", rms < 1.8e-4)
    }

    @Test fun nonEqEffectsRemainWithinTheStrictLegacyFloatComparisonBound() {
        val random = Random(29491)
        val input = DoubleArray(12_000 * 2) { (random.nextInt(-8_192, 8_193) / 32_768f).toDouble() }
        val p = richParameters().copy(graphicFreqs = floatArrayOf(24_000f), parametric = emptyList())
        val legacy = LegacyFloatEffectsReference.process(input, p, 48_000)
        val precise = process(input, p, 256)
        val maximum = precise.indices.maxOf { abs(precise[it] - legacy[it]) }
        val rms = sqrt(precise.indices.sumOf { (precise[it] - legacy[it]).pow(2) } / precise.size)
        assertTrue("non-EQ peak difference $maximum", maximum < 2e-6)
        assertTrue("non-EQ RMS difference $rms", rms < 6e-7)
    }

    @Test fun lowFrequencyBankAgreesWithIndependentTransposedDoubleRecurrence() {
        val random = Random(29491)
        val input = DoubleArray(12_000 * 2) { (random.nextInt(-8_192, 8_193) / 32_768f).toDouble() }
        val rich = richParameters()
        val p = neutral.copy(graphic = rich.graphic, graphicFreqs = rich.graphicFreqs,
            graphicQ = rich.graphicQ, parametric = rich.parametric)
        val coefficients = PrecisionDspCoeffBuilder.build(p, 48_000)
        val expected = input.copyOf()
        // Independently use direct-form II transposed, rather than the production direct-form I
        // x/y histories. The same transfer function must agree despite the different state layout.
        val z1 = DoubleArray(coefficients.nBiquads * 2)
        val z2 = DoubleArray(coefficients.nBiquads * 2)
        for (i in expected.indices) {
            var sample = expected[i]
            repeat(coefficients.nBiquads) { band ->
                val b = coefficients.filter(band)
                val state = band * 2 + i % 2
                val output = b.b0 * sample + z1[state]
                z1[state] = b.b1 * sample - b.a1 * output + z2[state]
                z2[state] = b.b2 * sample - b.a2 * output
                sample = output
            }
            expected[i] = sample
        }
        val precise = process(input, p, 256)
        val legacy = LegacyFloatEffectsReference.process(input, p, 48_000)
        val preciseMaximum = precise.indices.maxOf { abs(precise[it] - expected[it]) }
        val legacyMaximum = legacy.indices.maxOf { abs(legacy[it] - expected[it]) }
        assertTrue("Double recurrence maximum error $preciseMaximum", preciseMaximum < 1e-10)
        assertTrue("legacy Float error $legacyMaximum versus Double error $preciseMaximum",
            legacyMaximum > preciseMaximum * 1_000_000)
    }

    private fun process(samples: DoubleArray, p: DspParams, chunk: Int = minOf(samples.size / 2, 8_192)): DoubleArray {
        val kernel = PrecisionEffectsKernel(format)
        val coefficients = PrecisionDspCoeffBuilder.build(p, format.sampleRate)
        val block = AudioBlock(format, chunk)
        val result = DoubleArray(samples.size)
        var offset = 0
        while (offset < samples.size) {
            val count = minOf(samples.size - offset, chunk * 2)
            samples.copyInto(block.samples, 0, offset, offset + count)
            block.begin(count / 2)
            kernel.process(block, coefficients)
            block.samples.copyInto(result, offset, 0, count)
            offset += count
        }
        return result
    }

    private fun magnitudeDb(b: BiquadCoefficients, hz: Double): Double {
        val omega = 2.0 * PI * hz / 48_000
        val realN = b.b0 + b.b1 * cos(omega) + b.b2 * cos(2.0 * omega)
        val imagN = -b.b1 * sin(omega) - b.b2 * sin(2.0 * omega)
        val realD = 1.0 + b.a1 * cos(omega) + b.a2 * cos(2.0 * omega)
        val imagD = -b.a1 * sin(omega) - b.a2 * sin(2.0 * omega)
        return 10.0 * log10((realN * realN + imagN * imagN) / (realD * realD + imagD * imagD))
    }

    private fun richParameters() = DspParams(
        graphic = FloatArray(31) { if (it % 2 == 0) 1.5f else -1.5f },
        graphicFreqs = DspCoeffBuilder.GRAPHIC_LAYOUTS[2].freqs.copyOf(), graphicQ = 4.32f,
        parametric = listOf(DspBand(90f, 2f, 0.8f, 1), DspBand(6_000f, -2f, 0.7f, 2), DspBand(1_300f, 3f, 1.2f)),
        preampDb = -5f, balance = -0.15f, width = 1.3f, crossfeed = 0.3f, saturation = 0.2f,
        delayLeftMs = 0.7f, delayRightMs = 1.1f, trimLeftDb = -1f, trimRightDb = -2f,
        limiterEnabled = true, limiterCeilingDb = -4f, compEnabled = true, compThreshDb = -20f, compRatio = 3f,
    )
}
