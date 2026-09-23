package com.aurora.music.playback.engine

import com.aurora.music.data.*
import com.aurora.music.playback.DspParams
import com.aurora.music.playback.ImpulseResponse
import kotlin.math.pow

private const val OUTPUT_FRAMES = ProductionSerialRack.OUTPUT_FRAMES

internal abstract class Node(val id: String, val kind: RackNodeKind, val bypass: Boolean, val wet: Double) {
    var volume = 1.0
    open val latencyFrames = 0
    open val tailFrames = 0
    open val supportsInputTailHandover = true
    @Volatile var peak = 0.0
    open fun meter() = RackNodeMeter(id, peak, 0.0, emptyList())
    fun measure(block: AudioBlock) {
        var maximum = 0.0
        for (i in 0 until block.sampleCount) maximum = maxOf(maximum, kotlin.math.abs(block.samples[i]))
        peak = maximum
    }
    abstract fun process(block: AudioBlock)
    abstract fun reset()
    open fun copyState(previous: Node) = Unit
    open fun affectsChannel(channel: Int): Boolean = true
}

internal class EffectsNode(id: String, kind: RackNodeKind, bypass: Boolean, wet: Double,
    format: AudioStreamFormat, params: DspParams) : Node(id, kind, bypass, wet) {
    private val coefficients = PrecisionDspCoeffBuilder.build(params, format.sampleRate)
    private val kernel = PrecisionEffectsKernel(format)
    override val tailFrames = if (bypass || wet == 0.0) 0 else maxOf(coefficients.delayL, coefficients.delayR)
    override val supportsInputTailHandover = coefficients.satDrive == 0.0 && !coefficients.compEnabled && !coefficients.limiterEnabled
    override fun process(block: AudioBlock) = kernel.process(block, coefficients, kind == RackNodeKind.LEGACY_DSP)
    override fun reset() = kernel.reset()
    override fun copyState(previous: Node) { if (previous is EffectsNode) kernel.copyStateFrom(previous.kernel) }
    fun copyLegacyState(previous: PrecisionEffectsKernel) = kernel.copyStateFrom(previous)
}

internal class EqNode(spec: ProcessingRackNode, rate: Int) : Node(spec.id, spec.kind, spec.bypass, spec.wet.toDouble()) {
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
        if (previous is EqNode && previous.channel == channel && previous.bands.contentEquals(bands)) {
            val count = minOf(x1.size, previous.x1.size)
            previous.x1.copyInto(x1, endIndex = count); previous.x2.copyInto(x2, endIndex = count)
            previous.y1.copyInto(y1, endIndex = count); previous.y2.copyInto(y2, endIndex = count)
        }
    }
}

internal class ConvolutionNode(spec: ProcessingRackNode) : Node(spec.id, spec.kind, true, 0.0) {
    override fun process(block: AudioBlock) = Unit
    override fun reset() = Unit
}

internal class AdvancedNode(spec: ProcessingRackNode, rate: Int) : Node(spec.id, spec.kind, spec.bypass, spec.wet.toDouble()) {
    override val supportsInputTailHandover = spec.kind in listOf(RackNodeKind.UTILITY, RackNodeKind.SPACE)
    private val kernel: AdvancedRackKernel = when (spec.kind) {
        RackNodeKind.DYNAMIC_EQ -> DynamicEqKernel(spec.dynamic ?: RackDynamicEq(), rate)
        RackNodeKind.MULTIBAND -> MultibandKernel(spec.multiband ?: RackMultiband(), rate)
        RackNodeKind.LOUDNESS -> RelativeLoudnessKernel(spec.loudness ?: RackLoudness(), rate)
        RackNodeKind.DYNAMICS -> DynamicsEffectKernel(spec.dynamics ?: RackDynamicsEffect(), rate)
        RackNodeKind.TONE -> ToneKernel(spec.tone ?: RackTone(), rate)
        RackNodeKind.SPACE -> SpaceKernel(spec.space ?: RackSpace(), rate)
        RackNodeKind.MODULATION -> ModulationKernel(spec.modulation ?: RackModulation(), rate)
        else -> UtilityKernel(spec.utility ?: RackUtility(), rate)
    }
    override val latencyFrames = if (bypass || wet == 0.0) 0 else kernel.latencyFrames
    override val tailFrames = if (bypass || wet == 0.0) 0 else kernel.tailFrames
    @Volatile private var reduction = 0.0
    private val bandChanges = DoubleArray(3)
    override fun process(block: AudioBlock) {
        kernel.process(block, volume); reduction = kernel.reductionDb
        if (kind == RackNodeKind.MULTIBAND) for (i in 0..2) bandChanges[i] = kernel.bandReductionDb(i)
    }
    override fun meter() = RackNodeMeter(id, peak, reduction, if (kind == RackNodeKind.MULTIBAND) bandChanges.toList() else emptyList())
    override fun reset() { kernel.reset(); reduction = 0.0; bandChanges.fill(0.0) }
    override fun copyState(previous: Node) { if (previous is AdvancedNode) kernel.copyStateFrom(previous.kernel) }
}

