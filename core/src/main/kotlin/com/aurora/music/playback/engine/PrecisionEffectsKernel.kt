package com.aurora.music.playback.engine

import com.aurora.music.playback.DspCoeffBuilder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.tanh

/**
 * Single audio-thread owner. In-place binary64 port of Aurora's existing stereo effects, in the
 * existing order: EQ, smoothed preamp/balance + trim, saturation, width, crossfeed, compressor,
 * limiter, channel delay. Histories persist across blocks/parameter updates and reset on seek.
 * Prepared coefficients are sampled once per block; processing allocates nothing and takes no lock.
 * Delay/IIR tails are deliberately not appended here: existing playback duration semantics remain.
 */
class PrecisionEffectsKernel(val format: AudioStreamFormat) {
    init { require(format.channelLayout == ChannelLayout.STEREO) }
    /** No block-buffering latency; configured per-channel delay is additional and may differ L/R. */
    val capabilities = NodeCapabilities(SamplePrecision.FLOAT_64, SamplePrecision.FLOAT_64, tailFrames = null)
    private val n = DspCoeffBuilder.TOTAL_BIQUADS
    private val xL1 = DoubleArray(n); private val xL2 = DoubleArray(n)
    private val yL1 = DoubleArray(n); private val yL2 = DoubleArray(n)
    private val xR1 = DoubleArray(n); private val xR2 = DoubleArray(n)
    private val yR1 = DoubleArray(n); private val yR2 = DoubleArray(n)
    private val ringL = DoubleArray(DspCoeffBuilder.MAX_CROSSFEED_DELAY + 1)
    private val ringR = DoubleArray(DspCoeffBuilder.MAX_CROSSFEED_DELAY + 1)
    private val delayL = DoubleArray(DspCoeffBuilder.MAX_CHANNEL_DELAY + 1)
    private val delayR = DoubleArray(DspCoeffBuilder.MAX_CHANNEL_DELAY + 1)
    private var crossfeedWrite = 0; private var delayWrite = 0
    private var crossfeedLpfL = 0.0; private var crossfeedLpfR = 0.0
    private var limiterGain = 1.0; private var compressorGain = 1.0
    private val smoothCoefficient = exp(-1.0 / (0.005 * format.sampleRate))
    private var preamp = 1.0; private var balanceL = 1.0; private var balanceR = 1.0; private var width = 1.0
    private var smoothingInitialized = false

