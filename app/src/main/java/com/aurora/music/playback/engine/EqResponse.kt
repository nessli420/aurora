package com.aurora.music.playback.engine

import com.aurora.music.data.AudioPrefs
import com.aurora.music.data.ProcessingRackNode
import com.aurora.music.data.RackEqChannel
import com.aurora.music.data.RackNodeKind
import com.aurora.music.playback.DspCoeffBuilder
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.exp
import kotlin.math.sin

/**
 * Calculated linear response of one dedicated EQ node, including its same-input wet/dry mix.
 * This is neither a measured response nor a prediction of the complete rack or output device.
 * Arrays have equal length and ascending frequencies. Magnitude is floored at [magnitudeFloorDb].
 * Phase and group delay are NaN below [phaseUndefinedBelowDb]; phase is unwrapped separately
 * across each contiguous valid segment of this sampled grid. Very narrow features can require
 * more points. Negative group delay is valid and must not be silently clamped to zero.
 */
data class EqResponse(
    val sampleRate: Int,
    val frequenciesHz: DoubleArray,
    val magnitudeDb: DoubleArray,
    val phaseDegrees: DoubleArray,
    val groupDelayMs: DoubleArray,
    val minFrequencyHz: Double,
    val maxFrequencyHz: Double,
    val nyquistFrequencyHz: Double,
    val magnitudeFloorDb: Double,
    val phaseUndefinedBelowDb: Double,
    val scope: String,
)

/** Preparation-only shared bank: these are the exact coefficients used by the playback node. */
internal object ProductionEqCoefficients {
    fun build(audio: AudioPrefs, sampleRate: Int): Array<BiquadCoefficients> {
        require(sampleRate in 8_000..768_000)
        require(audio.dspGraphicBands.size <= 31 && audio.dspParametric.size <= 64)
        val layout = DspCoeffBuilder.GRAPHIC_LAYOUTS.getOrElse(audio.dspGraphicLayout) { DspCoeffBuilder.GRAPHIC_LAYOUTS.first() }
        val graphic = audio.dspGraphicBands.mapIndexed { index, gain ->
            PrecisionDspCoeffBuilder.band(0, layout.freqs.getOrElse(index) { 0f }.toDouble(),
                gain.toDouble(), layout.q.toDouble(), sampleRate)
        }
        return (graphic + audio.dspParametric.map {
            // Preserve the production node's existing Q floor and out-of-band bypass semantics.
            PrecisionDspCoeffBuilder.band(it.type, it.freqHz.toDouble(), it.gainDb.toDouble(),
                it.q.toDouble().coerceAtLeast(.1), sampleRate)
        }).toTypedArray()
    }
}

/** Pure calculation for a background/UI worker; no playback objects, settings writes or I/O. */
object EqResponseCalculator {
    const val DEFAULT_POINTS = 512
    const val MIN_POINTS = 32
    const val MAX_POINTS = 4096
    const val MAGNITUDE_FLOOR_DB = -180.0
    const val PHASE_UNDEFINED_BELOW_DB = -120.0
    private const val PHASE_MIN_MAGNITUDE = 1e-6
    private const val SCOPE = "Calculated EQ node response, including graphic EQ, parametric EQ and wet/bypass; excludes the rest of the rack and output device"

    /** Log-spaced 20 Hz–20 kHz grid, capped strictly below this stream's Nyquist frequency. */
    fun calculateNode(node: ProcessingRackNode, sampleRate: Int, points: Int = DEFAULT_POINTS,
        channel: RackEqChannel = node.eqChannel): EqResponse {
        require(sampleRate in 8_000..768_000)
        require(points in MIN_POINTS..MAX_POINTS) { "Response point count must be between $MIN_POINTS and $MAX_POINTS" }
        val minimum = 20.0
        val maximum = minOf(20_000.0, sampleRate * .5 * (1.0 - 1e-6))
        val logarithmicSpan = ln(maximum / minimum)
        val frequencies = DoubleArray(points) { index -> minimum * exp(logarithmicSpan * index / (points - 1)) }
        // Keep the advertised bounds exact rather than depending on exp(log(x)) rounding.
        frequencies[0] = minimum; frequencies[points - 1] = maximum
        return calculateNodeAtFrequencies(node, sampleRate, frequencies, channel)
    }

    internal fun calculateNodeAtFrequencies(node: ProcessingRackNode, sampleRate: Int, frequenciesHz: DoubleArray,
        channel: RackEqChannel = node.eqChannel): EqResponse {
        require(node.kind == RackNodeKind.EQ) { "Only dedicated EQ nodes have a complete linear EQ response" }
        require(node.wet.isFinite() && node.wet in 0f..1f)
        require(channel != RackEqChannel.BOTH || node.eqChannel == RackEqChannel.BOTH) {
            "Select left or right when this stage processes only one channel."
        }
        val dryChannel = node.eqChannel != RackEqChannel.BOTH && node.eqChannel != channel
        return calculateCoefficients(ProductionEqCoefficients.build(node.audio, sampleRate), sampleRate,
            frequenciesHz, node.wet.toDouble(), node.bypass || dryChannel).let {
            it.copy(scope = "${channel.name.lowercase().replaceFirstChar { c -> c.uppercase() }} channel response. ${it.scope}" +
                if (dryChannel) "; this channel passes through unchanged" else "")
        }
    }

