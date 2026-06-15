package com.aurora.music.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sin

/**
 * Pure DSP coefficient math for [AuroraDspProcessor]: no Android types, JVM-unit-testable.
 *
 * Turns rate-independent parameters ([DspParams]) plus a sample rate into an immutable [Coeffs]
 * snapshot of flat float arrays the realtime callback reads with zero allocation. Biquads are RBJ
 * Audio-EQ-Cookbook filters in Direct Form I. A peaking filter at 0 dB gain is the identity, so a
 * fixed bank of [TOTAL_BIQUADS] runs every buffer with unused bands as pass-throughs. Keeping the
 * bank size constant means filter state never needs remapping or zeroing when bands change (no
 * clicks).
 */
object DspCoeffBuilder {

    const val GRAPHIC_BANDS = 10           // default layout band count
    const val MAX_GRAPHIC = 31             // 1/3-octave layout uses all 31
    const val MAX_PARAMETRIC = 12          // enough for a full AutoEq correction (~10 filters)
    const val TOTAL_BIQUADS = MAX_GRAPHIC + MAX_PARAMETRIC

    /** Centre frequencies of the default 10-band graphic EQ (≈1-octave spacing). */
    val GRAPHIC_FREQS = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)

    /** Selectable graphic-EQ layouts: centre frequencies + a Q matched to the band spacing. */
    val GRAPHIC_LAYOUTS: List<GraphicLayout> = listOf(
        GraphicLayout("10-band", GRAPHIC_FREQS, 1.41f),
        GraphicLayout(
            "15-band",
            floatArrayOf(25f, 40f, 63f, 100f, 160f, 250f, 400f, 630f, 1000f, 1600f, 2500f, 4000f, 6300f, 10000f, 16000f),
            2.1f,
        ),
        GraphicLayout(
            "31-band",
            floatArrayOf(20f, 25f, 31f, 40f, 50f, 63f, 80f, 100f, 125f, 160f, 200f, 250f, 315f, 400f, 500f, 630f,
                800f, 1000f, 1250f, 1600f, 2000f, 2500f, 3150f, 4000f, 5000f, 6300f, 8000f, 10000f, 12500f, 16000f, 20000f),
            4.32f,
        ),
    )

    fun build(p: DspParams, sampleRate: Int): Coeffs {
        val fs = sampleRate.coerceAtLeast(8000)
        val b0 = FloatArray(TOTAL_BIQUADS)
        val b1 = FloatArray(TOTAL_BIQUADS)
        val b2 = FloatArray(TOTAL_BIQUADS)
        val a1 = FloatArray(TOTAL_BIQUADS)
        val a2 = FloatArray(TOTAL_BIQUADS)

        val freqs = if (p.graphicFreqs.isNotEmpty()) p.graphicFreqs else GRAPHIC_FREQS
        val gq = if (p.graphicQ > 0f) p.graphicQ else 1.41f
        for (i in 0 until MAX_GRAPHIC) {
            if (i < freqs.size) peaking(freqs[i], p.graphic.getOrElse(i) { 0f }, gq, fs, b0, b1, b2, a1, a2, i)
            else identity(b0, b1, b2, a1, a2, i)
        }
        for (i in 0 until MAX_PARAMETRIC) {
            val slot = MAX_GRAPHIC + i
            val band = p.parametric.getOrNull(i)
            if (band != null && band.gainDb != 0f) {
                val q = band.q.coerceAtLeast(0.1f)
                when (band.type) {
                    1 -> lowShelf(band.freqHz, band.gainDb, q, fs, b0, b1, b2, a1, a2, slot)
                    2 -> highShelf(band.freqHz, band.gainDb, q, fs, b0, b1, b2, a1, a2, slot)
                    else -> peaking(band.freqHz, band.gainDb, q, fs, b0, b1, b2, a1, a2, slot)
                }
            } else {
                identity(b0, b1, b2, a1, a2, slot)
            }
        }

        // Crossfeed: short delay + one-pole low-pass on the opposite channel (Bauer-style).
        val crossfeedDelay = (0.0003f * fs).toInt().coerceIn(1, MAX_CROSSFEED_DELAY)
        val crossfeedLpfA = onePoleCoef(700f, fs)

        val dl = (p.delayLeftMs / 1000f * fs).toInt().coerceIn(0, MAX_CHANNEL_DELAY)
        val dr = (p.delayRightMs / 1000f * fs).toInt().coerceIn(0, MAX_CHANNEL_DELAY)

        return Coeffs(
            b0 = b0, b1 = b1, b2 = b2, a1 = a1, a2 = a2,
            preampLin = dbToLin(p.preampDb),
            balL = if (p.balance > 0f) 1f - p.balance else 1f,
            balR = if (p.balance < 0f) 1f + p.balance else 1f,
            width = p.width.coerceIn(0f, 2f),
            satDrive = p.saturation.coerceIn(0f, 1f),
            delayL = dl, delayR = dr,
            trimL = dbToLin(p.trimLeftDb), trimR = dbToLin(p.trimRightDb),
            crossfeedAmt = p.crossfeed.coerceIn(0f, 1f) * 0.5f,
            crossfeedLpfA = crossfeedLpfA,
            crossfeedDelay = crossfeedDelay,
            limiterEnabled = p.limiterEnabled,
            ceilingLin = dbToLin(p.limiterCeilingDb),
            limAtt = envCoef(0.002f, fs),
            limRel = envCoef(0.10f, fs),
            compEnabled = p.compEnabled,
            compThreshLin = dbToLin(p.compThreshDb),
            compRatio = p.compRatio.coerceAtLeast(1f),
            compAtt = envCoef(0.010f, fs),
            compRel = envCoef(0.20f, fs),
        )
    }

    /**
     * Peak gain (dB) of the combined graphic + parametric EQ curve across 20 Hz–20 kHz, for
     * headroom / auto-preamp. Excludes preamp/trim. 0 means the curve never exceeds unity.
     */
    fun eqPeakDb(p: DspParams, fs: Int = 48000): Float {
        val c = build(p, fs)
        val n = c.nBiquads
        var maxDb = 0.0
        var f = 20.0
        val top = (fs / 2.0).coerceAtMost(20000.0)
        while (f <= top) {
            val w = 2.0 * PI * f / fs
            val cw = cos(w); val c2w = cos(2.0 * w); val sw = sin(w); val s2w = sin(2.0 * w)
            var totalDb = 0.0
            for (i in 0 until n) {
                val numRe = c.b0[i] + c.b1[i] * cw + c.b2[i] * c2w
                val numIm = -(c.b1[i] * sw + c.b2[i] * s2w)
                val denRe = 1.0 + c.a1[i] * cw + c.a2[i] * c2w
                val denIm = -(c.a1[i] * sw + c.a2[i] * s2w)
                val den2 = denRe * denRe + denIm * denIm
                if (den2 > 1e-12) totalDb += 10.0 * log10((numRe * numRe + numIm * numIm) / den2)
            }
            if (totalDb > maxDb) maxDb = totalDb
            f *= 1.0293   // ~24 points / octave
        }
        return maxDb.toFloat()
    }

    /**
     * Magnitude (dB) of a single RBJ biquad ([type]: 0 peaking / 1 low-shelf / 2 high-shelf) at
     * frequency [atHz]. Used by the AutoEQ generator to fit filters to a measured response — it
     * builds the exact coefficients the realtime DSP uses, so the fit matches what's actually applied.
     */
    fun bandMagnitudeDb(type: Int, f0: Float, gainDb: Float, q: Float, atHz: Double, fs: Int = 48000): Double {
        val b0 = FloatArray(1); val b1 = FloatArray(1); val b2 = FloatArray(1)
        val a1 = FloatArray(1); val a2 = FloatArray(1)
        val qq = q.coerceAtLeast(0.1f)
        when (type) {
            1 -> lowShelf(f0, gainDb, qq, fs, b0, b1, b2, a1, a2, 0)
            2 -> highShelf(f0, gainDb, qq, fs, b0, b1, b2, a1, a2, 0)
            else -> peaking(f0, gainDb, qq, fs, b0, b1, b2, a1, a2, 0)
        }
        val w = 2.0 * PI * atHz / fs
        val cw = cos(w); val c2w = cos(2.0 * w); val sw = sin(w); val s2w = sin(2.0 * w)
        val numRe = b0[0] + b1[0] * cw + b2[0] * c2w
        val numIm = -(b1[0] * sw + b2[0] * s2w)
        val denRe = 1.0 + a1[0] * cw + a2[0] * c2w
        val denIm = -(a1[0] * sw + a2[0] * s2w)
        val den2 = denRe * denRe + denIm * denIm
        return if (den2 > 1e-12) 10.0 * log10((numRe * numRe + numIm * numIm) / den2) else 0.0
    }

    /** RBJ peaking-EQ biquad, normalised by a0 and written into the arrays at [i]. */
    private fun peaking(
        f0: Float, gainDb: Float, q: Float, fs: Int,
        b0: FloatArray, b1: FloatArray, b2: FloatArray, a1: FloatArray, a2: FloatArray, i: Int,
    ) {
        // Nyquist guard — a band at/above fs/2 is undefined; pass it through.
        if (f0 <= 0f || f0 >= fs / 2f) {
            identity(b0, b1, b2, a1, a2, i); return
        }
        val a = Math.pow(10.0, (gainDb / 40.0)).toFloat()              // sqrt of linear gain
        val w0 = (2.0 * PI * f0 / fs).toFloat()
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / (2f * q)
        val a0 = 1f + alpha / a
        b0[i] = (1f + alpha * a) / a0
        b1[i] = (-2f * cosW0) / a0
        b2[i] = (1f - alpha * a) / a0
        a1[i] = (-2f * cosW0) / a0
        a2[i] = (1f - alpha / a) / a0
    }

    /** RBJ low-shelf biquad (AutoEq LSC), normalised by a0 and written at [i]. */
    private fun lowShelf(
        f0: Float, gainDb: Float, q: Float, fs: Int,
        b0: FloatArray, b1: FloatArray, b2: FloatArray, a1: FloatArray, a2: FloatArray, i: Int,
    ) {
        if (f0 <= 0f || f0 >= fs / 2f) { identity(b0, b1, b2, a1, a2, i); return }
        val a = Math.pow(10.0, (gainDb / 40.0)).toFloat()
        val w0 = (2.0 * PI * f0 / fs).toFloat()
        val cosW0 = cos(w0); val sinW0 = sin(w0)
        val alpha = sinW0 / (2f * q)
        val twoSqrtAAlpha = 2f * Math.sqrt(a.toDouble()).toFloat() * alpha
        val a0 = (a + 1f) + (a - 1f) * cosW0 + twoSqrtAAlpha
        b0[i] = (a * ((a + 1f) - (a - 1f) * cosW0 + twoSqrtAAlpha)) / a0
        b1[i] = (2f * a * ((a - 1f) - (a + 1f) * cosW0)) / a0
        b2[i] = (a * ((a + 1f) - (a - 1f) * cosW0 - twoSqrtAAlpha)) / a0
        a1[i] = (-2f * ((a - 1f) + (a + 1f) * cosW0)) / a0
        a2[i] = ((a + 1f) + (a - 1f) * cosW0 - twoSqrtAAlpha) / a0
    }

    /** RBJ high-shelf biquad (AutoEq HSC), normalised by a0 and written at [i]. */
    private fun highShelf(
        f0: Float, gainDb: Float, q: Float, fs: Int,
        b0: FloatArray, b1: FloatArray, b2: FloatArray, a1: FloatArray, a2: FloatArray, i: Int,
    ) {
        if (f0 <= 0f || f0 >= fs / 2f) { identity(b0, b1, b2, a1, a2, i); return }
        val a = Math.pow(10.0, (gainDb / 40.0)).toFloat()
        val w0 = (2.0 * PI * f0 / fs).toFloat()
        val cosW0 = cos(w0); val sinW0 = sin(w0)
        val alpha = sinW0 / (2f * q)
        val twoSqrtAAlpha = 2f * Math.sqrt(a.toDouble()).toFloat() * alpha
        val a0 = (a + 1f) - (a - 1f) * cosW0 + twoSqrtAAlpha
        b0[i] = (a * ((a + 1f) + (a - 1f) * cosW0 + twoSqrtAAlpha)) / a0
        b1[i] = (-2f * a * ((a - 1f) + (a + 1f) * cosW0)) / a0
        b2[i] = (a * ((a + 1f) + (a - 1f) * cosW0 - twoSqrtAAlpha)) / a0
        a1[i] = (2f * ((a - 1f) - (a + 1f) * cosW0)) / a0
        a2[i] = ((a + 1f) - (a - 1f) * cosW0 - twoSqrtAAlpha) / a0
    }

    private fun identity(b0: FloatArray, b1: FloatArray, b2: FloatArray, a1: FloatArray, a2: FloatArray, i: Int) {
        b0[i] = 1f; b1[i] = 0f; b2[i] = 0f; a1[i] = 0f; a2[i] = 0f
    }

    private fun dbToLin(db: Float): Float = Math.pow(10.0, (db / 20.0)).toFloat()

    /** One-pole smoothing coefficient for an attack/release time constant. */
    private fun envCoef(seconds: Float, fs: Int): Float =
        if (seconds <= 0f) 0f else exp(-1.0 / (seconds.toDouble() * fs)).toFloat()

    /** One-pole low-pass coefficient (state weight) for cutoff [fc]. */
    private fun onePoleCoef(fc: Float, fs: Int): Float = exp(-2.0 * PI * fc / fs).toFloat()

    const val MAX_CROSSFEED_DELAY = 64
    const val MAX_CHANNEL_DELAY = 9600     // 50 ms @ 192 kHz
}