    fun process(block: AudioBlock, coefficients: PrecisionDspCoefficients, processEqualizer: Boolean = true) {
        require(block.format == format && coefficients.sampleRate == format.sampleRate)
        if (block.frameCount == 0) return
        val c = coefficients
        if (!smoothingInitialized) {
            preamp = c.preampLin; balanceL = c.balL; balanceR = c.balR; width = c.width
            smoothingInitialized = true
        }
        val satK = 1.0 + 5.0 * c.satDrive
        val delayOn = c.delayL > 0 || c.delayR > 0
        var position = 0
        while (position < block.sampleCount) {
            var left = if (processEqualizer) cascade(block.samples[position], c, xL1, xL2, yL1, yL2) else block.samples[position]
            var right = if (processEqualizer) cascade(block.samples[position + 1], c, xR1, xR2, yR1, yR2) else block.samples[position + 1]
            preamp = c.preampLin + (preamp - c.preampLin) * smoothCoefficient
            balanceL = c.balL + (balanceL - c.balL) * smoothCoefficient
            balanceR = c.balR + (balanceR - c.balR) * smoothCoefficient
            width = c.width + (width - c.width) * smoothCoefficient
            left *= preamp * balanceL * c.trimL
            right *= preamp * balanceR * c.trimR
            if (c.satDrive > 0.0) {
                val saturatedL = tanh(satK * left) / satK
                val saturatedR = tanh(satK * right) / satK
                // Preserve the existing asymmetric saturation, including its DC component.
                left = saturatedL + c.satDrive * 0.2 * (saturatedL * saturatedL - 0.33)
                right = saturatedR + c.satDrive * 0.2 * (saturatedR * saturatedR - 0.33)
            }
            val mid = 0.5 * (left + right)
            val side = 0.5 * (left - right) * width
            left = mid + side; right = mid - side
            if (c.crossfeedAmt > 0.0) {
                val read = (crossfeedWrite - c.crossfeedDelay + ringL.size) % ringL.size
                crossfeedLpfL = c.crossfeedLpfA * crossfeedLpfL + (1.0 - c.crossfeedLpfA) * ringL[read]
                crossfeedLpfR = c.crossfeedLpfA * crossfeedLpfR + (1.0 - c.crossfeedLpfA) * ringR[read]
                ringL[crossfeedWrite] = left; ringR[crossfeedWrite] = right
                crossfeedWrite = (crossfeedWrite + 1) % ringL.size
                left += c.crossfeedAmt * crossfeedLpfR
                right += c.crossfeedAmt * crossfeedLpfL
            }
            if (c.compEnabled) {
                val level = max(abs(left), abs(right))
                val desired = if (level > c.compThreshLin) (level / c.compThreshLin).pow(1.0 / c.compRatio - 1.0) else 1.0
                val coefficient = if (desired < compressorGain) c.compAtt else c.compRel
                compressorGain = desired + (compressorGain - desired) * coefficient
                left *= compressorGain; right *= compressorGain
            }
            if (c.limiterEnabled) {
                val peak = max(abs(left), abs(right))
                val desired = if (peak > c.ceilingLin) c.ceilingLin / peak else 1.0
                val coefficient = if (desired < limiterGain) c.limAtt else c.limRel
                limiterGain = desired + (limiterGain - desired) * coefficient
                left *= limiterGain; right *= limiterGain
            }
            if (delayOn) {
                delayL[delayWrite] = left; delayR[delayWrite] = right
                left = delayL[(delayWrite - c.delayL + delayL.size) % delayL.size]
                right = delayR[(delayWrite - c.delayR + delayR.size) % delayR.size]
                delayWrite = (delayWrite + 1) % delayL.size
            }
            block.samples[position] = left; block.samples[position + 1] = right
            position += 2
        }
    }

    // Retain direct-form I state layout so live coefficient changes preserve the existing history.
    private fun cascade(input: Double, c: PrecisionDspCoefficients, x1: DoubleArray, x2: DoubleArray,
                        y1: DoubleArray, y2: DoubleArray): Double {
        var sample = input
        var i = 0
        while (i < n) {
            val b = c.filter(i)
            val output = b.b0 * sample + b.b1 * x1[i] + b.b2 * x2[i] - b.a1 * y1[i] - b.a2 * y2[i]
            x2[i] = x1[i]; x1[i] = sample; y2[i] = y1[i]; y1[i] = output
            sample = output
            i++
        }
        return sample
    }

    fun reset() {
        xL1.fill(0.0); xL2.fill(0.0); yL1.fill(0.0); yL2.fill(0.0)
        xR1.fill(0.0); xR2.fill(0.0); yR1.fill(0.0); yR2.fill(0.0)
        ringL.fill(0.0); ringR.fill(0.0); delayL.fill(0.0); delayR.fill(0.0)
        crossfeedWrite = 0; delayWrite = 0; crossfeedLpfL = 0.0; crossfeedLpfR = 0.0
        limiterGain = 1.0; compressorGain = 1.0; smoothingInitialized = false
    }

    /** Stable-node migration at a block boundary; independent histories remain safe to crossfade. */
    fun copyStateFrom(other: PrecisionEffectsKernel) {
        require(format == other.format)
        other.xL1.copyInto(xL1); other.xL2.copyInto(xL2); other.yL1.copyInto(yL1); other.yL2.copyInto(yL2)
        other.xR1.copyInto(xR1); other.xR2.copyInto(xR2); other.yR1.copyInto(yR1); other.yR2.copyInto(yR2)
        other.ringL.copyInto(ringL); other.ringR.copyInto(ringR)
        other.delayL.copyInto(delayL); other.delayR.copyInto(delayR)
        crossfeedWrite = other.crossfeedWrite; delayWrite = other.delayWrite
        crossfeedLpfL = other.crossfeedLpfL; crossfeedLpfR = other.crossfeedLpfR
        limiterGain = other.limiterGain; compressorGain = other.compressorGain
        preamp = other.preamp; balanceL = other.balanceL; balanceR = other.balanceR; width = other.width
        smoothingInitialized = other.smoothingInitialized
    }
}
