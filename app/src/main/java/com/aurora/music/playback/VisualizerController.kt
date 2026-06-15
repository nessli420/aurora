package com.aurora.music.playback

import androidx.media3.common.C
import com.aurora.music.data.VisualizerPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// fft must stay off the audio thread bit-perfect usb underruns otherwise
class VisualizerController(private val scope: CoroutineScope) {

    class Frame {
        @Volatile var bands: FloatArray = FloatArray(64)
        @Volatile var peaks: FloatArray = FloatArray(64)
        @Volatile var wave: FloatArray = FloatArray(256)
        @Volatile var rms: Float = 0f
        @Volatile var bass: Float = 0f
        @Volatile var level: Float = 0f
    }

    val frame = Frame()

    // pull source for pcm that bypasses the sink taps native flac decodes in c++
    interface MonoSource {
        fun read(out: FloatArray): Int
        fun sampleRate(): Int
        fun active(): Boolean
    }
    @Volatile var monoSource: MonoSource? = null

    @Volatile private var bandCount = 64
    @Volatile private var fftSize = 2048
    @Volatile private var smoothing = 0.78f
    @Volatile private var sensitivity = 1.0f
    @Volatile private var minHz = 30
    @Volatile private var maxHz = 16000
    @Volatile private var peakHold = true
    @Volatile private var fpsCap = 60

    private val ringBits = 14
    private val ringSize = 1 shl ringBits
    private val ringMask = ringSize - 1
    private val ring = FloatArray(ringSize)
    @Volatile private var writeIdx = 0
    @Volatile private var sampleRate = 48000

    @Volatile var active = false
        private set
    private var job: Job? = null

    fun applyPrefs(p: VisualizerPrefs) {
        bandCount = p.barCount.coerceIn(8, 256)
        fftSize = when { p.fftSize >= 4096 -> 4096; p.fftSize <= 1024 -> 1024; else -> 2048 }
        smoothing = p.smoothing.coerceIn(0f, 0.97f)
        sensitivity = p.sensitivity.coerceIn(0.1f, 6f)
        minHz = p.minHz.coerceIn(10, 2000)
        maxHz = p.maxHz.coerceIn(minHz + 100, 22000)
        peakHold = p.peakHold
        fpsCap = p.fpsCap.coerceIn(15, 120)
    }

    fun start() {
        if (active) return
        active = true
        job = scope.launch(Dispatchers.Default) { analyseLoop() }
    }

    fun stop() {
        active = false
        job?.cancel(); job = null
        java.util.Arrays.fill(frame.bands, 0f)
        java.util.Arrays.fill(frame.peaks, 0f)
        java.util.Arrays.fill(frame.wave, 0f)
        frame.rms = 0f; frame.bass = 0f; frame.level = 0f
    }

    fun pushFloat(samples: FloatArray, channelCount: Int, sr: Int) {
        if (!active || channelCount <= 0) return
        sampleRate = sr
        var w = writeIdx
        val ch = channelCount
        var i = 0
        val n = samples.size
        if (ch == 1) {
            while (i < n) { ring[w] = samples[i]; w = (w + 1) and ringMask; i++ }
        } else {
            while (i + ch <= n) {
                var s = 0f; var c = 0; while (c < ch) { s += samples[i + c]; c++ }
                ring[w] = s / ch; w = (w + 1) and ringMask; i += ch
            }
        }
        writeIdx = w
    }

