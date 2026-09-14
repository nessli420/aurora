package com.aurora.music.playback.engine

import com.aurora.music.data.AudioPrefs
import com.aurora.music.data.ProcessingRack
import com.aurora.music.data.ProcessingRackNode
import com.aurora.music.data.RackNodeKind
import com.aurora.music.data.RackEqChannel
import com.aurora.music.playback.ConvolutionPreparationState
import com.aurora.music.playback.DspBand
import com.aurora.music.playback.DspCoeffBuilder
import com.aurora.music.playback.DspParams
import com.aurora.music.playback.ImpulseResponse
import java.util.concurrent.Executors
import kotlin.math.pow

/**
 * An off-thread compiled, ordered stereo graph. One optional convolution node splits its
 * synchronous prefix/suffix. Its dry FIFO contains the SAME input frames as each wet output,
 * even when callers supply irregular blocks. Processing never quantizes PCM, allocates, locks,
 * or appends tail samples beyond input duration. Output is take-once until the next mutation.
 */
class ProductionSerialRack private constructor(
    val format: AudioStreamFormat,
    val description: String,
    val explicitRack: Boolean,
    private val nodes: Array<Node>,
    private val convolutionIndex: Int,
    private val convolution: PrecisionConvolver?,
    private val convolutionWet: Double,
    private val convolutionMakeup: Double,
    val convolutionUnavailableReason: String? = null,
) {
    private val stage = AudioBlock(format, INPUT_FRAMES)
    private val output = AudioBlock(format, OUTPUT_FRAMES)
    private val dry = SampleFifo(OUTPUT_FRAMES + INPUT_FRAMES)
    private val wetScratch = DoubleArray(OUTPUT_FRAMES * 2)
    private var stagePosition = 0
    private var stageCount = 0
    private var outputReady = false
    private var ended = false
    private var convolutionTimeUs = AUDIO_TIME_UNSET
    private var convolutionFrame = AUDIO_TIME_UNSET
    val convolutionActive: Boolean get() = convolution != null
    val canAcceptInput: Boolean get() = !ended && !outputReady && stagePosition == stageCount &&
        (convolution?.availableOutputFrames ?: 0) == 0
    val framesUntilBoundary: Int get() = convolution?.let { OUTPUT_FRAMES - it.bufferedInputFrames } ?: INPUT_FRAMES
    val hasPendingData: Boolean get() = outputReady || stagePosition < stageCount ||
        (convolution?.bufferedInputFrames ?: 0) > 0 || (convolution?.availableOutputFrames ?: 0) > 0
    val isEnded: Boolean get() = ended && !hasPendingData

    fun queueInput(input: AudioBlock): Boolean {
        require(input.format == format && input.frameCount <= INPUT_FRAMES)
        if (ended || outputReady || stagePosition < stageCount || (convolution?.availableOutputFrames ?: 0) > 0) return false
        stage.begin(input.frameCount, input.presentationTimeUs, input.firstFramePosition)
        System.arraycopy(input.samples, 0, stage.samples, 0, input.sampleCount)
        processNodes(stage, 0, if (convolution == null) nodes.size else convolutionIndex)
        stagePosition = 0; stageCount = input.frameCount
        feedStage()
        return true
    }

    fun getOutput(): AudioBlock? {
        if (!outputReady) {
            if (convolution != null && convolution.availableOutputFrames > 0) emitConvolution()
            if (!outputReady && stagePosition < stageCount) feedStage()
            if (!outputReady && ended && convolution != null && stagePosition == stageCount) {
                convolution.queueEndOfInput()
                emitConvolution()
            }
        }
        if (!outputReady) return null
        outputReady = false
        return output
    }

    private fun feedStage() {
        if (outputReady || stagePosition == stageCount) return
        val engine = convolution
        if (engine == null) {
            val count = stageCount - stagePosition
            output.begin(count, timeAt(stage.presentationTimeUs, stagePosition), frameAt(stage.firstFramePosition, stagePosition))
            System.arraycopy(stage.samples, stagePosition * 2, output.samples, 0, count * 2)
            stagePosition = stageCount; outputReady = true
            return
        }
        if (engine.availableOutputFrames > 0) { emitConvolution(); return }
        if (engine.bufferedInputFrames == 0) {
            convolutionTimeUs = timeAt(stage.presentationTimeUs, stagePosition)
            convolutionFrame = frameAt(stage.firstFramePosition, stagePosition)
        }
        val count = engine.queueInput(stage.samples, stagePosition, stageCount - stagePosition)
        dry.push(stage.samples, stagePosition, count)
        stagePosition += count
        emitConvolution()
    }

    private fun emitConvolution() {
        if (outputReady) return
        val count = checkNotNull(convolution).readOutput(output.samples, 0, OUTPUT_FRAMES)
        if (count == 0) return
        output.begin(count, convolutionTimeUs, convolutionFrame)
        var i = 0
        while (i < count) {
            val left = dry.takeLeft(); val right = dry.takeRight()
            output.samples[i * 2] = left * (1.0 - convolutionWet) + output.samples[i * 2] * convolutionMakeup * convolutionWet
            output.samples[i * 2 + 1] = right * (1.0 - convolutionWet) + output.samples[i * 2 + 1] * convolutionMakeup * convolutionWet
            i++
        }
        processNodes(output, convolutionIndex + 1, nodes.size)
        convolutionTimeUs = AUDIO_TIME_UNSET; convolutionFrame = AUDIO_TIME_UNSET
        outputReady = true
    }

    private fun processNodes(block: AudioBlock, from: Int, until: Int) {
        var index = from
        while (index < until) {
            val node = nodes[index++]
            if (node.bypass || node.wet == 0.0) continue
            if (node.wet < 1.0) System.arraycopy(block.samples, 0, wetScratch, 0, block.sampleCount)
            node.process(block)
            if (node.wet < 1.0) {
                var sample = 0
                while (sample < block.sampleCount) {
                    if (node.affectsChannel(sample and 1)) {
                        block.samples[sample] = wetScratch[sample] * (1.0 - node.wet) + block.samples[sample] * node.wet
                    }
                    sample++
                }
            }
        }
    }

    fun queueEndOfStream() { ended = true }
    fun reset() {
        nodes.forEach { it.reset() }
        convolution?.reset(); dry.reset()
        stagePosition = 0; stageCount = 0; outputReady = false; ended = false
        convolutionTimeUs = AUDIO_TIME_UNSET; convolutionFrame = AUDIO_TIME_UNSET
    }

    /** Small effects histories migrate by stable id/kind; convolution FFT histories stay owned. */
    fun copyNodeHistoriesFrom(previous: ProductionSerialRack) {
        require(previous.format == format)
        var i = 0
        while (i < nodes.size) {
            var j = 0
            while (j < previous.nodes.size) {
                if (nodes[i].id == previous.nodes[j].id && nodes[i].kind == previous.nodes[j].kind) {
                    nodes[i].copyState(previous.nodes[j]); break
                }
                j++
            }
            i++
        }
    }

    fun copyLegacyHistoryFrom(kernel: PrecisionEffectsKernel) {
        val first = nodes.firstOrNull()
        if (first is EffectsNode && first.kind == RackNodeKind.LEGACY_DSP) first.copyLegacyState(kernel)
    }

    private fun timeAt(time: Long, offset: Int): Long = if (time == AUDIO_TIME_UNSET) time else time + offset * 1_000_000L / format.sampleRate
    private fun frameAt(frame: Long, offset: Int): Long = if (frame == AUDIO_TIME_UNSET) frame else frame + offset

    private abstract class Node(val id: String, val kind: RackNodeKind, val bypass: Boolean, val wet: Double) {
        abstract fun process(block: AudioBlock)
        abstract fun reset()
        open fun copyState(previous: Node) = Unit
        open fun affectsChannel(channel: Int): Boolean = true
    }

    private class EffectsNode(id: String, kind: RackNodeKind, bypass: Boolean, wet: Double,
        format: AudioStreamFormat, params: DspParams) : Node(id, kind, bypass, wet) {
        private val coefficients = PrecisionDspCoeffBuilder.build(params, format.sampleRate)
        private val kernel = PrecisionEffectsKernel(format)
        override fun process(block: AudioBlock) = kernel.process(block, coefficients, kind == RackNodeKind.LEGACY_DSP)
        override fun reset() = kernel.reset()
        override fun copyState(previous: Node) { if (previous is EffectsNode) kernel.copyStateFrom(previous.kernel) }
        fun copyLegacyState(previous: PrecisionEffectsKernel) = kernel.copyStateFrom(previous)
    }

    private class EqNode(spec: ProcessingRackNode, rate: Int) : Node(spec.id, spec.kind, spec.bypass, spec.wet.toDouble()) {
        private val channel = spec.eqChannel
        override fun affectsChannel(channel: Int): Boolean = this.channel == RackEqChannel.BOTH ||
            channel == if (this.channel == RackEqChannel.LEFT) 0 else 1
        private val bands: Array<BiquadCoefficients>
        private val x1: DoubleArray; private val x2: DoubleArray
        private val y1: DoubleArray; private val y2: DoubleArray
        init {
            bands = ProductionEqCoefficients.build(spec.audio, rate)
            x1 = DoubleArray(bands.size * 2); x2 = DoubleArray(bands.size * 2)
            y1 = DoubleArray(bands.size * 2); y2 = DoubleArray(bands.size * 2)
        }
        override fun process(block: AudioBlock) {
            var sample = 0
            while (sample < block.sampleCount) {
                if (!affectsChannel(sample and 1)) { sample++; continue }
                var value = block.samples[sample]
                var band = 0
                while (band < bands.size) {
                    val state = band * 2 + (sample and 1); val c = bands[band++]
                    val next = c.b0 * value + c.b1 * x1[state] + c.b2 * x2[state] - c.a1 * y1[state] - c.a2 * y2[state]
                    x2[state] = x1[state]; x1[state] = value; y2[state] = y1[state]; y1[state] = next
                    value = next
                }
                block.samples[sample++] = value
            }
        }
        override fun reset() { x1.fill(0.0); x2.fill(0.0); y1.fill(0.0); y2.fill(0.0) }
        override fun copyState(previous: Node) {
            if (previous is EqNode && previous.channel == channel) {
                val count = minOf(x1.size, previous.x1.size)
                previous.x1.copyInto(x1, endIndex = count); previous.x2.copyInto(x2, endIndex = count)
                previous.y1.copyInto(y1, endIndex = count); previous.y2.copyInto(y2, endIndex = count)
            }
        }
    }

    private class ConvolutionNode(spec: ProcessingRackNode) : Node(spec.id, spec.kind, true, 0.0) {
        override fun process(block: AudioBlock) = Unit
        override fun reset() = Unit
    }

    companion object {
        const val INPUT_FRAMES = 256
        const val OUTPUT_FRAMES = 1024

        /** Called on a preparation worker, never by queueInput/getOutput. */
        fun compile(rack: ProcessingRack, sampleRate: Int, impulse: ImpulseResponse?): ProductionSerialRack {
            require(rack.nodes.size <= 16 && rack.nodes.map { it.id }.distinct().size == rack.nodes.size)
            require(rack.nodes.count { it.kind == RackNodeKind.CONVOLUTION } <= 1)
            require(rack.nodes.filter { it.kind == RackNodeKind.EQ || it.kind == RackNodeKind.LEGACY_DSP }
                .sumOf { it.audio.dspParametric.size } <= 64)
            require(rack.nodes.all { it.wet.isFinite() && it.wet in 0f..1f && it.audio.dspGraphicBands.size <= 31 })
            require(rack.nodes.none { it.kind == RackNodeKind.LEGACY_DSP && it.audio.dspParametric.size > 12 })
            require(rack.nodes.all { it.kind == RackNodeKind.EQ || it.eqChannel == RackEqChannel.BOTH })
            val format = AudioStreamFormat(sampleRate, ChannelLayout.STEREO)
            val nodes = rack.nodes.map { spec -> when (spec.kind) {
                RackNodeKind.EQ -> EqNode(spec, sampleRate)
                RackNodeKind.CONVOLUTION -> ConvolutionNode(spec)
                else -> EffectsNode(spec.id, spec.kind, spec.bypass, spec.wet.toDouble(), format, params(spec.kind, spec.audio))
            } }.toTypedArray()
            val index = rack.nodes.indexOfFirst { it.kind == RackNodeKind.CONVOLUTION && !it.bypass && it.wet > 0f }
            val spec = rack.nodes.getOrNull(index)
            val convolver = if (spec != null && impulse != null) convolver(impulse, sampleRate) else null
            return ProductionSerialRack(format, rack.nodes.joinToString(" → ") {
                it.kind.name.replace('_', ' ') + (if (it.eqChannel == RackEqChannel.BOTH) "" else " [${it.eqChannel.name.lowercase().replaceFirstChar { c -> c.uppercase() }}]") + when {
                    it.bypass || it.wet == 0f -> " (bypassed)"
                    it.kind == RackNodeKind.CONVOLUTION && impulse == null -> " (unavailable: no impulse response)"
                    it.wet < 1f -> " (${(it.wet * 100).toInt()}% wet)"
                    else -> ""
                }
            }, true, nodes, index, convolver, spec?.wet?.toDouble() ?: 1.0,
                10.0.pow((spec?.audio?.dspConvMakeupDb ?: 0f).toDouble() / 20.0),
                if (spec != null && impulse == null) "The convolution node has no impulse response; its input passes through unchanged" else null)
        }

        fun compileLegacy(sampleRate: Int, params: DspParams, enabled: Boolean,
            convolutionEnabled: Boolean, makeupDb: Float, impulse: ImpulseResponse?): ProductionSerialRack {
            val format = AudioStreamFormat(sampleRate, ChannelLayout.STEREO)
            val nodes = arrayOf<Node>(EffectsNode("legacy-dsp", RackNodeKind.LEGACY_DSP, !enabled, 1.0, format, params))
            return ProductionSerialRack(format, "Legacy processing", false, nodes, 1,
                if (convolutionEnabled && impulse != null) convolver(impulse, sampleRate) else null,
                1.0, 10.0.pow(makeupDb.toDouble() / 20.0))
        }

        private fun params(kind: RackNodeKind, a: AudioPrefs): DspParams {
            val neutral = DspParams(limiterEnabled = false)
            return when (kind) {
                RackNodeKind.LEGACY_DSP -> {
                    val layout = DspCoeffBuilder.GRAPHIC_LAYOUTS.getOrElse(a.dspGraphicLayout) { DspCoeffBuilder.GRAPHIC_LAYOUTS.first() }
                    DspParams(graphic = a.dspGraphicBands.toFloatArray(), graphicFreqs = layout.freqs, graphicQ = layout.q,
                        parametric = a.dspParametric.map { DspBand(it.freqHz, it.gainDb, it.q, it.type) },
                        preampDb = a.dspPreampDb, balance = a.dspBalance, width = a.dspWidth,
                        crossfeed = a.dspCrossfeed, saturation = a.dspSaturation,
                        delayLeftMs = a.dspDelayLeftMs, delayRightMs = a.dspDelayRightMs,
                        trimLeftDb = a.dspTrimLeftDb, trimRightDb = a.dspTrimRightDb,
                        limiterEnabled = a.dspLimiterEnabled, limiterCeilingDb = a.dspLimiterCeilingDb,
                        compEnabled = a.dspCompEnabled, compThreshDb = a.dspCompThreshDb, compRatio = a.dspCompRatio)
                }
                RackNodeKind.GAIN -> neutral.copy(preampDb = a.dspPreampDb)
                RackNodeKind.SATURATION -> neutral.copy(saturation = a.dspSaturation)
                RackNodeKind.STEREO -> neutral.copy(balance = a.dspBalance, width = a.dspWidth, trimLeftDb = a.dspTrimLeftDb, trimRightDb = a.dspTrimRightDb)
                RackNodeKind.CROSSFEED -> neutral.copy(crossfeed = a.dspCrossfeed)
                RackNodeKind.COMPRESSOR -> neutral.copy(compEnabled = true, compThreshDb = a.dspCompThreshDb, compRatio = a.dspCompRatio)
                RackNodeKind.LIMITER -> neutral.copy(limiterEnabled = true, limiterCeilingDb = a.dspLimiterCeilingDb)
                RackNodeKind.DELAY -> neutral.copy(delayLeftMs = a.dspDelayLeftMs, delayRightMs = a.dspDelayRightMs)
                else -> neutral
            }
        }

        private fun convolver(impulse: ImpulseResponse, rate: Int): PrecisionConvolver = PrecisionConvolver(
            resample(impulse.preciseLeft, impulse.sampleRate, rate), resample(impulse.preciseRight, impulse.sampleRate, rate), OUTPUT_FRAMES)

        private fun resample(source: DoubleArray, sourceRate: Int, targetRate: Int): DoubleArray {
            require(sourceRate in 8_000..768_000 && source.isNotEmpty() && source.all { it.isFinite() })
            val ratio = targetRate.toDouble() / sourceRate
            val length = (source.size * ratio).toLong().coerceAtLeast(1)
            require(length <= PrecisionConvolver.MAX_IR_FRAMES) { "Impulse response is too long at the current sample rate" }
            if (sourceRate == targetRate) return source
            return DoubleArray(length.toInt()) { i ->
                val position = i / ratio; val first = position.toInt(); val fraction = position - first
                val a = source.getOrElse(first) { 0.0 }; val b = source.getOrElse(first + 1) { a }
                a + (b - a) * fraction
            }
        }
    }
}

