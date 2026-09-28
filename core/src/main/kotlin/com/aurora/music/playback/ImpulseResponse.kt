package com.aurora.music.playback

import com.aurora.music.playback.engine.BandlimitedResampler
import com.aurora.music.playback.engine.PrecisionConvolver
import com.aurora.music.playback.engine.SamplePrecision
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Float-array construction stays compatible; WAV decoding retains its original PCM precision. */
class ImpulseResponse private constructor(
    internal val preciseLeft: DoubleArray,
    internal val preciseRight: DoubleArray,
    val sampleRate: Int,
    val sourcePrecision: SamplePrecision,
    val sourceValidBits: Int,
    val sourceChannels: Int = 2,
    internal val preciseLeftToRight: DoubleArray? = null,
    internal val preciseRightToLeft: DoubleArray? = null,
    val alignmentFrames: Int = 0,
) {
    constructor(left: FloatArray, right: FloatArray, sampleRate: Int) : this(
        DoubleArray(left.size) { left[it].toDouble() },
        DoubleArray(right.size) { right[it].toDouble() }, sampleRate, SamplePrecision.FLOAT_32, 24
    )
    // Compatibility views; processing always uses the owned binary64 samples above.
    val left: FloatArray get() = FloatArray(preciseLeft.size) { preciseLeft[it].toFloat() }
    val right: FloatArray get() = FloatArray(preciseRight.size) { preciseRight[it].toFloat() }
    val frameCount: Int get() = maxOf(preciseLeft.size, preciseRight.size,
        preciseLeftToRight?.size ?: 0, preciseRightToLeft?.size ?: 0)
    val trueStereo: Boolean get() = sourceChannels == 4
    fun alignmentFramesAt(rate: Int): Int = (alignmentFrames * rate.toDouble() / sampleRate).toInt() +
        BandlimitedResampler.impulseDelayFrames(sampleRate, rate)
    internal fun matrixChannels(): List<DoubleArray> = if (trueStereo)
        listOf(preciseLeft, checkNotNull(preciseLeftToRight), checkNotNull(preciseRightToLeft), preciseRight)
    else if (sourceChannels == 1) listOf(preciseLeft) else listOf(preciseLeft, preciseRight)

    private val preparedRates = LinkedHashMap<Int, List<DoubleArray>>()

    fun createConvolver(rate: Int, blockSize: Int = 1024): PrecisionConvolver {
        val channels = synchronized(preparedRates) {
            preparedRates[rate] ?: matrixChannels().map {
                BandlimitedResampler.resampleImpulse(it, sampleRate, rate)
            }.also { prepared ->
                preparedRates[rate] = prepared
                while (preparedRates.size > 1 && (preparedRates.size > 3 ||
                        preparedRates.values.sumOf { paths -> paths.sumOf { it.size.toLong() * 8 } } > 8L * 1024 * 1024)) {
                    preparedRates.remove(preparedRates.keys.first())
                }
            }
        }
        return PrecisionConvolver(channels.first(), channels.last(), blockSize,
            if (trueStereo) channels[1] else null, if (trueStereo) channels[2] else null)
    }

    companion object {
        const val MAX_DECODED_IR_FRAMES = 1_048_576

        internal fun decoded(left: DoubleArray, right: DoubleArray, rate: Int, precision: SamplePrecision, validBits: Int,
            channels: Int = 2, leftToRight: DoubleArray? = null, rightToLeft: DoubleArray? = null, alignmentFrames: Int = 0) =
            ImpulseResponse(left, right, rate, precision, validBits, channels, leftToRight, rightToLeft, alignmentFrames)

        fun loadWav(file: File): ImpulseResponse? = loadWavResult(file).getOrNull()

        /** bounded import: at most 32 mib of matrix samples and a 64 kib input buffer. */
        fun loadWavResult(file: File): Result<ImpulseResponse> = runCatching {
            require(file.length() in 44..64L * 1024 * 1024) { "Impulse-response WAV must be between 44 bytes and 64 MiB" }
            java.io.RandomAccessFile(file, "r").use { input ->
                fun intLe(): Int = Integer.reverseBytes(input.readInt())
                fun shortLe(): Int = java.lang.Short.reverseBytes(input.readShort()).toInt() and 0xffff
                require(intLe() == 0x46464952) { "Impulse response is not a RIFF WAV" }
                val riffEnd = (intLe().toLong() and 0xffffffffL) + 8
                require(riffEnd == input.length() && intLe() == 0x45564157) { "Invalid WAV container length" }
                var code = 0; var channels = 0; var rate = 0; var bits = 0; var validBits = 0; var alignment = 0
                var dataOffset = -1L; var dataLength = 0L; var hasFormat = false
                var alignmentFrames = 0; var hasAlignment = false
                while (input.filePointer + 8 <= riffEnd) {
                    val chunk = intLe()
                    val length = intLe().toLong() and 0xffffffffL
                    val start = input.filePointer
                    val end = start + length
                    require(end <= riffEnd) { "WAV chunk exceeds the container" }
                    when (chunk) {
                        0x20746d66 -> {
                            require(!hasFormat && length >= 16) { "Invalid or duplicate WAV format chunk" }
                            code = shortLe(); channels = shortLe(); rate = intLe()
                            val byteRate = intLe().toLong() and 0xffffffffL
                            alignment = shortLe(); bits = shortLe()
                            validBits = bits
                            if (code == 0xfffe) {
                                require(length >= 40) { "Incomplete extensible WAV format" }
                                val extensionBytes = shortLe()
                                require(extensionBytes >= 22 && extensionBytes.toLong() <= length - 18) { "Invalid extensible WAV format size" }
                                validBits = shortLe()
                                intLe() // matrix order is independent of the speaker mask
                                val subFormat = intLe()
                                require(intLe() == 0x00100000 && intLe() == 0xaa000080.toInt() && intLe() == 0x719b3800 && subFormat in intArrayOf(1, 3)) {
                                    "Unsupported extensible WAV subformat"
                                }
                                code = subFormat
                            }
                            require(channels in listOf(1, 2, 4) && rate in 8_000..768_000 &&
                                (code == 1 && bits in listOf(8, 16, 24, 32) || code == 3 && bits == 32) &&
                                validBits in 1..bits && (code != 3 || validBits == 32) &&
                                alignment == channels * (bits / 8) && byteRate == rate.toLong() * alignment) {
                                "Use mono, stereo or LL/LR/RL/RR PCM or float32 WAV."
                            }
                            hasFormat = true
                        }
                        0x61746164 -> {
                            require(dataOffset < 0) { "Duplicate WAV data chunk" }
                            dataOffset = start; dataLength = length
                        }
                        0x4c447561 -> {
                            require(!hasAlignment && length == 4L) { "Invalid impulse alignment metadata." }
                            alignmentFrames = intLe()
                            require(alignmentFrames >= 0) { "Invalid impulse alignment delay." }
                            hasAlignment = true
                        }
                    }
                    val next = end + (length and 1)
                    require(next <= riffEnd) { "Missing WAV chunk padding" }
                    input.seek(next)
                }
                require(input.filePointer == riffEnd) { "Incomplete trailing WAV chunk" }
                require(hasFormat && dataOffset >= 0 && dataLength > 0 && dataLength % alignment == 0L) { "Missing or misaligned WAV samples" }
                val frames = dataLength / alignment
                require(alignmentFrames < frames) { "Impulse alignment exceeds its length." }
                require(frames <= MAX_DECODED_IR_FRAMES) { "Impulse response exceeds $MAX_DECODED_IR_FRAMES source frames per channel" }
                val left = DoubleArray(frames.toInt()); val right = DoubleArray(frames.toInt())
                val leftToRight = if (channels == 4) DoubleArray(frames.toInt()) else null
                val rightToLeft = if (channels == 4) DoubleArray(frames.toInt()) else null
                val bytes = ByteArray(64 * 1024)
                val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                fun sample(): Double = when {
                    code == 3 -> data.float.toDouble()
                    bits == 8 -> ((data.get().toInt() and 0xff) - 128) / 128.0
                    bits == 16 -> data.short / 32768.0
                    bits == 24 -> {
                        val low = data.get().toInt() and 0xff; val mid = data.get().toInt() and 0xff
                        val high = data.get().toInt()
                        ((high shl 16) or (mid shl 8) or low) / 8388608.0
                    }
                    else -> data.int / 2147483648.0
                }
                input.seek(dataOffset)
                var offset = 0
                while (offset < frames) {
                    val count = minOf(bytes.size / alignment, frames.toInt() - offset)
                    input.readFully(bytes, 0, count * alignment)
                    data.clear().limit(count * alignment)
                    repeat(count) {
                        val l = sample()
                        if (channels == 4) {
                            val lr = sample(); val rl = sample()
                            require(lr.isFinite() && rl.isFinite()) { "Impulse response contains non-finite samples" }
                            leftToRight!![offset] = lr; rightToLeft!![offset] = rl
                        }
                        val r = if (channels > 1) sample() else l
                        require(l.isFinite() && r.isFinite()) { "Impulse response contains non-finite samples" }
                        left[offset] = l; right[offset] = r
                        offset++
                    }
                }
                val precision = when {
                    code == 3 -> SamplePrecision.FLOAT_32
                    bits == 32 -> SamplePrecision.PCM_SIGNED_32
                    bits == 24 -> SamplePrecision.PCM_SIGNED_24
                    bits == 8 -> SamplePrecision.PCM_SIGNED_8
                    else -> SamplePrecision.PCM_SIGNED_16
                }
                ImpulseResponse.decoded(left, right, rate, precision, if (code == 3) 24 else validBits, channels, leftToRight, rightToLeft, alignmentFrames)
            }
        }
    }
}

enum class ConvolutionPreparationState { IDLE, PREPARING, READY, FAILED }