/** A selectable graphic-EQ layout: a display name, centre frequencies, and a spacing-matched Q. */
class GraphicLayout(val name: String, val freqs: FloatArray, val q: Float)

/** One parametric EQ band. [type]: 0 = peaking, 1 = low-shelf, 2 = high-shelf. */
data class DspBand(val freqHz: Float, val gainDb: Float, val q: Float, val type: Int = 0)

/** Rate-independent DSP parameters, mapped from persisted AudioPrefs by the playback layer. */
data class DspParams(
    val graphic: FloatArray = FloatArray(DspCoeffBuilder.GRAPHIC_BANDS),
    val graphicFreqs: FloatArray = DspCoeffBuilder.GRAPHIC_FREQS,
    val graphicQ: Float = 1.41f,
    val parametric: List<DspBand> = emptyList(),
    val preampDb: Float = 0f,
    val balance: Float = 0f,
    val width: Float = 1f,
    val crossfeed: Float = 0f,
    val saturation: Float = 0f,
    val delayLeftMs: Float = 0f,
    val delayRightMs: Float = 0f,
    val trimLeftDb: Float = 0f,
    val trimRightDb: Float = 0f,
    val limiterEnabled: Boolean = true,
    val limiterCeilingDb: Float = -0.3f,
    val compEnabled: Boolean = false,
    val compThreshDb: Float = -18f,
    val compRatio: Float = 2f,
) {
    // graphic is a FloatArray, so a generated equals/hashCode would be identity-based anyway. Nothing
    // relies on DspParams equality (each prefs emission rebuilds), so override explicitly.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * Immutable coefficient snapshot consumed by [AuroraDspProcessor.queueInput]. Biquad coefficients
 * are shared across L/R (the EQ curve is identical per channel); only the filter state differs and
 * lives in the processor. Time-constant values are pre-resolved to the current sample rate so the
 * realtime callback does no math beyond the difference equations.
 */
class Coeffs(
    val b0: FloatArray, val b1: FloatArray, val b2: FloatArray, val a1: FloatArray, val a2: FloatArray,
    val preampLin: Float,
    val balL: Float, val balR: Float,
    val width: Float,
    val satDrive: Float,
    val delayL: Int, val delayR: Int,
    val trimL: Float, val trimR: Float,
    val crossfeedAmt: Float,
    val crossfeedLpfA: Float,
    val crossfeedDelay: Int,
    val limiterEnabled: Boolean,
    val ceilingLin: Float,
    val limAtt: Float, val limRel: Float,
    val compEnabled: Boolean,
    val compThreshLin: Float, val compRatio: Float,
    val compAtt: Float, val compRel: Float,
) {
    val nBiquads: Int get() = b0.size
}
