package com.aurora.music.playback.dsd

class DstDecoder(val channels: Int) : AutoCloseable {
    private var handle = create(channels)
    init { require(channels in 1..2); check(handle != 0L) { "DST decoder could not start." } }
    val frameBytes: Int get() = 4704 * channels
    fun decode(input: ByteArray, output: ByteArray) {
        check(handle != 0L)
        require(input.size in 2..1_048_576 && output.size == frameBytes)
        val result = decode(handle, input, output)
        if (result == -2) throw UnsupportedOperationException("This DST segmentation mode is not supported.")
        require(result == frameBytes) { "Invalid DST frame." }
    }
    override fun close() { if (handle != 0L) { destroy(handle); handle = 0 } }
    private external fun create(channels: Int): Long
    private external fun decode(handle: Long, input: ByteArray, output: ByteArray): Int
    private external fun destroy(handle: Long)
    companion object { init { System.loadLibrary("aurora_dst") } }
}
