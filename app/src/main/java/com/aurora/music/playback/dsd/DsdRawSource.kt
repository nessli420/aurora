package com.aurora.music.playback.dsd

class DsdRawSource(val format: DsdFormat) {
    private val normalized = ByteArray(8192)
    var bytePosition: Long = 0; private set
    val ended: Boolean get() = bytePosition == format.bytesPerChannel
    val filePosition: Long get() = format.position(bytePosition)
    val readSize: Int get() = if (ended) 0 else if (format.container == DsdContainer.DSF)
        format.blockBytes * format.channels
        else minOf(8192L / format.channels, format.bytesPerChannel - bytePosition).toInt() * format.channels

    fun seek(firstByte: Long) {
        require(firstByte in 0..format.bytesPerChannel)
        require(format.container != DsdContainer.DSF || firstByte == format.bytesPerChannel || firstByte % format.blockBytes == 0L)
        bytePosition = firstByte
    }

    fun consume(containerBytes: ByteArray, size: Int): DsdRawBlock {
        require(!ended && size == readSize && containerBytes.size >= size)
        val count = minOf((size / format.channels).toLong(), format.bytesPerChannel - bytePosition).toInt()
        val samples = minOf(count * 8L, format.sampleCount - bytePosition * 8)
        for (frame in 0 until count) for (channel in 0 until format.channels) {
            val index = if (format.container == DsdContainer.DSF) channel * format.blockBytes + frame else frame * format.channels + channel
            val value = containerBytes[index].toInt() and 255
            normalized[frame * format.channels + channel] =
                (if (format.leastSignificantBitFirst) Integer.reverse(value) ushr 24 else value).toByte()
        }
        return DsdRawBlock(normalized, count * format.channels, bytePosition * 8, samples).also { bytePosition += count }
    }
}

// bytes are interleaved, msb first, and valid until the next source read
data class DsdRawBlock(val bytes: ByteArray, val size: Int, val firstSample: Long, val sampleCount: Long)
