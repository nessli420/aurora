package com.aurora.music.playback.engine

import com.aurora.music.data.ProcessingRack
import com.aurora.music.data.RackEqChannel
import com.aurora.music.data.RackNodeKind
import com.aurora.music.data.*
import com.aurora.music.playback.ImpulseResponse
import kotlin.math.*

data class RackHeadroom(val sampleRate: Int, val estimatedBoostDb: Double, val attenuationDb: Double,
    val nonlinear: Boolean, val firBound: Boolean) {
    val gain: Double get() = 10.0.pow(attenuationDb / 20.0)
    fun description(): String = if (gain == 0.0) "Auto headroom muted · Excessive gain" else java.lang.String.format(java.util.Locale.ROOT,
        "Auto headroom %.1f dB at %.1f kHz%s", attenuationDb, sampleRate / 1000.0,
        if (nonlinear) " · Linear estimate; nonlinear stages excluded" else if (firBound) " · Conservative FIR bound" else " · Linear estimate")
}

object RackHeadroomAnalyzer {
    fun analyze(rack: ProcessingRack, rate: Int, impulse: ImpulseResponse?, impulseMap: Map<String, ImpulseResponse> = emptyMap()): RackHeadroom {
        require(rate in 8_000..768_000)
        if (rack.usesGraph() || rack.nodes.any { it.kind in listOf(RackNodeKind.UTILITY, RackNodeKind.DYNAMIC_EQ, RackNodeKind.MULTIBAND, RackNodeKind.LOUDNESS) || it.impulseId != null }) {
            return graphBound(rack, rate, impulse, impulseMap)
        }
        val active = rack.nodes.filter { !it.bypass && it.wet > 0f }
        val prepared = active.map { node ->
            val eq = when (node.kind) {
                RackNodeKind.EQ, RackNodeKind.LEGACY_DSP -> ProductionEqCoefficients.build(node.audio, rate)
                else -> emptyArray()
            }
            Triple(node, eq, PrecisionDspCoeffBuilder.build(ProductionSerialRack.params(node.kind, node.audio), rate))
        }
        val frequencies = sortedSetOf(0.0, rate * .499999)
        repeat(2048) { frequencies += 1.0 * (rate * .499999).pow(it / 2047.0) }
        active.forEach { node -> node.audio.dspParametric.filter { it.isEnabled }.forEach { band ->
            for (offset in -8..8) frequencies += (band.freqHz.toDouble() * (1 + offset / (32.0 * band.q.coerceAtLeast(.1f))))
                .coerceIn(0.0, rate * .499999)
            band.coefficients?.let { coefficients ->
                val a1 = coefficients[3]; val a2 = coefficients[4]
                if (a2 > 0 && a1 * a1 < 4 * a2) {
                    val radius = sqrt(a2)
                    val angle = acos((-a1 / (2 * radius)).coerceIn(-1.0, 1.0))
                    for (offset in listOf(-8.0, -4.0, -2.0, -1.0, -.5, -.25, 0.0, .25, .5, 1.0, 2.0, 4.0, 8.0)) {
                        frequencies += ((angle + offset * (1 - radius)) * rate / (2 * PI)).coerceIn(0.0, rate * .499999)
                    }
                }
            }
        } }
        val firL = impulse?.let { ProductionSerialRack.resample(it.preciseLeft, it.sampleRate, rate).sumOf(::abs) + (it.preciseRightToLeft?.let { cross -> ProductionSerialRack.resample(cross, it.sampleRate, rate).sumOf(::abs) } ?: 0.0) } ?: 1.0
        val firR = impulse?.let { ProductionSerialRack.resample(it.preciseRight, it.sampleRate, rate).sumOf(::abs) + (it.preciseLeftToRight?.let { cross -> ProductionSerialRack.resample(cross, it.sampleRate, rate).sumOf(::abs) } ?: 0.0) } ?: 1.0
        var maximumLog = Double.NEGATIVE_INFINITY
        for (frequency in frequencies) {
            val w = 2 * PI * frequency / rate
            var left = 1.0
            var right = 1.0
            var logScale = 0.0
            for ((node, eq, c) in prepared) {
                val wet = node.wet.toDouble()
                var ll = 1.0; var rr = 1.0; var lr = 0.0; var rl = 0.0
                when (node.kind) {
                    RackNodeKind.EQ -> {
                        val response = response(eq, w, wet)
                        if (node.eqChannel != RackEqChannel.RIGHT) ll = response
                        if (node.eqChannel != RackEqChannel.LEFT) rr = response
                    }
                    RackNodeKind.CONVOLUTION -> if (impulse != null) {
                        val makeup = 10.0.pow(node.audio.dspConvMakeupDb / 20.0)
                        ll = 1 - wet + wet * makeup * firL
                        rr = 1 - wet + wet * makeup * firR
                    }
                    else -> {
                        val gain = if (eq.isEmpty()) 1.0 else response(eq, w, 1.0)
                        val l = gain * c.preampLin * c.balL * c.trimL
                        val r = gain * c.preampLin * c.balR * c.trimR
                        val mid = abs((1 + c.width) / 2)
                        val side = abs((1 - c.width) / 2)
                        val cross = c.crossfeedAmt * (1 - c.crossfeedLpfA) /
                            sqrt(1 + c.crossfeedLpfA * c.crossfeedLpfA - 2 * c.crossfeedLpfA * cos(w))
                        ll = (mid + cross * side) * l
                        lr = (side + cross * mid) * r
                        rl = (side + cross * mid) * l
                        rr = (mid + cross * side) * r
                        ll = 1 - wet + wet * ll; rr = 1 - wet + wet * rr
                        lr *= wet; rl *= wet
                    }
                }
                val nextL = ll * left + lr * right
                right = rl * left + rr * right
                left = nextL
                val scale = max(left, right)
                if (!scale.isFinite()) { logScale = Double.POSITIVE_INFINITY; break }
                if (scale == 0.0) { logScale = Double.NEGATIVE_INFINITY; break }
                left /= scale; right /= scale; logScale += ln(scale)
            }
            maximumLog = max(maximumLog, logScale)
        }
        val boost = when (maximumLog) {
            Double.POSITIVE_INFINITY -> Double.MAX_VALUE
            Double.NEGATIVE_INFINITY -> -600.0
            else -> 20 * maximumLog / ln(10.0)
        }
        val nonlinear = active.any { it.kind == RackNodeKind.SATURATION && it.audio.dspSaturation > 0 ||
            it.kind == RackNodeKind.LEGACY_DSP && it.audio.dspSaturation > 0 }
        return RackHeadroom(rate, boost, if (boost > .001) -boost - 1.0 else 0.0,
            nonlinear, impulse != null && active.any { it.kind == RackNodeKind.CONVOLUTION })
    }

