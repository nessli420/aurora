package com.aurora.music.playback.engine

import com.aurora.music.data.*
import kotlin.math.*

internal class DynamicsEffectKernel(private val settings: RackDynamicsEffect, rate: Int) : AdvancedRackKernel {
    private val d = settings.dynamics
    private val crossover = tan(PI * min(settings.frequencyHz, rate * .45) / rate)
    private val high = RackFilter(BiquadCoefficients(1 / (1 + crossover), -1 / (1 + crossover), 0.0,
        (crossover - 1) / (crossover + 1), 0.0))
    private val compressor = RackEnvelope(d, rate)
    private val attack = exp(-1.0 / (.001 * d.attackMs * rate))
    private val release = exp(-1.0 / (.001 * d.releaseMs * rate))
    private val rms = exp(-1.0 / (.01 * rate))
    private val holdFrames = (settings.holdMs * rate / 1000).toInt()
    private val makeup = 10.0.pow(d.makeupDb / 20)
    private var hold = 0
    private var power = 0.0
    private var gainDb = if (settings.mode == RackDynamicsMode.GATE) -96.0 else 0.0
    private var open = false
    override val reductionDb get() = gainDb
    override fun process(block: AudioBlock, volume: Double) {
        for (i in 0 until block.sampleCount step 2) {
            val left = block.samples[i]; val right = block.samples[i + 1]
            val dl = if (settings.mode == RackDynamicsMode.DEESSER) high.tick(left, 0) else left
            val dr = if (settings.mode == RackDynamicsMode.DEESSER) high.tick(right, 1) else right
            if (settings.mode == RackDynamicsMode.DEESSER) gainDb = compressor.tick(dl, dr)
            else {
                val squared = (dl * dl + dr * dr) * .5
                power = squared + rms * (power - squared)
                val level = if (d.detector == RackDetector.PEAK) max(abs(dl), abs(dr)) else sqrt(max(0.0, power))
                val db = 20 * log10(max(level, 1e-15))
                val desired = if (settings.mode == RackDynamicsMode.GATE) {
                    if (db >= d.thresholdDb) { open = true; hold = holdFrames }
                    else if (db < d.thresholdDb - 3) { if (hold > 0) hold-- else open = false }
                    if (open) 0.0 else -96.0
                } else {
                    val below = d.thresholdDb - db
                    val knee = d.kneeDb
                    val over = when {
                        knee == 0.0 -> max(0.0, below)
                        below <= -knee * .5 -> 0.0
                        below >= knee * .5 -> below
                        else -> (below + knee * .5).pow(2) / (2 * knee)
                    }
                    -(over * (d.ratio - 1)).coerceAtMost(d.rangeDb)
                }
                val coefficient = if (desired > gainDb) attack else release
                gainDb = desired + coefficient * (gainDb - desired)
            }
            val gain = 10.0.pow(gainDb / 20)
            block.samples[i] = if (d.mute) 0.0 else if (d.solo) dl else
                (if (settings.mode == RackDynamicsMode.DEESSER) left + (gain - 1) * dl else left * gain) * makeup
            block.samples[i + 1] = if (d.mute) 0.0 else if (d.solo) dr else
                (if (settings.mode == RackDynamicsMode.DEESSER) right + (gain - 1) * dr else right * gain) * makeup
        }
    }
    override fun reset() { high.reset(); compressor.reset(); hold = 0; power = 0.0; gainDb = if (settings.mode == RackDynamicsMode.GATE) -96.0 else 0.0; open = false }
    override fun copyStateFrom(previous: AdvancedRackKernel) {
        if (previous is DynamicsEffectKernel && previous.settings == settings) {
            high.copyStateFrom(previous.high); compressor.copyStateFrom(previous.compressor)
            hold = previous.hold; power = previous.power; gainDb = previous.gainDb; open = previous.open
        }
    }
}

