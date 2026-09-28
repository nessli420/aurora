package com.aurora.music.playback.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Binary64 radix-2 FFT. Tables and work buffers are created before processing begins. */
class PrecisionFft(val size: Int) {
    init { require(size in 2..1_048_576 && size and (size - 1) == 0) }
    private val realTwiddle = DoubleArray(size / 2) { cos(-2.0 * PI * it / size) }
    private val imaginaryTwiddle = DoubleArray(size / 2) { sin(-2.0 * PI * it / size) }
    private val reversed = IntArray(size) { Integer.reverse(it) ushr (32 - Integer.numberOfTrailingZeros(size)) }

    fun transform(real: DoubleArray, imaginary: DoubleArray, inverse: Boolean) {
        require(real.size >= size && imaginary.size >= size && real !== imaginary)
        var i = 0
        while (i < size) {
            val j = reversed[i]
            if (j > i) {
                val r = real[i]; real[i] = real[j]; real[j] = r
                val v = imaginary[i]; imaginary[i] = imaginary[j]; imaginary[j] = v
            }
            i++
        }
        var length = 2
        while (length <= size) {
            val half = length / 2
            val step = size / length
            var start = 0
            while (start < size) {
                var k = 0
                while (k < half) {
                    val even = start + k; val odd = even + half; val table = k * step
                    val c = realTwiddle[table]
                    val s = if (inverse) -imaginaryTwiddle[table] else imaginaryTwiddle[table]
                    val r = real[odd] * c - imaginary[odd] * s
                    val v = real[odd] * s + imaginary[odd] * c
                    val er = real[even]; val ei = imaginary[even]
                    real[even] = er + r; imaginary[even] = ei + v
                    real[odd] = er - r; imaginary[odd] = ei - v
                    k++
                }
                start += length
            }
            length *= 2
        }
        if (inverse) {
            val scale = 1.0 / size
            i = 0
            while (i < size) { real[i] *= scale; imaginary[i] *= scale; i++ }
        }
    }
}

enum class ConvolutionTailMode { TRUNCATE_AT_INPUT, FULL }

/**
 * Stereo partitioned overlap-save convolution. Preparation owns all allocations; processing has
 * one owner, no locks, no allocation and at most one block of pending output. [queueInput] may
 * consume fewer frames than supplied: callers must drain [readOutput] and retry the remainder.
 * No leading silence is inserted. Block collection adds up to blockSize-1 frames of buffering.
 * Truncated EOS returns exactly the input frame count. FULL returns input + max(IR lengths)-1
 * frames for nonempty input. Neither mode exposes zero-padding beyond that explicit contract.
 */
