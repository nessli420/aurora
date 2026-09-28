package com.aurora.music.playback.engine

/** Wire contracts are versioned independently of the app or a future saved rack schema. */
const val AUDIO_BLOCK_SCHEMA_VERSION = 1
const val AUDIO_TIME_UNSET = Long.MIN_VALUE

enum class ChannelLayout(val channelCount: Int) { MONO(1), STEREO(2) }

enum class SamplePrecision(val significandBits: Int) {
    PCM_SIGNED_8(8), PCM_SIGNED_16(16), PCM_SIGNED_24(24), PCM_SIGNED_32(32), FLOAT_32(24), FLOAT_64(53)
}

/** Packed, interleaved, little-endian samples; integer full scale is [-1, 1). */
enum class PcmEncoding(val bytesPerSample: Int, val precision: SamplePrecision, val integerBits: Int = 0) {
    SIGNED_16_LE(2, SamplePrecision.PCM_SIGNED_16, 16),
    SIGNED_24_LE(3, SamplePrecision.PCM_SIGNED_24, 24),
    SIGNED_32_LE(4, SamplePrecision.PCM_SIGNED_32, 32),
    FLOAT_32_LE(4, SamplePrecision.FLOAT_32),
    FLOAT_64_LE(8, SamplePrecision.FLOAT_64)
}

data class AudioStreamFormat(val sampleRate: Int, val channelLayout: ChannelLayout) {
    init { require(sampleRate in 8_000..768_000) { "Unsupported sample rate" } }
    val channelCount: Int get() = channelLayout.channelCount
}

/**
 * One audio-thread-owned, reusable block. Only [frameCount] frames are valid. Samples are
 * normalized, interleaved binary64 values and may exceed unity between nodes. Promoting a
 * float32 decoder sample does not restore precision absent from that decoder output.
 *
 * Presentation time and first frame position describe the FIRST INPUT frame, before node
 * latency. Unknown values use [AUDIO_TIME_UNSET]. Callers must not retain samples across calls.
 */
class AudioBlock(val format: AudioStreamFormat, val capacityFrames: Int) {
    init { require(capacityFrames in 1..MAX_BLOCK_FRAMES) }
    val schemaVersion: Int = AUDIO_BLOCK_SCHEMA_VERSION
    val precision: SamplePrecision = SamplePrecision.FLOAT_64
    val samples = DoubleArray(capacityFrames * format.channelCount)
    var frameCount: Int = 0; private set
    var presentationTimeUs: Long = AUDIO_TIME_UNSET; private set
    var firstFramePosition: Long = AUDIO_TIME_UNSET; private set
    val sampleCount: Int get() = frameCount * format.channelCount

    fun begin(frames: Int, presentationTimeUs: Long = AUDIO_TIME_UNSET, firstFramePosition: Long = AUDIO_TIME_UNSET) {
        require(frames in 0..capacityFrames)
        require(firstFramePosition == AUDIO_TIME_UNSET || firstFramePosition >= 0)
        this.frameCount = frames
        this.presentationTimeUs = presentationTimeUs
        this.firstFramePosition = firstFramePosition
    }

    companion object { const val MAX_BLOCK_FRAMES = 8192 }
}

/** Unknown IIR tail is explicit: null is unbounded, zero is no stored tail. */
data class NodeCapabilities(
    val arithmeticPrecision: SamplePrecision,
    val statePrecision: SamplePrecision,
    val latencyFrames: Int = 0,
    val tailFrames: Long? = 0,
    val supportsInPlace: Boolean = true
) {
    init {
        require(latencyFrames >= 0)
        require(tailFrames == null || tailFrames >= 0)
    }
}

/** An output adapter must advertise its actual negotiated format, not a requested UI mode. */
data class AudioOutputCapabilities(
    val encoding: PcmEncoding,
    val format: AudioStreamFormat,
    val clockOwner: String,
    val supportsUnchangedSampleBypass: Boolean,
    val observed: Boolean
) { init { require(clockOwner.isNotBlank()) } }

interface AudioOutputAdapter {
    val capabilities: AudioOutputCapabilities
    /** Called by the output owner; encoding conversion belongs at this boundary only. */
    fun write(block: AudioBlock): Int
}