internal class SyncConvolutionNode(spec: ProcessingRackNode, private val impulse: ImpulseResponse, rate: Int) : Node(spec.id, spec.kind, spec.bypass, spec.wet.toDouble()) {
    private val convolution = impulse.createConvolver(rate, OUTPUT_FRAMES)
    private val makeup = 10.0.pow(spec.audio.dspConvMakeupDb / 20.0)
    override val latencyFrames = impulse.alignmentFramesAt(rate)
    override val tailFrames = convolution.tailFrames
    override fun process(block: AudioBlock) {
        check(block.frameCount == OUTPUT_FRAMES)
        check(convolution.queueInput(block.samples, 0, OUTPUT_FRAMES) == OUTPUT_FRAMES)
        check(convolution.readOutput(block.samples, 0, OUTPUT_FRAMES) == OUTPUT_FRAMES)
        if (makeup != 1.0) for (i in 0 until block.sampleCount) block.samples[i] *= makeup
    }
    override fun reset() = convolution.reset()
    override fun copyState(previous: Node) {
        if (previous is SyncConvolutionNode && previous.impulse === impulse) convolution.copyStateFrom(previous.convolution)
    }
}

internal class OversampledNode(spec: ProcessingRackNode) : Node(spec.id, spec.kind, spec.bypass, spec.wet.toDouble()) {
    override val supportsInputTailHandover = spec.audio.dspSaturation == 0f
    private val kernel = OversampledSaturator(spec.oversampling ?: 1, spec.audio.dspSaturation.toDouble())
    override val latencyFrames = if (bypass || wet == 0.0) 0 else kernel.latencyFrames
    override val tailFrames = if (bypass || wet == 0.0) 0 else kernel.tailFrames
    override fun process(block: AudioBlock) = kernel.process(block)
    override fun reset() = kernel.reset()
    override fun copyState(previous: Node) { if (previous is OversampledNode) kernel.copyStateFrom(previous.kernel) }
}

internal class AlignmentNode(spec: ProcessingRackNode, rate: Int) : Node(spec.id, spec.kind, spec.bypass, spec.wet.toDouble()) {
    override val latencyFrames = if (bypass || wet == 0.0) 0 else ((spec.utility ?: RackUtility()).delayMs * rate / 1000).toInt()
    override val tailFrames = latencyFrames
    private val delay = FrameDelay(latencyFrames)
    override fun process(block: AudioBlock) = delay.process(block.samples, block.frameCount)
    override fun reset() = delay.reset()
    override fun copyState(previous: Node) { if (previous is AlignmentNode) delay.copyStateFrom(previous.delay) }
}

internal class FrameDelay(private val frames: Int) {
    private val history = DoubleArray(frames * 2)
    private var position = 0
    fun process(samples: DoubleArray, count: Int) {
        if (frames == 0) return
        for (i in 0 until count * 2) {
            val previous = history[position]; history[position] = samples[i]; samples[i] = previous
            if (++position == history.size) position = 0
        }
    }
    fun reset() { history.fill(0.0); position = 0 }
    fun copyStateFrom(previous: FrameDelay) {
        if (previous.frames == frames) { previous.history.copyInto(history); position = previous.position }
    }
}
