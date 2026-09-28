package com.aurora.music.playback.dsd

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import java.util.concurrent.ConcurrentHashMap

class DsdPcmDecoder(val format: DsdFormat) {
    private val taps = format.filterTaps
    private val groups = taps / 8
    private val kernel = kernels.computeIfAbsent(format.bitRate) { Kernel(it, taps) }
    internal val coefficients: DoubleArray get() = kernel.coefficients
    private val lookup = kernel.lookup
    private val history = Array(format.channels) { IntArray(groups) }
    private var fullBytes = 0
    private var position = 0
    private var sourceByte = 0L
    var outputFrame: Long = 0; private set
    val isEnded: Boolean get() = outputFrame >= format.pcmFrames

    fun reset(firstSourceByte: Long = 0, firstOutputFrame: Long = 0) {
        require(firstOutputFrame in 0..format.pcmFrames && firstSourceByte in 0..format.bytesPerChannel)
        require(firstSourceByte <= format.prerollByte(firstOutputFrame)) { "DSD seek needs filter preroll." }
        history.forEach { it.fill(0) }
        position = 0; fullBytes = 0; sourceByte = firstSourceByte; outputFrame = firstOutputFrame
    }

    fun push(channelBytes: IntArray, validBits: Int, output: FloatArray, sampleOffset: Int): Boolean {
        require(channelBytes.size >= format.channels && validBits in 0..8 && sampleOffset >= 0 && sampleOffset <= output.size - format.channels)
        check(!isEnded)
        if (history[0][position] ushr 8 == 8) fullBytes--
        if (validBits == 8) fullBytes++
        for (channel in 0 until format.channels) history[channel][position] = (validBits shl 8) or (channelBytes[channel] and 255)
        sourceByte++
        val ready = sourceByte * 8 - 1 >= outputFrame * format.decimation + taps / 2 - 1
        if (ready) {
            if (fullBytes == groups) {
                val left = history[0]
                val right = history.getOrNull(1)
                var leftValue = 0.0
                var rightValue = 0.0
                var ring = position
                if (right != null) {
                    for (group in 0 until groups) {
                        val base = group shl 8
                        leftValue += lookup[base or (left[ring] and 255)]
                        rightValue += lookup[base or (right[ring] and 255)]
                        ring = (ring - 1) and (groups - 1)
                    }
                    output[sampleOffset + 1] = rightValue.toFloat()
                } else for (group in 0 until groups) {
                    leftValue += lookup[(group shl 8) or (left[ring] and 255)]
                    ring = (ring - 1) and (groups - 1)
                }
                output[sampleOffset] = leftValue.toFloat()
            } else for (channel in 0 until format.channels) {
                var value = 0.0
                var ring = position
                for (group in 0 until groups) {
                    val packed = history[channel][ring]
                    val count = packed ushr 8
                    if (count == 8) value += lookup[(group shl 8) or (packed and 255)]
                    else if (count > 0) for (bit in 8 - count..7) {
                        value += coefficients[group * 8 + bit] * if (packed and (1 shl bit) == 0) -1.0 else 1.0
                    }
                    if (--ring < 0) ring = groups - 1
                }
                output[sampleOffset + channel] = value.toFloat()
            }
            outputFrame++
        }
        if (++position == groups) position = 0
        return ready
    }

    private class Kernel(bitRate: Int, taps: Int) {
        val coefficients = DoubleArray(taps) { i ->
            val x = i - (taps - 1) / 2.0
            val cutoff = 48_000.0 / bitRate
            val window = .42 - .5 * cos(2 * PI * i / (taps - 1)) + .08 * cos(4 * PI * i / (taps - 1))
            sin(2 * PI * cutoff * x) / (PI * x) * window
        }.also { values -> val sum = values.sum(); for (i in values.indices) values[i] /= sum }
        val lookup = DoubleArray(taps / 8 * 256) { index ->
            val group = index ushr 8
            val value = index and 255
            var sum = 0.0
            for (bit in 0..7) sum += coefficients[group * 8 + bit] * if (value and (1 shl bit) == 0) -1.0 else 1.0
            sum
        }
    }

    companion object {
        private val kernels = ConcurrentHashMap<Int, Kernel>()
        internal fun coefficientsFor(format: DsdFormat): DoubleArray =
            kernels.computeIfAbsent(format.bitRate) { Kernel(it, format.filterTaps) }.coefficients
    }
}