/** Interleaved stereo frames; separate L/R reads advance one complete frame only after R. */
private class SampleFifo(private val capacity: Int) {
    private val samples = DoubleArray(capacity * 2)
    private var read = 0
    var frames = 0; private set
    fun push(input: DoubleArray, offset: Int, count: Int) {
        check(frames + count <= capacity) { "Bounded rack output FIFO is full" }
        var i = 0
        while (i < count) {
            val p = (read + frames + i) % capacity * 2
            samples[p] = input[(offset + i) * 2]; samples[p + 1] = input[(offset + i) * 2 + 1]
            i++
        }
        frames += count
    }
    fun takeLeft(): Double { check(frames > 0); return samples[read * 2] }
    fun takeRight(): Double {
        check(frames > 0)
        val value = samples[read * 2 + 1]; read = (read + 1) % capacity; frames--
        return value
    }
    fun reset() { frames = 0; read = 0 }
}

/**
 * Coalesced worker preparation and matched-input,20ms graph crossfades. A swap begins only
 * when old accepted audio has naturally reached a block boundary, avoiding padded/reset IR
 * history. At most two graphs process audio; one replacement may be preparing. Two bounded
 * FIFOs align their output by SOURCE FRAME rather than wall-clock readiness or FFT block size.
 */
class ProductionRackProcessor {
    private data class Request(val rate: Int, val rack: ProcessingRack?, val legacy: DspParams,
        val effects: Boolean, val convolution: Boolean, val makeupDb: Float, val impulse: ImpulseResponse?, val legacyKey: List<Any>)
    private data class Prepared(val request: Request, val graph: ProductionSerialRack?, val failure: String?,
        val mixOutput: AudioBlock? = graph?.let { AudioBlock(it.format, ProductionSerialRack.OUTPUT_FRAMES) },
        val input: AudioBlock? = graph?.let { AudioBlock(it.format, ProductionSerialRack.INPUT_FRAMES) },
        val inputSlice: AudioBlock? = graph?.let { AudioBlock(it.format, ProductionSerialRack.INPUT_FRAMES) })
    private val lock = Any()
    private var rate = 0
    private var desiredRack: ProcessingRack? = null
    private var legacy = DspParams()
    private var effects = false
    private var convolution = false
    private var makeupDb = 0f
    private var impulse: ImpulseResponse? = null
    @Volatile private var requested: Request? = null
    @Volatile private var completed: Prepared? = null
    private var workerRunning = false
    private var active: Prepared? = null
    private var fading: ProductionSerialRack? = null
    private var output: AudioBlock? = null
    private var input: AudioBlock? = null
    private var inputSlice: AudioBlock? = null
    private var inputPosition = 0
    private var inputCount = 0
    private val oldOutput = SampleFifo(4096)
    private val newOutput = SampleFifo(4096)
    private var blendPosition = 0L
    private var blendFrames = 0L
    private var outputFrames = 0L
    private var anchorTimeUs = AUDIO_TIME_UNSET
    private var anchorFrame = AUDIO_TIME_UNSET
    private var anchorSet = false
    private var ended = false
    private var initialLegacyHistory: PrecisionEffectsKernel? = null
    @Volatile var rackActive = false; private set
    @Volatile var description = "Preparing processing rack"; private set
    @Volatile var convolutionUnavailableReason: String? = null; private set
    val convolutionActive: Boolean get() = active?.graph?.convolutionActive == true
    val preparationState: ConvolutionPreparationState get() {
        val request = requested ?: return ConvolutionPreparationState.IDLE
        val result = completed
        return if (result?.request !== request) ConvolutionPreparationState.PREPARING
            else if (result.failure == null && result.graph != null) ConvolutionPreparationState.READY else ConvolutionPreparationState.FAILED
    }
    val preparationFailure: String? get() = completed?.takeIf { it.request === requested }?.failure
    val hasPendingData: Boolean get() = inputPosition < inputCount || oldOutput.frames > 0 || newOutput.frames > 0 || active?.graph?.hasPendingData == true || fading?.hasPendingData == true
    val isEnded: Boolean get() = ended && !hasPendingData

