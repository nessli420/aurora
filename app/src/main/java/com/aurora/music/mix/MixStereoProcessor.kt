package com.aurora.music.mix

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The per-deck EQ/pan chain expects stereo. Preserve mono tracks by duplicating each sample. */
@androidx.media3.common.util.UnstableApi
class MixStereoProcessor : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT && inputAudioFormat.channelCount == 1)
            AudioFormat(inputAudioFormat.sampleRate, 2, C.ENCODING_PCM_16BIT) else AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val output = replaceOutputBuffer(inputBuffer.remaining() * 2).order(ByteOrder.nativeOrder())
        inputBuffer.order(ByteOrder.nativeOrder())
        while (inputBuffer.remaining() >= 2) { val sample = inputBuffer.short; output.putShort(sample); output.putShort(sample) }
        output.flip()
    }
}
