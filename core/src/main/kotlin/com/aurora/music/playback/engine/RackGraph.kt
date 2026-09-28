package com.aurora.music.playback.engine

import com.aurora.music.data.*
import kotlin.math.pow

private const val INPUT_FRAMES = ProductionSerialRack.INPUT_FRAMES
private const val OUTPUT_FRAMES = ProductionSerialRack.OUTPUT_FRAMES

internal class ParallelSchedule(rack: ProcessingRack, private val format: AudioStreamFormat,
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
        require(tailFrames <= minOf(format.sampleRate * 32, PrecisionConvolver.MAX_IR_FRAMES * 16)) { "The graph tail exceeds its processing budget." }
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