    fun update(rack: ProcessingRack?, params: DspParams, enabled: Boolean, conv: Boolean, makeup: Float, ir: ImpulseResponse?) {
        synchronized(lock) {
            desiredRack = rack?.takeIf { it.enabled }
            legacy = params
            effects = enabled; convolution = conv; makeupDb = makeup; impulse = ir
            requestLocked()
        }
    }

    fun configure(sampleRate: Int) {
        check(!hasPendingData)
        flush()
        output = null; input = null; inputSlice = null
        active = null
        prepareRate(sampleRate)
    }

    /** Control-thread preparation can run while a different legacy pipeline still owns audio. */
    fun prepareRate(sampleRate: Int) { synchronized(lock) { rate = sampleRate; requestLocked() } }

    fun preserveInitialLegacyHistory(kernel: PrecisionEffectsKernel?) { initialLegacyHistory = kernel }

    fun queueInput(block: AudioBlock): Boolean {
        if (ended || inputPosition < inputCount) return false
        applyPrepared()
        val graph = active?.graph ?: return false
        require(block.format == graph.format && block.frameCount <= ProductionSerialRack.INPUT_FRAMES)
        val owned = checkNotNull(input)
        owned.begin(block.frameCount, block.presentationTimeUs, block.firstFramePosition)
        System.arraycopy(block.samples, 0, owned.samples, 0, block.sampleCount)
        inputPosition = 0; inputCount = block.frameCount
        feedInput()
        return true
    }

