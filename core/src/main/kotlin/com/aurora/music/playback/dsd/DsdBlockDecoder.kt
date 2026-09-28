package com.aurora.music.playback.dsd

class DsdBlockDecoder(val format: DsdFormat) : AutoCloseable {
    private var handle = create(format.channels, format.decimation, format.sampleCount,
        DsdPcmDecoder.coefficientsFor(format))
    var outputFrame: Long = 0; private set
    val ended: Boolean get() = outputFrame == format.pcmFrames
    private var nextSample = 0L

    init { check(handle != 0L) { "DSD decoder could not start." } }

    fun reset(firstByte: Long, firstFrame: Long) {
        check(handle != 0L)
        require(firstFrame in 0..format.pcmFrames && firstByte in 0..format.prerollByte(firstFrame))
        reset(handle, firstByte, firstFrame)
        outputFrame = firstFrame
        nextSample = firstByte * 8
    }

    fun decode(block: DsdRawBlock?, output: FloatArray): Int {
        check(handle != 0L)
        if (ended) return 0
        if (block != null) {
            require(block.firstSample == nextSample && block.size in 1..8192 && block.size <= block.bytes.size)
            require(block.size % format.channels == 0 && block.sampleCount in 1..block.size / format.channels * 8L)
            require(block.sampleCount == minOf(block.size / format.channels * 8L, format.sampleCount - nextSample))
        } else require(nextSample >= format.sampleCount)
        val frames = decode(handle, block?.bytes, block?.size ?: 0, output)
        check(frames >= 0) { "Invalid DSD decoder block." }
        outputFrame += frames
        nextSample += block?.sampleCount ?: 0
        return frames
    }

    override fun close() { if (handle != 0L) { destroy(handle); handle = 0 } }
    private external fun create(channels: Int, decimation: Int, samples: Long, coefficients: DoubleArray): Long
    private external fun reset(handle: Long, byte: Long, frame: Long)
    private external fun decode(handle: Long, bytes: ByteArray?, size: Int, output: FloatArray): Int
    private external fun destroy(handle: Long)

    companion object { init { System.loadLibrary("aurora_dsd") } }
}
