package com.aurora.music.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.aurora.music.playback.engine.AUDIO_TIME_UNSET
import com.aurora.music.playback.engine.AudioBlock
import com.aurora.music.playback.engine.AudioStreamFormat
import com.aurora.music.playback.engine.ChannelLayout
import com.aurora.music.playback.engine.PcmBoundary
import com.aurora.music.playback.engine.PcmEncoding
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Processes decoder PCM before Media3's ordinary processor chain can narrow it to PCM16.
 * Eligible stereo streams reach the delegate as float32; compatibility streams reach its
 * existing PCM16 processors, preserving Media3's trim, channel map and silence skipping.
 * The delegate must enable float output and AudioTrack playback parameters. USB and Mix use
 * their existing sinks. One owned output buffer bounds backpressure and survives retries.
 */
@UnstableApi
class PrecisionAudioSink(
    delegate: AudioSink,
    private val processor: PrecisionBlockProcessor,
    private val afterMeter: PcmLevelMeter,
) : ForwardingAudioSink(delegate) {
    @Volatile var precisionActive: Boolean = false
        private set
    @Volatile var fallbackReason: String? = null
        private set
    @Volatile var configuredOutputEncoding: Int = Format.NO_VALUE
        private set

    private data class Configuration(
        val format: Format,
        val bufferSize: Int,
        val channelMap: IntArray?,
        val block: AudioBlock?,
        val sameStream: Boolean = false,
    )

    private var active: Configuration? = null
    private var pending: Configuration? = null
    private var activePrecision = false
    private var requestedSkipSilence = false
    private var stickyCompatibility = false
    private var compatibilityAfterTrim = false
    private var tunneling = false
    private var sessionId = C.AUDIO_SESSION_ID_UNSET
    private var sourceBuffer: ByteBuffer? = null
    private var sourceStart = 0
    private var sourceTimeUs = C.TIME_UNSET
    private var sourceFrames = 0L
    private var discontinuityPending = false
    private var processorEos = false
    private var streamEnded = false
    private val output = ByteBuffer.allocateDirect(OUTPUT_FRAMES * 2 * 4)
        .order(ByteOrder.LITTLE_ENDIAN).apply { limit(0) }
    private var outputTimeUs = C.TIME_UNSET
    private var meteredOutputBytes = 0

    override fun supportsFormat(format: Format): Boolean =
        getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatSupport(format: Format): Int {
        // This sink promises decoded processing. Advertising compressed passthrough could let
        // the renderer route encoded data around both precision and compatibility effects.
        if (format.sampleMimeType != MimeTypes.AUDIO_RAW) return AudioSink.SINK_FORMAT_UNSUPPORTED
        if (format.sampleMimeType == MimeTypes.AUDIO_RAW && bytesPerSample(format.pcmEncoding) > 0) {
            // This also allows MediaCodecAudioRenderer to request float decoder output. The
            // final path is decided from configure's actual decoded format and trim metadata.
            return AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        }
        return AudioSink.SINK_FORMAT_UNSUPPORTED
    }

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        if (inputFormat.sampleMimeType != MimeTypes.AUDIO_RAW || bytesPerSample(inputFormat.pcmEncoding) == 0 ||
            inputFormat.channelCount !in 1..32 || inputFormat.sampleRate <= 0) {
            throw AudioSink.ConfigurationException("Unsupported decoded PCM format", inputFormat)
        }
        val block = if (inputFormat.channelCount == 2 && inputFormat.sampleRate in 8_000..768_000)
            AudioBlock(AudioStreamFormat(inputFormat.sampleRate, ChannelLayout.STEREO), INPUT_FRAMES)
        else null
        val next = Configuration(inputFormat, specifiedBufferSize, outputChannels?.copyOf(), block)
        stickyCompatibility = requestedSkipSilence
        if (inputFormat.encoderDelay != 0 || inputFormat.encoderPadding != 0) compatibilityAfterTrim = true
        pending = next
        // There cannot be old wrapper data on the first configuration. Later configurations
        // remain pending until old output and the old convolver's valid partial block drain.
        if (active == null) activatePending()
    }

    private fun reason(configuration: Configuration): String? = when {
        configuration.format.sampleMimeType != MimeTypes.AUDIO_RAW -> "Encoded audio uses the existing output path"
        configuration.block == null -> "Precision processing requires stereo PCM at a supported sample rate"
        pcmEncoding(configuration.format.pcmEncoding) == null -> "This PCM encoding uses the compatibility processor path"
        configuration.format.encoderDelay != 0 || configuration.format.encoderPadding != 0 ->
            "Encoder delay or padding requires the compatibility path for gapless trimming"
        configuration.channelMap?.let { it.size != 2 || it[0] != 0 || it[1] != 1 } == true ->
            "Channel mapping requires the compatibility processor path"
        compatibilityAfterTrim ->
            "Compatibility processing is retained after a padded stream until playback restarts, preserving gapless trimming"
        tunneling -> "Tunneled playback uses the compatibility output path"
        requestedSkipSilence -> "Silence skipping requires the compatibility processor path"
        stickyCompatibility -> "Compatibility processing is retained until the next stream after silence skipping"
        else -> null
    }

    private fun activatePending() {
        val next = pending ?: return
        val why = reason(next)
        val precision = why == null
        val encoding = if (precision) C.ENCODING_PCM_FLOAT else C.ENCODING_PCM_16BIT
        val delegateFormat = next.format.buildUpon().setPcmEncoding(encoding).apply {
            // A live transition can only leave an untrimmed precision stream. It must not
            // introduce another encoder trim point in the middle of that same stream.
            if (precision || next.sameStream) { setEncoderDelay(0); setEncoderPadding(0) }
        }.build()
        super.configure(delegateFormat, next.bufferSize, if (precision) null else next.channelMap)
        if (precision) {
            processor.configure(next.format.sampleRate)
            afterMeter.configure(C.ENCODING_PCM_FLOAT, 2, next.format.sampleRate)
        } else {
            processor.flush()
            afterMeter.reset()
        }
        super.setSkipSilenceEnabled(if (precision) false else requestedSkipSilence)
        active = next
        pending = null
        activePrecision = precision
        // PCM16 also uses the owned buffer (a byte-exact copy). This preserves frame offsets
        // when a live precision-to-compatibility transition splits one decoder buffer.
        precisionActive = false
        configuredOutputEncoding = encoding
        fallbackReason = why
        processorEos = false
        streamEnded = false
        if (!next.sameStream) {
            sourceBuffer = null
            sourceFrames = 0
        }
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (!advanceBoundary()) return false
        val configuration = checkNotNull(active) { "Audio sink is not configured" }
        if (sourceBuffer !== buffer) {
            check(sourceBuffer == null) { "A pending decoder buffer must be retried before another buffer" }
            sourceBuffer = buffer
            sourceStart = buffer.position()
            sourceTimeUs = presentationTimeUs
        }
        val stride = bytesPerSample(configuration.format.pcmEncoding) * configuration.format.channelCount
        require(stride > 0 && buffer.remaining() % stride == 0) { "Incomplete PCM frame" }
        var iterations = 0
        while (iterations++ < MAX_BLOCKS_PER_CALL) {
            if (!writeOutput()) return false
            if (activePrecision && takeProcessorOutput()) continue
            if (!buffer.hasRemaining()) {
                sourceBuffer = null
                return true
            }
            val timeUs = addFrames(sourceTimeUs, (buffer.position() - sourceStart).toLong() / stride,
                configuration.format.sampleRate)
            if (activePrecision) {
                val block = checkNotNull(configuration.block)
                val frames = minOf(INPUT_FRAMES, buffer.remaining() / stride)
                block.begin(frames, toBlockTime(timeUs), sourceFrames)
                val before = buffer.position()
                PcmBoundary.decode(buffer, checkNotNull(pcmEncoding(configuration.format.pcmEncoding)), block)
                if (!processor.queueInput(block)) {
                    // queueInput's false contract leaves the block unprocessed/unconsumed.
                    buffer.position(before)
                    return false
                }
                precisionActive = true
                sourceFrames += frames
            } else {
                val frames = minOf(INPUT_FRAMES, buffer.remaining() / stride,
                    output.capacity() / (configuration.format.channelCount * 2))
                require(frames > 0) { "Unsupported PCM channel count" }
                output.clear()
                convertToPcm16(buffer, output, configuration.format.pcmEncoding,
                    frames * configuration.format.channelCount)
                output.flip()
                outputTimeUs = timeUs
                meteredOutputBytes = 0
                sourceFrames += frames
            }
        }
        return false // Bound callback work even if a decoder supplies an unusually large buffer.
    }

    /** Finish old wrapper data before the delegate sees a new format or discontinuity. */
    private fun advanceBoundary(): Boolean {
        if (pending == null && !discontinuityPending) return true
        if (!drainProcessor()) return false
        if (pending != null) activatePending()
        if (discontinuityPending) {
            super.handleDiscontinuity()
            processor.flush()
            processorEos = false
            streamEnded = false
            sourceBuffer = null
            sourceFrames = 0
            discontinuityPending = false
        }
        return true
    }

    private fun takeProcessorOutput(): Boolean {
        val block = processor.getOutput() ?: return false
        check(block.frameCount <= OUTPUT_FRAMES && block.format.channelCount == 2)
        output.clear()
        PcmBoundary.encode(block, PcmEncoding.FLOAT_32_LE, output)
        output.flip()
        outputTimeUs = if (block.presentationTimeUs == AUDIO_TIME_UNSET) C.TIME_UNSET else block.presentationTimeUs
        meteredOutputBytes = 0
        return output.hasRemaining()
    }

    private fun writeOutput(): Boolean {
        if (!output.hasRemaining()) return true
        val accepted = super.handleBuffer(output, outputTimeUs, 1)
        if (activePrecision) {
            // An unusual partial-byte write is measured when its whole frame has completed.
            val completeBytes = output.position() / 8 * 8
            if (completeBytes > meteredOutputBytes) {
                afterMeter.observe(output, meteredOutputBytes, completeBytes,
                    addFrames(outputTimeUs, meteredOutputBytes.toLong() / 8, checkNotNull(active).format.sampleRate))
                meteredOutputBytes = completeBytes
            }
        }
        if (accepted) {
            // A delegate may recover from a stalled AudioTrack by flushing and accepting the
            // buffer without advancing through every byte. Respect its acceptance result.
            output.clear().limit(0)
        }
        return accepted
    }

    private fun drainProcessor(): Boolean {
        if (!writeOutput()) return false
        if (!activePrecision) return true
        if (!processorEos) { processor.queueEndOfStream(); processorEos = true }
        var iterations = 0
        while (iterations++ < MAX_BLOCKS_PER_CALL) {
            if (!takeProcessorOutput()) return processor.isEnded
            if (!writeOutput()) return false
        }
        return false
    }

    override fun playToEndOfStream() {
        if (!advanceBoundary() || !drainProcessor()) return
        streamEnded = true
        super.playToEndOfStream()
    }

    override fun isEnded(): Boolean = streamEnded && !output.hasRemaining() &&
        (!activePrecision || processor.isEnded) && super.isEnded()

    override fun hasPendingData(): Boolean = output.hasRemaining() ||
        (activePrecision && processor.hasPendingData) || super.hasPendingData()

    override fun handleDiscontinuity() { discontinuityPending = true }

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        requestedSkipSilence = skipSilenceEnabled
        if (skipSilenceEnabled && activePrecision) {
            stickyCompatibility = true
            if (pending == null) pending = active?.copy(sameStream = true)
            // Apply only after the old precision output drains. Once changed, retain PCM16
            // until the next stream even if the user immediately disables silence skipping.
        } else if (!activePrecision) {
            super.setSkipSilenceEnabled(skipSilenceEnabled)
        }
    }

    private fun clearBufferedState() {
        output.clear().limit(0)
        meteredOutputBytes = 0
        sourceBuffer = null
        sourceStart = 0
        sourceTimeUs = C.TIME_UNSET
        sourceFrames = 0
        processorEos = false
        streamEnded = false
        discontinuityPending = false
        precisionActive = false
        processor.flush()
        afterMeter.reset()
    }

    override fun flush() { super.flush(); clearBufferedState() }

    override fun reset() {
        super.reset()
        clearBufferedState()
        processor.reset()
        active = null
        pending = null
        activePrecision = false
        stickyCompatibility = requestedSkipSilence
        compatibilityAfterTrim = false
        configuredOutputEncoding = Format.NO_VALUE
        fallbackReason = null
    }

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        val changes = super.getAudioAttributes() != audioAttributes
        super.setAudioAttributes(audioAttributes)
        if (changes && !tunneling) clearBufferedState()
    }

    override fun setAudioSessionId(audioSessionId: Int) {
        // The delegate can allocate a session id internally after receiving UNSET. A later
        // UNSET request may therefore flush it even though our last requested id is unchanged.
        val changes = sessionId != audioSessionId || audioSessionId == C.AUDIO_SESSION_ID_UNSET && active != null
        sessionId = audioSessionId
        super.setAudioSessionId(audioSessionId)
        if (changes) clearBufferedState()
    }

    override fun enableTunnelingV21() {
        super.enableTunnelingV21()
        if (!tunneling) {
            tunneling = true
            clearBufferedState()
            if (pending == null) pending = active?.copy(sameStream = true)
        }
    }

    override fun disableTunneling() {
        super.disableTunneling()
        if (tunneling) {
            tunneling = false
            clearBufferedState()
            if (pending == null) pending = active?.copy(sameStream = true)
        }
    }

    override fun release() { clearBufferedState(); processor.reset(); super.release() }

    companion object {
        private const val INPUT_FRAMES = 256
        private const val OUTPUT_FRAMES = 1024
        private const val MAX_BLOCKS_PER_CALL = 64

        private fun pcmEncoding(encoding: Int): PcmEncoding? = when (encoding) {
            C.ENCODING_PCM_16BIT -> PcmEncoding.SIGNED_16_LE
            C.ENCODING_PCM_24BIT -> PcmEncoding.SIGNED_24_LE
            C.ENCODING_PCM_32BIT -> PcmEncoding.SIGNED_32_LE
            C.ENCODING_PCM_FLOAT -> PcmEncoding.FLOAT_32_LE
            else -> null
        }

        private fun bytesPerSample(encoding: Int): Int = when (encoding) {
            C.ENCODING_PCM_8BIT -> 1
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 2
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 4
            else -> 0
        }

        /** Match Media3 ToInt16PcmAudioProcessor's truncation and float scaling convention. */
        private fun convertToPcm16(input: ByteBuffer, output: ByteBuffer, encoding: Int, samples: Int) {
            var i = 0
            while (i++ < samples) {
                when (encoding) {
                    C.ENCODING_PCM_8BIT -> { output.put(0); output.put(((input.get().toInt() and 255) - 128).toByte()) }
                    C.ENCODING_PCM_16BIT -> { output.put(input.get()); output.put(input.get()) }
                    C.ENCODING_PCM_16BIT_BIG_ENDIAN -> { val high = input.get(); output.put(input.get()); output.put(high) }
                    C.ENCODING_PCM_24BIT -> { input.get(); output.put(input.get()); output.put(input.get()) }
                    C.ENCODING_PCM_24BIT_BIG_ENDIAN -> { val high = input.get(); val low = input.get(); input.get(); output.put(low); output.put(high) }
                    C.ENCODING_PCM_32BIT -> { input.get(); input.get(); output.put(input.get()); output.put(input.get()) }
                    C.ENCODING_PCM_32BIT_BIG_ENDIAN -> { val high = input.get(); val low = input.get(); input.get(); input.get(); output.put(low); output.put(high) }
                    C.ENCODING_PCM_FLOAT -> {
                        val bits = (input.get().toInt() and 255) or ((input.get().toInt() and 255) shl 8) or
                            ((input.get().toInt() and 255) shl 16) or (input.get().toInt() shl 24)
                        val value = Float.fromBits(bits)
                        val pcm = if (value.isFinite()) (value.coerceIn(-1f, 1f) * 32767f).toInt() else 0
                        output.put(pcm.toByte()); output.put((pcm shr 8).toByte())
                    }
                    else -> error("Unsupported PCM conversion")
                }
            }
        }

        private fun toBlockTime(timeUs: Long): Long = if (timeUs == C.TIME_UNSET) AUDIO_TIME_UNSET else timeUs
        private fun addFrames(timeUs: Long, frames: Long, rate: Int): Long =
            if (timeUs == C.TIME_UNSET || rate <= 0) C.TIME_UNSET else timeUs + frames * 1_000_000L / rate
    }
}
