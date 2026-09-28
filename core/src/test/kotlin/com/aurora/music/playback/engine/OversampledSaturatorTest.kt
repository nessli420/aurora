package com.aurora.music.playback.engine

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class OversampledSaturatorTest {
    private val rate = 48_000
    private val format = AudioStreamFormat(rate, ChannelLayout.STEREO)

    @Test fun oversamplingReducesFoldedThirdHarmonicAtAllSupportedFactors() {
        val size = 49_024
        val input = DoubleArray(size) { .9 * sin(2 * PI * 9375 * it / rate) }
        fun amplitude(samples: DoubleArray, frequency: Int): Double {
            var real = 0.0; var imaginary = 0.0
            for (i in 1024 until samples.size) { real += samples[i] * cos(2 * PI * frequency * i / rate); imaginary += samples[i] * sin(2 * PI * frequency * i / rate) }
            return hypot(real, imaginary) * 2 / (samples.size - 1024)
        }
        val direct = DoubleArray(size) { val y = tanh(6 * input[it]) / 6; y + .2 * (y * y - .33) }
        val alias = amplitude(direct, 19_875)
        assertTrue(alias > .01)
        for (factor in listOf(2, 4, 8)) {
            val processor = OversampledSaturator(factor, 1.0)
            val output = process(processor, input)
            assertTrue("$factor times alias: ${amplitude(output, 19_875)} vs $alias", amplitude(output, 19_875) < alias / 10)
            assertTrue(amplitude(output, 9375) > .1)
        }
    }

    @Test fun neutralOversamplingHasDeclaredDelayAndResetClearsHistory() {
        for (factor in listOf(2, 4, 8)) {
            val processor = OversampledSaturator(factor, 0.0)
            val impulse = DoubleArray(512).apply { this[0] = 1.0 }
            val output = process(processor, impulse)
            assertEquals(processor.latencyFrames, output.indices.maxBy { abs(output[it]) })
            assertEquals(1.0, output.sum(), 1e-5)
            processor.reset()
            assertArrayEquals(DoubleArray(512), process(processor, DoubleArray(512)), 0.0)
        }
    }

    @Test fun stateMigrationKeepsBothFilterHistoriesAcrossEveryFactor() {
        fun stereo(processor: OversampledSaturator, input: DoubleArray): DoubleArray {
            val block = AudioBlock(format, input.size)
            block.begin(input.size)
            for (i in input.indices) { block.samples[i * 2] = input[i]; block.samples[i * 2 + 1] = input[i] * .3 }
            processor.process(block)
            return block.samples.copyOf(input.size * 2)
        }
        for (factor in listOf(2, 4, 8)) {
            val previous = OversampledSaturator(factor, .6)
            val replacement = OversampledSaturator(factor, .6)
            stereo(previous, DoubleArray(377) { .7 * sin(it * .23) })
            stereo(replacement, DoubleArray(83) { -.2 })
            assertFalse(replacement.copyStateFrom(OversampledSaturator(factor, .4)))
            assertFalse(replacement.copyStateFrom(OversampledSaturator(if (factor == 2) 4 else 2, .6)))
            assertTrue(replacement.copyStateFrom(previous))
            val suffix = DoubleArray(503) { .5 * cos(it * .17) }
            assertArrayEquals(stereo(previous, suffix), stereo(replacement, suffix), 0.0)
            assertArrayEquals(stereo(previous, DoubleArray(96)), stereo(replacement, DoubleArray(96)), 0.0)
        }
    }

    private fun process(processor: OversampledSaturator, input: DoubleArray): DoubleArray {
        val result = DoubleArray(input.size)
        val block = AudioBlock(format, 256)
        var offset = 0
        while (offset < input.size) {
            val count = minOf(256, input.size - offset)
            block.begin(count)
            for (i in 0 until count) { block.samples[i * 2] = input[offset + i]; block.samples[i * 2 + 1] = -input[offset + i] }
            processor.process(block)
            for (i in 0 until count) result[offset + i] = block.samples[i * 2]
            offset += count
        }
        return result
    }
}
