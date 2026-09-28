package com.aurora.music.playback.engine

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class PrecisionConvolverTest {
    @Test fun trueStereoRoutesAllFourPathsAndDrainsCrossChannelTail() {
        val left = doubleArrayOf(.8, .2)
        val right = doubleArrayOf(.4, -.1)
        val lr = doubleArrayOf(0.0, .3, -.2, .1)
        val rl = doubleArrayOf(.15, 0.0, .25)
        val input = DoubleArray(71 * 2) { sinForTest(it) }
        val actual = stream(PrecisionConvolver(left, right, 16, lr, rl), input, 7, ConvolutionTailMode.FULL)
        val expected = DoubleArray((71 + 3) * 2)
        for (frame in 0 until 71) {
            for (tap in left.indices) expected[(frame + tap) * 2] += input[frame * 2] * left[tap]
            for (tap in right.indices) expected[(frame + tap) * 2 + 1] += input[frame * 2 + 1] * right[tap]
            for (tap in lr.indices) expected[(frame + tap) * 2 + 1] += input[frame * 2] * lr[tap]
            for (tap in rl.indices) expected[(frame + tap) * 2] += input[frame * 2 + 1] * rl[tap]
        }
        assertArrayEquals(expected, actual, 1e-14)
    }

    private fun sinForTest(i: Int) = kotlin.math.sin(i * .123) * .3

    @Test fun stereoAsymmetricImpulseMatchesDirectConvolutionAcrossArbitrarySplits() {
        val random = Random(490)
        val input = DoubleArray(239 * 2) { random.nextDouble(-0.5, 0.5) }
        val left = DoubleArray(87) { random.nextDouble(-0.15, 0.15) }
        val right = DoubleArray(123) { random.nextDouble(-0.15, 0.15) }
        val expected = direct(input, left, right, true)
        for (split in intArrayOf(1, 7, 16, 17, 63, 239)) {
            val actual = stream(PrecisionConvolver(left, right, 16), input, split, ConvolutionTailMode.FULL)
            assertArrayEquals("split=$split", expected, actual, 3e-14)
        }
    }

    @Test fun truncatedPartialEosReturnsExactlyInputFramesWithoutPaddedSilence() {
        val input = DoubleArray(71 * 2) { if (it % 2 == 0) 0.25 else -0.125 }
        val left = doubleArrayOf(0.7, 0.2, 0.1)
        val right = doubleArrayOf(0.4, -0.2)
        val actual = stream(PrecisionConvolver(left, right, 32), input, 13)
        assertEquals(input.size, actual.size)
        assertArrayEquals(direct(input, left, right, false), actual, 1e-15)
    }

    @Test fun fullTailDrainsWhenInputEndedOnAnExactBlockBoundary() {
        val input = DoubleArray(32 * 2).apply { this[0] = 1.0; this[1] = 1.0 }
        val left = DoubleArray(121).apply { this[0] = 0.25; this[120] = 0.75 }
        val right = DoubleArray(83).apply { this[82] = -0.5 }
        val actual = stream(PrecisionConvolver(left, right, 16), input, 32, ConvolutionTailMode.FULL)
        assertEquals((32 + 120) * 2, actual.size)
        assertArrayEquals(direct(input, left, right, true), actual, 1e-15)
    }

    @Test fun veryLargeInputCannotOverwritePendingFrames() {
        val random = Random(692)
        val input = DoubleArray(70_003 * 2) { random.nextDouble(-0.5, 0.5) }
        val left = doubleArrayOf(1.0, 0.125)
        val right = doubleArrayOf(0.5, -0.25, 0.125)
        val result = stream(PrecisionConvolver(left, right, 128), input, 70_003)
        assertArrayEquals(direct(input, left, right, false), result, 2e-14)
    }

    @Test fun outputBackpressureLeavesUnconsumedInputForTheCaller() {
        val kernel = PrecisionConvolver(doubleArrayOf(1.0), doubleArrayOf(1.0), 16)
        val input = DoubleArray(80) { it / 80.0 }
        assertEquals(16, kernel.queueInput(input, 0, 40))
        assertEquals(0, kernel.queueInput(input, 16, 24))
        val output = DoubleArray(80)
        assertEquals(3, kernel.readOutput(output, 0, 3))
        assertEquals(0, kernel.queueInput(input, 16, 24))
        assertEquals(13, kernel.readOutput(output, 3, 37))
        assertEquals(16, kernel.queueInput(input, 16, 24))
        assertArrayEquals(input.copyOfRange(0, 32), output.copyOfRange(0, 32), 1e-15)
    }

    @Test fun resetClearsEveryPartitionHistoryAndAnIncompleteBlock() {
        val impulse = DoubleArray(99).apply { this[0] = 1.0; this[17] = 0.8; this[98] = 0.4 }
        val kernel = PrecisionConvolver(impulse, impulse, 16)
        stream(kernel, DoubleArray(137 * 2) { 0.25 }, 19, ConvolutionTailMode.FULL)
        kernel.reset()
        assertEquals(7, kernel.queueInput(DoubleArray(14) { 1.0 }, 0, 7))
        kernel.reset()
        val silence = stream(kernel, DoubleArray(173 * 2), 11, ConvolutionTailMode.FULL)
        assertTrue(silence.all { it == 0.0 })
    }

    @Test fun stateMigrationPreservesEveryMatrixHistoryAndTheCompleteTail() {
        val left = DoubleArray(87) { kotlin.math.sin(it * .17) * .03 }
        val right = DoubleArray(123) { kotlin.math.cos(it * .21) * .04 }
        val lr = DoubleArray(137) { kotlin.math.sin(it * .29) * -.02 }
        val rl = DoubleArray(101) { kotlin.math.cos(it * .13) * .01 }
        fun kernel() = PrecisionConvolver(left, right, 16, lr, rl)
        fun warm(kernel: PrecisionConvolver, frames: Int) {
            val input = DoubleArray(frames * 2) { sinForTest(it) }
            val output = DoubleArray(32)
            for (offset in 0 until frames step 16) {
                assertEquals(16, kernel.queueInput(input, offset, 16))
                assertEquals(16, kernel.readOutput(output, 0, 16))
            }
        }
        val previous = kernel()
        warm(previous, 160); previous.reset(); previous.reset(); warm(previous, 80)
        val replacement = kernel()
        warm(replacement, 192); replacement.reset()
        assertTrue(replacement.copyStateFrom(previous))
        val suffix = DoubleArray(67 * 2) { kotlin.math.cos(it * .073) * .4 }
        val expected = stream(previous, suffix, 7, ConvolutionTailMode.FULL)
        val actual = stream(replacement, suffix, 11, ConvolutionTailMode.FULL)
        assertEquals((67 + 136) * 2, actual.size)
        assertArrayEquals(expected, actual, 0.0)
    }

    @Test fun stateMigrationRejectsPendingFramesEosAndDifferentCoefficients() {
        val previous = PrecisionConvolver(doubleArrayOf(1.0, .25), doubleArrayOf(.5), 16)
        val replacement = PrecisionConvolver(doubleArrayOf(1.0, .25), doubleArrayOf(.5), 16)
        previous.queueInput(DoubleArray(32) { .25 }, 0, 16)
        assertFalse(replacement.copyStateFrom(previous))
        previous.readOutput(DoubleArray(32), 0, 16)
        assertFalse(PrecisionConvolver(doubleArrayOf(1.0, .5), doubleArrayOf(.5), 16).copyStateFrom(previous))
        assertFalse(PrecisionConvolver(doubleArrayOf(1.0, .25), doubleArrayOf(.5), 32).copyStateFrom(previous))
        assertTrue(replacement.copyStateFrom(previous))
        replacement.queueInput(DoubleArray(2), 0, 1)
        assertFalse(replacement.copyStateFrom(previous))
        replacement.reset()
        previous.queueInput(DoubleArray(2), 0, 1)
        assertFalse(replacement.copyStateFrom(previous))
        previous.queueEndOfInput(ConvolutionTailMode.FULL)
        assertFalse(replacement.copyStateFrom(previous))
    }

    @Test fun binary64RetainsQuietSamplesAndSubFloat32CoefficientDetail() {
        val coefficient = 1.0 + 1.0 / (1L shl 31)
        val input = DoubleArray(95 * 2) { if (it % 2 == 0) 1.0 / (1L shl 31) else -1.0 / (1L shl 30) }
        val result = stream(PrecisionConvolver(doubleArrayOf(coefficient), doubleArrayOf(coefficient), 32), input, 3)
        for (i in input.indices) assertEquals(input[i] * coefficient, result[i], 5e-24)
        assertTrue(abs(result[0] - input[0]) > 0)
    }

    @Test fun compiledImpulseOwnsItsCoefficientsAndRejectsInvalidLimits() {
        val impulse = doubleArrayOf(1.0)
        val kernel = PrecisionConvolver(impulse, impulse, 16)
        impulse[0] = 0.0
        val output = stream(kernel, DoubleArray(22 * 2) { 0.25 }, 22)
        assertTrue(output.all { abs(it - 0.25) < 1e-15 })
        assertThrows(IllegalArgumentException::class.java) { PrecisionConvolver(doubleArrayOf(), doubleArrayOf(1.0)) }
        assertThrows(IllegalArgumentException::class.java) { PrecisionConvolver(doubleArrayOf(Double.NaN), doubleArrayOf(1.0)) }
        assertThrows(IllegalArgumentException::class.java) { PrecisionConvolver(doubleArrayOf(1.0), doubleArrayOf(1.0), 17) }
        assertThrows(IllegalArgumentException::class.java) {
            PrecisionConvolver(DoubleArray(PrecisionConvolver.MAX_IR_FRAMES + 1), doubleArrayOf(1.0))
        }
    }

    @Test fun emptyStreamHasNoTailAndNonFiniteInputBecomesSilence() {
        val kernel = PrecisionConvolver(doubleArrayOf(1.0, 0.5), doubleArrayOf(1.0, 0.5), 16)
        assertTrue(stream(kernel, doubleArrayOf(), 1, ConvolutionTailMode.FULL).isEmpty())
        assertTrue(kernel.isEnded)
        kernel.reset()
        val output = stream(kernel, doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY), 1)
        assertArrayEquals(doubleArrayOf(0.0, 0.0), output, 0.0)
        assertThrows(IllegalStateException::class.java) { kernel.queueInput(DoubleArray(2), 0, 1) }
    }

    @Test fun fftRoundTripRemainsBinary64AndRejectsAliasedBuffers() {
        val random = Random(42)
        val input = DoubleArray(128) { random.nextDouble(-1.0, 1.0) }
        val real = input.copyOf(); val imaginary = DoubleArray(128)
        val fft = PrecisionFft(128)
        fft.transform(real, imaginary, false); fft.transform(real, imaginary, true)
        assertArrayEquals(input, real, 1e-14)
        assertTrue(imaginary.all { abs(it) < 1e-14 })
        assertThrows(IllegalArgumentException::class.java) { fft.transform(real, real, false) }
    }

    private fun stream(kernel: PrecisionConvolver, input: DoubleArray, split: Int,
        tail: ConvolutionTailMode = ConvolutionTailMode.TRUNCATE_AT_INPUT): DoubleArray {
        val result = ArrayList<Double>()
        val scratch = DoubleArray(kernel.blockSize * 2)
        fun drain() {
            while (true) {
                val count = kernel.readOutput(scratch, 0, kernel.blockSize)
                if (count == 0) return
                repeat(count * 2) { result.add(scratch[it]) }
            }
        }
        var offset = 0
        while (offset < input.size / 2) {
            val end = minOf(offset + split, input.size / 2)
            while (offset < end) {
                val count = kernel.queueInput(input, offset, end - offset)
                offset += count
                drain()
                check(count > 0)
            }
        }
        kernel.queueEndOfInput(tail)
        drain()
        assertTrue(kernel.isEnded)
        return result.toDoubleArray()
    }

    private fun direct(input: DoubleArray, left: DoubleArray, right: DoubleArray, tail: Boolean): DoubleArray {
        val frames = input.size / 2
        val outputFrames = frames + if (tail && frames > 0) maxOf(left.size, right.size) - 1 else 0
        return DoubleArray(outputFrames * 2) { sample ->
            val channel = sample % 2
            val index = sample / 2
            val impulse = if (channel == 0) left else right
            var sum = 0.0
            for (tap in impulse.indices) {
                val source = index - tap
                if (source in 0 until frames) sum += input[source * 2 + channel] * impulse[tap]
            }
            sum
        }
    }
}
