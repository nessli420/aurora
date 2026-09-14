package com.aurora.music.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.aurora.music.playback.engine.AudioBlock
import com.aurora.music.playback.engine.AudioStreamFormat
import com.aurora.music.playback.engine.ChannelLayout
import com.aurora.music.playback.engine.PrecisionDspCoeffBuilder
import com.aurora.music.playback.engine.PrecisionDspCoefficients
import com.aurora.music.playback.engine.PrecisionEffectsKernel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

/**
 * Media3's current compatibility boundary remains PCM16 stereo (Sonic/silence skipping follow it).
 * The reusable effects kernel and its coefficients/histories are binary64; this adapter does not
 * claim to preserve high-resolution decoder samples already narrowed by DefaultAudioSink.
 */
@UnstableApi
class AuroraDspProcessor : BaseAudioProcessor() {
    @Volatile var enabled: Boolean = false
    /** Observed processing of the active format, independent of Media3's pending isActive value. */
    @Volatile var processingActive: Boolean = false
        private set

    private data class Prepared(
        val params: DspParams = DspParams(),
        val activeRate: Int = 0,
        val pendingRate: Int = 0,
        val active: PrecisionDspCoefficients? = null,
        val pending: PrecisionDspCoefficients? = null,
    )
    private class Processing(val kernel: PrecisionEffectsKernel) {
        val block = AudioBlock(kernel.format, 256)
    }

    private val prepared = AtomicReference(Prepared())
    private var activeProcessing: Processing? = null
    private var pendingProcessing: Processing? = null

    /** Control-thread preparation; the audio callback only reads one immutable publication. */
    fun update(p: DspParams) {
        val snapshot = p.copy(graphic = p.graphic.copyOf(), graphicFreqs = p.graphicFreqs.copyOf(),
            parametric = p.parametric.toList())
        while (true) {
            val previous = prepared.get()
            val active = if (previous.activeRate > 0) PrecisionDspCoeffBuilder.build(snapshot, previous.activeRate) else null
            val pending = if (previous.pendingRate == previous.activeRate) active
                else if (previous.pendingRate > 0) PrecisionDspCoeffBuilder.build(snapshot, previous.pendingRate) else null
            if (prepared.compareAndSet(previous, previous.copy(params = snapshot, active = active, pending = pending))) return
        }
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        val supported = inputAudioFormat.channelCount == 2 && inputAudioFormat.encoding == C.ENCODING_PCM_16BIT &&
            inputAudioFormat.sampleRate in 8_000..768_000
        val pendingRate = if (supported) inputAudioFormat.sampleRate else 0
        pendingProcessing = if (supported) Processing(PrecisionEffectsKernel(
            AudioStreamFormat(pendingRate, ChannelLayout.STEREO))) else null
        while (true) {
            val previous = prepared.get()
            val pending = if (pendingRate == previous.activeRate) previous.active
                else if (pendingRate > 0) PrecisionDspCoeffBuilder.build(previous.params, pendingRate) else null
            if (prepared.compareAndSet(previous, previous.copy(pendingRate = pendingRate, pending = pending))) break
        }
        // Do not replace coefficients, smoothing or histories of the still-draining active format.
        return if (supported) inputAudioFormat else AudioFormat.NOT_SET
    }

    override fun onFlush() {
        processingActive = false
        activeProcessing = pendingProcessing
        activeProcessing?.kernel?.reset()
        while (true) {
            val previous = prepared.get()
            if (prepared.compareAndSet(previous, previous.copy(activeRate = previous.pendingRate, active = previous.pending))) break
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return // Media3 may pass the shared EMPTY_BUFFER while draining.
        val output = replaceOutputBuffer(remaining)
        val processing = activeProcessing
        val coefficients = prepared.get().active
        if (!enabled || processing == null || coefficients == null) {
            processingActive = false
            output.put(inputBuffer)
            output.flip()
            return
        }
        require(remaining % 4 == 0) { "Incomplete stereo PCM16 frame" }
        processingActive = true
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        output.order(ByteOrder.LITTLE_ENDIAN)
        val block = processing.block
        while (inputBuffer.hasRemaining()) {
            val frames = minOf(inputBuffer.remaining() / 4, block.capacityFrames)
            block.begin(frames)
            var i = 0
            while (i < frames * 2) block.samples[i++] = inputBuffer.short / 32768.0
            processing.kernel.process(block, coefficients)
            i = 0
            while (i < frames * 2) output.putShort(toLegacyPcm16(block.samples[i++]))
        }
        output.flip()
    }

    override fun onReset() {
        processingActive = false
        activeProcessing = null
        pendingProcessing = null
        while (true) {
            val previous = prepared.get()
            if (prepared.compareAndSet(previous, Prepared(params = previous.params))) break
        }
    }

    private fun toLegacyPcm16(sample: Double): Short {
        // Preserve the existing endpoint convention and nearest rounding (ties toward +infinity).
        val value = sample * 32767.0
        return when {
            value >= 32767.0 -> 32767
            value <= -32768.0 -> -32768
            else -> Math.round(value).toInt()
        }.toShort()
    }
}