    fun getOutput(): AudioBlock? {
        var iteration = 0
        while (iteration++ < 4) {
            applyPrepared()
            val graph = active?.graph ?: return null
            if (ended && inputPosition == inputCount) {
                graph.queueEndOfStream(); fading?.queueEndOfStream()
            }
            if (fading == null) {
                graph.getOutput()?.let { return it }
            } else {
                blendOutput()?.let { return it }
            }
            // The last read may have exposed a natural convolution boundary. Start an edit
            // before feeding the remainder of the already accepted RAW input block.
            applyPrepared()
            if (!feedInput()) return null
        }
        return null
    }

    private fun feedInput(): Boolean {
        if (inputPosition == inputCount) return false
        val graph = active?.graph ?: return false
        val previous = fading
        if (!graph.canAcceptInput || previous?.canAcceptInput == false) return false
        if (oldOutput.frames > 2816 || newOutput.frames > 2816) return false
        val count = minOf(inputCount - inputPosition, graph.framesUntilBoundary,
            previous?.framesUntilBoundary ?: ProductionSerialRack.INPUT_FRAMES)
        val owned = checkNotNull(input); val slice = checkNotNull(inputSlice)
        val time = if (owned.presentationTimeUs == AUDIO_TIME_UNSET) AUDIO_TIME_UNSET else
            owned.presentationTimeUs + inputPosition * 1_000_000L / graph.format.sampleRate
        val frame = if (owned.firstFramePosition == AUDIO_TIME_UNSET) AUDIO_TIME_UNSET else owned.firstFramePosition + inputPosition
        slice.begin(count, time, frame)
        System.arraycopy(owned.samples, inputPosition * 2, slice.samples, 0, count * 2)
        // Neither graph can reject after this single-owner readiness check.
        if (previous != null) check(previous.queueInput(slice))
        check(graph.queueInput(slice))
        if (previous != null && !anchorSet) {
            anchorTimeUs = time; anchorFrame = frame; anchorSet = true
        }
        inputPosition += count
        return true
    }