    private fun graphBound(rack: ProcessingRack, rate: Int, shared: ImpulseResponse?, impulses: Map<String, ImpulseResponse>): RackHeadroom {
        val gains = mutableMapOf(RackInput.INPUT to 0.0)
        fun sum(inputs: List<RackInput>): Double {
            val logs = inputs.map { checkNotNull(gains[it.source]) + it.gainDb * ln(10.0) / 20 + if (it.channel == RackChannel.DECODE_MS) ln(2.0) else 0.0 }
            val maximum = logs.max()
            return maximum + ln(logs.sumOf { exp(it - maximum) })
        }
        rack.nodes.forEachIndexed { index, node ->
            val inputs = node.inputs ?: listOf(RackInput(rack.nodes.getOrNull(index - 1)?.id ?: RackInput.INPUT))
            val contribution = if (node.bypass || node.wet == 0f) 0.0 else {
                val boost = when (node.kind) {
                    RackNodeKind.ALIGNMENT_DELAY -> 0.0
                    RackNodeKind.DYNAMIC_EQ -> (node.dynamic ?: RackDynamicEq()).let { it.dynamics.makeupDb + if (it.upward) it.dynamics.rangeDb else 0.0 }
                    RackNodeKind.MULTIBAND -> 20 * log10((node.multiband ?: RackMultiband()).bands.sumOf { if (it.mute) 0.0 else 10.0.pow(it.makeupDb / 20) }.coerceAtLeast(1e-15))
                    RackNodeKind.LOUDNESS -> (node.loudness ?: RackLoudness()).let { it.bassCapDb + it.trebleCapDb }
                    RackNodeKind.UTILITY -> (node.utility ?: RackUtility()).let {
                        20 * log10(max(abs(it.ll) + abs(it.lr), abs(it.rl) + abs(it.rr)).coerceAtLeast(1e-15)) + if (it.monoBassHz > 0) 6.021 else 0.0
                    }
                    else -> {
                        val ir = impulses[node.impulseId] ?: if (node.impulseId == null) shared else null
                        analyze(ProcessingRack(nodes = listOf(node.copy(inputs = null, impulseId = null, wet = 1f, oversampling = null)), autoHeadroom = false), rate, ir).estimatedBoostDb
                    }
                }
                val logWet = boost * ln(10.0) / 20 + ln(node.wet.toDouble())
                if (node.wet == 1f) logWet else {
                    val logDry = ln(1 - node.wet.toDouble()); val peak = max(logWet, logDry)
                    peak + ln(exp(logWet - peak) + exp(logDry - peak))
                }
            }
            gains[node.id] = sum(inputs) + contribution
        }
        val boost = 20 * sum(rack.output ?: listOf(RackInput(rack.nodes.lastOrNull()?.id ?: RackInput.INPUT))) / ln(10.0)
        return RackHeadroom(rate, boost, if (boost > .001) -boost - 1 else 0.0,
            rack.nodes.any { it.kind in listOf(RackNodeKind.SATURATION, RackNodeKind.DYNAMIC_EQ, RackNodeKind.MULTIBAND) },
            rack.nodes.any { it.kind == RackNodeKind.CONVOLUTION })
    }

    private fun response(bank: Array<BiquadCoefficients>, w: Double, wet: Double): Double {
        val cos1 = cos(w); val sin1 = -sin(w); val cos2 = cos(2 * w); val sin2 = -sin(2 * w)
        var re = 1.0; var im = 0.0
        for (c in bank) {
            val nr = c.b0 + c.b1 * cos1 + c.b2 * cos2
            val ni = c.b1 * sin1 + c.b2 * sin2
            val dr = 1 + c.a1 * cos1 + c.a2 * cos2
            val di = c.a1 * sin1 + c.a2 * sin2
            val denominator = dr * dr + di * di
            val hr = (nr * dr + ni * di) / denominator
            val hi = (ni * dr - nr * di) / denominator
            val next = re * hr - im * hi
            im = re * hi + im * hr; re = next
        }
        return hypot(1 - wet + wet * re, wet * im)
    }
}
