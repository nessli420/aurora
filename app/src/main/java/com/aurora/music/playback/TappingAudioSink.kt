package com.aurora.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer

// reads with absolute indexing never mutates the buffer so playback is unaffected
@OptIn(UnstableApi::class)
class TappingAudioSink(
    delegate: AudioSink,
    private val controller: VisualizerController,
    private val meter: PcmLevelMeter = PcmLevelMeter(),
    private val onVolume: (Float) -> Unit = {},
    private val onConfigured: (Format?) -> Unit = {},
) : ForwardingAudioSink(delegate) {

    private var encoding: Int = Format.NO_VALUE
    private var sampleRate: Int = Format.NO_VALUE
    private var channelCount: Int = Format.NO_VALUE
    private var pendingBuffer: ByteBuffer? = null
    private var pendingStart = 0

    override fun setVolume(volume: Float) {
        super.setVolume(volume)
        onVolume(volume)
    }

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        encoding = inputFormat.pcmEncoding
        sampleRate = inputFormat.sampleRate
        channelCount = inputFormat.channelCount
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
        meter.configure(encoding, channelCount, sampleRate)
        pendingBuffer = null
        onConfigured(inputFormat)
    }

    override fun reset() {
        super.reset()
        meter.reset()
        pendingBuffer = null
        onConfigured(null)
    }

    override fun flush() {
        super.flush()
        meter.reset()
        pendingBuffer = null
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        val start = buffer.position()
        if (pendingBuffer !== buffer) { pendingBuffer = buffer; pendingStart = start }
        val consumed = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        val end = buffer.position()
        // A sink can retain/retry an input. Count only the newly consumed bytes, once.
        val bytesPerSample = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> 0
        }
        val offsetUs = if (bytesPerSample > 0 && channelCount > 0 && sampleRate > 0)
            (start - pendingStart) / (bytesPerSample * channelCount) * 1_000_000L / sampleRate else 0L
        meter.observe(buffer, start, end, if (presentationTimeUs == C.TIME_UNSET) C.TIME_UNSET else presentationTimeUs + offsetUs)
        if (end > start && controller.active) controller.pushPcm(buffer, encoding, channelCount, sampleRate, start, end)
        if (consumed) pendingBuffer = null
        return consumed
    }
}