    private fun blendOutput(): AudioBlock? {
        val graph = checkNotNull(active?.graph)
        val previous = checkNotNull(fading)
        drainToFifo(previous, oldOutput)
        drainToFifo(graph, newOutput)
        val count = minOf(oldOutput.frames, newOutput.frames, ProductionSerialRack.OUTPUT_FRAMES)
        if (count == 0) return null
        val result = checkNotNull(output)
        result.begin(count, if (anchorTimeUs == AUDIO_TIME_UNSET) AUDIO_TIME_UNSET else anchorTimeUs + outputFrames * 1_000_000L / graph.format.sampleRate,
            if (anchorFrame == AUDIO_TIME_UNSET) AUDIO_TIME_UNSET else anchorFrame + outputFrames)
        var i = 0
        while (i < count) {
            val wet = ((blendPosition + 1).toDouble() / blendFrames).coerceIn(0.0, 1.0)
            val oldL = oldOutput.takeLeft(); val oldR = oldOutput.takeRight()
            val newL = newOutput.takeLeft(); val newR = newOutput.takeRight()
            result.samples[i * 2] = oldL * (1.0 - wet) + newL * wet
            result.samples[i * 2 + 1] = oldR * (1.0 - wet) + newR * wet
            blendPosition++; i++
        }
        outputFrames += count
        if (blendPosition >= blendFrames && newOutput.frames == 0) {
            fading = null; oldOutput.reset()
        }
        return result
    }

