package com.aurora.music.data

import com.aurora.music.playback.Fft
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

/**
 * On-device classic-MIR feature extraction for the sonic-similarity engine. Decodes a local/downloaded
 * track to mono PCM (via [AudioDecoder]) and reduces it to a fixed-length descriptor:
 *  - MFCC timbre, mean + std (what it "sounds like")
 *  - spectral shape stats (centroid / rolloff / flatness / flux / zero-crossing), mean + std
 *  - dynamics (RMS), mean + std
 *  - a 12-bin chroma (harmonic / key content)
 *  - a coarse tempo estimate
 *
 * The vector is raw and un-normalized; [SonicEngine] z-scores each dimension across the analyzed
 * library before comparing, so wildly different feature scales don't dominate the similarity.
 */
object SonicFeatures {
    /** Bump when the algorithm/layout changes so stale vectors are re-analyzed. */
    const val VERSION = 2

    private const val FFT_SIZE = 2048
    private const val HOP = 2048              // no overlap — ~4x fewer FFTs than 50% overlap
    private const val N_MELS = 40
    private const val N_MFCC = 13
    private const val MAX_SECONDS = 30        // a 30s excerpt is plenty for similarity, ~2x faster decode
    private const val MIN_BPM = 60.0
    private const val MAX_BPM = 180.0

    // 19 per-frame scalar streams (13 mfcc + centroid,rolloff,flatness,flux,zcr + rms) -> mean+std = 38,
    // plus 12 chroma + 1 tempo.
    private const val SCALARS = N_MFCC + 5 + 1
    const val DIMS = SCALARS * 2 + 12 + 1

    /** Decode [path] and compute its [DIMS]-length feature vector, or null on failure / too-short audio. */
    fun analyze(path: String, isCancelled: () -> Boolean = { false }): FloatArray? {
        var sr = 0
        var ch = 1
        var mono: FloatArray? = null
        var n = 0

        AudioDecoder.decode(
            path,
            { s, c -> sr = s; ch = max(1, c); mono = FloatArray(MAX_SECONDS * s) },
            { pcm, len ->
                val m = mono
                if (m != null) {
                    var i = 0
                    while (i + ch <= len && n < m.size) {
                        var sum = 0f; var k = 0
                        while (k < ch) { sum += pcm[i + k] / 32768f; k++ }
                        m[n++] = sum / ch
                        i += ch
                    }
                }
            },
            { isCancelled() || (mono != null && n >= mono!!.size) },
        )

        val samples = mono ?: return null
        if (sr <= 0 || n < FFT_SIZE * 4) return null   // too short to characterize

        val bins = FFT_SIZE / 2 + 1
        val fft = Fft(FFT_SIZE)
        val re = FloatArray(FFT_SIZE)
        val im = FloatArray(FFT_SIZE)
        val window = FloatArray(FFT_SIZE) { (0.5 - 0.5 * cos(2.0 * Math.PI * it / (FFT_SIZE - 1))).toFloat() }
        val mag = FloatArray(bins)
        var prevMag = FloatArray(bins)

        val melBank = melFilterbank(sr, bins)         // [N_MELS][bins]
        val dct = dctMatrix()                          // [N_MFCC][N_MELS]
        val pitchClass = chromaBins(sr, bins)          // bin -> 0..11 (or -1)

        // Welford accumulators for the 19 scalar streams.
        var count = 0L
        val mean = DoubleArray(SCALARS)
        val m2 = DoubleArray(SCALARS)
        val chroma = DoubleArray(12)
        val frameCount = (n - FFT_SIZE) / HOP + 1
        val flux = FloatArray(max(1, frameCount))
        var fi = 0

        val logMel = DoubleArray(N_MELS)
        val scal = DoubleArray(SCALARS)

        var start = 0
        while (start + FFT_SIZE <= n) {
            if (isCancelled()) return null

            // time-domain RMS + zero-crossings on the raw frame
            var sq = 0.0; var zc = 0; var prevSign = 0
            for (j in 0 until FFT_SIZE) {
                val s = samples[start + j]
                sq += s.toDouble() * s
                val sign = if (s >= 0f) 1 else -1
                if (j > 0 && sign != prevSign) zc++
                prevSign = sign
                re[j] = s * window[j]; im[j] = 0f
            }
            val rms = sqrt(sq / FFT_SIZE)
            val zcr = zc.toDouble() / FFT_SIZE

            fft.transform(re, im, false)
            var magSum = 0.0; var centNum = 0.0
            var logSum = 0.0; var powSum = 0.0
            for (b in 0 until bins) {
                val mg = sqrt(re[b] * re[b] + im[b] * im[b])
                mag[b] = mg
                val f = b.toFloat() * sr / FFT_SIZE
                magSum += mg
                centNum += f * mg
                val p = mg * mg + 1e-12
                logSum += ln(p); powSum += p
            }
            val centroid = if (magSum > 0) centNum / magSum else 0.0
            // spectral flatness = geometric mean / arithmetic mean of power
            val flatness = if (powSum > 0) Math.exp(logSum / bins) / (powSum / bins) else 0.0
            // rolloff: frequency below which 85% of magnitude energy lies
            var acc = 0.0; var rollBin = 0; val thr = magSum * 0.85
            for (b in 0 until bins) { acc += mag[b]; if (acc >= thr) { rollBin = b; break } }
            val rolloff = rollBin.toDouble() * sr / FFT_SIZE
            // spectral flux (positive change vs previous frame)
            var fl = 0.0
            for (b in 0 until bins) { val d = mag[b] - prevMag[b]; if (d > 0) fl += d }
            val tmp = prevMag; prevMag = mag.copyInto(tmp)  // keep prev = this frame's mag
            if (fi < flux.size) flux[fi] = fl.toFloat()

            // mel -> log -> MFCC
            for (m in 0 until N_MELS) {
                var e = 0.0
                val filt = melBank[m]
                for (b in 0 until bins) e += filt[b] * (mag[b] * mag[b])
                logMel[m] = ln(e + 1e-10)
            }
            for (k in 0 until N_MFCC) {
                var c = 0.0
                val row = dct[k]
                for (m in 0 until N_MELS) c += row[m] * logMel[m]
                scal[k] = c
            }
            scal[N_MFCC] = centroid
            scal[N_MFCC + 1] = rolloff
            scal[N_MFCC + 2] = flatness
            scal[N_MFCC + 3] = fl
            scal[N_MFCC + 4] = zcr
            scal[N_MFCC + 5] = rms

            // Welford update across all scalar streams
            count++
            for (s in 0 until SCALARS) {
                val delta = scal[s] - mean[s]
                mean[s] += delta / count
                m2[s] += delta * (scal[s] - mean[s])
            }
            // chroma accumulation
            for (b in 1 until bins) {
                val pc = pitchClass[b]
                if (pc >= 0) chroma[pc] += mag[b]
            }

            fi++
            start += HOP
        }

        if (count < 2) return null

        val out = FloatArray(DIMS)
        var o = 0
        // means then stds, grouped: mfcc mean(13), mfcc std(13), spectral mean(5), spectral std(5), rms mean, rms std
        for (s in 0 until N_MFCC) out[o++] = mean[s].toFloat()
        for (s in 0 until N_MFCC) out[o++] = sqrt(m2[s] / (count - 1)).toFloat()
        for (s in N_MFCC until N_MFCC + 5) out[o++] = mean[s].toFloat()
        for (s in N_MFCC until N_MFCC + 5) out[o++] = sqrt(m2[s] / (count - 1)).toFloat()
        out[o++] = mean[N_MFCC + 5].toFloat()
        out[o++] = sqrt(m2[N_MFCC + 5] / (count - 1)).toFloat()
        // chroma, normalized to sum 1 (key/harmony shape, scale-invariant)
        var chSum = 0.0; for (v in chroma) chSum += v
        for (c in 0 until 12) out[o++] = (if (chSum > 0) chroma[c] / chSum else 0.0).toFloat()
        // coarse tempo
        out[o] = estimateTempo(flux, fi, sr).toFloat()
        return out
    }

