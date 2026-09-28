package com.aurora.music.playback.chain

import com.aurora.music.playback.ConvolutionPreparationState
import com.aurora.music.playback.PrecisionBlockProcessor
import com.aurora.music.playback.engine.AUDIO_TIME_UNSET
import com.aurora.music.playback.engine.AudioBlock
import com.aurora.music.playback.engine.AudioStreamFormat
import com.aurora.music.playback.engine.BandlimitedResampler
import com.aurora.music.playback.engine.ChannelLayout
import com.aurora.music.playback.engine.OutputDitherMode
import com.aurora.music.playback.engine.OutputRatePolicy
import com.aurora.music.playback.engine.PcmEncoding
import com.aurora.music.playback.engine.SamplePrecision
import kotlin.math.roundToInt

data class ChainFormat(
    val sourceRate: Int,
    val outputRate: Int,
    val encoding: PcmEncoding = PcmEncoding.FLOAT_32_LE,
    val sourcePrecision: SamplePrecision = SamplePrecision.FLOAT_64,
    val policy: OutputRatePolicy = OutputRatePolicy(),
    val speed: Double = 1.0,
) {
    init {
        require(sourceRate in 8_000..768_000 && outputRate in 8_000..768_000) { "Unsupported chain sample rate" }
        require(speed in 0.25..4.0 && resampleRate in 8_000..768_000) { "Unsupported playback speed" }
    }

    val resampleRate: Int get() = if (speed == 1.0) sourceRate else (sourceRate * speed).roundToInt()
    val resampling: Boolean get() = resampleRate != outputRate
    val holdsSource: Boolean get() = encoding.holds(sourcePrecision)
}

fun PcmEncoding.holds(precision: SamplePrecision): Boolean = when (this) {
    PcmEncoding.FLOAT_64_LE -> true
    PcmEncoding.FLOAT_32_LE -> precision.significandBits <= 24
    else -> precision != SamplePrecision.FLOAT_32 && precision != SamplePrecision.FLOAT_64 && precision.significandBits <= integerBits
}

data class DspChainReport(
    val processingChangesSamples: Boolean,
    val processingActive: Boolean,
    val rackActive: Boolean,
    val description: String,
    val preparation: ConvolutionPreparationState,
    val preparationFailure: String?,
    val convolutionUnavailableReason: String?,
    val latencyFrames: Int,
    val tailFrames: Int,
    val resampling: Boolean,
    val resamplerLookaheadFrames: Int,
    val ditherLabel: String?,
    val bitExact: Boolean,
)

class DspChain(val engine: PrecisionBlockProcessor = PrecisionBlockProcessor()) {
    var format: ChainFormat? = null; private set
    private var input: AudioBlock? = null
    private var resampler: BandlimitedResampler? = null
    private var pending: AudioBlock? = null
    private var pendingOffset = 0
    private var ending = false
    private var resamplerEnded = false
    var inputFrames = 0L; private set
    var outputFrames = 0L; private set

    val latencyFrames: Int get() = format?.let { (engine.rackLatencyFrames.toLong() * it.outputRate / it.resampleRate).toInt() } ?: 0
    val isEnded: Boolean get() = ending && pending == null && engine.isEnded && resampler?.isEnded != false

    fun configure(format: ChainFormat) {
        val block = input
        if (block != null) engine.flush()
        if (block == null || block.format.sampleRate != format.sourceRate) {
            engine.configure(format.sourceRate)
            input = AudioBlock(AudioStreamFormat(format.sourceRate, ChannelLayout.STEREO), PrecisionBlockProcessor.INPUT_FRAMES)
        }
        val current = resampler
        resampler = when {
            !format.resampling -> null
            current?.sourceRate == format.resampleRate && current.targetRate == format.outputRate -> current
            else -> BandlimitedResampler(format.resampleRate, format.outputRate)
        }
        this.format = format
        clear()
    }

    fun queueInput(samples: DoubleArray, offsetFrames: Int, frames: Int): Int {
        check(!ending) { "End of stream was already queued" }
        val block = checkNotNull(input) { "Configure the chain before queueing input" }
        require(offsetFrames >= 0 && frames >= 0 && (offsetFrames + frames) * 2 <= samples.size)
        val count = minOf(frames, block.capacityFrames)
        if (count == 0 || pending != null) return 0
        block.begin(count, AUDIO_TIME_UNSET, inputFrames)
        System.arraycopy(samples, offsetFrames * 2, block.samples, 0, count * 2)
        if (!engine.queueInput(block)) return 0
        inputFrames += count
        return count
    }

    fun readOutput(target: DoubleArray, offsetFrames: Int, maxFrames: Int): Int {
        require(offsetFrames >= 0 && maxFrames >= 0 && (offsetFrames + maxFrames) * 2 <= target.size)
        if (input == null) return 0
        var written = 0
        while (written < maxFrames) {
            val converter = resampler
            if (converter != null) {
                written += converter.readOutput(target, offsetFrames + written, maxFrames - written)
                if (written == maxFrames) break
            }
            val block = pending ?: engine.getOutput()?.also { pending = it; pendingOffset = 0 }
            if (block == null) {
                if (converter == null || !ending || resamplerEnded || !engine.isEnded) break
                converter.queueEndOfInput()
                resamplerEnded = true
                continue
            }
            val count = if (converter != null) converter.queueInput(block.samples, pendingOffset, block.frameCount - pendingOffset)
            else minOf(block.frameCount - pendingOffset, maxFrames - written).also {
                System.arraycopy(block.samples, pendingOffset * 2, target, (offsetFrames + written) * 2, it * 2)
                written += it
            }
            pendingOffset += count
            if (pendingOffset == block.frameCount) pending = null
            else if (count == 0) break
        }
        outputFrames += written
        return written
    }

    fun queueEndOfStream(drainTail: Boolean = true) {
        if (ending || input == null) return
        ending = true
        engine.queueEndOfStream(drainTail)
    }

    fun flush() {
        if (input != null) engine.flush()
        clear()
    }

    fun reset() {
        engine.reset()
        format = null
        input = null
        resampler = null
        clear()
    }

    fun needsQuantization(gain: Double = 1.0): Boolean {
        val format = format ?: return false
        return format.encoding.integerBits > 0 && !(gain == 1.0 && bitExact(format))
    }

    fun report(gain: Double = 1.0): DspChainReport {
        val format = format
        val exact = format != null && bitExact(format)
        val dither = format != null && format.policy.ditherMode != OutputDitherMode.OFF && needsQuantization(gain)
        return DspChainReport(
            processingChangesSamples = engine.processingChangesSamples,
            processingActive = engine.processingActive || engine.convolutionProcessingActive,
            rackActive = engine.rackActive,
            description = engine.rackDescription,
            preparation = engine.preparationState,
            preparationFailure = engine.preparationFailure,
            convolutionUnavailableReason = engine.convolutionUnavailableReason,
            latencyFrames = engine.rackLatencyFrames,
            tailFrames = engine.rackTailFrames,
            resampling = resampler != null,
            resamplerLookaheadFrames = resampler?.lookaheadFrames ?: 0,
            ditherLabel = if (dither) format?.policy?.ditherLabel(format.outputRate) else null,
            bitExact = exact && gain == 1.0,
        )
    }

    private fun bitExact(format: ChainFormat) = !engine.processingChangesSamples && resampler == null && format.holdsSource

    private fun clear() {
        pending = null
        pendingOffset = 0
        ending = false
        resamplerEnded = false
        resampler?.reset()
        inputFrames = 0
        outputFrames = 0
    }
}
