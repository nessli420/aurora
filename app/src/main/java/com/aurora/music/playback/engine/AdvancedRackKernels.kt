package com.aurora.music.playback.engine

import com.aurora.music.data.*
import kotlin.math.*

internal interface AdvancedRackKernel {
    fun process(block: AudioBlock, volume: Double = 1.0)
    fun reset()
    fun copyStateFrom(previous: AdvancedRackKernel) = Unit
    val reductionDb: Double get() = 0.0
    fun bandReductionDb(index: Int): Double = reductionDb
}

internal class RackFilter(private val coefficients: BiquadCoefficients) {
    private val x1 = DoubleArray(2); private val x2 = DoubleArray(2)
    private val y1 = DoubleArray(2); private val y2 = DoubleArray(2)
    fun tick(input: Double, channel: Int, c: BiquadCoefficients = coefficients): Double {
        val output = c.b0 * input + c.b1 * x1[channel] + c.b2 * x2[channel] - c.a1 * y1[channel] - c.a2 * y2[channel]
        x2[channel] = x1[channel]; x1[channel] = input; y2[channel] = y1[channel]; y1[channel] = output
        return output
    }
    fun tickInterpolated(input: Double, channel: Int, a: BiquadCoefficients, b: BiquadCoefficients, mix: Double): Double {
        val inverse = 1.0 - mix
        val output = (a.b0 * inverse + b.b0 * mix) * input + (a.b1 * inverse + b.b1 * mix) * x1[channel] +
            (a.b2 * inverse + b.b2 * mix) * x2[channel] - (a.a1 * inverse + b.a1 * mix) * y1[channel] -
            (a.a2 * inverse + b.a2 * mix) * y2[channel]
        x2[channel] = x1[channel]; x1[channel] = input; y2[channel] = y1[channel]; y1[channel] = output
        return output
    }
    fun reset() { x1.fill(0.0); x2.fill(0.0); y1.fill(0.0); y2.fill(0.0) }
    fun copyStateFrom(previous: RackFilter) {
        previous.x1.copyInto(x1); previous.x2.copyInto(x2); previous.y1.copyInto(y1); previous.y2.copyInto(y2)
    }
}

internal class RackEnvelope(private val settings: RackDynamics, rate: Int) {
    private val attack = exp(-1.0 / (.001 * settings.attackMs * rate))
    private val release = exp(-1.0 / (.001 * settings.releaseMs * rate))
    private val rmsCoefficient = exp(-1.0 / (.01 * rate))
    private var power = 0.0
    var changeDb = 0.0; private set
    fun tick(left: Double, right: Double, upward: Boolean = false): Double {
        val squared = (left * left + right * right) * .5
        power = squared + rmsCoefficient * (power - squared)
        val level = if (settings.detector == RackDetector.PEAK) max(abs(left), abs(right)) else sqrt(max(0.0, power))
        val over = if (upward) settings.thresholdDb - 20 * log10(max(level, 1e-15)) else 20 * log10(max(level, 1e-15)) - settings.thresholdDb
        val knee = settings.kneeDb
        val compressed = when {
            knee == 0.0 -> max(0.0, over)
            over <= -knee * .5 -> 0.0
            over >= knee * .5 -> over
            else -> (over + knee * .5).pow(2) / (2 * knee)
        } * (1 - 1 / settings.ratio)
        val desired = compressed.coerceAtMost(settings.rangeDb) * if (upward) 1 else -1
        val coefficient = if (abs(desired) > abs(changeDb)) attack else release
        changeDb = desired + coefficient * (changeDb - desired)
        return changeDb
    }
    fun reset() { power = 0.0; changeDb = 0.0 }
    fun copyStateFrom(previous: RackEnvelope) { power = previous.power; changeDb = previous.changeDb }
}

