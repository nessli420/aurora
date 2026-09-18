package com.aurora.music.playback.engine

import com.aurora.music.data.FilterType
import com.aurora.music.data.ParamBandCodec
import com.aurora.music.playback.DspBand
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
        val bank = Array(DspCoeffBuilder.TOTAL_BIQUADS) { BiquadCoefficients.IDENTITY }
        for (i in 0 until DspCoeffBuilder.MAX_GRAPHIC) {
            if (i < frequencies.size) bank[i] = band(0, frequencies[i].toDouble(),
                p.graphic.getOrElse(i) { 0f }.toDouble(), graphicQ, sampleRate)
        }
        p.parametric.take(DspCoeffBuilder.MAX_PARAMETRIC).forEachIndexed { index, b ->
            cascade(b, sampleRate).forEachIndexed { section, coefficient ->
                bank[DspCoeffBuilder.MAX_GRAPHIC + index * DspCoeffBuilder.MAX_SECTIONS_PER_BAND + section] = coefficient
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
            3, 4, 5, 6, 7 -> {
                val a0 = 1.0 + alpha
                val numerator = when (type) {
                    3 -> doubleArrayOf((1.0 - cosine) / 2.0, 1.0 - cosine, (1.0 - cosine) / 2.0)
                    4 -> doubleArrayOf((1.0 + cosine) / 2.0, -(1.0 + cosine), (1.0 + cosine) / 2.0)
                    5 -> doubleArrayOf(alpha, 0.0, -alpha)
                    6 -> doubleArrayOf(1.0, -2.0 * cosine, 1.0)
                    else -> doubleArrayOf(1.0 - alpha, -2.0 * cosine, 1.0 + alpha)
                }
                BiquadCoefficients(numerator[0] / a0, numerator[1] / a0, numerator[2] / a0,
                    -2.0 * cosine / a0, (1.0 - alpha) / a0)
            }
            0 -> {
                val a0 = 1.0 + alpha / a
                BiquadCoefficients((1.0 + alpha * a) / a0, -2.0 * cosine / a0,
                    (1.0 - alpha * a) / a0, -2.0 * cosine / a0, (1.0 - alpha / a) / a0)
            }
            else -> error("This filter requires a cascade.")
        }
    }

    fun cascade(b: DspBand, sampleRate: Int): List<BiquadCoefficients> {
        require(sampleRate in 8_000..768_000)
        val type = FilterType.fromLegacy(b.type)
        require(b.order in type.orders && b.freqHz.isFinite() && b.gainDb.isFinite() && b.q.isFinite())
        require(type.hasGain || b.gainDb == 0f)
        if (type == FilterType.CUSTOM_BIQUAD) {
            val c = ParamBandCodec.validateCoefficients(requireNotNull(b.coefficients))
            return listOf(if (b.enabled) BiquadCoefficients(c[0], c[1], c[2], c[3], c[4]) else BiquadCoefficients.IDENTITY)
        }
        require(b.coefficients == null) { "Only custom biquads accept coefficients." }
        if (!b.enabled || b.freqHz <= 0f || b.freqHz >= sampleRate / 2.0) return listOf(BiquadCoefficients.IDENTITY)
        val frequency = b.freqHz.toDouble()
        val q = b.q.toDouble().coerceAtLeast(.1)
        val sections = when (type) {
            FilterType.TILT -> listOf(band(1, frequency, -b.gainDb.toDouble() / 2, q, sampleRate),
                band(2, frequency, b.gainDb.toDouble() / 2, q, sampleRate))
            FilterType.BUTTERWORTH_LOW_PASS, FilterType.BUTTERWORTH_HIGH_PASS,
            FilterType.LINKWITZ_RILEY_LOW_PASS, FilterType.LINKWITZ_RILEY_HIGH_PASS -> {
                val linkwitz = type == FilterType.LINKWITZ_RILEY_LOW_PASS || type == FilterType.LINKWITZ_RILEY_HIGH_PASS
                val order = if (linkwitz) b.order / 2 else b.order
                val highPass = type == FilterType.BUTTERWORTH_HIGH_PASS || type == FilterType.LINKWITZ_RILEY_HIGH_PASS
                val prototype = List(order / 2) { i ->
                    val sectionQ = 1.0 / (2.0 * cos(PI * (2 * i + 1) / (2 * order)))
                    band(if (highPass) 4 else 3, frequency, 0.0, sectionQ, sampleRate)
                }
                if (linkwitz) prototype + prototype else prototype
            }
            else -> listOf(if (type.hasGain && b.gainDb == 0f) BiquadCoefficients.IDENTITY
                else band(type.code, frequency, b.gainDb.toDouble(), q, sampleRate))
        }
        require(sections.all { c ->
            listOf(c.b0, c.b1, c.b2, c.a1, c.a2).all { it.isFinite() } &&
                kotlin.math.abs(c.a2) < 1.0 && 1.0 + c.a1 + c.a2 > 0.0 && 1.0 - c.a1 + c.a2 > 0.0
        }) { "Filter coefficients are unstable at this sample rate." }
        return sections
    }

    private fun dbToLin(db: Double): Double = 10.0.pow(db / 20.0)
    private fun envelope(seconds: Double, sampleRate: Int): Double = exp(-1.0 / (seconds * sampleRate))
}
