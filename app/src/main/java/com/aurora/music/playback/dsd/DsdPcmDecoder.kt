package com.aurora.music.playback.dsd

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class DsdPcmDecoder(val format: DsdFormat) {
    private val taps = format.filterTaps
    private val groups = taps / 8
    internal val coefficients = DoubleArray(taps) { i ->
        val x = i - (taps - 1) / 2.0
        val cutoff = 48_000.0 / format.bitRate
        val window = .42 - .5 * cos(2 * PI * i / (taps - 1)) + .08 * cos(4 * PI * i / (taps - 1))
        sin(2 * PI * cutoff * x) / (PI * x) * window
    }.also { values -> val sum = values.sum(); for (i in values.indices) values[i] /= sum }
    private val lookup = Array(groups) { group -> DoubleArray(256) { value ->
        var sum = 0.0
        for (bit in 0..7) sum += coefficients[group * 8 + bit] * if (value and (1 shl bit) == 0) -1.0 else 1.0
        sum
    } }
    private val history = Array(format.channels) { IntArray(groups) }
    private var position = 0
    private var sourceByte = 0L
    var outputFrame: Long = 0; private set
    val isEnded: Boolean get() = outputFrame >= format.pcmFrames

    fun reset(firstSourceByte: Long = 0, firstOutputFrame: Long = 0) {
        require(firstOutputFrame in 0..format.pcmFrames && firstSourceByte in 0..format.bytesPerChannel)
        require(firstSourceByte <= format.prerollByte(firstOutputFrame)) { "DSD seek needs filter preroll." }
        history.forEach { it.fill(0) }
        position = 0; sourceByte = firstSourceByte; outputFrame = firstOutputFrame
    }

    fun push(channelBytes: IntArray, validBits: Int, output: FloatArray, sampleOffset: Int): Boolean {
        require(channelBytes.size >= format.channels && validBits in 0..8 && sampleOffset >= 0 && sampleOffset + format.channels <= output.size)
        check(!isEnded)
        for (channel in 0 until format.channels) history[channel][position] = (validBits shl 8) or (channelBytes[channel] and 255)
        sourceByte++
        val ready = sourceByte * 8 - 1 >= outputFrame * format.decimation + taps / 2 - 1
        if (ready) {
            for (channel in 0 until format.channels) {
                var value = 0.0
                var ring = position
                for (group in 0 until groups) {
                    val packed = history[channel][ring]
                    val count = packed ushr 8
                    if (count == 8) value += lookup[group][packed and 255]
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
}
