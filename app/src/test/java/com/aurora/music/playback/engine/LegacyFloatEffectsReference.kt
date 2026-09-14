package com.aurora.music.playback.engine

import com.aurora.music.playback.DspCoeffBuilder
import com.aurora.music.playback.DspParams
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.tanh

/** Frozen pre-R1c float arithmetic for a numerical compatibility check, not a production fallback. */
internal object LegacyFloatEffectsReference {
    fun process(input: DoubleArray, p: DspParams, rate: Int): DoubleArray {
        val c = DspCoeffBuilder.build(p, rate)
        val n = c.nBiquads
        val history = Array(2) { Array(4) { FloatArray(n) } }
        val crossfeedL = FloatArray(DspCoeffBuilder.MAX_CROSSFEED_DELAY + 1)
        val crossfeedR = FloatArray(DspCoeffBuilder.MAX_CROSSFEED_DELAY + 1)
        val delayL = FloatArray(DspCoeffBuilder.MAX_CHANNEL_DELAY + 1)
        val delayR = FloatArray(DspCoeffBuilder.MAX_CHANNEL_DELAY + 1)
        var cfWrite = 0; var dWrite = 0
        var lpfL = 0f; var lpfR = 0f; var compGain = 1f; var limGain = 1f
        val satK = 1f + 5f * c.satDrive
        val output = DoubleArray(input.size)
        fun cascade(sample: Float, channel: Int): Float {
            val (x1, x2, y1, y2) = history[channel]
            var s = sample
            repeat(n) { k ->
                val x = s
                val y = c.b0[k] * x + c.b1[k] * x1[k] + c.b2[k] * x2[k] - c.a1[k] * y1[k] - c.a2[k] * y2[k]
                x2[k] = x1[k]; x1[k] = x; y2[k] = y1[k]; y1[k] = y; s = y
            }
            return s
        }
        for (i in input.indices step 2) {
            var l = cascade(input[i].toFloat(), 0)
            var r = cascade(input[i + 1].toFloat(), 1)
            l *= c.preampLin * c.balL * c.trimL; r *= c.preampLin * c.balR * c.trimR
            if (c.satDrive > 0f) {
                val sl = tanh(satK * l) / satK; val sr = tanh(satK * r) / satK
                l = sl + c.satDrive * 0.2f * (sl * sl - 0.33f)
                r = sr + c.satDrive * 0.2f * (sr * sr - 0.33f)
            }
            val mid = 0.5f * (l + r); val side = 0.5f * (l - r) * c.width
            l = mid + side; r = mid - side
            if (c.crossfeedAmt > 0f) {
                val read = (cfWrite - c.crossfeedDelay + crossfeedL.size) % crossfeedL.size
                lpfR = c.crossfeedLpfA * lpfR + (1f - c.crossfeedLpfA) * crossfeedR[read]
                lpfL = c.crossfeedLpfA * lpfL + (1f - c.crossfeedLpfA) * crossfeedL[read]
                crossfeedL[cfWrite] = l; crossfeedR[cfWrite] = r
                cfWrite = (cfWrite + 1) % crossfeedL.size
                l += c.crossfeedAmt * lpfR; r += c.crossfeedAmt * lpfL
            }
            if (c.compEnabled) {
                val level = max(abs(l), abs(r))
                val desired = if (level > c.compThreshLin) Math.pow((level / c.compThreshLin).toDouble(), 1.0 / c.compRatio - 1.0).toFloat() else 1f
                val coef = if (desired < compGain) c.compAtt else c.compRel
                compGain = desired + (compGain - desired) * coef
                l *= compGain; r *= compGain
            }
            if (c.limiterEnabled) {
                val peak = max(abs(l), abs(r))
                val desired = if (peak > c.ceilingLin) c.ceilingLin / peak else 1f
                val coef = if (desired < limGain) c.limAtt else c.limRel
                limGain = desired + (limGain - desired) * coef
                l *= limGain; r *= limGain
            }
            if (c.delayL > 0 || c.delayR > 0) {
                delayL[dWrite] = l; delayR[dWrite] = r
                l = delayL[(dWrite - c.delayL + delayL.size) % delayL.size]
                r = delayR[(dWrite - c.delayR + delayR.size) % delayR.size]
                dWrite = (dWrite + 1) % delayL.size
            }
            output[i] = l.toDouble(); output[i + 1] = r.toDouble()
        }
        return output
    }
}
