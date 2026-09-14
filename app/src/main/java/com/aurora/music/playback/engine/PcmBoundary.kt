package com.aurora.music.playback.engine

import java.nio.ByteBuffer
import kotlin.math.floor

/** Stateful, deterministic TPDF at one integer LSB peak per random difference. */
class TpdfDither(seed: Int = 0x51a72e3b) {
    private var state = if (seed == 0) 1 else seed
    private fun uniform(): Double {
        var x = state
        x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
        state = x
        return (x ushr 1) / 2147483648.0
    }
    internal fun nextLsb(): Double = uniform() - uniform()
}

/** No allocation, buffer-order dependency, or intermediate integer conversion. */
object PcmBoundary {
    fun decode(input: ByteBuffer, encoding: PcmEncoding, block: AudioBlock) {
        require(input.remaining() >= block.sampleCount * encoding.bytesPerSample)
        var i = 0
        while (i < block.sampleCount) {
            val value = when (encoding) {
                PcmEncoding.SIGNED_16_LE -> readSigned(input, 2) / 32768.0
                PcmEncoding.SIGNED_24_LE -> readSigned(input, 3) / 8388608.0
                PcmEncoding.SIGNED_32_LE -> readSigned(input, 4) / 2147483648.0
                PcmEncoding.FLOAT_32_LE -> Float.fromBits(readSigned(input, 4)).toDouble()
                PcmEncoding.FLOAT_64_LE -> Double.fromBits(readLong(input))
            }
            // Malformed floating decoder values must not poison a recursive filter's state.
            block.samples[i++] = if (value.isFinite()) value else 0.0
        }
    }

    /** Integer output clips and rounds ties toward positive infinity; floats retain headroom. */
    fun encode(block: AudioBlock, encoding: PcmEncoding, output: ByteBuffer, dither: TpdfDither? = null) {
        require(output.remaining() >= block.sampleCount * encoding.bytesPerSample)
        var i = 0
        while (i < block.sampleCount) {
            val raw = block.samples[i++]
            val value = if (raw.isFinite()) raw else 0.0
            when (encoding) {
                PcmEncoding.FLOAT_32_LE -> {
                    // Finite double overflow is clipped to the largest representable float.
                    val f = value.coerceIn(-Float.MAX_VALUE.toDouble(), Float.MAX_VALUE.toDouble()).toFloat()
                    writeInteger(output, f.toRawBits().toLong(), 4)
                }
                PcmEncoding.FLOAT_64_LE -> writeInteger(output, value.toRawBits(), 8)
                else -> {
                    val scale = (1L shl (encoding.integerBits - 1)).toDouble()
                    val rounded = floor(value.coerceIn(-1.0, 1.0) * scale + (dither?.nextLsb() ?: 0.0) + 0.5)
                    val signed = rounded.coerceIn(-scale, scale - 1.0).toLong()
                    writeInteger(output, signed, encoding.bytesPerSample)
                }
            }
        }
    }

    /** Explicit unchanged-sample bypass, including float NaN payloads; never adds dither. */
    fun copyUnchanged(input: ByteBuffer, output: ByteBuffer, byteCount: Int) {
        require(byteCount >= 0 && input.remaining() >= byteCount && output.remaining() >= byteCount)
        // Avoid creating a slice or changing the caller's limit.
        var remaining = byteCount
        while (remaining-- > 0) output.put(input.get())
    }

    private fun readSigned(input: ByteBuffer, bytes: Int): Int {
        var value = 0
        var shift = 0
        while (shift < bytes * 8) {
            value = value or ((input.get().toInt() and 0xff) shl shift)
            shift += 8
        }
        val signShift = 32 - bytes * 8
        return (value shl signShift) shr signShift
    }

    private fun readLong(input: ByteBuffer): Long {
        var value = 0L
        var shift = 0
        while (shift < 64) {
            value = value or ((input.get().toLong() and 0xff) shl shift)
            shift += 8
        }
        return value
    }

    private fun writeInteger(output: ByteBuffer, value: Long, bytes: Int) {
        var shift = 0
        while (shift < bytes * 8) { output.put((value shr shift).toByte()); shift += 8 }
    }
}
