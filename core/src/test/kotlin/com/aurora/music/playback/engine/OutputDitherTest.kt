package com.aurora.music.playback.engine

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class OutputDitherTest {
    private val stereo = AudioStreamFormat(48_000, ChannelLayout.STEREO)

    @Test fun triangularDitherHasExpectedSupportMeanAndVariance() {
        val dither = TpdfDither(733)
        val samples = DoubleArray(131072) { dither.nextLsb() }
        assertTrue(samples.all { it > -1.0 && it < 1.0 })
        assertEquals(0.0, samples.average(), .004)
        assertEquals(1.0 / 6.0, power(samples), .003)
    }

    @Test fun shapingMovesNoiseUpwardWithBoundedTotalPowerAndLowFrequencySlope() {
        val source = DoubleArray(65536)
        val format = AudioStreamFormat(48_000, ChannelLayout.MONO)
        val flat = render(source, format, TpdfDither(733))
        val shaped = render(source, format, NoiseShapedDither(733))
        assertEquals(0.0, shaped.average(), .0001)
        assertTrue(shaped.all { abs(it) <= 2.0 })
        assertEquals(.25, power(flat), .015)
        assertEquals(.5, power(shaped), .03)
        val flatSpectrum = spectrum(flat)
        val shapedSpectrum = spectrum(shaped)
        assertTrue(band(shapedSpectrum, 256, 1024) < band(flatSpectrum, 256, 1024) * .025)
        assertTrue(band(shapedSpectrum, 18000, 30000) > band(flatSpectrum, 18000, 30000) * 2.5)
        val octaveRatio = band(shapedSpectrum, 1024, 2048) / band(shapedSpectrum, 512, 1024)
        assertTrue("low-frequency power ratio $octaveRatio", octaveRatio in 3.2..4.8)
    }

    @Test fun lowLevelSignalsKeepTheirMeanWithoutSignalDependentNoisePower() {
        for (level in listOf(-.9, -.5, -.1, 0.0, .1, .5, .9)) {
            val source = DoubleArray(65536) { level / 32768.0 }
            val output = render(source, AudioStreamFormat(48_000, ChannelLayout.MONO), NoiseShapedDither(71))
            val error = DoubleArray(output.size) { output[it] - level }
            assertEquals("mean at $level LSB", 0.0, error.average(), .0001)
            assertEquals("noise at $level LSB", .5, power(error), .03)
            assertTrue(error.all { abs(it) <= 3.0 })
        }
    }

    @Test fun channelsHaveIndependentNoiseAndFeedbackState() {
        val source = DoubleArray(65536 * 2)
        val baseline = render(source, stereo, NoiseShapedDither(12))
        val changed = source.copyOf().apply {
            for (frame in 0 until size / 2) this[frame * 2] = when (frame % 4) {
                0 -> Double.NaN
                1 -> 400.0
                2 -> -400.0
                else -> .001 * sin(frame.toDouble())
            }
        }
        val output = render(changed, stereo, NoiseShapedDither(12))
        var cross = 0.0
        for (frame in 0 until source.size / 2) {
            assertEquals(baseline[frame * 2 + 1], output[frame * 2 + 1], 0.0)
            cross += baseline[frame * 2] * baseline[frame * 2 + 1]
        }
        assertTrue(abs(cross / (source.size / 2)) < .015)
        val mono = render(DoubleArray(source.size / 2), AudioStreamFormat(48_000, ChannelLayout.MONO), NoiseShapedDither(12))
        mono.indices.forEach { assertEquals(mono[it], baseline[it * 2], 0.0) }
    }

    @Test fun clippingAndMalformedSamplesCannotWindUpTheFeedback() {
        val source = DoubleArray(4096 * 2) { index -> when (index % 8) {
            0 -> Double.NaN
            1 -> Double.POSITIVE_INFINITY
            2 -> Double.NEGATIVE_INFINITY
            3 -> Double.MAX_VALUE
            4 -> -Double.MAX_VALUE
            5 -> 1.0
            6 -> -1.0
            else -> 0.0
        } }
        val dither = NoiseShapedDither(31)
        val result = render(source, stereo, dither)
        result.indices.forEach { index ->
            assertTrue(result[index].isFinite() && result[index] in -32768.0..32767.0)
            when (index % 8) {
                0, 1, 2 -> assertEquals(0.0, result[index], 0.0)
                3, 5 -> assertEquals(32767.0, result[index], 0.0)
                4 -> assertEquals(-32768.0, result[index], 0.0)
            }
        }
        val recovered = render(DoubleArray(32768), stereo, dither)
        assertTrue(recovered.all { abs(it) <= 2.0 })
        assertEquals(0.0, recovered.average(), .0002)
    }

    @Test fun resetModeAndFormatChangesDiscardOldErrors() {
        val source = DoubleArray(4096) { .3 / 32768.0 }
        val dither = NoiseShapedDither(15)
        val first = render(source, stereo, dither)
        render(DoubleArray(4096) { .12345 }, stereo, dither)
        dither.reset()
        assertArrayEquals(first, render(source, stereo, dither), 0.0)
        val changedRate = AudioStreamFormat(96_000, ChannelLayout.STEREO)
        assertArrayEquals(render(source, changedRate, NoiseShapedDither(15)), render(source, changedRate, dither), 0.0)
        assertArrayEquals(render(source, changedRate, NoiseShapedDither(15), encoding = PcmEncoding.SIGNED_24_LE),
            render(source, changedRate, dither, encoding = PcmEncoding.SIGNED_24_LE), 0.0)
        val modes = OutputDither()
        val initial = render(source, stereo, requireNotNull(modes.select(OutputDitherMode.NOISE_SHAPED)))
        assertNull(modes.select(OutputDitherMode.OFF))
        assertArrayEquals(initial, render(source, stereo, requireNotNull(modes.select(OutputDitherMode.NOISE_SHAPED))), 0.0)
    }

    @Test fun everyIntegerWidthUsesItsOwnLsbAndBlockSplitsAreTransparent() {
        for (encoding in listOf(PcmEncoding.SIGNED_16_LE, PcmEncoding.SIGNED_24_LE, PcmEncoding.SIGNED_32_LE)) {
            val scale = (1L shl (encoding.integerBits - 1)).toDouble()
            val source = DoubleArray(16384) { .13 * sin(it * .173) + .3 / scale }
            val full = render(source, stereo, NoiseShapedDither(79), encoding = encoding)
            val split = render(source, stereo, NoiseShapedDither(79), chunk = 17, encoding = encoding)
            assertArrayEquals(full, split, 0.0)
            assertTrue(full.indices.all { abs(full[it] - source[it] * scale) <= 3.000001 })
        }
    }

    @Test fun lowRatesFallBackToTpdfAndFloatOutputDoesNotDither() {
        for (rate in listOf(8000, 22050, 32000)) {
            val format = AudioStreamFormat(rate, ChannelLayout.STEREO)
            val source = DoubleArray(4096) { .25 / 32768.0 }
            assertArrayEquals(render(source, format, TpdfDither(17)), render(source, format, NoiseShapedDither(17)), 0.0)
        }
        val block = AudioBlock(stereo, 16).apply {
            begin(16)
            samples.indices.forEach { samples[it] = it / 31.0 - .5 }
        }
        for (encoding in listOf(PcmEncoding.FLOAT_32_LE, PcmEncoding.FLOAT_64_LE)) {
            val dry = ByteBuffer.allocate(block.sampleCount * encoding.bytesPerSample)
            val shaped = ByteBuffer.allocate(dry.capacity())
            PcmBoundary.encode(block, encoding, dry)
            PcmBoundary.encode(block, encoding, shaped, NoiseShapedDither())
            assertArrayEquals(dry.array(), shaped.array())
        }
    }

    private fun render(source: DoubleArray, format: AudioStreamFormat, dither: PcmDither,
        chunk: Int = 8192, encoding: PcmEncoding = PcmEncoding.SIGNED_16_LE): DoubleArray {
        val output = DoubleArray(source.size)
        val block = AudioBlock(format, chunk)
        val bytes = ByteBuffer.allocate(chunk * format.channelCount * encoding.bytesPerSample)
        val scale = (1L shl (encoding.integerBits - 1)).toDouble()
        var offset = 0
        while (offset < source.size) {
            val count = minOf(chunk, (source.size - offset) / format.channelCount)
            block.begin(count)
            source.copyInto(block.samples, 0, offset, offset + block.sampleCount)
            bytes.clear()
            PcmBoundary.encode(block, encoding, bytes, dither)
            bytes.flip()
            PcmBoundary.decode(bytes, encoding, block)
            for (index in 0 until block.sampleCount) output[offset + index] = block.samples[index] * scale
            offset += block.sampleCount
        }
        return output
    }

    private fun power(values: DoubleArray) = values.sumOf { it * it } / values.size

    private fun spectrum(samples: DoubleArray): DoubleArray {
        val real = DoubleArray(samples.size) { samples[it] * (.5 - .5 * cos(2 * PI * it / samples.size)) }
        val imaginary = DoubleArray(samples.size)
        PrecisionFft(samples.size).transform(real, imaginary, false)
        return DoubleArray(samples.size / 2) { real[it] * real[it] + imaginary[it] * imaginary[it] }
    }

    private fun band(spectrum: DoubleArray, start: Int, end: Int) = (start until end).sumOf { spectrum[it] } / (end - start)
}