    private fun drainToFifo(graph: ProductionSerialRack, fifo: SampleFifo) {
        var i = 0
        while (i++ < 4 && fifo.frames <= 3072) {
            val block = graph.getOutput() ?: return
            fifo.push(block.samples, 0, block.frameCount)
        }
    }

    private fun applyPrepared() {
        val candidate = completed ?: return
        if (candidate.request !== requested || candidate.graph == null || candidate === active || ended || fading != null) return
        if (candidate.failure != null && active != null) return // Keep the last working graph on a rejected edit.
        val old = active?.graph
        if (old?.hasPendingData == true) return
        candidate.graph.reset()
        if (old != null) candidate.graph.copyNodeHistoriesFrom(old)
        else initialLegacyHistory?.let { candidate.graph.copyLegacyHistoryFrom(it) }
        initialLegacyHistory = null
        active = candidate
        output = candidate.mixOutput
        if (input == null || input?.format != candidate.graph.format) {
            check(inputPosition == inputCount)
            input = candidate.input; inputSlice = candidate.inputSlice
        }
        rackActive = candidate.graph.explicitRack
        description = candidate.graph.description
        convolutionUnavailableReason = candidate.graph.convolutionUnavailableReason
        if (old != null) {
            fading = old; blendPosition = 0; blendFrames = (candidate.graph.format.sampleRate / 50).coerceAtLeast(1).toLong()
            outputFrames = 0; anchorTimeUs = AUDIO_TIME_UNSET; anchorFrame = AUDIO_TIME_UNSET
            anchorSet = false
            oldOutput.reset(); newOutput.reset()
        }
    }

