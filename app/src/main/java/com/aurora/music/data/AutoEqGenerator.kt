package com.aurora.music.data

import com.aurora.music.playback.DspCoeffBuilder
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sqrt

// (frequency hz magnitude db) ascending by frequency
typealias FrCurve = List<Pair<Float, Float>>

// band magnitude uses the same rbj biquads the realtime dsp runs so the fit matches whats applied
object AutoEqGenerator {

    private const val F_MIN = 20.0
    private const val F_MAX = 20000.0
    private const val PTS_PER_OCT = 12
    private const val MAX_BANDS_DEFAULT = 10

    private val grid: DoubleArray = run {
        val n = (log2(F_MAX / F_MIN) * PTS_PER_OCT).toInt() + 1
        DoubleArray(n) { F_MIN * 2.0.pow(it.toDouble() / PTS_PER_OCT) }
    }

    fun generate(measured: FrCurve, target: FrCurve, maxBands: Int = MAX_BANDS_DEFAULT): ParsedEq? {
        if (measured.size < 8 || target.size < 8) return null
        val m = normalize(interpolate(measured))
        val t = normalize(interpolate(target))
        val error = DoubleArray(grid.size) { t[it] - m[it] }
        smooth(error, radius = 2)
        // treble rig data unreliable de-emphasise above ~9khz
        for (i in grid.indices) if (grid[i] > 9000) error[i] *= (0.75 - 0.35 * ((grid[i] - 9000) / (F_MAX - 9000))).coerceIn(0.4, 0.75)

        val bands = ArrayList<ParamBand>()

        bassRegionMean(error, below = 90.0).takeIf { abs(it) >= 0.5 }?.let { g ->
            val band = ParamBand(105f, g.coerceIn(-12.0, 12.0).toFloat(), 0.7f, BandType.LOW_SHELF)
            bands.add(band); subtractBand(error, band)
        }
        trebleRegionMean(error, above = 11000.0).takeIf { abs(it) >= 0.5 }?.let { g ->
            val band = ParamBand(10000f, g.coerceIn(-10.0, 10.0).toFloat(), 0.7f, BandType.HIGH_SHELF)
            bands.add(band); subtractBand(error, band)
        }

        val cap = maxBands.coerceAtMost(DspCoeffBuilder.MAX_PARAMETRIC)
        while (bands.size < cap) {
            val i = peakIndex(error) ?: break
            val gain = error[i]
            if (abs(gain) < 0.5) break
            val q = estimateQ(error, i, abs(gain))
            val band = ParamBand(
                grid[i].toFloat(),
                gain.coerceIn(-12.0, 12.0).toFloat(),
                q.toFloat(),
                BandType.PEAK,
            )
            bands.add(band); subtractBand(error, band)
        }

        if (bands.isEmpty()) return ParsedEq(0f, emptyList())
        return ParsedEq(headroomPreamp(bands), bands)
    }

    private fun interpolate(curve: FrCurve): DoubleArray {
        val xs = curve.map { ln(it.first.toDouble().coerceAtLeast(1.0)) }
        val ys = curve.map { it.second.toDouble() }
        val out = DoubleArray(grid.size)
        var j = 0
        for (i in grid.indices) {
            val x = ln(grid[i])
            while (j < xs.size - 2 && xs[j + 1] < x) j++
            out[i] = when {
                x <= xs.first() -> ys.first()
                x >= xs.last() -> ys.last()
                else -> {
                    val x0 = xs[j]; val x1 = xs[j + 1]
                    val frac = if (x1 > x0) (x - x0) / (x1 - x0) else 0.0
                    ys[j] + frac * (ys[j + 1] - ys[j])
                }
            }
        }
        return out
    }

    private fun normalize(arr: DoubleArray): DoubleArray {
        var sum = 0.0; var n = 0
        for (i in grid.indices) if (grid[i] in 200.0..2000.0) { sum += arr[i]; n++ }
        val mean = if (n > 0) sum / n else 0.0
        for (i in arr.indices) arr[i] -= mean
        return arr
    }

    private fun smooth(arr: DoubleArray, radius: Int) {
        if (radius <= 0) return
        val src = arr.copyOf()
        for (i in arr.indices) {
            var sum = 0.0; var n = 0
            for (k in -radius..radius) {
                val j = i + k
                if (j in src.indices) { sum += src[j]; n++ }
            }
            arr[i] = sum / n
        }
    }

    private fun bassRegionMean(error: DoubleArray, below: Double): Double {
        var sum = 0.0; var n = 0
        for (i in grid.indices) if (grid[i] <= below) { sum += error[i]; n++ }
        return if (n > 0) sum / n else 0.0
    }

    private fun trebleRegionMean(error: DoubleArray, above: Double): Double {
        var sum = 0.0; var n = 0
        for (i in grid.indices) if (grid[i] >= above) { sum += error[i]; n++ }
        return if (n > 0) sum / n else 0.0
    }

    private fun peakIndex(error: DoubleArray): Int? {
        var best = -1; var bestVal = 0.0
        for (i in grid.indices) {
            if (grid[i] < 25 || grid[i] > 17000) continue
            val v = abs(error[i])
            if (v > bestVal) { bestVal = v; best = i }
        }
        return best.takeIf { it >= 0 }
    }

    private fun estimateQ(error: DoubleArray, center: Int, peakAbs: Double): Double {
        val half = peakAbs / 2.0
        val sign = if (error[center] >= 0) 1.0 else -1.0
        var lo = center
        while (lo > 0 && sign * error[lo] > half) lo--
        var hi = center
        while (hi < grid.size - 1 && sign * error[hi] > half) hi++
        val bwOct = log2(grid[hi] / grid[lo]).coerceAtLeast(0.05)
        val twoBw = 2.0.pow(bwOct)
        val q = sqrt(twoBw) / (twoBw - 1.0)
        return q.coerceIn(0.5, 6.0)
    }

    private fun subtractBand(error: DoubleArray, band: ParamBand) {
        for (i in grid.indices) {
            error[i] -= DspCoeffBuilder.bandMagnitudeDb(band.type, band.freqHz, band.gainDb, band.q, grid[i])
        }
    }

    // negative of the largest positive combined gain so boosted eq never clips
    private fun headroomPreamp(bands: List<ParamBand>): Float {
        var maxDb = 0.0
        for (f in grid) {
            var sum = 0.0
            for (b in bands) sum += DspCoeffBuilder.bandMagnitudeDb(b.type, b.freqHz, b.gainDb, b.q, f)
            if (sum > maxDb) maxDb = sum
        }
        return (-maxDb).coerceIn(-20.0, 0.0).toFloat()
    }
}
