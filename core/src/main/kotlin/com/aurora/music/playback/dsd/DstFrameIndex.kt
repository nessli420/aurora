package com.aurora.music.playback.dsd

data class DstFrame(val offset: Long, val size: Int, val crc: Int? = null)

object DstFrameCrc {
    fun calculate(bytes: ByteArray): Int {
        var crc = 0
        for (byte in bytes) {
            crc = crc xor ((byte.toInt() and 255) shl 24)
            repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x80000011.toInt() else crc shl 1 }
        }
        return crc
    }
}
