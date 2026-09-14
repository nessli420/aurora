package com.aurora.music.playback.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Normalized denominator: y = b0*x + z1; z1 = b1*x - a1*y + z2; z2 = b2*x - a2*y. */
data class BiquadCoefficients(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
    init {
        require(b0.isFinite() && b1.isFinite() && b2.isFinite() && a1.isFinite() && a2.isFinite())
        require(abs(a2) < 1.0 && 1.0 + a1 + a2 > 0.0 && 1.0 - a1 + a2 > 0.0) { "Unstable biquad" }
    }

    companion object {
        val IDENTITY = BiquadCoefficients(1.0, 0.0, 0.0, 0.0, 0.0)

        /** RBJ peaking EQ; coefficients, state, and sample calculations remain binary64. */
        fun peaking(sampleRate: Int, frequencyHz: Double, gainDb: Double, q: Double): BiquadCoefficients {
            require(sampleRate in 8_000..768_000)
            require(frequencyHz > 0.0 && frequencyHz < sampleRate / 2.0 && frequencyHz.isFinite())
            require(gainDb.isFinite() && gainDb in -120.0..60.0)
            require(q.isFinite() && q in 0.001..1000.0)
            val a = 10.0.pow(gainDb / 40.0)
            val omega = 2.0 * PI * frequencyHz / sampleRate
            val alpha = sin(omega) / (2.0 * q)
            val a0 = 1.0 + alpha / a
            val c = cos(omega)
            return BiquadCoefficients((1 + alpha * a) / a0, -2 * c / a0, (1 - alpha * a) / a0,
                -2 * c / a0, (1 - alpha / a) / a0)
        }
    }
}

sealed interface RackNodeSpec {
    val id: String
    val name: String
    val bypass: Boolean
    val wet: Double
}

data class GainNodeSpec(
    override val id: String,
    override val name: String = "Gain",
    val linearGain: Double = 1.0,
    override val bypass: Boolean = false,
    override val wet: Double = 1.0
) : RackNodeSpec

data class MonoNodeSpec(
    override val id: String,
    override val name: String = "Mono",
    override val bypass: Boolean = false,
    override val wet: Double = 1.0
) : RackNodeSpec

data class BiquadNodeSpec(
    override val id: String,
    override val name: String = "Parametric EQ",
    val bands: List<BiquadCoefficients>,
    override val bypass: Boolean = false,
    override val wet: Double = 1.0
) : RackNodeSpec

data class RackNodeDescriptor(val id: String, val name: String, val bypass: Boolean, val wet: Double, val capabilities: NodeCapabilities)

/** Fixed stereo slots; an observer on another thread must copy these after the owner publishes. */
class AudioMeterTap internal constructor(val id: String) {
    var frames: Int = 0; private set
    var presentationTimeUs: Long = AUDIO_TIME_UNSET; private set
    var peakLeft: Double = 0.0; private set
    var peakRight: Double = 0.0; private set
    var rmsLeft: Double = 0.0; private set
    var rmsRight: Double = 0.0; private set
    var nonFiniteSamples: Int = 0; private set

    internal fun measure(block: AudioBlock) {
        frames = block.frameCount; presentationTimeUs = block.presentationTimeUs
        var peakL = 0.0; var peakR = 0.0; var sumL = 0.0; var sumR = 0.0; var bad = 0
        val channels = block.format.channelCount
        var i = 0
        while (i < block.sampleCount) {
            val l = block.samples[i++]
            val r = if (channels == 2) block.samples[i++] else l
            if (l.isFinite()) { peakL = maxOf(peakL, abs(l)); sumL += l * l } else bad++
            if (r.isFinite()) { peakR = maxOf(peakR, abs(r)); sumR += r * r } else if (channels == 2) bad++
        }
        peakLeft = peakL; peakRight = peakR; nonFiniteSamples = bad
        rmsLeft = if (frames > 0) sqrt(sumL / frames) else 0.0
        rmsRight = if (frames > 0) sqrt(sumR / frames) else 0.0
    }
}

/**
 * Compiled off the callback. Schedule, coefficients, histories and wet/dry scratch are bounded
 * and preallocated. Single audio-thread owner; no locks or allocation in [process]. No latency-
 * bearing or float32 fallback nodes are accepted in this first prototype. Node bypass freezes
 * its history. This class does not promise click-free graph replacement or preserve old tails.
 */
