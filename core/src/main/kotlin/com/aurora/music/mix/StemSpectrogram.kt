package com.aurora.music.mix

import com.aurora.music.playback.Fft
import kotlin.math.*

/** Bluestein transform supports the separation model's 7680-point FFT. */
internal class ArbitraryFft(private val n: Int) {
    private val size = Integer.highestOneBit(2 * n - 2) shl 1
    private val fft = Fft(size)
    private val cosines = FloatArray(n) { cos(PI * it.toLong() * it / n).toFloat() }
    private val sines = FloatArray(n) { sin(PI * it.toLong() * it / n).toFloat() }
    private val br = FloatArray(size)
    private val bi = FloatArray(size)
    private val ar = FloatArray(size)
    private val ai = FloatArray(size)
    init {
        for (i in 0 until n) {
            br[i] = cosines[i]; bi[i] = sines[i]
            if (i > 0) { br[size - i] = cosines[i]; bi[size - i] = sines[i] }
        }
        fft.transform(br, bi, false)
    }
    fun transform(re: FloatArray, im: FloatArray, inverse: Boolean = false) {
        ar.fill(0f); ai.fill(0f)
        for (i in 0 until n) {
            val y = if (inverse) -im[i] else im[i]
            ar[i] = re[i] * cosines[i] + y * sines[i]
            ai[i] = y * cosines[i] - re[i] * sines[i]
        }
        fft.transform(ar, ai, false)
        for (i in ar.indices) {
            val x = ar[i] * br[i] - ai[i] * bi[i]
            ai[i] = ar[i] * bi[i] + ai[i] * br[i]; ar[i] = x
        }
        fft.transform(ar, ai, true)
        for (i in 0 until n) {
            re[i] = (ar[i] * cosines[i] + ai[i] * sines[i]) / if (inverse) n.toFloat() else 1f
            im[i] = (ai[i] * cosines[i] - ar[i] * sines[i]) / if (inverse) -n.toFloat() else 1f
        }
    }
}

/** MDX complex stereo layout: [left real, left imaginary, right real, right imaginary]. */
internal class StemSpectrogram {
    companion object { const val FFT = 7680; const val BINS = 3072; const val HOP = 1024; const val TIMES = 256; const val FRAMES = HOP * (TIMES - 1); const val TRIM = FFT / 2 }
    private val fft = ArbitraryFft(FFT)
    private val window = FloatArray(FFT) { (.5 - .5 * cos(2 * PI * it / FFT)).toFloat() }
    private val re = FloatArray(FFT)
    private val im = FloatArray(FFT)
    fun encode(stereo: Array<FloatArray>, cancelled: () -> Unit = {}): FloatArray {
        val out = FloatArray(4 * BINS * TIMES)
        for (channel in 0..1) for (frame in 0 until TIMES) {
            cancelled()
            for (i in 0 until FFT) { re[i] = stereo[channel].getOrElse(frame * HOP + i - TRIM) { 0f } * window[i]; im[i] = 0f }
            fft.transform(re, im)
            for (bin in 3 until BINS) { out[((channel * 2) * BINS + bin) * TIMES + frame] = re[bin]; out[((channel * 2 + 1) * BINS + bin) * TIMES + frame] = im[bin] }
        }
        return out
    }
    fun decode(spec: FloatArray, cancelled: () -> Unit = {}): Array<FloatArray> {
        val out = Array(2) { FloatArray(FRAMES) }
        val norm = FloatArray(FRAMES)
        for (frame in 0 until TIMES) for (i in 0 until FFT) {
            val index = frame * HOP + i - TRIM
            if (index in norm.indices) norm[index] += window[i] * window[i]
        }
        for (channel in 0..1) for (frame in 0 until TIMES) {
            cancelled(); re.fill(0f); im.fill(0f)
            for (bin in 0 until BINS) {
                re[bin] = spec[((channel * 2) * BINS + bin) * TIMES + frame]
                im[bin] = spec[((channel * 2 + 1) * BINS + bin) * TIMES + frame]
                if (bin > 0) { re[FFT - bin] = re[bin]; im[FFT - bin] = -im[bin] }
            }
            fft.transform(re, im, true)
            for (i in 0 until FFT) {
                val index = frame * HOP + i - TRIM
                if (index in norm.indices) out[channel][index] += re[i] * window[i]
            }
        }
        for (channel in 0..1) for (i in norm.indices) out[channel][i] /= norm[i].coerceAtLeast(1e-9f)
        return out
    }
}
