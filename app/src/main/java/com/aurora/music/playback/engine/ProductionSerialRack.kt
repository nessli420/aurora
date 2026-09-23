package com.aurora.music.playback.engine

import com.aurora.music.data.*
import com.aurora.music.playback.DspBand
import com.aurora.music.playback.DspCoeffBuilder
import com.aurora.music.playback.DspParams
import com.aurora.music.playback.ImpulseResponse
import kotlin.math.pow

data class RackNodeMeter(val id: String, val peak: Double, val changeDb: Double, val bandChangesDb: List<Double>)

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
    val headroom: RackHeadroom? = null,
    private val graph: ParallelSchedule? = null,
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
    var relativeVolume: Double = 1.0
        set(value) { field = value.coerceIn(0.0, 1.0); graph?.relativeVolume = field }
    val latencyFrames: Int get() = graph?.latencyFrames ?: 0
    val tailFrames: Int get() = graph?.tailFrames ?: convolution?.tailFrames ?: 0
    val supportsInputTailHandover: Boolean get() = graph?.supportsInputTailHandover ?:
        nodes.all { it.bypass || it.wet == 0.0 || it.supportsInputTailHandover }
    val processingChangesSamples: Boolean get() = graph?.processingChangesSamples ?:
        (nodes.any { !it.bypass && it.wet > 0.0 } || convolution != null || (headroom?.gain ?: 1.0) != 1.0)
    fun meterSnapshot(): List<RackNodeMeter> = nodes.map { it.meter() }
    val convolutionActive: Boolean get() = graph?.convolutionActive ?: (convolution != null)
    val canAcceptInput: Boolean get() = graph?.canAcceptInput ?: (!ended && !outputReady && stagePosition == stageCount &&
        (convolution?.availableOutputFrames ?: 0) == 0)
    val framesUntilBoundary: Int get() = graph?.framesUntilBoundary ?: convolution?.let { OUTPUT_FRAMES - it.bufferedInputFrames } ?: INPUT_FRAMES
    val hasPendingData: Boolean get() = graph?.hasPendingData ?: (outputReady || stagePosition < stageCount ||
        (convolution?.bufferedInputFrames ?: 0) > 0 || (convolution?.availableOutputFrames ?: 0) > 0)
    val isEnded: Boolean get() = graph?.isEnded ?: (ended && !hasPendingData)

    fun queueInput(input: AudioBlock): Boolean {
        graph?.let { return it.queueInput(input) }
        require(input.format == format && input.frameCount <= INPUT_FRAMES)
        if (ended || outputReady || stagePosition < stageCount || (convolution?.availableOutputFrames ?: 0) > 0) return false
        stage.begin(input.frameCount, input.presentationTimeUs, input.firstFramePosition)
        System.arraycopy(input.samples, 0, stage.samples, 0, input.sampleCount)
        val attenuation = headroom?.gain ?: 1.0
        if (attenuation != 1.0) for (i in 0 until stage.sampleCount) stage.samples[i] *= attenuation
        processNodes(stage, 0, if (convolution == null) nodes.size else convolutionIndex)
        stagePosition = 0; stageCount = input.frameCount
        feedStage()
        return true
    }

    fun getOutput(): AudioBlock? {
        graph?.let { return it.getOutput() }
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
            if (node.bypass || node.wet == 0.0) { node.measure(block); continue }
            if (node.wet < 1.0) System.arraycopy(block.samples, 0, wetScratch, 0, block.sampleCount)
            node.volume = relativeVolume
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
            node.measure(block)
        }
    }

    fun queueEndOfStream(drainTail: Boolean = true) { ended = true; graph?.queueEndOfStream(drainTail) }
    fun reset() {
        graph?.reset()
        nodes.forEach { it.reset() }
        convolution?.reset(); dry.reset()
        stagePosition = 0; stageCount = 0; outputReady = false; ended = false
        convolutionTimeUs = AUDIO_TIME_UNSET; convolutionFrame = AUDIO_TIME_UNSET
    }

    // - preserve small effect histories by node id and kind
    fun copyNodeHistoriesFrom(previous: ProductionSerialRack) {
        require(previous.format == format)
        if (graph != null && previous.graph != null) graph.copyDelaysFrom(previous.graph)
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

    companion object {
        const val INPUT_FRAMES = 256
        const val OUTPUT_FRAMES = 1024

        // - prepare on the worker thread
        fun compile(rack: ProcessingRack, sampleRate: Int, impulse: ImpulseResponse?,
            impulseMap: Map<String, ImpulseResponse> = emptyMap(), relativeVolume: Double = 1.0): ProductionSerialRack {
            ProcessingRackCodec.validate(rack)
            val oversamplingLoad = rack.nodes.filter { it.kind == RackNodeKind.SATURATION && !it.bypass && it.wet > 0f && (it.oversampling ?: 1) > 1 }
                .sumOf { (it.oversampling ?: 1).toLong() * sampleRate } +
                rack.nodes.count { it.kind == RackNodeKind.TONE && !it.bypass && it.wet > 0 &&
                    (it.tone ?: RackTone()).let { tone -> tone.mode != RackToneMode.BASS && tone.amount > 0 && tone.driveDb > 0 } } * 2L * sampleRate
            require(oversamplingLoad <= 16L * 96000) { "Oversampling exceeds the processing budget at this sample rate." }
            val delayStorage = rack.nodes.sumOf { node -> when (node.kind) {
                RackNodeKind.SPACE -> ((node.space ?: RackSpace()).timeMs / 1000 * sampleRate * 2 + sampleRate * .35).toLong()
                RackNodeKind.MODULATION -> (sampleRate * .12).toLong()
                else -> 0L
            } }
            require(delayStorage <= 4_000_000) { "Delay stages exceed the rack memory budget at this sample rate." }
            rack.nodes.filter { !it.bypass && it.wet > 0f }.forEach { node ->
                if (node.kind == RackNodeKind.DYNAMIC_EQ) (node.dynamic ?: RackDynamicEq()).let {
                    require(it.frequencyHz < sampleRate * .5 && it.detectorHz < sampleRate * .5) { "Dynamic EQ frequencies must be below Nyquist at this sample rate." }
                }
                if (node.kind == RackNodeKind.MULTIBAND) require((node.multiband ?: RackMultiband()).highHz < sampleRate * .5) {
                    "The upper crossover must be below Nyquist at this sample rate."
                }
            }
            val routed = rack.usesGraph() || rack.nodes.any { !it.bypass && it.wet > 0f &&
                (it.kind == RackNodeKind.CONVOLUTION && (impulseMap[it.impulseId] != null || it.impulseId == null && impulse != null) ||
                    it.kind in listOf(RackNodeKind.DELAY, RackNodeKind.LEGACY_DSP) && (it.audio.dspDelayLeftMs > 0f || it.audio.dspDelayRightMs > 0f)) }
            val impulseFrames = rack.nodes.filter { it.kind == RackNodeKind.CONVOLUTION && !it.bypass && it.wet > 0f }.sumOf { node ->
                val ir = impulseMap[node.impulseId] ?: if (node.impulseId == null) impulse else null
                if (ir == null) 0L else {
                    val count = kotlin.math.ceil(ir.frameCount * sampleRate.toDouble() / ir.sampleRate).toLong() +
                        BandlimitedResampler.impulseDelayFrames(ir.sampleRate, sampleRate) * 2L
                    count * if (ir.trueStereo) 4 else 2
                }
            }
            require(impulseFrames <= PrecisionConvolver.MAX_IR_FRAMES * 2L) { "The combined impulse responses exceed the rack memory budget at this sample rate." }
            require(rack.nodes.size <= 16 && rack.nodes.map { it.id }.distinct().size == rack.nodes.size)
            require(rack.nodes.count { it.kind == RackNodeKind.CONVOLUTION } <= 4)
            require(rack.nodes.filter { it.kind == RackNodeKind.EQ || it.kind == RackNodeKind.LEGACY_DSP }
                .sumOf { it.audio.dspParametric.size } <= ProcessingRackCodec.MAX_TOTAL_PARAMETRIC_BANDS)
            require(rack.nodes.sumOf(ProcessingRackCodec::sectionCount) <= ProcessingRackCodec.MAX_BIQUAD_SECTIONS)
            require(rack.nodes.all { it.audio.dspParametric.size <= ProcessingRackCodec.MAX_PARAMETRIC_BANDS })
            require(rack.nodes.all { it.wet.isFinite() && it.wet in 0f..1f && it.audio.dspGraphicBands.size <= 31 })
            require(rack.nodes.none { it.kind == RackNodeKind.LEGACY_DSP && it.audio.dspParametric.size > 12 })
            require(rack.nodes.all { it.kind == RackNodeKind.EQ || it.eqChannel == RackEqChannel.BOTH })
            val format = AudioStreamFormat(sampleRate, ChannelLayout.STEREO)
            val nodes = rack.nodes.map { spec -> when (spec.kind) {
                RackNodeKind.EQ -> EqNode(spec, sampleRate)
                RackNodeKind.CONVOLUTION -> {
                    val ir = impulseMap[spec.impulseId] ?: if (spec.impulseId == null) impulse else null
                    if (routed && ir != null && !spec.bypass && spec.wet > 0f) SyncConvolutionNode(spec, ir, sampleRate) else ConvolutionNode(spec)
                }
                RackNodeKind.UTILITY, RackNodeKind.DYNAMIC_EQ, RackNodeKind.MULTIBAND, RackNodeKind.LOUDNESS, RackNodeKind.DYNAMICS, RackNodeKind.TONE, RackNodeKind.SPACE, RackNodeKind.MODULATION -> AdvancedNode(spec, sampleRate)
                RackNodeKind.ALIGNMENT_DELAY -> AlignmentNode(spec, sampleRate)
                RackNodeKind.SATURATION -> if ((spec.oversampling ?: 1) > 1) OversampledNode(spec) else EffectsNode(spec.id, spec.kind, spec.bypass, spec.wet.toDouble(), format, params(spec.kind, spec.audio))
                else -> EffectsNode(spec.id, spec.kind, spec.bypass, spec.wet.toDouble(), format, params(spec.kind, spec.audio))
            } }.toTypedArray()
            val index = rack.nodes.indexOfFirst { it.kind == RackNodeKind.CONVOLUTION && !it.bypass && it.wet > 0f }
            val spec = rack.nodes.getOrNull(index)
            val convolver = if (!routed && spec != null && impulse != null) convolver(impulse, sampleRate) else null
            val headroom = if (rack.autoHeadroom) RackHeadroomAnalyzer.analyze(rack, sampleRate, impulse, impulseMap) else null
            return ProductionSerialRack(format, (headroom?.description()?.plus(" → ") ?: "") +
                (if (rack.usesGraph()) "Routed graph · " else "") + rack.nodes.joinToString(if (rack.usesGraph()) " · " else " → ") {
                it.kind.name.replace('_', ' ') + (if (it.eqChannel == RackEqChannel.BOTH) "" else " [${it.eqChannel.name.lowercase().replaceFirstChar { c -> c.uppercase() }}]") + when {
                    it.bypass || it.wet == 0f -> " (bypassed)"
                    it.kind == RackNodeKind.CONVOLUTION && impulseMap[it.impulseId] == null && (it.impulseId != null || impulse == null) -> " (unavailable: no impulse response)"
                    it.wet < 1f -> " (${(it.wet * 100).toInt()}% wet)"
                    else -> ""
                }
            }, true, nodes, index, convolver, spec?.wet?.toDouble() ?: 1.0,
                10.0.pow((spec?.audio?.dspConvMakeupDb ?: 0f).toDouble() / 20.0),
                if (rack.nodes.any { it.kind == RackNodeKind.CONVOLUTION && !it.bypass && it.wet > 0f && impulseMap[it.impulseId] == null && (it.impulseId != null || impulse == null) })
                    "An impulse response is unavailable; its stage is bypassed" else null, headroom,
                if (routed) ParallelSchedule(rack, format, nodes, headroom?.gain ?: 1.0) else null).also { it.relativeVolume = relativeVolume }
        }

        fun compileLegacy(sampleRate: Int, params: DspParams, enabled: Boolean,
            convolutionEnabled: Boolean, makeupDb: Float, impulse: ImpulseResponse?): ProductionSerialRack {
            val format = AudioStreamFormat(sampleRate, ChannelLayout.STEREO)
            val effect = EffectsNode("legacy-dsp", RackNodeKind.LEGACY_DSP, !enabled, 1.0, format, params)
            if (convolutionEnabled && impulse != null) {
                val conv = ProcessingRackNode("legacy-convolution", "Convolution", RackNodeKind.CONVOLUTION,
                    audio = AudioPrefs(dspConvMakeupDb = makeupDb))
                val nodes = arrayOf<Node>(effect, SyncConvolutionNode(conv, impulse, sampleRate))
                val rack = ProcessingRack(nodes = listOf(ProcessingRackNode("legacy-dsp", "Legacy DSP", RackNodeKind.LEGACY_DSP), conv))
                return ProductionSerialRack(format, "Legacy processing", false, nodes, 1, null, 1.0, 1.0,
                    graph = ParallelSchedule(rack, format, nodes, 1.0))
            }
            return ProductionSerialRack(format, "Legacy processing", false, arrayOf(effect), 1, null, 1.0, 1.0)
        }

        internal fun params(kind: RackNodeKind, a: AudioPrefs): DspParams {
            val neutral = DspParams(limiterEnabled = false)
            return when (kind) {
                RackNodeKind.LEGACY_DSP -> {
                    val layout = DspCoeffBuilder.GRAPHIC_LAYOUTS.getOrElse(a.dspGraphicLayout) { DspCoeffBuilder.GRAPHIC_LAYOUTS.first() }
                    DspParams(graphic = a.dspGraphicBands.toFloatArray(), graphicFreqs = layout.freqs, graphicQ = layout.q,
                        parametric = a.dspParametric.map { DspBand.from(it) },
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

        private fun convolver(impulse: ImpulseResponse, rate: Int): PrecisionConvolver = impulse.createConvolver(rate, OUTPUT_FRAMES)

        internal fun resample(source: DoubleArray, sourceRate: Int, targetRate: Int): DoubleArray =
            BandlimitedResampler.resampleImpulse(source, sourceRate, targetRate)

    }
}
