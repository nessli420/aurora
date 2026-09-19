package com.aurora.music.playback.engine

import kotlin.math.*

class BandlimitedResampler(val sourceRate: Int, val targetRate: Int, private val channels: Int = 2) {
    init { require(sourceRate in 8_000..768_000 && targetRate in 8_000..768_000 && channels in 1..4) }
    private val cutoff = minOf(1.0, targetRate.toDouble() / sourceRate) * 0.94
    val lookaheadFrames = ceil(32.0 / cutoff).toInt()
    private val taps = lookaheadFrames * 2 + 1
    private val kernels = Array(PHASES + 1) { phase ->
        val fraction = phase.toDouble() / PHASES
        DoubleArray(taps) { tap ->
            val x = tap - lookaheadFrames - fraction
            val normalized = x / lookaheadFrames
            val window = if (abs(normalized) >= 1.0) 0.0 else
                0.42 + 0.5 * cos(PI * normalized) + 0.08 * cos(2.0 * PI * normalized)
            val argument = PI * cutoff * x
            cutoff * (if (abs(argument) < 1e-14) 1.0 else sin(argument) / argument) * window
        }.also { kernel -> val gain = kernel.sum(); for (i in kernel.indices) kernel[i] /= gain }
    }
    private val capacity = 16_384
    private val samples = DoubleArray(capacity * channels)
    private var received = 0L
    private var produced = 0L
    private var ended = false
    private var targetFrames = Long.MAX_VALUE
    val bufferedFrames: Long get() = received - floor(produced.toDouble() * sourceRate / targetRate).toLong()
    val isEnded: Boolean get() = ended && produced >= targetFrames
    val canAcceptFrames: Int get() {
        val earliest = (floor(produced.toDouble() * sourceRate / targetRate).toLong() - lookaheadFrames).coerceAtLeast(0)
        return (capacity - (received - earliest)).coerceIn(0, capacity.toLong()).toInt()
    }

    fun queueInput(input: DoubleArray, offsetFrames: Int, frames: Int): Int {
        check(!ended)
        require(offsetFrames >= 0 && frames >= 0 && (offsetFrames.toLong() + frames) * channels <= input.size)
        val count = minOf(frames, canAcceptFrames)
        for (frame in 0 until count) for (channel in 0 until channels) {
            val value = input[(offsetFrames + frame) * channels + channel]
            samples[((received + frame) % capacity).toInt() * channels + channel] = if (value.isFinite()) value else 0.0
        }
        received += count
        return count
    }

    fun readOutput(output: DoubleArray, offsetFrames: Int, maxFrames: Int): Int {
        require(offsetFrames >= 0 && maxFrames >= 0 && (offsetFrames.toLong() + maxFrames) * channels <= output.size)
        var count = 0
        while (count < maxFrames && produced < targetFrames) {
            val position = produced.toDouble() * sourceRate / targetRate
            val center = floor(position).toLong()
            if (!ended && center + lookaheadFrames >= received) break
            val phase = (position - center) * PHASES
            val index = phase.toInt().coerceIn(0, PHASES - 1)
            val blend = phase - index
            val first = kernels[index]; val second = kernels[index + 1]
            for (channel in 0 until channels) {
                var value = 0.0
                for (tap in 0 until taps) {
                    val frame = center + tap - lookaheadFrames
                    if (frame >= 0 && frame < received) {
                        val weight = first[tap] + (second[tap] - first[tap]) * blend
                        value += samples[(frame % capacity).toInt() * channels + channel] * weight
                    }
                }
                output[(offsetFrames + count) * channels + channel] = value
            }
            produced++; count++
        }
        return count
    }

    fun queueEndOfInput() {
        if (ended) return
        ended = true
        targetFrames = (received * targetRate.toDouble() / sourceRate).roundToLong()
    }

    fun reset() { samples.fill(0.0); received = 0; produced = 0; ended = false; targetFrames = Long.MAX_VALUE }

    companion object {
        private const val PHASES = 256

        fun resample(source: DoubleArray, sourceRate: Int, targetRate: Int): DoubleArray {
            require(sourceRate in 8_000..768_000 && targetRate in 8_000..768_000 && source.isNotEmpty() && source.all(Double::isFinite))
            if (sourceRate == targetRate) return source.copyOf()
            val length = (source.size * targetRate.toDouble() / sourceRate).roundToLong().coerceAtLeast(1)
            require(length <= 4_194_304) { "Resampled audio exceeds the preparation limit." }
            val converter = BandlimitedResampler(sourceRate, targetRate, 1)
            val result = DoubleArray(length.toInt())
            var input = 0; var output = 0
            while (input < source.size) {
                input += converter.queueInput(source, input, minOf(1024, source.size - input))
                output += converter.readOutput(result, output, result.size - output)
            }
            converter.queueEndOfInput()
            output += converter.readOutput(result, output, result.size - output)
            check(output == result.size || result.size == 1 && output == 0)
            return result
        }

        fun resampleImpulse(source: DoubleArray, sourceRate: Int, targetRate: Int): DoubleArray {
            require(sourceRate in 8_000..768_000 && targetRate in 8_000..768_000) { "Unsupported impulse sample rate." }
            require(source.isNotEmpty()) { "Impulse response is empty." }
            require(source.all(Double::isFinite)) { "Impulse response contains non-finite samples." }
            if (sourceRate == targetRate) return source.copyOf().also {
                require(it.size <= PrecisionConvolver.MAX_IR_FRAMES) { "Impulse response is too long at the selected rate." }
            }
            val padding = impulsePadding(sourceRate, targetRate)
            val frames = ((source.size + 2L * padding) * targetRate.toDouble() / sourceRate).roundToLong()
            require(frames <= PrecisionConvolver.MAX_IR_FRAMES) { "Impulse response is too long at the selected rate." }
            val padded = DoubleArray(source.size + 2 * padding)
            source.copyInto(padded, padding)
            val result = resample(padded, sourceRate, targetRate)
            require(result.size <= PrecisionConvolver.MAX_IR_FRAMES) { "Impulse response is too long at the selected rate." }
            if (sourceRate != targetRate) {
                val gain = sourceRate.toDouble() / targetRate
                for (i in result.indices) result[i] *= gain
            }
            return result
        }

        fun impulsePadding(sourceRate: Int, targetRate: Int): Int {
            if (sourceRate == targetRate) return 0
            var a = sourceRate; var b = targetRate
            while (b != 0) { val next = a % b; a = b; b = next }
            val period = sourceRate / a
            val radius = ceil(32.0 / (minOf(1.0, targetRate.toDouble() / sourceRate) * 0.94)).toInt()
            return ((radius + period - 1) / period) * period
        }

        fun impulseDelayFrames(sourceRate: Int, targetRate: Int): Int =
            (impulsePadding(sourceRate, targetRate) * targetRate.toDouble() / sourceRate).roundToInt()
    }
}
