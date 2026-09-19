package com.aurora.music.playback.engine

import com.aurora.music.data.*
import com.aurora.music.data.AudioPrefs
import com.aurora.music.data.ProcessingRack
import com.aurora.music.data.ProcessingRackCodec
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

data class RackNodeMeter(val id: String, val peak: Double, val changeDb: Double, val bandChangesDb: List<Double>)

// prepared binary64 rack with bounded routing, aligned branches and convolution tails.
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

    /** Small effects histories migrate by stable id/kind; convolution FFT histories stay owned. */
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

    private abstract class Node(val id: String, val kind: RackNodeKind, val bypass: Boolean, val wet: Double) {
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

    private class EffectsNode(id: String, kind: RackNodeKind, bypass: Boolean, wet: Double,
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
            if (previous is EqNode && previous.channel == channel && previous.bands.contentEquals(bands)) {
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

    private class AdvancedNode(spec: ProcessingRackNode, rate: Int) : Node(spec.id, spec.kind, spec.bypass, spec.wet.toDouble()) {
        override val supportsInputTailHandover = spec.kind == RackNodeKind.UTILITY
        private val kernel: AdvancedRackKernel = when (spec.kind) {
            RackNodeKind.DYNAMIC_EQ -> DynamicEqKernel(spec.dynamic ?: RackDynamicEq(), rate)
            RackNodeKind.MULTIBAND -> MultibandKernel(spec.multiband ?: RackMultiband(), rate)
            RackNodeKind.LOUDNESS -> RelativeLoudnessKernel(spec.loudness ?: RackLoudness(), rate)
            else -> UtilityKernel(spec.utility ?: RackUtility(), rate)
        }
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

    private class SyncConvolutionNode(spec: ProcessingRackNode, private val impulse: ImpulseResponse, rate: Int) : Node(spec.id, spec.kind, spec.bypass, spec.wet.toDouble()) {
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

    private class OversampledNode(spec: ProcessingRackNode) : Node(spec.id, spec.kind, spec.bypass, spec.wet.toDouble()) {
        override val supportsInputTailHandover = spec.audio.dspSaturation == 0f
        private val kernel = OversampledSaturator(spec.oversampling ?: 1, spec.audio.dspSaturation.toDouble())
        override val latencyFrames = if (bypass || wet == 0.0) 0 else kernel.latencyFrames
        override val tailFrames = if (bypass || wet == 0.0) 0 else kernel.tailFrames
        override fun process(block: AudioBlock) = kernel.process(block)
        override fun reset() = kernel.reset()
        override fun copyState(previous: Node) { if (previous is OversampledNode) kernel.copyStateFrom(previous.kernel) }
    }

    private class AlignmentNode(spec: ProcessingRackNode, rate: Int) : Node(spec.id, spec.kind, spec.bypass, spec.wet.toDouble()) {
        override val latencyFrames = if (bypass || wet == 0.0) 0 else ((spec.utility ?: RackUtility()).delayMs * rate / 1000).toInt()
        override val tailFrames = latencyFrames
        private val delay = FrameDelay(latencyFrames)
        override fun process(block: AudioBlock) = delay.process(block.samples, block.frameCount)
        override fun reset() = delay.reset()
        override fun copyState(previous: Node) { if (previous is AlignmentNode) delay.copyStateFrom(previous.delay) }
    }

    private class FrameDelay(private val frames: Int) {
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

    private class ParallelSchedule(rack: ProcessingRack, private val format: AudioStreamFormat,
        private val nodes: Array<Node>, private val inputGain: Double) {
        private class Edge(val source: Int, val channel: RackChannel, val gain: Double, val delay: FrameDelay) {
            val scratch = DoubleArray(OUTPUT_FRAMES * 2)
        }
        private val input = AudioBlock(format, OUTPUT_FRAMES)
        private val output = AudioBlock(format, OUTPUT_FRAMES)
        private val pending = AudioBlock(format, INPUT_FRAMES)
        private var pendingPosition = 0
        private var pendingCount = 0
        private val blocks = Array(nodes.size) { AudioBlock(format, OUTPUT_FRAMES) }
        private val wetScratch = DoubleArray(OUTPUT_FRAMES * 2)
        private val dryDelays = Array(nodes.size) { FrameDelay(nodes[it].latencyFrames) }
        private val connections: Array<Array<Edge>>
        private val outputs: Array<Edge>
        val latencyFrames: Int
        val tailFrames: Int
        val processingChangesSamples: Boolean
        val convolutionActive: Boolean
        val supportsInputTailHandover: Boolean
        var relativeVolume = 1.0
        private var inputCount = 0
        private var ready = false
        private var ended = false
        private var inputSeen = false
        private var tailRemaining = 0
        private var nextTime = AUDIO_TIME_UNSET
        private var nextFrame = AUDIO_TIME_UNSET
        val framesUntilBoundary get() = OUTPUT_FRAMES - inputCount
        val canAcceptInput get() = !ended && !ready && pendingPosition == pendingCount
        val hasPendingData get() = ready || inputCount > 0 || pendingPosition < pendingCount || ended && tailRemaining > 0
        val isEnded get() = ended && !hasPendingData
        init {
            val latencies = IntArray(nodes.size)
            val tails = IntArray(nodes.size)
            fun sourceIndex(source: String) = if (source == RackInput.INPUT) -1 else rack.nodes.indexOfFirst { it.id == source }
            fun edges(inputs: List<RackInput>, maximum: Int) = inputs.map { spec ->
                val source = sourceIndex(spec.source)
                Edge(source, spec.channel, 10.0.pow(spec.gainDb / 20), FrameDelay(maximum - if (source < 0) 0 else latencies[source]))
            }.toTypedArray()
            connections = Array(nodes.size) { index ->
                val inputs = rack.nodes[index].inputs ?: listOf(RackInput(if (index == 0) RackInput.INPUT else nodes[index - 1].id))
                val maximum = inputs.maxOf { source -> sourceIndex(source.source).let { if (it < 0) 0 else latencies[it] } }
                val intrinsic = nodes[index].latencyFrames
                latencies[index] = maximum + intrinsic
                require(latencies[index] <= format.sampleRate * 2) { "The graph exceeds two seconds of alignment delay." }
                tails[index] = inputs.maxOf { source -> sourceIndex(source.source).let { if (it < 0) maximum else tails[it] + maximum - latencies[it] } } + nodes[index].tailFrames
                edges(inputs, maximum)
            }
            val outputSpec = rack.output ?: listOf(RackInput(nodes.lastOrNull()?.id ?: RackInput.INPUT))
            latencyFrames = outputSpec.maxOf { sourceIndex(it.source).let { source -> if (source < 0) 0 else latencies[source] } }
            tailFrames = outputSpec.maxOf { sourceIndex(it.source).let { source -> if (source < 0) latencyFrames else tails[source] + latencyFrames - latencies[source] } }
            require(tailFrames <= PrecisionConvolver.MAX_IR_FRAMES * 4) { "The graph tail exceeds its processing budget." }
            outputs = edges(outputSpec, latencyFrames)
            val reachable = BooleanArray(nodes.size)
            fun visit(source: Int) {
                if (source < 0 || reachable[source]) return
                reachable[source] = true; connections[source].forEach { visit(it.source) }
            }
            outputs.forEach { visit(it.source) }
            convolutionActive = nodes.indices.any { reachable[it] && nodes[it] is SyncConvolutionNode }
            supportsInputTailHandover = nodes.indices.all {
                !reachable[it] || nodes[it].bypass || nodes[it].wet == 0.0 || nodes[it].supportsInputTailHandover
            }
            fun changesRouting(edges: Array<Edge>) = edges.size != 1 || edges.any { it.channel != RackChannel.STEREO || it.gain != 1.0 }
            processingChangesSamples = inputGain != 1.0 || changesRouting(outputs) || nodes.indices.any {
                reachable[it] && (!nodes[it].bypass && nodes[it].wet > 0.0 || changesRouting(connections[it]))
            }
        }
        fun queueInput(block: AudioBlock): Boolean {
            require(block.format == format && block.frameCount <= INPUT_FRAMES)
            if (!canAcceptInput) return false
            pending.begin(block.frameCount, block.presentationTimeUs, block.firstFramePosition)
            block.samples.copyInto(pending.samples, endIndex = block.sampleCount)
            pendingPosition = 0; pendingCount = block.frameCount
            inputSeen = inputSeen || block.frameCount > 0
            feedPending()
            return true
        }
        private fun feedPending() {
            if (ready || pendingPosition == pendingCount) return
            if (inputCount == 0) {
                nextTime = if (pending.presentationTimeUs == AUDIO_TIME_UNSET) AUDIO_TIME_UNSET else pending.presentationTimeUs + pendingPosition * 1_000_000L / format.sampleRate
                nextFrame = if (pending.firstFramePosition == AUDIO_TIME_UNSET) AUDIO_TIME_UNSET else pending.firstFramePosition + pendingPosition
            }
            val count = minOf(pendingCount - pendingPosition, framesUntilBoundary)
            System.arraycopy(pending.samples, pendingPosition * 2, input.samples, inputCount * 2, count * 2)
            inputCount += count; pendingPosition += count
            if (inputCount == OUTPUT_FRAMES) render(OUTPUT_FRAMES)
        }
        private fun mix(edges: Array<Edge>, destination: AudioBlock) {
            destination.samples.fill(0.0)
            for (edge in edges) {
                val source = if (edge.source < 0) input.samples else blocks[edge.source].samples
                for (frame in 0 until OUTPUT_FRAMES) {
                    val i = frame * 2; val l = source[i]; val r = source[i + 1]
                    val left: Double; val right: Double
                    when (edge.channel) {
                        RackChannel.STEREO -> { left = l; right = r }
                        RackChannel.LEFT -> { left = l; right = 0.0 }
                        RackChannel.RIGHT -> { left = 0.0; right = r }
                        RackChannel.MID -> { left = (l + r) * .5; right = left }
                        RackChannel.SIDE -> { left = (l - r) * .5; right = -left }
                        RackChannel.ENCODE_MS -> { left = (l + r) * .5; right = (l - r) * .5 }
                        RackChannel.DECODE_MS -> { left = l + r; right = l - r }
                    }
                    edge.scratch[i] = left * edge.gain; edge.scratch[i + 1] = right * edge.gain
                }
                edge.delay.process(edge.scratch, OUTPUT_FRAMES)
                for (i in 0 until OUTPUT_FRAMES * 2) destination.samples[i] += edge.scratch[i]
            }
        }
        private fun render(validFrames: Int) {
            input.begin(OUTPUT_FRAMES, nextTime, nextFrame)
            if (inputGain != 1.0) for (i in 0 until input.sampleCount) input.samples[i] *= inputGain
            for (index in nodes.indices) {
                val node = nodes[index]; val block = blocks[index]
                block.begin(OUTPUT_FRAMES, nextTime, nextFrame); mix(connections[index], block)
                if (!node.bypass && node.wet > 0.0) {
                    if (node.wet < 1.0) { block.samples.copyInto(wetScratch); dryDelays[index].process(wetScratch, OUTPUT_FRAMES) }
                    node.volume = relativeVolume; node.process(block)
                    if (node.wet < 1.0) for (i in 0 until block.sampleCount) if (node.affectsChannel(i and 1))
                        block.samples[i] = wetScratch[i] * (1 - node.wet) + block.samples[i] * node.wet
                }
                node.measure(block)
            }
            mix(outputs, output); output.begin(validFrames, nextTime, nextFrame)
            if (nextTime != AUDIO_TIME_UNSET) nextTime += OUTPUT_FRAMES * 1_000_000L / format.sampleRate
            if (nextFrame != AUDIO_TIME_UNSET) nextFrame += OUTPUT_FRAMES
            inputCount = 0; ready = true
        }
        fun getOutput(): AudioBlock? {
            feedPending()
            if (!ready && ended && (inputCount > 0 || tailRemaining > 0)) {
                val valid = minOf(OUTPUT_FRAMES, inputCount + tailRemaining)
                tailRemaining -= minOf(tailRemaining, OUTPUT_FRAMES - inputCount)
                java.util.Arrays.fill(input.samples, inputCount * 2, input.samples.size, 0.0)
                render(valid)
            }
            if (!ready) return null
            ready = false; return output
        }
        fun queueEndOfStream(drainTail: Boolean) { if (!ended) { ended = true; tailRemaining = if (inputSeen && drainTail) tailFrames else 0 } }
        fun reset() {
            inputCount = 0; pendingPosition = 0; pendingCount = 0; ready = false; ended = false; inputSeen = false; tailRemaining = 0
            connections.forEach { it.forEach { edge -> edge.delay.reset() } }; outputs.forEach { it.delay.reset() }
            dryDelays.forEach { it.reset() }; input.samples.fill(0.0)
            nextTime = AUDIO_TIME_UNSET; nextFrame = AUDIO_TIME_UNSET
        }
        fun copyDelaysFrom(previous: ParallelSchedule) {
            if (nodes.size != previous.nodes.size || nodes.indices.any { nodes[it].id != previous.nodes[it].id }) return
            for (i in connections.indices) {
                if (connections[i].size != previous.connections[i].size) continue
                for (j in connections[i].indices) {
                    val current = connections[i][j]; val old = previous.connections[i][j]
                    if (current.source == old.source && current.channel == old.channel && current.gain == old.gain) current.delay.copyStateFrom(old.delay)
                }
                dryDelays[i].copyStateFrom(previous.dryDelays[i])
            }
            if (outputs.size == previous.outputs.size) for (i in outputs.indices) {
                val current = outputs[i]; val old = previous.outputs[i]
                if (current.source == old.source && current.channel == old.channel && current.gain == old.gain) current.delay.copyStateFrom(old.delay)
            }
        }
    }

    companion object {
        const val INPUT_FRAMES = 256
        const val OUTPUT_FRAMES = 1024

        /** Called on a preparation worker, never by queueInput/getOutput. */
        fun compile(rack: ProcessingRack, sampleRate: Int, impulse: ImpulseResponse?,
            impulseMap: Map<String, ImpulseResponse> = emptyMap(), relativeVolume: Double = 1.0): ProductionSerialRack {
            ProcessingRackCodec.validate(rack)
            val oversamplingLoad = rack.nodes.filter { it.kind == RackNodeKind.SATURATION && !it.bypass && it.wet > 0f && (it.oversampling ?: 1) > 1 }
                .sumOf { (it.oversampling ?: 1).toLong() * sampleRate }
            require(oversamplingLoad <= 16L * 96000) { "Saturation oversampling exceeds the processing budget at this sample rate." }
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
                RackNodeKind.UTILITY, RackNodeKind.DYNAMIC_EQ, RackNodeKind.MULTIBAND, RackNodeKind.LOUDNESS -> AdvancedNode(spec, sampleRate)
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
    fun pushSilence(count: Int) {
        check(frames + count <= capacity)
        for (i in 0 until count) {
            val p = (read + frames + i) % capacity * 2
            samples[p] = 0.0; samples[p + 1] = 0.0
        }
        frames += count
    }
}

private class RackOutputDelay(private val format: AudioStreamFormat) {
    private val capacity = format.sampleRate * 2 + 1
    private val history = DoubleArray(capacity * 2)
    private val tail = AudioBlock(format, ProductionSerialRack.OUTPUT_FRAMES)
    private var position = 0
    var frames = 0; private set
    private var previousFrames = 0
    private var changePosition = 0
    private var changeFrames = 0
    private var seenInput = false
    private var remainingTail = -1
    private var time = AUDIO_TIME_UNSET
    private var frame = AUDIO_TIME_UNSET
    val isEnded get() = remainingTail == 0
    fun setFrames(value: Int, fadeFrames: Int = 0) {
        require(value in 0 until capacity)
        previousFrames = frames; frames = value; changePosition = 0; changeFrames = fadeFrames
    }
    fun process(block: AudioBlock) {
        seenInput = seenInput || block.frameCount > 0
        for (i in 0 until block.frameCount) {
            val blend = if (changeFrames == 0 || changePosition >= changeFrames) 1.0 else (++changePosition).toDouble().div(changeFrames).coerceAtMost(1.0)
            val read = (position - frames + capacity) % capacity
            val oldRead = (position - previousFrames + capacity) % capacity
            for (channel in 0..1) {
                history[position * 2 + channel] = block.samples[i * 2 + channel]
                block.samples[i * 2 + channel] = history[oldRead * 2 + channel] * (1 - blend) + history[read * 2 + channel] * blend
            }
            if (++position == capacity) position = 0
        }
        time = if (block.presentationTimeUs == AUDIO_TIME_UNSET) AUDIO_TIME_UNSET else block.presentationTimeUs + block.frameCount * 1_000_000L / format.sampleRate
        frame = if (block.firstFramePosition == AUDIO_TIME_UNSET) AUDIO_TIME_UNSET else block.firstFramePosition + block.frameCount
    }
    fun finish(drainTail: Boolean = true) { if (remainingTail < 0) remainingTail = if (seenInput && drainTail) frames else 0 }
    fun getTail(drainTail: Boolean): AudioBlock? {
        finish(drainTail)
        if (remainingTail == 0) return null
        val count = minOf(remainingTail, tail.capacityFrames)
        tail.begin(count, time, frame); tail.samples.fill(0.0); process(tail); remainingTail -= count
        return tail
    }
    fun reset() {
        history.fill(0.0); position = 0; previousFrames = frames; changeFrames = 0; changePosition = 0
        seenInput = false; remainingTail = -1; time = AUDIO_TIME_UNSET; frame = AUDIO_TIME_UNSET
    }
    fun copyStateFrom(previous: RackOutputDelay) {
        if (frames != previous.frames || format != previous.format) return
        previous.history.copyInto(history); position = previous.position; previousFrames = frames; changeFrames = 0
        seenInput = previous.seenInput; time = previous.time; frame = previous.frame
    }
}

/**
 * Coalesced worker preparation and matched-input,20ms graph crossfades. A swap begins only
 * when old accepted audio has naturally reached a block boundary, avoiding padded/reset IR
 * history. At most two graphs process audio; one replacement may be preparing. Two bounded
 * FIFOs align their output by SOURCE FRAME rather than wall-clock readiness or FFT block size.
 */
class ProductionRackProcessor {
    private data class Request(val rate: Int, val rack: ProcessingRack?, val legacy: DspParams,
        val effects: Boolean, val convolution: Boolean, val makeupDb: Float, val impulse: ImpulseResponse?, val legacyKey: List<Any>, val impulseMap: Map<String, ImpulseResponse>)
    private data class Prepared(val request: Request, val graph: ProductionSerialRack?, val failure: String?,
        val mixOutput: AudioBlock? = graph?.let { AudioBlock(it.format, ProductionSerialRack.OUTPUT_FRAMES) },
        val input: AudioBlock? = graph?.let { AudioBlock(it.format, ProductionSerialRack.INPUT_FRAMES) },
        val inputSlice: AudioBlock? = graph?.let { AudioBlock(it.format, ProductionSerialRack.INPUT_FRAMES) },
        val fadingSlice: AudioBlock? = graph?.let { AudioBlock(it.format, ProductionSerialRack.INPUT_FRAMES) },
        val outputDelay: RackOutputDelay? = graph?.let { RackOutputDelay(it.format) })
    private val lock = Any()
    private var rate = 0
    private var desiredRack: ProcessingRack? = null
    private var legacy = DspParams()
    private var effects = false
    private var convolution = false
    private var makeupDb = 0f
    private var impulse: ImpulseResponse? = null
    private var impulseMap: Map<String, ImpulseResponse> = emptyMap()
    @Volatile private var requested: Request? = null
    @Volatile private var completed: Prepared? = null
    private var workerRunning = false
    private var active: Prepared? = null
    private var fading: ProductionSerialRack? = null
    private var fadingPrepared: Prepared? = null
    private var output: AudioBlock? = null
    private var input: AudioBlock? = null
    private var inputSlice: AudioBlock? = null
    private var fadingSlice: AudioBlock? = null
    private var inputPosition = 0
    private var inputCount = 0
    private val oldOutput = SampleFifo(4096)
    private val newOutput = SampleFifo(4096)
    private var blendPosition = 0L
    private var blendFrames = 0L
    private var preserveTail = false
    private var fadeInputPosition = 0L
    private var handoverFrames = 0L
    private var outputFrames = 0L
    private var anchorTimeUs = AUDIO_TIME_UNSET
    private var anchorFrame = AUDIO_TIME_UNSET
    private var anchorSet = false
    private var ended = false
    private var drainTailOnEnd = true
    private var initialLegacyHistory: PrecisionEffectsKernel? = null
    @Volatile var relativeVolume: Double = 1.0
    val latencyFrames: Int get() = (active?.graph?.latencyFrames ?: 0) + (active?.outputDelay?.frames ?: 0)
    val tailFrames: Int get() = (active?.graph?.tailFrames ?: 0) + (active?.outputDelay?.frames ?: 0)
    val processingChangesSamples: Boolean get() = active?.graph?.processingChangesSamples == true || fading?.processingChangesSamples == true ||
        (active?.outputDelay?.frames ?: 0) > 0 || (fadingPrepared?.outputDelay?.frames ?: 0) > 0
    fun meterSnapshot(): List<RackNodeMeter> = active?.graph?.meterSnapshot() ?: emptyList()
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
    val hasPendingData: Boolean get() = inputPosition < inputCount || oldOutput.frames > 0 || newOutput.frames > 0 ||
        active?.graph?.hasPendingData == true || fading?.hasPendingData == true ||
        ended && (active?.let { !fullyEnded(it) } == true || fadingPrepared?.let { !fullyEnded(it) } == true)
    val isEnded: Boolean get() = ended && !hasPendingData

    fun update(rack: ProcessingRack?, params: DspParams, enabled: Boolean, conv: Boolean, makeup: Float, ir: ImpulseResponse?, impulseMap: Map<String, ImpulseResponse> = emptyMap()) {
        synchronized(lock) {
            desiredRack = rack?.takeIf { it.enabled }
            legacy = params
            effects = enabled; convolution = conv; makeupDb = makeup; impulse = ir; this.impulseMap = impulseMap.toMap()
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
        graph.relativeVolume = relativeVolume; fading?.relativeVolume = relativeVolume
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
                graph.queueEndOfStream(drainTailOnEnd); fading?.queueEndOfStream(drainTailOnEnd)
            }
            if (fading == null) {
                outputOf(checkNotNull(active))?.let { return it }
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
        if (previous != null && preserveTail) {
            val oldSlice = checkNotNull(fadingSlice)
            oldSlice.begin(count, time, frame)
            for (i in 0 until count) {
                val gain = ((fadeInputPosition + i + 1).toDouble() / blendFrames).coerceIn(0.0, 1.0)
                oldSlice.samples[i * 2] = slice.samples[i * 2] * (1 - gain)
                oldSlice.samples[i * 2 + 1] = slice.samples[i * 2 + 1] * (1 - gain)
                slice.samples[i * 2] *= gain; slice.samples[i * 2 + 1] *= gain
            }
            fadeInputPosition += count
            check(previous.queueInput(oldSlice))
        } else if (previous != null) check(previous.queueInput(slice))
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
        if (ended && fullyEnded(checkNotNull(fadingPrepared)) && oldOutput.frames == 0 && newOutput.frames > 0) oldOutput.pushSilence(newOutput.frames)
        if (ended && fullyEnded(checkNotNull(active)) && newOutput.frames == 0 && oldOutput.frames > 0) {
            if (preserveTail) newOutput.pushSilence(oldOutput.frames) else { oldOutput.reset(); fading = null; fadingPrepared = null; return null }
        }
        if (ended && fullyEnded(checkNotNull(active)) && fullyEnded(checkNotNull(fadingPrepared)) && oldOutput.frames == 0 && newOutput.frames == 0) { fading = null; fadingPrepared = null; return null }
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
            result.samples[i * 2] = if (preserveTail) oldL + newL else oldL * (1.0 - wet) + newL * wet
            result.samples[i * 2 + 1] = if (preserveTail) oldR + newR else oldR * (1.0 - wet) + newR * wet
            blendPosition++; i++
        }
        outputFrames += count
        if (blendPosition >= handoverFrames && newOutput.frames == 0) {
            fading = null; fadingPrepared = null; oldOutput.reset()
        }
        return result
    }

    private fun drainToFifo(graph: ProductionSerialRack, fifo: SampleFifo) {
        val prepared = if (active?.graph === graph) checkNotNull(active) else checkNotNull(fadingPrepared)
        var i = 0
        while (i++ < 4 && fifo.frames <= 3072) {
            val block = outputOf(prepared) ?: return
            fifo.push(block.samples, 0, block.frameCount)
        }
    }

    private fun outputOf(prepared: Prepared): AudioBlock? {
        val graph = checkNotNull(prepared.graph); val delay = checkNotNull(prepared.outputDelay)
        val block = graph.getOutput()
        if (block != null) { delay.process(block); return block }
        return if (graph.isEnded) delay.getTail(drainTailOnEnd) else null
    }
    private fun fullyEnded(prepared: Prepared): Boolean {
        if (prepared.graph?.isEnded != true) return false
        prepared.outputDelay?.finish(drainTailOnEnd)
        return prepared.outputDelay?.isEnded == true
    }

    private fun applyPrepared() {
        val candidate = completed ?: return
        if (candidate.request !== requested || candidate.graph == null || candidate === active || ended || fading != null) return
        if (candidate.failure != null && active != null) return // Keep the last working graph on a rejected edit.
        val old = active?.graph
        val oldPrepared = active
        if (old?.hasPendingData == true) return
        val inputTailHandover = old != null && old.supportsInputTailHandover && candidate.graph.supportsInputTailHandover &&
            (old.tailFrames > 0 || candidate.graph.tailFrames > 0 || (oldPrepared?.outputDelay?.frames ?: 0) > 0)
        candidate.graph.reset()
        if (old != null && !inputTailHandover) candidate.graph.copyNodeHistoriesFrom(old)
        else initialLegacyHistory?.let { candidate.graph.copyLegacyHistoryFrom(it) }
        initialLegacyHistory = null
        active = candidate
        output = candidate.mixOutput
        fadingSlice = candidate.fadingSlice
        if (input == null || input?.format != candidate.graph.format) {
            check(inputPosition == inputCount)
            input = candidate.input; inputSlice = candidate.inputSlice
        }
        rackActive = candidate.graph.explicitRack
        description = candidate.graph.description
        convolutionUnavailableReason = candidate.graph.convolutionUnavailableReason
        if (old != null) {
            fadingPrepared = oldPrepared
            fading = old; blendPosition = 0; blendFrames = (candidate.graph.format.sampleRate / 50).coerceAtLeast(1).toLong()
            val oldDelay = checkNotNull(oldPrepared?.outputDelay)
            val newDelay = checkNotNull(candidate.outputDelay)
            val commonLatency = maxOf(old.latencyFrames + oldDelay.frames, candidate.graph.latencyFrames)
            oldDelay.setFrames(commonLatency - old.latencyFrames, blendFrames.toInt())
            newDelay.setFrames(commonLatency - candidate.graph.latencyFrames)
            // nonlinear stages keep full-level input; their live replacement fades for 20 ms.
            preserveTail = inputTailHandover
            if (!preserveTail) newDelay.copyStateFrom(oldDelay)
            fadeInputPosition = 0
            handoverFrames = blendFrames + if (preserveTail) old.tailFrames + oldDelay.frames else 0
            outputFrames = 0; anchorTimeUs = AUDIO_TIME_UNSET; anchorFrame = AUDIO_TIME_UNSET
            anchorSet = false
            oldOutput.reset(); newOutput.reset()
        }
    }

    fun queueEndOfStream(drainTail: Boolean = true) {
        if (!ended) drainTailOnEnd = drainTail
        ended = true
        if (inputPosition == inputCount) {
            active?.graph?.queueEndOfStream(drainTailOnEnd); fading?.queueEndOfStream(drainTailOnEnd)
        }
    }
    fun flush() {
        active?.graph?.reset(); active?.outputDelay?.setFrames(0); active?.outputDelay?.reset(); fading = null; fadingPrepared = null
        oldOutput.reset(); newOutput.reset(); ended = false; drainTailOnEnd = true
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
        if (previous != null && previous.rate == rate && previous.rack == desiredRack && previous.impulse === impulse && previous.impulseMap == impulseMap &&
            (desiredRack != null || previous.legacyKey == key)) return
        requested = Request(rate, desiredRack, legacy, effects, convolution, makeupDb, impulse, key, impulseMap)
        completed = null
        if (!workerRunning) { workerRunning = true; PREPARATION.execute { prepare() } }
    }

    private fun prepare() {
        while (true) {
            val request = synchronized(lock) { requested ?: run { workerRunning = false; return } }
            val result = try {
                Prepared(request, if (request.rack != null) ProductionSerialRack.compile(request.rack, request.rate, request.impulse, request.impulseMap)
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