    /** Also permits known FIR/delay reference coefficients in tests without adding filter types. */
    internal fun calculateCoefficients(bands: Array<BiquadCoefficients>, sampleRate: Int,
        frequenciesHz: DoubleArray, wet: Double = 1.0, bypass: Boolean = false): EqResponse {
        require(sampleRate in 8_000..768_000)
        require(wet.isFinite() && wet in 0.0..1.0)
        require(frequenciesHz.isNotEmpty() && frequenciesHz.size <= MAX_POINTS)
        require(frequenciesHz.all { it.isFinite() && it in 0.0..(sampleRate * .5) })
        require((1 until frequenciesHz.size).all { frequenciesHz[it] > frequenciesHz[it - 1] })
        val frequencies = frequenciesHz.copyOf()
        val magnitude = DoubleArray(frequencies.size)
        val phase = DoubleArray(frequencies.size)
        val delay = DoubleArray(frequencies.size)
        val transfer = DoubleArray(4)
        var previousPhase = Double.NaN
        val mix = if (bypass) 0.0 else wet
        for (index in frequencies.indices) {
            evaluate(bands, 2.0 * PI * frequencies[index] / sampleRate, transfer)
            val real = 1.0 - mix + mix * transfer[0]
            val imaginary = mix * transfer[1]
            val derivativeReal = mix * transfer[2]
            val derivativeImaginary = mix * transfer[3]
            val amplitude = hypot(real, imaginary)
            magnitude[index] = if (amplitude.isFinite()) maxOf(MAGNITUDE_FLOOR_DB, 20.0 * log10(amplitude)) else Double.NaN
            if (!amplitude.isFinite() || amplitude < PHASE_MIN_MAGNITUDE) {
                phase[index] = Double.NaN; delay[index] = Double.NaN; previousPhase = Double.NaN
                continue
            }
            var radians = atan2(imaginary, real)
            if (previousPhase.isFinite()) {
                while (radians - previousPhase > PI) radians -= 2.0 * PI
                while (radians - previousPhase < -PI) radians += 2.0 * PI
            }
            previousPhase = radians
            phase[index] = radians * 180.0 / PI
            // H'(w) / H(w) has imaginary part d(phase)/dw; w is radians per sample.
            // The analytic derivative avoids spacing-dependent finite-difference delay errors.
            delay[index] = -(derivativeImaginary * real - derivativeReal * imaginary) /
                (amplitude * amplitude) * 1000.0 / sampleRate
            if (!delay[index].isFinite()) delay[index] = Double.NaN
        }
        return EqResponse(sampleRate, frequencies, magnitude, phase, delay, frequencies.first(), frequencies.last(),
            sampleRate * .5, MAGNITUDE_FLOOR_DB, PHASE_UNDEFINED_BELOW_DB, SCOPE)
    }

    /** H(w) and its analytic complex derivative for the normalized direct-form biquad cascade. */
    private fun evaluate(bands: Array<BiquadCoefficients>, omega: Double, output: DoubleArray) {
        val c1 = cos(omega); val s1 = sin(omega)
        val c2 = cos(2.0 * omega); val s2 = sin(2.0 * omega)
        var real = 1.0; var imaginary = 0.0
        var derivativeReal = 0.0; var derivativeImaginary = 0.0
        for (b in bands) {
            val nr = b.b0 + b.b1 * c1 + b.b2 * c2
            val ni = -b.b1 * s1 - b.b2 * s2
            val dr = 1.0 + b.a1 * c1 + b.a2 * c2
            val di = -b.a1 * s1 - b.a2 * s2
            val denominator = dr * dr + di * di
            val hr = (nr * dr + ni * di) / denominator
            val hi = (ni * dr - nr * di) / denominator
            val npr = -b.b1 * s1 - 2.0 * b.b2 * s2
            val npi = -b.b1 * c1 - 2.0 * b.b2 * c2
            val dpr = -b.a1 * s1 - 2.0 * b.a2 * s2
            val dpi = -b.a1 * c1 - 2.0 * b.a2 * c2
            val tr = npr - (hr * dpr - hi * dpi)
            val ti = npi - (hr * dpi + hi * dpr)
            val hpr = (tr * dr + ti * di) / denominator
            val hpi = (ti * dr - tr * di) / denominator
            val nextDerivativeReal = derivativeReal * hr - derivativeImaginary * hi + real * hpr - imaginary * hpi
            val nextDerivativeImaginary = derivativeReal * hi + derivativeImaginary * hr + real * hpi + imaginary * hpr
            val nextReal = real * hr - imaginary * hi
            imaginary = real * hi + imaginary * hr; real = nextReal
            derivativeReal = nextDerivativeReal; derivativeImaginary = nextDerivativeImaginary
        }
        output[0] = real; output[1] = imaginary
        output[2] = derivativeReal; output[3] = derivativeImaginary
    }
}
