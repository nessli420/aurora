package com.aurora.music.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/** Last app processor: samples after convolution, before Media3 speed/silence and player gain. */
@UnstableApi
class LevelMeterAudioProcessor(private val meter: PcmLevelMeter) : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        return if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT && inputAudioFormat.channelCount in 1..2)
            inputAudioFormat else AudioFormat.NOT_SET
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // Media3 drains the pipeline with its shared EMPTY_BUFFER; do not copy it into itself.
        if (!inputBuffer.hasRemaining()) return
        meter.observe(inputBuffer, inputBuffer.position(), inputBuffer.limit())
        val output = replaceOutputBuffer(inputBuffer.remaining())
        output.put(inputBuffer).flip()
    }

    override fun onFlush() {
        // configure() can negotiate the next track while old-format audio is still draining.
        // BaseAudioProcessor activates inputAudioFormat immediately before this callback.
        meter.configure(inputAudioFormat.encoding, inputAudioFormat.channelCount, inputAudioFormat.sampleRate)
    }
    override fun onReset() { meter.reset() }
}