internal class ToneKernel(private val settings: RackTone, rate: Int) : AdvancedRackKernel {
    private val active = settings.amount > 0 && settings.driveDb > 0
    override val latencyFrames = if (active && settings.mode != RackToneMode.BASS) 32 else 0
    override val tailFrames = if (latencyFrames > 0) 64 + (rate * .25).toInt() else 0
    private val drive = 10.0.pow(settings.driveDb / 20)
    private val bass = RackFilter(PrecisionDspCoeffBuilder.band(FilterType.LOW_SHELF.code,
        settings.frequencyHz.coerceIn(30.0, min(300.0, rate * .4)), settings.driveDb * settings.amount * .5, sqrt(.5), rate))
    private val high = RackFilter(PrecisionDspCoeffBuilder.band(FilterType.HIGH_PASS.code,
        min(settings.frequencyHz, rate * .45), 0.0, sqrt(.5), rate))
    private val taps = DoubleArray(65) { i ->
        val x = i - 32
        val a = PI * .47 * x
        (if (x == 0) .47 else sin(a) / (PI * x)) * (.42 + .5 * cos(PI * x / 32) + .08 * cos(2 * PI * x / 32))
    }.also { val sum = it.sum(); for (i in it.indices) it[i] /= sum }
    private val up = Array(2) { DoubleArray(65) }; private val down = Array(2) { DoubleArray(65) }
    private val dry = Array(2) { DoubleArray(32) }
    private val dcInput = DoubleArray(2); private val dcOutput = DoubleArray(2)
    private val dcR = exp(-2 * PI * 15 / rate)
    private var position = 0; private var dryPosition = 0
    override fun process(block: AudioBlock, volume: Double) {
        if (!active) return
        for (frame in 0 until block.frameCount) {
            val index = frame * 2
            if (settings.mode == RackToneMode.BASS) {
                for (ch in 0..1) block.samples[index + ch] = bass.tick(block.samples[index + ch], ch)
                continue
            }
            for (ch in 0..1) {
                val input = block.samples[index + ch]
                val delayed = dry[ch][dryPosition]; dry[ch][dryPosition] = input
                val source = if (settings.mode == RackToneMode.EXCITER) high.tick(input, ch) else input
                var processed = 0.0
                for (phase in 0..1) {
                    val pos = (position + phase) % 65
                    up[ch][pos] = if (phase == 0) source * 2 else 0.0
                    var interpolated = 0.0
                    var tap = phase
                    while (tap < 65) { interpolated += taps[tap] * up[ch][(pos - tap + 65) % 65]; tap += 2 }
                    val shaped = if (settings.mode == RackToneMode.TUBE) {
                        val bias = .25; val offset = tanh(bias)
                        (tanh(interpolated * drive + bias) - offset) / (drive * (1 - offset * offset))
                    } else tanh(interpolated * drive) / drive
                    down[ch][pos] = shaped
                    if (phase == 0) for (k in taps.indices) processed += taps[k] * down[ch][(pos - k + 65) % 65]
                }
                if (settings.mode == RackToneMode.TUBE) {
                    val dc = (1 + dcR) * .5 * (processed - dcInput[ch]) + dcR * dcOutput[ch]
                    dcInput[ch] = processed; dcOutput[ch] = dc; processed = dc
                }
                block.samples[index + ch] = if (settings.mode == RackToneMode.EXCITER) delayed + settings.amount * (drive - 1) / (drive + 1) * processed
                    else delayed * (1 - settings.amount) + processed * settings.amount
            }
            position = (position + 2) % 65; dryPosition = (dryPosition + 1) % 32
        }
    }
    override fun reset() {
        bass.reset(); high.reset(); up.forEach { it.fill(0.0) }; down.forEach { it.fill(0.0) }; dry.forEach { it.fill(0.0) }
        dcInput.fill(0.0); dcOutput.fill(0.0); position = 0; dryPosition = 0
    }
    override fun copyStateFrom(previous: AdvancedRackKernel) {
        if (previous is ToneKernel && previous.settings == settings) {
            bass.copyStateFrom(previous.bass); high.copyStateFrom(previous.high)
            for (ch in 0..1) { previous.up[ch].copyInto(up[ch]); previous.down[ch].copyInto(down[ch]); previous.dry[ch].copyInto(dry[ch]) }
            previous.dcInput.copyInto(dcInput); previous.dcOutput.copyInto(dcOutput)
            position = previous.position; dryPosition = previous.dryPosition
        }
    }
}

