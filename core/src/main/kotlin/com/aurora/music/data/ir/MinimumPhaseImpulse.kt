package com.aurora.music.data.ir

import com.aurora.music.playback.engine.PrecisionFft
import kotlin.math.*

object MinimumPhaseImpulse {
    fun convert(input: DoubleArray): DoubleArray {
        require(input.isNotEmpty() && input.size <= 262_144 && input.all(Double::isFinite))
        if (input.all { it == 0.0 }) return input.copyOf()
        val dc = input.sum()
        // preserve dc polarity; use the dominant tap when dc cancels.
        val reference = if (abs(dc) > input.sumOf { abs(it) } * 1e-12) dc else input.maxBy { abs(it) }
        val polarity = if (reference < 0.0) -1.0 else 1.0
        var size = 2
        while (size < input.size * 4) size *= 2
        val fft = PrecisionFft(size)
        val real = input.copyOf(size)
        val imaginary = DoubleArray(size)
        fft.transform(real, imaginary, false)
        val maximum = real.indices.maxOf { hypot(real[it], imaginary[it]) }
        val floor = maxOf(maximum * 1e-12, 1e-300)
        for (i in real.indices) { real[i] = ln(maxOf(hypot(real[i], imaginary[i]), floor)); imaginary[i] = 0.0 }
        fft.transform(real, imaginary, true)
        for (i in 1 until size / 2) real[i] *= 2.0
        java.util.Arrays.fill(real, size / 2 + 1, size, 0.0)
        imaginary.fill(0.0)
        fft.transform(real, imaginary, false)
        for (i in real.indices) {
            val magnitude = exp(real[i])
            real[i] = magnitude * cos(imaginary[i])
            imaginary[i] = magnitude * sin(imaginary[i])
        }
        fft.transform(real, imaginary, true)
        return DoubleArray(input.size) { real[it] * polarity }
    }
}
