package com.aurora.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer

/**
 * A transparent [AudioSink] wrapper that copies the decoded PCM passing through it into the
 * [VisualizerController] before forwarding to the real sink. Used in normal (16-bit) and hi-res
 * (float) playback; the bit-perfect path taps [com.decent.usbaudio.media3.UsbAudioSink] directly.
 *
 * The tap reads with absolute indexing and never mutates the buffer, so playback is unaffected, and
 * it no-ops entirely when the visualizer isn't running.
 */
@OptIn(UnstableApi::class)
class TappingAudioSink(
    delegate: AudioSink,
    private val controller: VisualizerController,
) : ForwardingAudioSink(delegate) {

    private var encoding: Int = C.ENCODING_PCM_16BIT
    private var sampleRate: Int = 48000
    private var channelCount: Int = 2

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        if (inputFormat.pcmEncoding != Format.NO_VALUE) encoding = inputFormat.pcmEncoding
        if (inputFormat.sampleRate != Format.NO_VALUE) sampleRate = inputFormat.sampleRate
        if (inputFormat.channelCount != Format.NO_VALUE) channelCount = inputFormat.channelCount
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        if (controller.active) controller.pushPcm(buffer, encoding, channelCount, sampleRate)
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }
}
