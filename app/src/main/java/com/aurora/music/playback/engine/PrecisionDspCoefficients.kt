package com.aurora.music.playback.engine

import com.aurora.music.playback.DspCoeffBuilder
import com.aurora.music.playback.DspParams
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Prepared on the control/configuration thread. The private bank cannot be mutated by callers. */
class PrecisionDspCoefficients internal constructor(
    val sampleRate: Int,
    private val bank: Array<BiquadCoefficients>,
    val preampLin: Double,
    val balL: Double, val balR: Double, val width: Double, val satDrive: Double,
    val delayL: Int, val delayR: Int, val trimL: Double, val trimR: Double,
    val crossfeedAmt: Double, val crossfeedLpfA: Double, val crossfeedDelay: Int,
    val limiterEnabled: Boolean, val ceilingLin: Double, val limAtt: Double, val limRel: Double,
    val compEnabled: Boolean, val compThreshLin: Double, val compRatio: Double,
    val compAtt: Double, val compRel: Double,
) {
    val nBiquads: Int get() = bank.size
    /** Biquad values are immutable binary64 objects, including shelves and identity slots. */
    fun filter(index: Int): BiquadCoefficients = bank[index]
}

/**
 * The same parameter model, 31 graphic + 12 parametric slots and RBJ formulas as DspCoeffBuilder.
 * User preferences still contain Float values; every coefficient calculation after promotion is
 * binary64. This does not recover precision already absent from those preferences or input PCM.
 */
object PrecisionDspCoeffBuilder {
    fun build(p: DspParams, sampleRate: Int): PrecisionDspCoefficients {
        require(sampleRate in 8_000..768_000)
        val frequencies = if (p.graphicFreqs.isNotEmpty()) p.graphicFreqs else DspCoeffBuilder.GRAPHIC_FREQS
        val graphicQ = if (p.graphicQ > 0f) p.graphicQ.toDouble() else 1.41f.toDouble()
        val bank = Array(DspCoeffBuilder.TOTAL_BIQUADS) { i ->
            if (i < DspCoeffBuilder.MAX_GRAPHIC) {
                if (i < frequencies.size) band(0, frequencies[i].toDouble(),
                    p.graphic.getOrElse(i) { 0f }.toDouble(), graphicQ, sampleRate)
                else BiquadCoefficients.IDENTITY
            } else {
                val b = p.parametric.getOrNull(i - DspCoeffBuilder.MAX_GRAPHIC)
                if (b == null || b.gainDb == 0f) BiquadCoefficients.IDENTITY
                else band(b.type, b.freqHz.toDouble(), b.gainDb.toDouble(),
                    b.q.toDouble().coerceAtLeast(0.1f.toDouble()), sampleRate)
            }
        }
        return PrecisionDspCoefficients(
            sampleRate = sampleRate, bank = bank,
            preampLin = dbToLin(p.preampDb.toDouble()),
            balL = if (p.balance > 0f) 1.0 - p.balance.toDouble() else 1.0,
            balR = if (p.balance < 0f) 1.0 + p.balance.toDouble() else 1.0,
            width = p.width.toDouble().coerceIn(0.0, 2.0),
            satDrive = p.saturation.toDouble().coerceIn(0.0, 1.0),
            // Keep the existing preference-to-integer frame mapping; this is not sample arithmetic.
            delayL = (p.delayLeftMs / 1000f * sampleRate).toInt().coerceIn(0, DspCoeffBuilder.MAX_CHANNEL_DELAY),
            delayR = (p.delayRightMs / 1000f * sampleRate).toInt().coerceIn(0, DspCoeffBuilder.MAX_CHANNEL_DELAY),
            trimL = dbToLin(p.trimLeftDb.toDouble()), trimR = dbToLin(p.trimRightDb.toDouble()),
            crossfeedAmt = p.crossfeed.toDouble().coerceIn(0.0, 1.0) * 0.5,
            crossfeedLpfA = exp(-2.0 * PI * 700.0 / sampleRate),
            crossfeedDelay = (0.0003f * sampleRate).toInt().coerceIn(1, DspCoeffBuilder.MAX_CROSSFEED_DELAY),
            limiterEnabled = p.limiterEnabled, ceilingLin = dbToLin(p.limiterCeilingDb.toDouble()),
            limAtt = envelope(0.002, sampleRate), limRel = envelope(0.10, sampleRate),
            compEnabled = p.compEnabled, compThreshLin = dbToLin(p.compThreshDb.toDouble()),
            compRatio = p.compRatio.toDouble().coerceAtLeast(1.0),
            compAtt = envelope(0.010, sampleRate), compRel = envelope(0.20, sampleRate),
        )
    }

    /** Out-of-band filters bypass. Keep gain-zero graphic pole histories for live-update parity. */
    fun band(type: Int, frequencyHz: Double, gainDb: Double, q: Double, sampleRate: Int): BiquadCoefficients {
        require(sampleRate in 8_000..768_000)
        require(frequencyHz.isFinite() && gainDb.isFinite() && q.isFinite() && q > 0.0)
        if (frequencyHz <= 0.0 || frequencyHz >= sampleRate / 2.0) return BiquadCoefficients.IDENTITY
        val a = 10.0.pow(gainDb / 40.0)
        val omega = 2.0 * PI * frequencyHz / sampleRate
        val cosine = cos(omega)
        val alpha = sin(omega) / (2.0 * q)
        val beta = 2.0 * sqrt(a) * alpha
        return when (type) {
            1 -> {
                val a0 = (a + 1.0) + (a - 1.0) * cosine + beta
                BiquadCoefficients(
                    a * ((a + 1.0) - (a - 1.0) * cosine + beta) / a0,
                    2.0 * a * ((a - 1.0) - (a + 1.0) * cosine) / a0,
                    a * ((a + 1.0) - (a - 1.0) * cosine - beta) / a0,
                    -2.0 * ((a - 1.0) + (a + 1.0) * cosine) / a0,
                    ((a + 1.0) + (a - 1.0) * cosine - beta) / a0,
                )
            }
            2 -> {
                val a0 = (a + 1.0) - (a - 1.0) * cosine + beta
                BiquadCoefficients(
                    a * ((a + 1.0) + (a - 1.0) * cosine + beta) / a0,
                    -2.0 * a * ((a - 1.0) + (a + 1.0) * cosine) / a0,
                    a * ((a + 1.0) + (a - 1.0) * cosine - beta) / a0,
                    2.0 * ((a - 1.0) - (a + 1.0) * cosine) / a0,
                    ((a + 1.0) - (a - 1.0) * cosine - beta) / a0,
                )
            }
            else -> {
                val a0 = 1.0 + alpha / a
                BiquadCoefficients((1.0 + alpha * a) / a0, -2.0 * cosine / a0,
                    (1.0 - alpha * a) / a0, -2.0 * cosine / a0, (1.0 - alpha / a) / a0)
            }
        }
    }

    private fun dbToLin(db: Double): Double = 10.0.pow(db / 20.0)
    private fun envelope(seconds: Double, sampleRate: Int): Double = exp(-1.0 / (seconds * sampleRate))
}
