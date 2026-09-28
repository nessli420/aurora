package com.aurora.music.playback.network.audio

import com.aurora.music.playback.engine.PcmEncoding
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class NetworkWaveSource(
    val sampleRate: Int,
    val channels: Int,
    val encoding: PcmEncoding?,
    val dataOffset: Long,
    val dataBytes: Long,
) {
    val frameBytes: Int get() = channels * (encoding?.bytesPerSample ?: 1)

    companion object {
        fun inspect(file: File): NetworkWaveSource? = RandomAccessFile(file, "r").use { input ->
            if (input.length() < 12) return@use null
            val prefix = ByteArray(12).also(input::readFully)
            if (String(prefix, 0, 4, Charsets.US_ASCII) != "RIFF" || String(prefix, 8, 4, Charsets.US_ASCII) != "WAVE") return@use null
            val end = ByteBuffer.wrap(prefix).order(ByteOrder.LITTLE_ENDIAN).getInt(4).toLong().and(0xffffffffL) + 8
            require(end in 12..input.length()) { "The WAV file is incomplete." }
            var format: ByteArray? = null
            var dataOffset = -1L
            var dataBytes = 0L
            var chunks = 0
            while (input.filePointer + 8 <= end) {
                require(++chunks <= 4096) { "The WAV has too many chunks." }
                val chunk = ByteArray(8).also(input::readFully)
                val size = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).getInt(4).toLong().and(0xffffffffL)
                val offset = input.filePointer
                require(size <= end - offset) { "The WAV chunk is incomplete." }
                when (String(chunk, 0, 4, Charsets.US_ASCII)) {
                    "fmt " -> {
                        require(format == null && size in 16..4096) { "The WAV format is unsupported." }
                        format = ByteArray(size.toInt()).also(input::readFully)
                    }
                    "data" -> {
                        require(dataOffset < 0) { "Multiple WAV data chunks are unsupported." }
                        dataOffset = offset
                        dataBytes = size
                    }
                }
                input.seek(offset + size + (size and 1))
            }
            val fields = ByteBuffer.wrap(requireNotNull(format) { "The WAV format is missing." }).order(ByteOrder.LITTLE_ENDIAN)
            var tag = fields.getShort(0).toInt() and 0xffff
            val channels = fields.getShort(2).toInt() and 0xffff
            val rate = fields.getInt(4)
            val stride = fields.getShort(12).toInt() and 0xffff
            val bits = fields.getShort(14).toInt() and 0xffff
            if (tag == 0xfffe) {
                require(fields.capacity() >= 40 && fields.getShort(16).toInt() >= 22) { "The extended WAV format is incomplete." }
                val valid = fields.getShort(18).toInt() and 0xffff
                require(valid in 1..bits) { "The WAV sample precision is invalid." }
                val mask = fields.getInt(20)
                require(mask == 0 || channels == 1 && mask in setOf(1, 4) || channels == 2 && mask == 3) { "The WAV channel layout is unsupported." }
                val suffix = byteArrayOf(0, 0, 0x10, 0, 0x80.toByte(), 0, 0, 0xaa.toByte(), 0, 0x38, 0x9b.toByte(), 0x71)
                require((0 until 12).all { fields.get(28 + it) == suffix[it] }) { "The WAV codec is unsupported." }
                tag = fields.getInt(24)
            }
            require(channels in 1..2 && rate in 8_000..192_000) { "The WAV channel count or sample rate is unsupported." }
            val encoding = when (tag to bits) {
                1 to 8 -> null
                1 to 16 -> PcmEncoding.SIGNED_16_LE
                1 to 24 -> PcmEncoding.SIGNED_24_LE
                1 to 32 -> PcmEncoding.SIGNED_32_LE
                3 to 32 -> PcmEncoding.FLOAT_32_LE
                3 to 64 -> PcmEncoding.FLOAT_64_LE
                else -> error("The WAV sample format is unsupported.")
            }
            require(stride == channels * (bits / 8) && dataOffset >= 0 && dataBytes > 0 && dataBytes % stride == 0L) {
                "The WAV data is incomplete."
            }
            NetworkWaveSource(rate, channels, encoding, dataOffset, dataBytes)
        }
    }
}
