package com.aurora.music.playback.engine

import java.nio.ByteBuffer

/**
 * Explicit decoder/output boundaries for the prototype. This is NOT registered with Media3:
 * its normal 16-bit processor chain and float-bypass behavior cannot carry this rack unchanged.
 * Input/output buffers are owned and sized by the caller; no allocation occurs in [process].
 */
class PrecisionPcmPipeline(
    val inputEncoding: PcmEncoding,
    val outputEncoding: PcmEncoding,
    val rack: PrecisionSerialRack,
    private val dither: TpdfDither? = null
) {
    private val block = AudioBlock(rack.format, rack.capacityFrames)
    val sourcePrecision: SamplePrecision get() = inputEncoding.precision
    val internalPrecision: SamplePrecision get() = rack.arithmeticPrecision
    val outputPrecision: SamplePrecision get() = outputEncoding.precision

    fun process(
        input: ByteBuffer,
        output: ByteBuffer,
        frames: Int,
        presentationTimeUs: Long = AUDIO_TIME_UNSET,
        firstFramePosition: Long = AUDIO_TIME_UNSET
    ) {
        require(frames in 0..block.capacityFrames)
        val samples = frames * rack.format.channelCount
        require(input !== output) { "PCM boundary buffers must be distinct" }
        require(input.remaining() >= samples * inputEncoding.bytesPerSample)
        require(output.remaining() >= samples * outputEncoding.bytesPerSample)
        block.begin(frames, presentationTimeUs, firstFramePosition)
        PcmBoundary.decode(input, inputEncoding, block)
        rack.process(block)
        PcmBoundary.encode(block, outputEncoding, output, dither)
    }

    /** Bypass copies original bytes, preserving the source precision and every bit pattern. */
    fun bypass(input: ByteBuffer, output: ByteBuffer, frames: Int) {
        require(inputEncoding == outputEncoding) { "Unchanged bypass cannot convert the sample format" }
        require(frames in 0..block.capacityFrames && input !== output)
        PcmBoundary.copyUnchanged(input, output, frames * rack.format.channelCount * inputEncoding.bytesPerSample)
    }

    fun reset() = rack.reset()
}