class PrecisionSerialRack private constructor(
    val format: AudioStreamFormat,
    val capacityFrames: Int,
    private val nodes: Array<CompiledNode>,
    val descriptors: List<RackNodeDescriptor>
) {
    val schemaVersion: Int = 1
    val arithmeticPrecision: SamplePrecision = SamplePrecision.FLOAT_64
    val inputTap = AudioMeterTap("input")
    val nodeTaps: List<AudioMeterTap> = nodes.map { AudioMeterTap(it.descriptor.id) }
    val outputTap = AudioMeterTap("output")
    val latencyFrames: Int = descriptors.sumOf { if (it.bypass) 0 else it.capabilities.latencyFrames }
    val tailFrames: Long? = if (descriptors.any { !it.bypass && it.wet > 0 && it.capabilities.tailFrames == null }) null else 0L
    private val dryScratch = DoubleArray(capacityFrames * format.channelCount)

    fun process(block: AudioBlock) {
        require(block.format == format && block.frameCount <= capacityFrames)
        inputTap.measure(block)
        var n = 0
        while (n < nodes.size) {
            val node = nodes[n]
            val d = node.descriptor
            if (!d.bypass && d.wet > 0.0) {
                if (d.wet < 1.0) System.arraycopy(block.samples, 0, dryScratch, 0, block.sampleCount)
                node.process(block)
                if (d.wet < 1.0) {
                    var i = 0
                    while (i < block.sampleCount) {
                        block.samples[i] = dryScratch[i] * (1.0 - d.wet) + block.samples[i] * d.wet
                        i++
                    }
                }
            }
            nodeTaps[n].measure(block)
            n++
        }
        outputTap.measure(block)
    }

    /** Control-thread operation. One immutable target is observed once at the next block. */
    fun setGainTarget(nodeId: String, linearGain: Double, rampFrames: Int) {
        require(linearGain.isFinite() && linearGain in 0.0..64.0 && rampFrames in 0..format.sampleRate * 10)
        val node = nodes.firstOrNull { it.descriptor.id == nodeId }
        require(node is GainNode) { "No gain node with id $nodeId" }
        node.target = GainTarget(linearGain, rampFrames)
    }

    fun reset() { for (node in nodes) node.reset() }

    companion object {
        const val MAX_NODES = 32
        const val MAX_BIQUADS = 64

        fun compile(format: AudioStreamFormat, capacityFrames: Int, specs: List<RackNodeSpec>): PrecisionSerialRack {
            require(capacityFrames in 1..AudioBlock.MAX_BLOCK_FRAMES)
            require(specs.size <= MAX_NODES)
            require(specs.map { it.id }.distinct().size == specs.size) { "Node IDs must be unique" }
            require(specs.sumOf { if (it is BiquadNodeSpec) it.bands.size else 0 } <= MAX_BIQUADS)
            val nodes = specs.map { spec ->
                require(spec.id.matches(Regex("[A-Za-z0-9_.-]{1,80}")))
                require(spec.name.isNotBlank() && spec.name.length <= 80)
                require(spec.wet.isFinite() && spec.wet in 0.0..1.0)
                val capabilities = NodeCapabilities(SamplePrecision.FLOAT_64, SamplePrecision.FLOAT_64,
                    tailFrames = if (spec is BiquadNodeSpec && spec.bands.isNotEmpty()) null else 0L)
                val descriptor = RackNodeDescriptor(spec.id, spec.name, spec.bypass, spec.wet, capabilities)
                when (spec) {
                    is GainNodeSpec -> {
                        require(spec.linearGain.isFinite() && spec.linearGain in 0.0..64.0)
                        GainNode(descriptor, spec.linearGain)
                    }
                    is MonoNodeSpec -> {
                        require(format.channelLayout == ChannelLayout.STEREO) { "Mono downmix requires stereo input" }
                        MonoNode(descriptor)
                    }
                    is BiquadNodeSpec -> BiquadNode(descriptor, spec.bands.toTypedArray(), format.channelCount)
                }
            }.toTypedArray()
            return PrecisionSerialRack(format, capacityFrames, nodes, nodes.map { it.descriptor })
        }
    }
}

private abstract class CompiledNode(val descriptor: RackNodeDescriptor) {
    abstract fun process(block: AudioBlock)
    open fun reset() = Unit
}

private data class GainTarget(val linearGain: Double, val rampFrames: Int)

private class GainNode(descriptor: RackNodeDescriptor, initial: Double) : CompiledNode(descriptor) {
    @Volatile var target = GainTarget(initial, 0)
    private var observedTarget = target
    private var current = initial
    private var step = 0.0
    private var remaining = 0

    override fun process(block: AudioBlock) {
        val request = target
        if (request !== observedTarget) {
            observedTarget = request
            remaining = request.rampFrames
            if (remaining == 0) current = request.linearGain else step = (request.linearGain - current) / remaining
        }
        var i = 0
        while (i < block.sampleCount) {
            if (remaining > 0) { current += step; if (--remaining == 0) current = request.linearGain }
            block.samples[i++] *= current
            if (block.format.channelCount == 2) block.samples[i++] *= current
        }
    }

    override fun reset() { observedTarget = target; current = observedTarget.linearGain; remaining = 0; step = 0.0 }
}

private class MonoNode(descriptor: RackNodeDescriptor) : CompiledNode(descriptor) {
    override fun process(block: AudioBlock) {
        var i = 0
        while (i < block.sampleCount) {
            val mono = block.samples[i] * 0.5 + block.samples[i + 1] * 0.5
            block.samples[i++] = mono; block.samples[i++] = mono
        }
    }
}

private class BiquadNode(descriptor: RackNodeDescriptor, private val bands: Array<BiquadCoefficients>, private val channels: Int) : CompiledNode(descriptor) {
    private val z1 = DoubleArray(bands.size * channels)
    private val z2 = DoubleArray(bands.size * channels)

    override fun process(block: AudioBlock) {
        var i = 0
        while (i < block.sampleCount) {
            var channel = 0
            while (channel < channels) {
                var x = block.samples[i + channel]
                var band = 0
                while (band < bands.size) {
                    val c = bands[band]
                    val s = band * channels + channel
                    val y = c.b0 * x + z1[s]
                    z1[s] = c.b1 * x - c.a1 * y + z2[s]
                    z2[s] = c.b2 * x - c.a2 * y
                    x = y
                    band++
                }
                block.samples[i + channel] = x
                channel++
            }
            i += channels
        }
    }

    override fun reset() { z1.fill(0.0); z2.fill(0.0) }
}
