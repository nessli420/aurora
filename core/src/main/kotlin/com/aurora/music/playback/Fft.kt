package com.aurora.music.playback

import kotlin.math.cos
import kotlin.math.sin

// no allocation in transform safe on the audio thread
class Fft(val n: Int) {
    init { require(n > 1 && (n and (n - 1)) == 0) { "FFT size must be a power of two" } }

    private val cosT = FloatArray(n / 2)
    private val sinT = FloatArray(n / 2)
    private val rev = IntArray(n)

    init {
        for (i in 0 until n / 2) {
            val ang = -2.0 * Math.PI * i / n
            cosT[i] = cos(ang).toFloat()
            sinT[i] = sin(ang).toFloat()
        }
        var bits = 0
        while ((1 shl bits) < n) bits++
        for (i in 0 until n) {
            var x = i; var r = 0
            for (b in 0 until bits) { r = (r shl 1) or (x and 1); x = x shr 1 }
            rev[i] = r
        }
    }

    fun transform(re: FloatArray, im: FloatArray, inverse: Boolean) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var i = 0
            while (i < n) {
                var k = 0
                var ti = 0
                while (k < half) {
                    val cosv = cosT[ti]
                    val sinv = if (inverse) -sinT[ti] else sinT[ti]
                    val iEven = i + k
                    val iOdd = i + k + half
                    val ure = re[iEven]; val uim = im[iEven]
                    val vre = re[iOdd] * cosv - im[iOdd] * sinv
                    val vim = re[iOdd] * sinv + im[iOdd] * cosv
                    re[iEven] = ure + vre; im[iEven] = uim + vim
                    re[iOdd] = ure - vre; im[iOdd] = uim - vim
                    k++; ti += step
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) {
            val inv = 1f / n
            for (i in 0 until n) { re[i] *= inv; im[i] *= inv }
        }
    }
}
