package com.aurora.music.playback.engine

import kotlin.math.floor

enum class OutputDitherMode { OFF, TPDF, NOISE_SHAPED }

interface PcmDither {
    fun prepare(format: AudioStreamFormat, encoding: PcmEncoding) = Unit
    fun quantize(value: Double, scale: Double, channel: Int): Long
    fun reset()
}

class NoiseShapedDither(seed: Int = 0x51a72e3b) : PcmDither {
    private val noise = arrayOf(TpdfDither(seed), TpdfDither(seed xor 0x6d2b79f5))
    private val flat = TpdfDither(seed)
    private val errors = DoubleArray(2)
    private var format: AudioStreamFormat? = null
    private var encoding: PcmEncoding? = null
    private var shaped = false

    override fun prepare(format: AudioStreamFormat, encoding: PcmEncoding) {
        if (this.format != format || this.encoding != encoding) {
            reset()
            this.format = format
            this.encoding = encoding
            shaped = encoding.integerBits > 0 && format.sampleRate >= MIN_SHAPED_RATE
        }
    }

    override fun quantize(value: Double, scale: Double, channel: Int): Long {
        if (!value.isFinite()) { errors[channel] = 0.0; return 0 }
        if (!shaped) return flat.quantize(value, scale, channel)
        val source = value.coerceIn(-1.0, 1.0) * scale
        if (value < -1.0 || source > scale - 1.0) {
            errors[channel] = 0.0
            return floor(source + 0.5).coerceIn(-scale, scale - 1.0).toLong()
        }
        // first-order total-error feedback: ntf = 1 - z^-1
        val input = source - errors[channel]
        val rounded = floor(input + noise[channel].nextLsb() + 0.5)
        val limited = rounded.coerceIn(-scale, scale - 1.0)
        errors[channel] = if (rounded == limited) (rounded - input).coerceIn(-1.5, 1.5) else 0.0
        return limited.toLong()
    }

    override fun reset() {
        errors.fill(0.0)
        noise.forEach { it.reset() }
        flat.reset()
    }

    companion object { const val MIN_SHAPED_RATE = 44_100 }
}

class OutputDither {
    private val flat = TpdfDither()
    private val shaped = NoiseShapedDither()
    private var selected = OutputDitherMode.OFF

    fun select(mode: OutputDitherMode): PcmDither? {
        if (mode != selected) { reset(); selected = mode }
        return when (mode) {
            OutputDitherMode.OFF -> null
            OutputDitherMode.TPDF -> flat
            OutputDitherMode.NOISE_SHAPED -> shaped
        }
    }

    fun reset() { flat.reset(); shaped.reset() }
}