    // absolute indexing leaves the buffer position untouched for the real sink
    fun pushPcm(buffer: ByteBuffer, encoding: Int, channelCount: Int, sr: Int) {
        if (!active || channelCount <= 0) return
        sampleRate = sr
        val order = buffer.order()
        val pos = buffer.position()
        val lim = buffer.limit()
        var w = writeIdx
        val ch = channelCount
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                val frameBytes = 2 * ch
                var p = pos
                while (p + frameBytes <= lim) {
                    var s = 0
                    var c = 0
                    while (c < ch) {
                        val lo = buffer.get(p + c * 2).toInt() and 0xFF
                        val hi = buffer.get(p + c * 2 + 1).toInt()
                        s += if (order == ByteOrder.LITTLE_ENDIAN) ((hi shl 8) or lo) else (((lo shl 8) or (hi and 0xFF)))
                        c++
                    }
                    ring[w] = (s.toFloat() / ch) / 32768f
                    w = (w + 1) and ringMask
                    p += frameBytes
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                val frameBytes = 4 * ch
                var p = pos
                while (p + frameBytes <= lim) {
                    var s = 0f; var c = 0
                    while (c < ch) { s += buffer.getFloat(p + c * 4); c++ }
                    ring[w] = s / ch
                    w = (w + 1) and ringMask
                    p += frameBytes
                }
            }
            else -> return // skip rather than mis-scale
        }
        writeIdx = w
    }

    private suspend fun analyseLoop() {
        var n = fftSize
        var fft = Fft(n)
        var re = FloatArray(n)
        var im = FloatArray(n)
        var window = hann(n)
        var samp = FloatArray(n)
        var bands = FloatArray(bandCount)
        var smoothed = FloatArray(bandCount)
        var peaks = FloatArray(bandCount)

        while (scope.isActive && active) {
          try {
            if (n != fftSize) {
                n = fftSize; fft = Fft(n); re = FloatArray(n); im = FloatArray(n); window = hann(n); samp = FloatArray(n)
            }
            if (bands.size != bandCount) {
                bands = FloatArray(bandCount); smoothed = FloatArray(bandCount); peaks = FloatArray(bandCount)
            }

            val src = monoSource
            if (src != null && src.active()) {
                val got = src.read(samp)
                if (got in 1 until n) java.util.Arrays.fill(samp, got, n, 0f)
                else if (got <= 0) java.util.Arrays.fill(samp, 0f)
                if (got > 0) sampleRate = src.sampleRate()
            } else {
                val end = writeIdx
                var idx = (end - n) and ringMask
                for (k in 0 until n) { samp[k] = ring[idx]; idx = (idx + 1) and ringMask }
            }

            var rmsAcc = 0f
            for (k in 0 until n) {
                val v = samp[k]
                re[k] = v * window[k]
                im[k] = 0f
                rmsAcc += v * v
            }
            fft.transform(re, im, false)

            val sr = sampleRate.coerceAtLeast(8000)
            val nyquistBins = n / 2
            val binHz = sr.toFloat() / n
            var loBin = max(1, (minHz / binHz).toInt())
            var hiBin = (maxHz / binHz).toInt()
            if (hiBin > nyquistBins - 1) hiBin = nyquistBins - 1
            if (loBin >= hiBin) loBin = (hiBin - 1).coerceAtLeast(1)
            val logLo = ln(loBin.toFloat())
            val logHi = ln(hiBin.toFloat())

            var levelMax = 0f
            for (b in 0 until bandCount) {
                val f0 = logLo + (logHi - logLo) * b / bandCount
                val f1 = logLo + (logHi - logLo) * (b + 1) / bandCount
                // manual clamps not coerceIn so an inverted range can never throw
                var b0 = kotlin.math.exp(f0).toInt()
                var b1 = kotlin.math.exp(f1).toInt()
                if (b0 < loBin) b0 = loBin
                if (b0 > hiBin) b0 = hiBin
                if (b1 <= b0) b1 = b0 + 1
                if (b1 > hiBin + 1) b1 = hiBin + 1
                var mag = 0f
                var c = b0
                while (c < b1 && c < nyquistBins) {
                    val m = sqrt(re[c] * re[c] + im[c] * im[c])
                    if (m > mag) mag = m
                    c++
                }
                val norm = mag / (n * 0.5f)
                var v = (ln(1f + norm * 320f * sensitivity) / ln(321f)).coerceIn(0f, 1f)
                v *= 0.55f + 0.45f * (b.toFloat() / bandCount)
                bands[b] = v
                if (v > levelMax) levelMax = v
            }

            val release = smoothing
            for (b in 0 until bandCount) {
                val target = bands[b]
                smoothed[b] = if (target > smoothed[b]) {
                    smoothed[b] + (target - smoothed[b]) * 0.6f
                } else {
                    smoothed[b] * release
                }
                if (peakHold) {
                    peaks[b] = if (smoothed[b] >= peaks[b]) smoothed[b] else peaks[b] * 0.94f
                }
            }

            val wave = FloatArray(256)
            val step = max(1, n / 256)
            for (k in 0 until 256) wave[k] = samp[k * step].coerceIn(-1f, 1f)

            var bass = 0f
            val bassBands = max(1, bandCount / 8)
            for (b in 0 until bassBands) bass += smoothed[b]
            bass /= bassBands

            frame.bands = smoothed.copyOf()
            frame.peaks = peaks.copyOf()
            frame.wave = wave
            frame.rms = sqrt(rmsAcc / n).coerceIn(0f, 1f)
            frame.bass = bass.coerceIn(0f, 1f)
            frame.level = levelMax

          } catch (_: Throwable) {
              // a visualizer frame must never crash the app
          }
          delay((1000L / fpsCap).coerceAtLeast(8L))
        }
    }

    private fun hann(n: Int): FloatArray = FloatArray(n) { i ->
        (0.5f - 0.5f * cos(2.0 * Math.PI * i / (n - 1)).toFloat())
    }
}
