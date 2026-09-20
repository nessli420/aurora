package com.aurora.music.playback.dsd

enum class DsdWireFormat(val sourceBytes: Int) { DOP(2), NATIVE_MSB32(4) }

class DsdUsbPacker(val channels: Int, val wire: DsdWireFormat, val containerBytes: Int) {
    private val carry = ByteArray(channels * wire.sourceBytes)
    private var carried = 0
    private var marker = 0x05
    private var finished = false
    init {
        require(channels in 1..2)
        require(if (wire == DsdWireFormat.DOP) containerBytes in 3..4 else containerBytes == 4)
    }

    fun reset() { carried = 0; marker = 0x05; finished = false }

    fun pack(bytes: ByteArray, size: Int = bytes.size): ByteArray {
        check(!finished)
        require(size in 0..minOf(bytes.size, 8192) && size % channels == 0)
        val output = ByteArray((carried + size) / carry.size * channels * containerBytes)
        var at = 0
        var destination = 0
        while (at < size) {
            val count = minOf(carry.size - carried, size - at)
            bytes.copyInto(carry, carried, at, at + count)
            carried += count; at += count
            if (carried == carry.size) { writeFrame(output, destination); destination += channels * containerBytes; carried = 0 }
        }
        return output
    }

    fun finish(): ByteArray {
        if (finished) return byteArrayOf()
        finished = true
        if (carried == 0) return byteArrayOf()
        carry.fill(0x69, carried)
        return ByteArray(channels * containerBytes).also { writeFrame(it, 0); carried = 0 }
    }

    private fun writeFrame(output: ByteArray, offset: Int) {
        for (channel in 0 until channels) {
            val at = offset + channel * containerBytes
            if (wire == DsdWireFormat.DOP) {
                val pad = containerBytes - 3
                if (pad == 1) output[at] = 0
                output[at + pad] = carry[channels + channel]
                output[at + pad + 1] = carry[channel]
                output[at + pad + 2] = marker.toByte()
            } else for (byte in 0..3) output[at + byte] = carry[(3 - byte) * channels + channel]
        }
        marker = marker xor 0xff
    }
}