internal class DynamicEqKernel(private val settings: RackDynamicEq, rate: Int) : AdvancedRackKernel {
    private val detector = RackFilter(PrecisionDspCoeffBuilder.band(FilterType.BAND_PASS.code,
        settings.detectorHz, 0.0, settings.detectorQ, rate))
    private val filter = RackFilter(BiquadCoefficients.IDENTITY)
    private val bank = Array(481) { PrecisionDspCoeffBuilder.band(0, settings.frequencyHz,
        (it - 240) / 10.0, settings.q, rate) }
    private val envelope = RackEnvelope(settings.dynamics, rate)
    private val makeup = 10.0.pow(settings.dynamics.makeupDb / 20)
    override val reductionDb: Double get() = envelope.changeDb
    override fun process(block: AudioBlock, volume: Double) {
        var i = 0
        while (i < block.sampleCount) {
            val left = block.samples[i]; val right = block.samples[i + 1]
            val dl = detector.tick(left, 0); val dr = detector.tick(right, 1)
            val index = (envelope.tick(dl, dr, settings.upward) * 10 + 240).coerceIn(0.0, 480.0)
            val first = index.toInt(); val second = min(480, first + 1); val mix = index - first
            val l = filter.tickInterpolated(left, 0, bank[first], bank[second], mix)
            val r = filter.tickInterpolated(right, 1, bank[first], bank[second], mix)
            block.samples[i] = if (settings.dynamics.mute) 0.0 else if (settings.dynamics.solo) dl else l * makeup
            block.samples[i + 1] = if (settings.dynamics.mute) 0.0 else if (settings.dynamics.solo) dr else r * makeup
            i += 2
        }
    }
    override fun reset() { detector.reset(); filter.reset(); envelope.reset() }
    override fun copyStateFrom(previous: AdvancedRackKernel) {
        if (previous is DynamicEqKernel && previous.settings == settings) {
            detector.copyStateFrom(previous.detector); filter.copyStateFrom(previous.filter); envelope.copyStateFrom(previous.envelope)
        }
    }
}

internal class MultibandKernel(private val settings: RackMultiband, private val rate: Int) : AdvancedRackKernel {
    private fun filter(type: FilterType, hz: Double) = RackFilter(PrecisionDspCoeffBuilder.band(type.code, hz, 0.0, sqrt(.5), rate))
    private val low = Array(2) { filter(FilterType.LOW_PASS, settings.lowHz) }
    private val upper = Array(2) { filter(FilterType.HIGH_PASS, settings.lowHz) }
    private val lowPhase = filter(FilterType.ALL_PASS, settings.highHz)
    private val mid = Array(2) { filter(FilterType.LOW_PASS, settings.highHz) }
    private val high = Array(2) { filter(FilterType.HIGH_PASS, settings.highHz) }
    private val envelope = Array(3) { RackEnvelope(settings.bands[it], rate) }
    private val values = DoubleArray(6)
    private val makeup = DoubleArray(3) { 10.0.pow(settings.bands[it].makeupDb / 20) }
    private val anySolo = settings.bands.any { it.solo }
    override val reductionDb: Double get() = minOf(envelope[0].changeDb, envelope[1].changeDb, envelope[2].changeDb)
    override fun bandReductionDb(index: Int): Double = envelope[index.coerceIn(0, 2)].changeDb
    override fun process(block: AudioBlock, volume: Double) {
        var i = 0
        while (i < block.sampleCount) {
            for (channel in 0..1) {
                val input = block.samples[i + channel]
                values[channel] = lowPhase.tick(low[1].tick(low[0].tick(input, channel), channel), channel)
                val upperValue = upper[1].tick(upper[0].tick(input, channel), channel)
                values[2 + channel] = mid[1].tick(mid[0].tick(upperValue, channel), channel)
                values[4 + channel] = high[1].tick(high[0].tick(upperValue, channel), channel)
            }
            var left = 0.0; var right = 0.0
            for (band in 0..2) {
                val p = band * 2
                val db = envelope[band].tick(values[p], values[p + 1])
                val s = settings.bands[band]
                val gain = if (s.mute || anySolo && !s.solo) 0.0 else 10.0.pow(db / 20) * makeup[band]
                left += values[p] * gain; right += values[p + 1] * gain
            }
            block.samples[i] = left; block.samples[i + 1] = right; i += 2
        }
    }
    override fun reset() {
        low.forEach { it.reset() }; upper.forEach { it.reset() }; lowPhase.reset()
        mid.forEach { it.reset() }; high.forEach { it.reset() }; envelope.forEach { it.reset() }
    }
    override fun copyStateFrom(previous: AdvancedRackKernel) {
        if (previous is MultibandKernel && previous.settings == settings) {
            for (i in 0..1) { low[i].copyStateFrom(previous.low[i]); upper[i].copyStateFrom(previous.upper[i]); mid[i].copyStateFrom(previous.mid[i]); high[i].copyStateFrom(previous.high[i]) }
            lowPhase.copyStateFrom(previous.lowPhase)
            for (i in 0..2) envelope[i].copyStateFrom(previous.envelope[i])
        }
    }
}