    fun queueEndOfStream() {
        ended = true
        if (inputPosition == inputCount) {
            active?.graph?.queueEndOfStream(); fading?.queueEndOfStream()
        }
    }
    fun flush() {
        active?.graph?.reset(); fading = null
        oldOutput.reset(); newOutput.reset(); ended = false
        inputPosition = 0; inputCount = 0
        anchorTimeUs = AUDIO_TIME_UNSET; anchorFrame = AUDIO_TIME_UNSET; outputFrames = 0
        anchorSet = false
        initialLegacyHistory = null
    }
    fun reset() {
        flush(); active = null; output = null; input = null; inputSlice = null; rackActive = false
        convolutionUnavailableReason = null
        synchronized(lock) { rate = 0; requested = null; completed = null }
    }

    private fun requestLocked() {
        if (rate == 0) return
        val p = legacy
        val key = listOf<Any>(p.graphic.toList(), p.graphicFreqs.toList(), p.parametric,
            p.graphicQ, p.preampDb, p.balance, p.width, p.crossfeed, p.saturation,
            p.delayLeftMs, p.delayRightMs, p.trimLeftDb, p.trimRightDb, p.limiterEnabled, p.limiterCeilingDb,
            p.compEnabled, p.compThreshDb, p.compRatio, effects, convolution, makeupDb)
        val previous = requested
        if (previous != null && previous.rate == rate && previous.rack == desiredRack && previous.impulse === impulse &&
            (desiredRack != null || previous.legacyKey == key)) return
        requested = Request(rate, desiredRack, legacy, effects, convolution, makeupDb, impulse, key)
        completed = null
        if (!workerRunning) { workerRunning = true; PREPARATION.execute { prepare() } }
    }