internal class SpaceKernel(private val settings: RackSpace, private val rate: Int) : AdvancedRackKernel {
    private val delayFrames = max(1, (settings.timeMs * rate / 1000).roundToInt())
    private val delay = Array(2) { DoubleArray(delayFrames) }
    private var position = 0
    private val damp = exp(-2 * PI * min(settings.dampingHz, rate * .45) / rate)
    private val damped = DoubleArray(8)
    private val lengths = IntArray(8) { index ->
        ((doubleArrayOf(.0297, .0371, .0411, .0437)[index / 2] + (index % 2) * .0013) * rate).roundToInt().coerceAtLeast(1)
    }
    private val comb = Array(8) { DoubleArray(lengths[it]) }
    private val combPosition = IntArray(8)
    private val feedback = DoubleArray(8) { .001.pow(lengths[it] / (settings.decaySeconds * rate)) }
    private val diffuse = Array(4) { DoubleArray((rate * if (it / 2 == 0) .005 else .0017).toInt().coerceAtLeast(1)) }
    private val diffusePosition = IntArray(4)
    override val tailFrames = if (settings.mode == RackSpaceMode.REVERB)
        delayFrames + ceil((settings.decaySeconds * 2 + .1) * rate).toInt()
    else if (settings.feedback == 0.0) delayFrames
    else (delayFrames + ceil(14 / -ln(damp)).toInt()) * (ceil(ln(1e-6) / ln(settings.feedback)).toInt() + 1)
    override fun process(block: AudioBlock, volume: Double) {
        for (i in 0 until block.sampleCount step 2) {
            val left = delay[0][position]; val right = delay[1][position]
            if (settings.mode == RackSpaceMode.REVERB) {
                delay[0][position] = block.samples[i]; delay[1][position] = block.samples[i + 1]
                for (ch in 0..1) {
                    var sum = 0.0
                    for (k in 0..3) {
                        val index = k * 2 + ch; val pos = combPosition[index]
                        val value = comb[index][pos]
                        damped[index] = value + damp * (damped[index] - value)
                        comb[index][pos] = (if (ch == 0) left else right) + feedback[index] * damped[index]
                        combPosition[index] = (pos + 1) % lengths[index]; sum += value * .25
                    }
                    for (k in 0..1) {
                        val index = k * 2 + ch; val pos = diffusePosition[index]; val value = diffuse[index][pos]
                        val next = value - .5 * sum
                        diffuse[index][pos] = sum + .5 * next; diffusePosition[index] = (pos + 1) % diffuse[index].size; sum = next
                    }
                    block.samples[i + ch] = sum
                }
            } else {
                damped[0] = left + damp * (damped[0] - left); damped[1] = right + damp * (damped[1] - right)
                delay[0][position] = block.samples[i] + settings.feedback * damped[if (settings.mode == RackSpaceMode.PING_PONG) 1 else 0]
                delay[1][position] = block.samples[i + 1] + settings.feedback * damped[if (settings.mode == RackSpaceMode.PING_PONG) 0 else 1]
                block.samples[i] = left; block.samples[i + 1] = right
            }
            position = (position + 1) % delayFrames
        }
    }
    override fun reset() {
        delay.forEach { it.fill(0.0) }; comb.forEach { it.fill(0.0) }; diffuse.forEach { it.fill(0.0) }
        position = 0; damped.fill(0.0); combPosition.fill(0); diffusePosition.fill(0)
    }
    override fun copyStateFrom(previous: AdvancedRackKernel) {
        if (previous is SpaceKernel && previous.settings == settings && previous.rate == rate) {
            for (i in delay.indices) previous.delay[i].copyInto(delay[i])
            for (i in comb.indices) previous.comb[i].copyInto(comb[i])
            for (i in diffuse.indices) previous.diffuse[i].copyInto(diffuse[i])
            previous.damped.copyInto(damped); previous.combPosition.copyInto(combPosition); previous.diffusePosition.copyInto(diffusePosition)
            position = previous.position
        }
    }
}