    /** Autocorrelation of the onset (flux) envelope -> dominant BPM, scaled to ~0..1. */
    private fun estimateTempo(flux: FloatArray, len: Int, sr: Int): Double {
        if (len < 16) return 0.0
        var meanF = 0.0; for (i in 0 until len) meanF += flux[i]; meanF /= len
        val env = DoubleArray(len) { (flux[it] - meanF) }
        val frameRate = sr.toDouble() / HOP
        val minLag = max(1, round(frameRate * 60.0 / MAX_BPM).toInt())
        val maxLag = min(len - 1, round(frameRate * 60.0 / MIN_BPM).toInt())
        if (maxLag <= minLag) return 0.0
        var bestLag = minLag; var best = -1.0
        for (lag in minLag..maxLag) {
            var s = 0.0
            for (i in lag until len) s += env[i] * env[i - lag]
            if (s > best) { best = s; bestLag = lag }
        }
        val bpm = frameRate * 60.0 / bestLag
        return ((bpm - MIN_BPM) / (MAX_BPM - MIN_BPM)).coerceIn(0.0, 1.0)
    }

    private fun hzToMel(f: Double) = 2595.0 * log10(1.0 + f / 700.0)
    private fun melToHz(m: Double) = 700.0 * (Math.pow(10.0, m / 2595.0) - 1.0)
    private fun log10(x: Double) = ln(x) / ln(10.0)

    private fun melFilterbank(sr: Int, bins: Int): Array<FloatArray> {
        val fMax = sr / 2.0
        val melMax = hzToMel(fMax)
        val points = DoubleArray(N_MELS + 2) { melToHz(melMax * it / (N_MELS + 1)) }
        val binFreq = DoubleArray(bins) { it.toDouble() * sr / FFT_SIZE }
        return Array(N_MELS) { m ->
            val lo = points[m]; val ctr = points[m + 1]; val hi = points[m + 2]
            FloatArray(bins) { b ->
                val f = binFreq[b]
                val w = when {
                    f < lo || f > hi -> 0.0
                    f <= ctr -> if (ctr > lo) (f - lo) / (ctr - lo) else 0.0
                    else -> if (hi > ctr) (hi - f) / (hi - ctr) else 0.0
                }
                w.toFloat()
            }
        }
    }

    private fun dctMatrix(): Array<DoubleArray> = Array(N_MFCC) { k ->
        DoubleArray(N_MELS) { m -> cos(Math.PI / N_MELS * (m + 0.5) * k) }
    }

    private fun chromaBins(sr: Int, bins: Int): IntArray = IntArray(bins) { b ->
        if (b == 0) -1 else {
            val f = b.toDouble() * sr / FFT_SIZE
            if (f < 27.5 || f > 8000.0) -1
            else (((round(12.0 * log2(f / 440.0)).toInt() + 69) % 12) + 12) % 12
        }
    }
}