class PrecisionConvolver(
    impulseLeft: DoubleArray,
    impulseRight: DoubleArray,
    val blockSize: Int = 1024,
    impulseLeftToRight: DoubleArray? = null,
    impulseRightToLeft: DoubleArray? = null,
) {
    init {
        require(blockSize in 16..4096 && blockSize and (blockSize - 1) == 0)
        require(impulseLeft.isNotEmpty() && impulseRight.isNotEmpty())
        require(impulseLeft.size <= MAX_IR_FRAMES && impulseRight.size <= MAX_IR_FRAMES)
        require(impulseLeft.all { it.isFinite() } && impulseRight.all { it.isFinite() })
        for (cross in listOfNotNull(impulseLeftToRight, impulseRightToLeft)) {
            require(cross.isNotEmpty() && cross.size <= MAX_IR_FRAMES && cross.all(Double::isFinite))
        }
    }
    val tailFrames: Int = maxOf(impulseLeft.size, impulseRight.size,
        impulseLeftToRight?.size ?: 0, impulseRightToLeft?.size ?: 0) - 1
    private val left = Channel(impulseLeft, blockSize)
    private val right = Channel(impulseRight, blockSize)
    private val leftToRight = impulseLeftToRight?.let { Channel(it, blockSize) }
    private val rightToLeft = impulseRightToLeft?.let { Channel(it, blockSize) }
    private val crossOutput = DoubleArray(blockSize)
    private val inputLeft = DoubleArray(blockSize)
    private val inputRight = DoubleArray(blockSize)
    private val outputLeft = DoubleArray(blockSize)
    private val outputRight = DoubleArray(blockSize)
    private var inputCount = 0
    private var outputCount = 0
    private var outputPosition = 0
    private var inputFrames = 0L
    private var producedFrames = 0L
    private var endTarget = -1L
    val bufferedInputFrames: Int get() = inputCount
    val availableOutputFrames: Int get() = outputCount - outputPosition
    val isEnded: Boolean get() = endTarget >= 0 && producedFrames >= endTarget && availableOutputFrames == 0

    fun queueInput(input: DoubleArray, offsetFrames: Int, frames: Int): Int {
        require(offsetFrames >= 0 && frames >= 0 && (offsetFrames.toLong() + frames) * 2 <= input.size)
        check(endTarget < 0) { "Input after EOS" }
        if (availableOutputFrames > 0) return 0
        val count = minOf(frames, blockSize - inputCount)
        var src = offsetFrames * 2
        var i = 0
        while (i < count) {
            val l = input[src++]; val r = input[src++]
            inputLeft[inputCount] = if (l.isFinite()) l else 0.0
            inputRight[inputCount++] = if (r.isFinite()) r else 0.0
            i++
        }
        inputFrames += count
        if (inputCount == blockSize) produce(blockSize)
        return count
    }

    fun queueEndOfInput(mode: ConvolutionTailMode = ConvolutionTailMode.TRUNCATE_AT_INPUT) {
        if (endTarget >= 0) return
        endTarget = inputFrames + if (mode == ConvolutionTailMode.FULL && inputFrames > 0) tailFrames else 0
    }

    fun readOutput(output: DoubleArray, offsetFrames: Int, maxFrames: Int): Int {
        require(offsetFrames >= 0 && maxFrames >= 0 && (offsetFrames.toLong() + maxFrames) * 2 <= output.size)
        if (maxFrames == 0) return 0
        if (availableOutputFrames == 0 && endTarget > producedFrames) {
            java.util.Arrays.fill(inputLeft, inputCount, blockSize, 0.0)
            java.util.Arrays.fill(inputRight, inputCount, blockSize, 0.0)
            produce(minOf(blockSize.toLong(), endTarget - producedFrames).toInt())
        }
        val count = minOf(maxFrames, availableOutputFrames)
        var dst = offsetFrames * 2
        var i = 0
        while (i < count) {
            output[dst++] = outputLeft[outputPosition]
            output[dst++] = outputRight[outputPosition++]
            i++
        }
        return count
    }

    fun reset() {
        left.reset(); right.reset(); leftToRight?.reset(); rightToLeft?.reset()
        inputLeft.fill(0.0); inputRight.fill(0.0)
        outputLeft.fill(0.0); outputRight.fill(0.0)
        inputCount = 0; outputCount = 0; outputPosition = 0
        inputFrames = 0; producedFrames = 0; endTarget = -1
    }

    fun copyStateFrom(previous: PrecisionConvolver): Boolean {
        if (blockSize != previous.blockSize || tailFrames != previous.tailFrames ||
            inputCount != 0 || previous.inputCount != 0 || availableOutputFrames != 0 || previous.availableOutputFrames != 0 ||
            endTarget >= 0 || previous.endTarget >= 0 || !left.matches(previous.left) || !right.matches(previous.right) ||
            (leftToRight == null) != (previous.leftToRight == null) || (rightToLeft == null) != (previous.rightToLeft == null)) return false
        if (leftToRight != null && !leftToRight.matches(previous.leftToRight!!)) return false
        if (rightToLeft != null && !rightToLeft.matches(previous.rightToLeft!!)) return false
        left.copyStateFrom(previous.left); right.copyStateFrom(previous.right)
        leftToRight?.copyStateFrom(previous.leftToRight!!)
        rightToLeft?.copyStateFrom(previous.rightToLeft!!)
        inputFrames = previous.inputFrames; producedFrames = previous.producedFrames
        outputCount = 0; outputPosition = 0
        return true
    }

    private fun produce(validFrames: Int) {
        left.process(inputLeft, outputLeft); right.process(inputRight, outputRight)
        leftToRight?.let { channel ->
            channel.process(inputLeft, crossOutput)
            for (i in 0 until blockSize) outputRight[i] += crossOutput[i]
        }
        rightToLeft?.let { channel ->
            channel.process(inputRight, crossOutput)
            for (i in 0 until blockSize) outputLeft[i] += crossOutput[i]
        }
        inputCount = 0; outputPosition = 0; outputCount = validFrames
        producedFrames += validFrames
    }

    private class Channel(impulse: DoubleArray, private val block: Int) {
        private val size = block * 2
        private val fft = PrecisionFft(size)
        private val partitions = (impulse.size + block - 1) / block
        private val hr = Array(partitions) { DoubleArray(size) }
        private val hi = Array(partitions) { DoubleArray(size) }
        private val historyReal = Array(partitions) { DoubleArray(size) }
        private val historyImaginary = Array(partitions) { DoubleArray(size) }
        private val historyEpoch = IntArray(partitions)
        private var epoch = 1
        private val previous = DoubleArray(block)
        private val xr = DoubleArray(size); private val xi = DoubleArray(size)
        private val yr = DoubleArray(size); private val yi = DoubleArray(size)
        private var position = 0
        init {
            var k = 0
            while (k < partitions) {
                val count = minOf(block, impulse.size - k * block)
                System.arraycopy(impulse, k * block, hr[k], 0, count)
                fft.transform(hr[k], hi[k], false)
                k++
            }
        }
        fun process(input: DoubleArray, output: DoubleArray) {
            System.arraycopy(previous, 0, xr, 0, block)
            System.arraycopy(input, 0, xr, block, block)
            xi.fill(0.0)
            fft.transform(xr, xi, false)
            System.arraycopy(xr, 0, historyReal[position], 0, size)
            System.arraycopy(xi, 0, historyImaginary[position], 0, size)
            historyEpoch[position] = epoch
            yr.fill(0.0); yi.fill(0.0)
            var k = 0
            while (k < partitions) {
                val history = (position - k + partitions) % partitions
                if (historyEpoch[history] != epoch) { k++; continue }
                val ar = hr[k]; val ai = hi[k]; val br = historyReal[history]; val bi = historyImaginary[history]
                var i = 0
                while (i < size) {
                    yr[i] += ar[i] * br[i] - ai[i] * bi[i]
                    yi[i] += ar[i] * bi[i] + ai[i] * br[i]
                    i++
                }
                k++
            }
            fft.transform(yr, yi, true)
            System.arraycopy(yr, block, output, 0, block)
            System.arraycopy(input, 0, previous, 0, block)
            position = (position + 1) % partitions
        }
        fun reset() {
            // Invalidating spectra avoids clearing tens of MiB when a seek or live bypass resets
            // history on the audio thread. No stale partition is read until rewritten this epoch.
            if (epoch == Int.MAX_VALUE) { historyEpoch.fill(0); epoch = 1 } else epoch++
            previous.fill(0.0); xr.fill(0.0); xi.fill(0.0); yr.fill(0.0); yi.fill(0.0)
            position = 0
        }

        fun matches(other: Channel): Boolean {
            if (block != other.block || partitions != other.partitions) return false
            for (i in 0 until partitions) if (!hr[i].contentEquals(other.hr[i]) || !hi[i].contentEquals(other.hi[i])) return false
            return true
        }

        fun copyStateFrom(other: Channel) {
            for (i in 0 until partitions) if (other.historyEpoch[i] == other.epoch) {
                other.historyReal[i].copyInto(historyReal[i])
                other.historyImaginary[i].copyInto(historyImaginary[i])
            }
            other.historyEpoch.copyInto(historyEpoch)
            other.previous.copyInto(previous)
            epoch = other.epoch
            position = other.position
        }
    }

    companion object {
        /** Approximately 32 MiB for the two channels' uniform-partition FFT spectra/history. */
        const val MAX_IR_FRAMES = 262_144
    }
}
