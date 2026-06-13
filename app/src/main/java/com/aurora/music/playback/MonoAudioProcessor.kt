package com.aurora.music.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Downmixes 16-bit stereo PCM to dual mono by averaging L/R when [enabled]. Always present in the
 * chain; passes through unchanged when disabled, so toggling needs no pipeline rebuild.
 */
@UnstableApi
class MonoAudioProcessor : BaseAudioProcessor() {

    @Volatile
    var enabled: Boolean = false

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        return if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT && inputAudioFormat.channelCount == 2) {
            inputAudioFormat
        } else {
            AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val output = replaceOutputBuffer(remaining)
        if (!enabled) {
            output.put(inputBuffer)
        } else {
            inputBuffer.order(ByteOrder.nativeOrder())
            output.order(ByteOrder.nativeOrder())
            val limit = inputBuffer.limit()
            var p = inputBuffer.position()
            while (p + 4 <= limit) {
                val l = inputBuffer.getShort(p).toInt()
                val r = inputBuffer.getShort(p + 2).toInt()
                val m = ((l + r) / 2).toShort()
                output.putShort(m)
                output.putShort(m)
                p += 4
            }
            inputBuffer.position(limit)
        }
        output.flip()
    }
}