internal class ModulationKernel(private val settings: RackModulation, private val rate: Int) : AdvancedRackKernel {
    private val length = ceil(.06 * rate).toInt() + 2
    private val delay = Array(2) { DoubleArray(length) }
    private val allpass = Array(2) { DoubleArray(6) }
    private val previous = DoubleArray(2)
    private var position = 0
    private var phase = 0.0
    private val increment = settings.rateHz * 2048 / rate
    private val wave = DoubleArray(2049) { sin(2 * PI * it / 2048) }
    private val phaseCoefficients = DoubleArray(2049) {
        val frequency = 300.0 * 10.0.pow((1 + wave[it] * settings.depth) * .5)
        val tangent = tan(PI * min(frequency, rate * .4) / rate)
        (1 - tangent) / (1 + tangent)
    }
    private fun lookup(table: DoubleArray, offset: Double): Double {
        val index = offset.toInt(); val mix = offset - index
        return table[index] * (1 - mix) + table[index + 1] * mix
    }
    override val tailFrames = if (settings.depth == 0.0 || settings.mode == RackModulationMode.TREMOLO) 0 else when (settings.mode) {
        RackModulationMode.VIBRATO -> ceil(.014 * rate).toInt() + 1
        RackModulationMode.PHASER -> rate / 2
        else -> {
            val repeats = if (settings.feedback == 0.0) 1 else ceil(ln(1e-6) / ln(abs(settings.feedback))).toInt() + 1
            ceil(rate * (if (settings.mode == RackModulationMode.CHORUS) .028 else .0028) * repeats).toInt() + 1
        }
    }
    private fun read(ch: Int, frames: Double): Double {
        val index = frames.toInt(); val blend = frames - index
        val a = delay[ch][(position - index + length) % length]
        val b = delay[ch][(position - index - 1 + length) % length]
        return a * (1 - blend) + b * blend
    }
    override fun process(block: AudioBlock, volume: Double) {
        if (settings.depth == 0.0) return
        for (i in 0 until block.sampleCount step 2) {
            for (ch in 0..1) {
                val input = block.samples[i + ch]
                val offset = (phase + ch * settings.stereoPhase * 2048 / 360) % 2048
                val lfo = lookup(wave, offset)
                block.samples[i + ch] = when (settings.mode) {
                    RackModulationMode.TREMOLO -> input * (1 - settings.depth * (1 + lfo) * .5)
                    RackModulationMode.PHASER -> {
                        val coefficient = lookup(phaseCoefficients, offset)
                        var value = input + previous[ch] * settings.feedback
                        for (stage in 0..5) {
                            val next = -coefficient * value + allpass[ch][stage]
                            allpass[ch][stage] = value + coefficient * next; value = next
                        }
                        previous[ch] = value
                        input * (1 - settings.depth * .5) + value * settings.depth * .5
                    }
                    else -> {
                        val milliseconds = when (settings.mode) {
                            RackModulationMode.FLANGER -> 1.5 + lfo * 1.3 * settings.depth
                            RackModulationMode.VIBRATO -> 8 + lfo * 6 * settings.depth
                            else -> 20 + lfo * 8 * settings.depth
                        }
                        val value = read(ch, (milliseconds * rate / 1000).coerceAtLeast(1.0))
                        delay[ch][position] = input + if (settings.mode == RackModulationMode.VIBRATO) 0.0 else value * settings.feedback
                        if (settings.mode == RackModulationMode.VIBRATO) value else input * (1 - settings.depth * .5) + value * settings.depth * .5
                    }
                }
            }
            position = (position + 1) % length; phase += increment; if (phase >= 2048) phase -= 2048
        }
    }
    override fun reset() { delay.forEach { it.fill(0.0) }; allpass.forEach { it.fill(0.0) }; previous.fill(0.0); position = 0; phase = 0.0 }
    override fun copyStateFrom(previous: AdvancedRackKernel) {
        if (previous is ModulationKernel && previous.settings.mode == settings.mode && previous.rate == rate) {
            for (ch in 0..1) { previous.delay[ch].copyInto(delay[ch]); previous.allpass[ch].copyInto(allpass[ch]) }
            previous.previous.copyInto(this.previous); position = previous.position; phase = previous.phase
        }
    }
}
