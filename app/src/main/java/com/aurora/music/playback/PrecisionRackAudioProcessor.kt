package com.aurora.music.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import com.aurora.music.playback.engine.AudioBlock
import com.aurora.music.playback.engine.AudioStreamFormat
import com.aurora.music.playback.engine.ChannelLayout
import com.aurora.music.playback.engine.PcmBoundary
import com.aurora.music.playback.engine.PcmEncoding
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** PCM16 compatibility boundary around the same binary64 global engine used by float output. */
@UnstableApi
class PrecisionRackAudioProcessor(val engine: PrecisionBlockProcessor = PrecisionBlockProcessor()) : AudioProcessor {
    private var pendingFormat = AudioFormat.NOT_SET
    private var activeFormat = AudioFormat.NOT_SET
    private var block: AudioBlock? = null
    private val pcm = ByteBuffer.allocateDirect(1024 * 4).order(ByteOrder.LITTLE_ENDIAN).apply { limit(0) }
    private var output: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var ended = false

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        pendingFormat = if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT &&
            inputAudioFormat.channelCount == 2 && inputAudioFormat.sampleRate in 8_000..768_000)
            inputAudioFormat else AudioFormat.NOT_SET
        return pendingFormat
    }

    override fun isActive() = pendingFormat != AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (pcm.hasRemaining() || ended) return
        if (emit()) return
        if (!inputBuffer.hasRemaining()) return
        require(activeFormat != AudioFormat.NOT_SET && inputBuffer.remaining() % 4 == 0)
        val owned = checkNotNull(block)
        owned.begin(minOf(256, inputBuffer.remaining() / 4))
        val start = inputBuffer.position()
        PcmBoundary.decode(inputBuffer, PcmEncoding.SIGNED_16_LE, owned)
        if (!engine.queueInput(owned)) inputBuffer.position(start)
        emit()
    }

    private fun emit(): Boolean {
        if (pcm.hasRemaining()) return false
        val ready = engine.getOutput() ?: return false
        pcm.clear()
        PcmBoundary.encode(ready, PcmEncoding.SIGNED_16_LE, pcm)
        pcm.flip()
        output = pcm
        return true
    }

    override fun queueEndOfStream() { ended = true; engine.queueEndOfStream(); emit() }

    override fun getOutput(): ByteBuffer {
        if (!pcm.hasRemaining()) emit()
        val result = output
        output = AudioProcessor.EMPTY_BUFFER
        return result
    }

    override fun isEnded() = ended && !pcm.hasRemaining() && engine.isEnded

    override fun flush() {
        engine.flush()
        activeFormat = pendingFormat
        pcm.clear().limit(0); output = AudioProcessor.EMPTY_BUFFER; ended = false
        if (activeFormat != AudioFormat.NOT_SET) {
            block = AudioBlock(AudioStreamFormat(activeFormat.sampleRate, ChannelLayout.STEREO), 256)
            engine.configure(activeFormat.sampleRate)
        } else { block = null; engine.reset() }
    }

    override fun reset() {
        engine.reset()
        pendingFormat = AudioFormat.NOT_SET; activeFormat = AudioFormat.NOT_SET; block = null
        pcm.clear().limit(0); output = AudioProcessor.EMPTY_BUFFER; ended = false
    }
}