    private fun prepare() {
        while (true) {
            val request = synchronized(lock) { requested ?: run { workerRunning = false; return } }
            val result = try {
                Prepared(request, if (request.rack != null) ProductionSerialRack.compile(request.rack, request.rate, request.impulse)
                    else ProductionSerialRack.compileLegacy(request.rate, request.legacy, request.effects, request.convolution,
                        request.makeupDb, request.impulse), null)
            } catch (failure: Exception) {
                // A first-load IR preparation error must not leave the sink waiting forever.
                // Preserve the requested effects with convolution dry where possible. Later
                // rejected edits keep the already running graph in applyPrepared instead.
                val fallback = runCatching {
                    if (request.rack != null) ProductionSerialRack.compile(request.rack, request.rate, null)
                    else ProductionSerialRack.compileLegacy(request.rate, request.legacy, request.effects, false, 0f, null)
                }.getOrElse { ProductionSerialRack.compileLegacy(request.rate, DspParams(limiterEnabled = false), false, false, 0f, null) }
                Prepared(request, fallback, failure.message ?: "Processing rack could not be prepared")
            }
            synchronized(lock) {
                if (requested === request) { completed = result; workerRunning = false; return }
            }
        }
    }

    companion object {
        private val PREPARATION = Executors.newSingleThreadExecutor { task -> Thread(task, "Aurora-rack-prepare").apply { isDaemon = true } }
    }
}