internal class UtilityKernel(private val settings: RackUtility, rate: Int) : AdvancedRackKernel {
    private val dcR = exp(-2 * PI * 10 / rate)
    private val dcInput = DoubleArray(2); private val dcOutput = DoubleArray(2)
    private val sideHigh = RackFilter(PrecisionDspCoeffBuilder.band(FilterType.HIGH_PASS.code,
        settings.monoBassHz.coerceAtLeast(20.0), 0.0, sqrt(.5), rate))
    override fun process(block: AudioBlock, volume: Double) {
        var i = 0
        while (i < block.sampleCount) {
            var left = block.samples[i]; var right = block.samples[i + 1]
            if (settings.dcBlock) {
                val l = (1 + dcR) * .5 * (left - dcInput[0]) + dcR * dcOutput[0]
                val r = (1 + dcR) * .5 * (right - dcInput[1]) + dcR * dcOutput[1]
                dcInput[0] = left; dcInput[1] = right; dcOutput[0] = l; dcOutput[1] = r; left = l; right = r
            }
            if (settings.monoBassHz > 0) {
                val mid = (left + right) * .5; val side = sideHigh.tick((left - right) * .5, 0)
                left = mid + side; right = mid - side
            }
            block.samples[i] = left * settings.ll + right * settings.lr
            block.samples[i + 1] = left * settings.rl + right * settings.rr; i += 2
        }
    }
    override fun reset() { dcInput.fill(0.0); dcOutput.fill(0.0); sideHigh.reset() }
    override fun copyStateFrom(previous: AdvancedRackKernel) {
        if (previous is UtilityKernel && previous.settings == settings) {
            previous.dcInput.copyInto(dcInput); previous.dcOutput.copyInto(dcOutput); sideHigh.copyStateFrom(previous.sideHigh)
        }
    }
}

internal class RelativeLoudnessKernel(private val settings: RackLoudness, rate: Int) : AdvancedRackKernel {
    private val bass = Array(101) { PrecisionDspCoeffBuilder.band(1, 100.0, settings.bassCapDb * it / 100, sqrt(.5), rate) }
    private val treble = Array(101) { PrecisionDspCoeffBuilder.band(2, min(5000.0, rate * .3), settings.trebleCapDb * it / 100, sqrt(.5), rate) }
    private val bassState = RackFilter(BiquadCoefficients.IDENTITY); private val trebleState = RackFilter(BiquadCoefficients.IDENTITY)
    private val smoothing = exp(-1.0 / (.2 * rate))
    private var amount = 0.0
    override val reductionDb: Double get() = amount * settings.bassCapDb
    override fun process(block: AudioBlock, volume: Double) {
        val target = (20 * log10(settings.referenceVolume / volume.coerceIn(.0001, 1.0)) / 40).coerceIn(0.0, 1.0) * settings.strength
        var i = 0
        while (i < block.sampleCount) {
            amount = target + smoothing * (amount - target)
            val index = (amount * 100).coerceIn(0.0, 100.0); val first = index.toInt(); val second = min(100, first + 1); val mix = index - first
            for (channel in 0..1) {
                val value = bassState.tickInterpolated(block.samples[i + channel], channel, bass[first], bass[second], mix)
                block.samples[i + channel] = trebleState.tickInterpolated(value, channel, treble[first], treble[second], mix)
            }
            i += 2
        }
    }
    override fun reset() { amount = 0.0; bassState.reset(); trebleState.reset() }
    override fun copyStateFrom(previous: AdvancedRackKernel) {
        if (previous is RelativeLoudnessKernel && previous.settings == settings) {
            amount = previous.amount; bassState.copyStateFrom(previous.bassState); trebleState.copyStateFrom(previous.trebleState)
        }
    }
}
