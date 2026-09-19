package com.aurora.music.playback.usb

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.aurora.music.playback.PcmLevelMeter
import com.aurora.music.playback.PrecisionBlockProcessor
import com.aurora.music.playback.engine.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class UsbGraphProcessor(
    val engine: PrecisionBlockProcessor,
    private val policy: () -> OutputRatePolicy,
    private val before: PcmLevelMeter? = null,
    private val after: PcmLevelMeter? = null,
) {
    private var source = Format.Builder().build()
    private var inputEncoding = PcmEncoding.FLOAT_32_LE
    private var outputEncoding = PcmEncoding.SIGNED_24_LE
    private var decoded: AudioBlock? = null
    private var input: AudioBlock? = null
    private var converted: AudioBlock? = null
    private var resampler: BandlimitedResampler? = null
    private var held = DoubleArray(0)
    private var read = 0
    private var heldFrames = 0
    private var headFrames = 0
    private var endFrames = 0
    private var engineEnded = false
    private var resamplerEnded = false
    private var ended = false
    private var submitted = 0L
    private var produced = 0L
    private var startTimeUs = C.TIME_UNSET
    private var pendingEngineOutput: AudioBlock? = null
    private var pendingEngineOffset = 0
    private val bytes = ByteBuffer.allocateDirect(8192 * 8).order(ByteOrder.LITTLE_ENDIAN).apply { limit(0) }
    private val dither = TpdfDither()
    @Volatile var volume = 1.0
    var outputFormat: Format = Format.Builder().build(); private set
    var rateFallbackReason: String? = null; private set
    var outputTimeUs: Long = C.TIME_UNSET; private set
    val resamplingLatencyFrames: Int get() = resampler?.lookaheadFrames ?: 0
    val inputDurationUs: Long get() = if (source.sampleRate > 0) submitted * 1_000_000L / source.sampleRate else 0
    val isEnded: Boolean get() = ended && engineEnded && engine.isEnded && pendingEngineOutput == null &&
        (resampler?.isEnded != false) && !bytes.hasRemaining()

    fun configure(format: Format, supportedRates: IntArray, validBits: Int, trimHead: Boolean = true): Format {
        require(format.sampleMimeType == MimeTypes.AUDIO_RAW && format.channelCount in 1..2) { "Processed USB requires mono or stereo PCM." }
        require(format.sampleRate in 8_000..768_000 && format.encoderDelay in 0..131072 && format.encoderPadding in 0..131072) {
            "Unsupported USB stream format."
        }
        inputEncoding = encoding(format.pcmEncoding) ?: error("Unsupported USB PCM encoding.")
        outputEncoding = when (validBits) {
            16 -> PcmEncoding.SIGNED_16_LE
            24 -> PcmEncoding.SIGNED_24_LE
            32 -> PcmEncoding.SIGNED_32_LE
            else -> error("Unsupported USB sample precision.")
        }
        source = format
        require(supportedRates.isNotEmpty()) { "USB clock capabilities are unavailable." }
        val requested = OutputRateNegotiator.choose(format.sampleRate, policy(), supportedRates)
        val chosen = if (requested.sampleRate in supportedRates) requested else {
            val family = if (format.sampleRate % 11025 == 0) 44100 else 48000
            val familyRates = supportedRates.filter { it % family == 0 }
            val rate = (familyRates.ifEmpty { supportedRates.toList() }).minBy { kotlin.math.abs(it.toLong() - format.sampleRate) }
            OutputRateDecision(rate, "${requested.sampleRate} Hz is unavailable; using $rate Hz.")
        }
        rateFallbackReason = chosen.fallbackReason
        outputFormat = format.buildUpon().setSampleRate(chosen.sampleRate).setChannelCount(2)
            .setPcmEncoding(androidEncoding(outputEncoding)).setEncoderDelay(0).setEncoderPadding(0).build()
        decoded = AudioBlock(AudioStreamFormat(format.sampleRate, if (format.channelCount == 1) ChannelLayout.MONO else ChannelLayout.STEREO), 256)
        input = AudioBlock(AudioStreamFormat(format.sampleRate, ChannelLayout.STEREO), 256)
        converted = AudioBlock(AudioStreamFormat(chosen.sampleRate, ChannelLayout.STEREO), 8192)
        resampler = if (chosen.sampleRate != format.sampleRate) BandlimitedResampler(format.sampleRate, chosen.sampleRate) else null
        endFrames = format.encoderPadding
        held = DoubleArray((endFrames + 256) * 2)
        engine.configure(format.sampleRate)
        clear(trimHead)
        before?.configure(format.pcmEncoding, format.channelCount, format.sampleRate)
        after?.configure(outputFormat.pcmEncoding, 2, chosen.sampleRate)
        return outputFormat
    }

    fun queueInput(buffer: ByteBuffer, timeUs: Long, mediaTimeUs: Long = timeUs): Boolean {
        check(!ended)
        if (bytes.hasRemaining() || pendingEngineOutput != null) return false
        if (!submitHeld()) return false
        val block = checkNotNull(decoded)
        val stride = source.channelCount * inputEncoding.bytesPerSample
        require(buffer.remaining() % stride == 0) { "Incomplete USB PCM frame." }
        if (startTimeUs == C.TIME_UNSET) {
            startTimeUs = timeUs
            if (mediaTimeUs <= 0) headFrames = source.encoderDelay
        }
        val count = minOf(256, buffer.remaining() / stride)
        if (count == 0) return true
        val snapshot = buffer.duplicate().apply { limit(position() + count * stride) }
        before?.observe(snapshot, snapshot.position(), snapshot.limit(), timeUs)
        block.begin(count)
        PcmBoundary.decode(buffer, inputEncoding, block)
        val skip = minOf(headFrames, count)
        headFrames -= skip
        val capacity = held.size / 2
        for (frame in skip until count) {
            val index = (read + heldFrames) % capacity
            held[index * 2] = block.samples[frame * source.channelCount]
            held[index * 2 + 1] = block.samples[frame * source.channelCount + source.channelCount - 1]
            heldFrames++
        }
        submitHeld()
        return !buffer.hasRemaining()
    }

    private fun submitHeld(): Boolean {
        val count = minOf(256, heldFrames - endFrames)
        if (count <= 0) return true
        val block = checkNotNull(input)
        block.begin(count, if (startTimeUs == C.TIME_UNSET) AUDIO_TIME_UNSET else startTimeUs + submitted * 1_000_000L / source.sampleRate, submitted)
        val capacity = held.size / 2
        for (frame in 0 until count) {
            val index = (read + frame) % capacity
            block.samples[frame * 2] = held[index * 2]
            block.samples[frame * 2 + 1] = held[index * 2 + 1]
        }
        if (!engine.queueInput(block)) return false
        read = (read + count) % capacity
        heldFrames -= count
        submitted += count
        return true
    }

    fun getOutput(): ByteBuffer? {
        if (bytes.hasRemaining()) return bytes
        repeat(16) {
            val converter = resampler
            if (converter != null) {
                val block = checkNotNull(converted)
                val frames = converter.readOutput(block.samples, 0, block.capacityFrames)
                if (frames > 0) { block.begin(frames); return encode(block) }
            }
            val ready = pendingEngineOutput ?: engine.getOutput()?.also { pendingEngineOutput = it; pendingEngineOffset = 0 }
            if (ready != null) {
                if (converter == null) {
                    pendingEngineOutput = null
                    return encode(ready)
                }
                pendingEngineOffset += converter.queueInput(ready.samples, pendingEngineOffset, ready.frameCount - pendingEngineOffset)
                if (pendingEngineOffset == ready.frameCount) pendingEngineOutput = null
            } else {
                if (heldFrames > endFrames && submitHeld()) return@repeat
                if (ended && !engineEnded && heldFrames <= endFrames) {
                    heldFrames = 0
                    engine.queueEndOfStream()
                    engineEnded = true
                    return@repeat
                }
                if (engineEnded && engine.isEnded && converter != null && !resamplerEnded) {
                    converter.queueEndOfInput(); resamplerEnded = true; return@repeat
                }
                return null
            }
        }
        return null
    }

    private fun encode(block: AudioBlock): ByteBuffer {
        val gain = volume.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
        if (gain != 1.0) for (i in 0 until block.sampleCount) block.samples[i] *= gain
        bytes.clear()
        outputTimeUs = if (startTimeUs == C.TIME_UNSET) C.TIME_UNSET else startTimeUs + produced * 1_000_000L / outputFormat.sampleRate
        PcmBoundary.encode(block, outputEncoding, bytes,
            if (policy().tpdfDither && (inputEncoding == PcmEncoding.FLOAT_32_LE ||
                inputEncoding.integerBits > outputEncoding.integerBits || engine.processingChangesSamples || resampler != null || gain != 1.0)) dither else null)
        bytes.flip()
        after?.observe(bytes, bytes.position(), bytes.limit(), outputTimeUs)
        produced += block.frameCount
        return bytes
    }

    fun queueEndOfStream() { ended = true }
    fun flush() { engine.flush(); clear(false) }
    fun reset() { engine.reset(); clear(false) }
    private fun clear(trimHead: Boolean) {
        bytes.clear().limit(0)
        read = 0; heldFrames = 0; headFrames = if (trimHead) source.encoderDelay else 0
        submitted = 0; produced = 0; startTimeUs = C.TIME_UNSET
        ended = false; engineEnded = false; resamplerEnded = false
        pendingEngineOutput = null; pendingEngineOffset = 0
        resampler?.reset()
    }

    companion object {
        fun encoding(value: Int): PcmEncoding? = when (value) {
            C.ENCODING_PCM_16BIT -> PcmEncoding.SIGNED_16_LE
            C.ENCODING_PCM_24BIT -> PcmEncoding.SIGNED_24_LE
            C.ENCODING_PCM_32BIT -> PcmEncoding.SIGNED_32_LE
            C.ENCODING_PCM_FLOAT -> PcmEncoding.FLOAT_32_LE
            else -> null
        }
        fun androidEncoding(value: PcmEncoding): Int = when (value) {
            PcmEncoding.SIGNED_16_LE -> C.ENCODING_PCM_16BIT
            PcmEncoding.SIGNED_24_LE -> C.ENCODING_PCM_24BIT
            PcmEncoding.SIGNED_32_LE -> C.ENCODING_PCM_32BIT
            else -> C.ENCODING_PCM_FLOAT
        }
    }
}
