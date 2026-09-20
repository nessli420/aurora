package com.aurora.music.playback.sacd

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class SacdTrackStream(private val image: SacdImage, trackNumber: Int,
    private val decodeDst: ((ByteArray) -> ByteArray)? = null) {
    val track = requireNotNull(image.disc.tracks.firstOrNull { it.number == trackNumber }) { "SACD track no longer exists." }
    private val dataBytes = track.frames.toLong() * 9408
    private val header: ByteArray
    private var cachedFrame = -1
    private var cached = ByteArray(0)
    val length: Long get() = header.size + dataBytes

    init {
        require(image.disc.frameFormat != 0 || decodeDst != null) { "DST decoding is required." }
        val props = "SND ".toByteArray() + chunk("FS  ", ByteBuffer.allocate(4).putInt(2822400).array()) +
            chunk("CHNL", byteArrayOf(0, 2) + "SLFTSRGT".toByteArray()) + chunk("CMPR", "DSD ".toByteArray() + byteArrayOf(0))
        val body = "DSD ".toByteArray() + chunk("FVER", byteArrayOf(1, 5, 0, 0)) + chunk("PROP", props) +
            "DSD ".toByteArray() + ByteBuffer.allocate(8).putLong(dataBytes).array()
        header = "FRM8".toByteArray() + ByteBuffer.allocate(8).putLong(body.size + dataBytes).array() + body
    }

    fun read(position: Long, output: ByteArray, offset: Int, size: Int): Int {
        require(position in 0..length && offset >= 0 && size >= 0 && offset <= output.size - size)
        if (size == 0) return 0
        if (position == length) return -1
        var cursor = position
        var written = 0
        if (cursor < header.size) {
            val count = minOf(size, header.size - cursor.toInt())
            header.copyInto(output, offset, cursor.toInt(), cursor.toInt() + count)
            cursor += count; written += count
        }
        while (written < size && cursor < length) {
            val audio = cursor - header.size
            val frame = (audio / 9408).toInt()
            if (cachedFrame != frame) {
                val absolute = track.firstFrame + frame
                cached = if (image.disc.frameFormat == 0) checkNotNull(decodeDst).invoke(image.compressedFrame(absolute))
                    else image.rawFrame(absolute)
                require(cached.size == 9408) { "Invalid decoded SACD frame length." }
                cachedFrame = frame
            }
            val inside = (audio % 9408).toInt()
            val count = minOf(size - written, 9408 - inside)
            cached.copyInto(output, offset + written, inside, inside + count)
            written += count; cursor += count
        }
        return written
    }

    private fun chunk(id: String, bytes: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        write(id.toByteArray()); write(ByteBuffer.allocate(8).putLong(bytes.size.toLong()).array()); write(bytes)
        if (bytes.size % 2 != 0) write(0)
    }.toByteArray()
}
