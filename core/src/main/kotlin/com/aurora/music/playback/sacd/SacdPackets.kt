package com.aurora.music.playback.sacd

internal data class SacdPacket(val offset: Int, val length: Int, val audio: Boolean, val frame: Int?, val sectors: Int)

internal object SacdPackets {
    fun parse(bytes: ByteArray, coded: Boolean = true): List<SacdPacket> {
        require(bytes.size == 2048)
        fun u8(at: Int) = bytes[at].toInt() and 255
        val header = u8(0)
        val count = header ushr 5
        val starts = header ushr 2 and 7
        require(header and 3 == (if (coded) 1 else 0) && count in 1..7 && starts <= count) { "Invalid SACD audio sector." }
        val frameBytes = if (coded) 4 else 3
        var cursor = 1 + count * 2 + starts * frameBytes
        var frameIndex = 0
        val result = List(count) { index ->
            val info = (u8(1 + index * 2) shl 8) or u8(2 + index * 2)
            val length = info and 2047
            val type = info ushr 11 and 7
            require(info and 16384 == 0 && length > 0 && cursor <= 2048 - length && type in 2..4) { "Invalid SACD packet." }
            val start = info and 32768 != 0
            var frame: Int? = null
            var span = 0
            if (start) {
                require(type == 2 && frameIndex < starts) { "Invalid SACD frame header." }
                val at = 1 + count * 2 + frameIndex++ * frameBytes
                val minutes = u8(at); val seconds = u8(at + 1); val frames = u8(at + 2)
                val flags = if (coded) u8(at + 3) else 4
                span = flags ushr 2 and 31
                require(seconds < 60 && frames < 75 && flags and 131 == 0 && span in 1..7) { "Unsupported SACD frame." }
                frame = (minutes * 60 + seconds) * 75 + frames
            }
            SacdPacket(cursor, length, type == 2, frame, span).also { cursor += length }
        }
        require(frameIndex == starts) { "SACD frame count does not match its packets." }
        return result
    }
}
