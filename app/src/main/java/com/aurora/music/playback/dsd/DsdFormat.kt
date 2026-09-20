package com.aurora.music.playback.dsd

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class DsdContainer { DSF, DFF }

data class DsdFormat(
    val container: DsdContainer,
    val bitRate: Int,
    val channels: Int,
    val sampleCount: Long,
    val dataOffset: Long,
    val dataBytes: Long,
    val leastSignificantBitFirst: Boolean = false,
    val blockBytes: Int = 4096,
) {
    init {
        require(supportsBitRate(bitRate)) { "Use DSD64, DSD128, DSD256 or DSD512." }
        require(channels in 1..2) { "Use mono or stereo DSD." }
        require(sampleCount in 1..bitRate.toLong() * 86_400) { "Invalid DSD duration." }
        require(dataOffset >= 0 && dataBytes > 0 && dataOffset <= Long.MAX_VALUE - dataBytes) { "Invalid DSD data range." }
        require(blockBytes == 4096) { "Unsupported DSF block size." }
    }
    val pcmRate: Int get() = 176_400
    val decimation: Int get() = bitRate / pcmRate
    val pcmFrames: Long get() = (sampleCount + decimation - 1) / decimation
    val durationUs: Long get() = sampleCount * 1_000_000L / bitRate
    val bytesPerChannel: Long get() = (sampleCount + 7) / 8
    val filterTaps: Int get() = 512 * (bitRate / 2_822_400)

    fun seekFrame(timeUs: Long): Long = if (timeUs > 0 && timeUs >= durationUs) pcmFrames
        else (timeUs.coerceAtLeast(0) * pcmRate / 1_000_000L).coerceAtMost(pcmFrames)
    fun prerollByte(frame: Long): Long {
        require(frame in 0..pcmFrames)
        val first = ((frame * decimation - filterTaps / 2).coerceAtLeast(0) / 8).coerceAtMost(bytesPerChannel)
        return if (container == DsdContainer.DSF) first / blockBytes * blockBytes else first
    }
    fun position(bytePerChannel: Long): Long {
        require(bytePerChannel in 0..bytesPerChannel)
        return dataOffset + if (container == DsdContainer.DSF) bytePerChannel / blockBytes * blockBytes * channels
            else bytePerChannel * channels
    }

    companion object {
        val supportedBitRates: List<Int> = listOf(2_822_400, 5_644_800, 11_289_600, 22_579_200)
        fun supportsBitRate(rate: Int): Boolean = rate in supportedBitRates
    }
}

internal object DsdHeaders {
    fun dsf(format: ByteArray, dataOffset: Long, dataBytes: Long): DsdFormat {
        require(format.size >= 40) { "Incomplete DSF format." }
        val b = ByteBuffer.wrap(format).order(ByteOrder.LITTLE_ENDIAN)
        require(b.int == 1 && b.int == 0) { "Unsupported DSF format." }
        val layout = b.int
        val channels = b.int
        require(layout == channels && channels in 1..2) { "Use mono or stereo DSF." }
        val rate = b.int
        val bits = b.int
        require(bits == 1 || bits == 8) { "Invalid DSF bit order." }
        val samples = b.long
        val block = b.int
        require(b.int == 0) { "Invalid DSF reserved field." }
        val result = DsdFormat(DsdContainer.DSF, rate, channels, samples, dataOffset, dataBytes, bits == 1, block)
        val expected = ((result.bytesPerChannel + block - 1) / block) * block * channels
        require(dataBytes == expected) { "DSF sample count does not match its blocks." }
        return result
    }

    fun dffProperties(payload: ByteArray): Pair<Int, Int> {
        val b = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        require(b.remaining() >= 4 && id(b) == "SND ") { "Invalid DFF sound properties." }
        var rate = 0; var channels = 0; var compression: String? = null
        var chunks = 0
        while (b.hasRemaining()) {
            require(++chunks <= 128 && b.remaining() >= 12) { "Invalid DFF property chunks." }
            val id = id(b)
            val size = b.long
            require(size >= 0 && size <= b.remaining() && size + (size and 1) <= b.remaining()) { "Truncated DFF property." }
            val end = b.position() + size.toInt()
            when (id) {
                "FS  " -> { require(rate == 0 && size == 4L); rate = b.int }
                "CHNL" -> {
                    require(channels == 0 && size >= 2)
                    channels = b.short.toInt() and 0xffff
                    require(channels in 1..2 && size == 2L + 4L * channels) { "Use mono or stereo DFF." }
                    val first = id(b)
                    if (channels == 2) require(first == "SLFT" && id(b) == "SRGT") { "Unsupported DFF channel layout." }
                }
                "CMPR" -> {
                    require(compression == null && size >= 5)
                    compression = id(b)
                    val nameBytes = b.get().toInt() and 255
                    require(nameBytes.toLong() == size - 5) { "Invalid DFF compression name." }
                    require(compression == "DSD ") { "DST-compressed DFF is not supported." }
                }
            }
            b.position(end + (size and 1).toInt())
        }
        require(DsdFormat.supportsBitRate(rate) && channels in 1..2 && compression == "DSD ") { "Incomplete or unsupported DFF properties." }
        return rate to channels
    }

    fun id(buffer: ByteBuffer): String {
        require(buffer.remaining() >= 4)
        return CharArray(4) { (buffer.get().toInt() and 255).toChar() }.concatToString()
    }
}
