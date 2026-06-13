package com.aurora.music.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.max

/** A loaded impulse response: per-channel taps at [sampleRate]. Mono IRs duplicate L into R. */
class ImpulseResponse(val left: FloatArray, val right: FloatArray, val sampleRate: Int)

/**
 * Uniformly-partitioned overlap-save FFT convolver for ONE channel. All buffers are preallocated in
 * [configure] (off the audio thread); [process] does no allocation. Latency = [B] samples.
 */
private class PartitionedConvolver(private val B: Int) {
    private val N = B * 2
    private val fft = Fft(N)
    private var K = 0
    private var hr = arrayOf<FloatArray>(); private var hi = arrayOf<FloatArray>()
    private var fdlRe = arrayOf<FloatArray>(); private var fdlIm = arrayOf<FloatArray>()
    private var fdlPos = 0
    private val prev = FloatArray(B)
    private val xr = FloatArray(N); private val xi = FloatArray(N)
    private val yr = FloatArray(N); private val yi = FloatArray(N)

    fun configure(ir: FloatArray) {
        K = max(1, ceil(ir.size / B.toDouble()).toInt())
        hr = Array(K) { FloatArray(N) }; hi = Array(K) { FloatArray(N) }
        val tr = FloatArray(N); val tiArr = FloatArray(N)
        for (k in 0 until K) {
            java.util.Arrays.fill(tr, 0f); java.util.Arrays.fill(tiArr, 0f)
            val start = k * B
            for (j in 0 until B) { val idx = start + j; if (idx < ir.size) tr[j] = ir[idx] }
            fft.transform(tr, tiArr, false)
            System.arraycopy(tr, 0, hr[k], 0, N); System.arraycopy(tiArr, 0, hi[k], 0, N)
        }
        fdlRe = Array(K) { FloatArray(N) }; fdlIm = Array(K) { FloatArray(N) }
        fdlPos = 0; java.util.Arrays.fill(prev, 0f)
    }

    /** Convolve [B] samples from input[inOff..] into output[outOff..]. */
    fun process(input: FloatArray, inOff: Int, output: FloatArray, outOff: Int) {
        System.arraycopy(prev, 0, xr, 0, B)
        System.arraycopy(input, inOff, xr, B, B)
        java.util.Arrays.fill(xi, 0f)
        fft.transform(xr, xi, false)
        System.arraycopy(xr, 0, fdlRe[fdlPos], 0, N)
        System.arraycopy(xi, 0, fdlIm[fdlPos], 0, N)
        java.util.Arrays.fill(yr, 0f); java.util.Arrays.fill(yi, 0f)
        for (k in 0 until K) {
            val idx = ((fdlPos - k) % K + K) % K
            val ar = hr[k]; val ai = hi[k]; val br = fdlRe[idx]; val bi = fdlIm[idx]
            var i = 0
            while (i < N) {
                yr[i] += ar[i] * br[i] - ai[i] * bi[i]
                yi[i] += ar[i] * bi[i] + ai[i] * br[i]
                i++
            }
        }
        fft.transform(yr, yi, true)
        System.arraycopy(yr, B, output, outOff, B)        // valid (overlap-save) half
        System.arraycopy(input, inOff, prev, 0, B)
        fdlPos = (fdlPos + 1) % K
    }
}

/** A fixed-capacity float FIFO (single producer/consumer on the audio thread). */
private class FloatFifo(capacity: Int) {
    private val buf = FloatArray(capacity)
    private var head = 0; private var tail = 0
    var count = 0; private set
    fun clear() { head = 0; tail = 0; count = 0 }
    fun push(v: Float) { buf[tail] = v; tail = (tail + 1) % buf.size; if (count < buf.size) count++ else head = (head + 1) % buf.size }
    fun pop(): Float { val v = buf[head]; head = (head + 1) % buf.size; count--; return v }
    fun popBlock(out: FloatArray, n: Int) { for (i in 0 until n) out[i] = pop() }
    fun pushBlock(src: FloatArray, n: Int) { for (i in 0 until n) push(src[i]) }
}

/**
 * Impulse-response convolver. Convolves 16-bit stereo PCM with a loaded WAV IR (headphone/room
 * correction, AutoEq convolution WAVs, speaker/room sims) using a partitioned overlap-save FFT per
 * channel. Last in the DSP chain; passes through when disabled or no IR is loaded.
 */
@UnstableApi
class ConvolutionProcessor : BaseAudioProcessor() {

    @Volatile var enabled: Boolean = false
    @Volatile private var makeupLin: Float = 1f

    private val b = 1024                       // partition size → ~21 ms latency @ 48 kHz
    private val lock = Any()
    @Volatile private var convL: PartitionedConvolver? = null
    @Volatile private var convR: PartitionedConvolver? = null
    @Volatile private var ready = false

    private var rawL: FloatArray? = null
    private var rawR: FloatArray? = null
    private var rawRate = 0
    private var configuredRate = 0

    private val cap = 1 shl 16
    private val inL = FloatFifo(cap); private val inR = FloatFifo(cap)
    private val outL = FloatFifo(cap); private val outR = FloatFifo(cap)
    private val blkIn = FloatArray(b); private val blkOut = FloatArray(b)

    fun setMakeup(db: Float) { makeupLin = Math.pow(10.0, db / 20.0).toFloat() }

    /** Set (or clear with null) the active impulse response. Heavy work runs on the calling thread. */
    fun setImpulse(ir: ImpulseResponse?, makeupDb: Float) {
        makeupLin = Math.pow(10.0, makeupDb / 20.0).toFloat()
        synchronized(lock) {
            rawL = ir?.left; rawR = ir?.right; rawRate = ir?.sampleRate ?: 0
            rebuild()
        }
    }

    private fun rebuild() {
        val l = rawL; val r = rawR
        if (l == null || r == null || configuredRate <= 0) { ready = false; return }
        val irL = resample(l, rawRate, configuredRate)
        val irR = resample(r, rawRate, configuredRate)
        val cl = PartitionedConvolver(b).apply { configure(irL) }
        val cr = PartitionedConvolver(b).apply { configure(irR) }
        convL = cl; convR = cr
        inL.clear(); inR.clear(); outL.clear(); outR.clear()
        ready = true
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount != 2) {
            return AudioFormat.NOT_SET
        }
        synchronized(lock) {
            configuredRate = inputAudioFormat.sampleRate
            rebuild()
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val rem = inputBuffer.remaining()
        if (rem == 0) return
        val cl = convL; val cr = convR
        if (!enabled || !ready || cl == null || cr == null) {
            val out = replaceOutputBuffer(rem); out.put(inputBuffer); out.flip(); return
        }
        inputBuffer.order(ByteOrder.nativeOrder())
        val limit = inputBuffer.limit()
        var p = inputBuffer.position()
        while (p + 4 <= limit) {
            inL.push(inputBuffer.getShort(p) / 32768f)
            inR.push(inputBuffer.getShort(p + 2) / 32768f)
            p += 4
        }
        inputBuffer.position(limit)

        while (inL.count >= b) {
            inL.popBlock(blkIn, b); cl.process(blkIn, 0, blkOut, 0); outL.pushBlock(blkOut, b)
            inR.popBlock(blkIn, b); cr.process(blkIn, 0, blkOut, 0); outR.pushBlock(blkOut, b)
        }
        emit(minOf(outL.count, outR.count))
    }

    private fun emit(frames: Int) {
        val out = replaceOutputBuffer(frames * 4)
        out.order(ByteOrder.nativeOrder())
        val mk = makeupLin
        for (i in 0 until frames) {
            out.putShort(toPcm16(outL.pop() * mk))
            out.putShort(toPcm16(outR.pop() * mk))
        }
        out.flip()
    }

    override fun onQueueEndOfStream() {
        val cl = convL; val cr = convR
        if (enabled && ready && cl != null && cr != null && inL.count > 0) {
            // Zero-pad the final partial block so the buffered audio isn't dropped at track end.
            while (inL.count % b != 0) { inL.push(0f); inR.push(0f) }
            while (inL.count >= b) {
                inL.popBlock(blkIn, b); cl.process(blkIn, 0, blkOut, 0); outL.pushBlock(blkOut, b)
                inR.popBlock(blkIn, b); cr.process(blkIn, 0, blkOut, 0); outR.pushBlock(blkOut, b)
            }
            emit(minOf(outL.count, outR.count))
        }
    }

    override fun onFlush() {
        inL.clear(); inR.clear(); outL.clear(); outR.clear()
    }

    private fun toPcm16(x: Float): Short {
        val v = x * 32767f
        return when { v >= 32767f -> 32767; v <= -32768f -> -32768; else -> Math.round(v) }.toShort()
    }

    private fun resample(src: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (srcRate <= 0 || srcRate == dstRate) return src
        val ratio = dstRate.toDouble() / srcRate
        val outLen = (src.size * ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val srcPos = i / ratio
            val i0 = srcPos.toInt()
            val frac = (srcPos - i0).toFloat()
            val a = src.getOrElse(i0) { 0f }
            val b2 = src.getOrElse(i0 + 1) { a }
            out[i] = a + (b2 - a) * frac
        }
        return out
    }

    companion object {
        /** Parse a PCM/float WAV file into an [ImpulseResponse]. Returns null on unsupported/invalid. */
        fun loadWav(file: File): ImpulseResponse? = runCatching {
            val bytes = file.readBytes()
            if (bytes.size < 44) return null
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (bb.int != 0x46464952) return null            // "RIFF"
            bb.int                                            // file size
            if (bb.int != 0x45564157) return null            // "WAVE"
            var fmtFound = false
            var audioFormat = 1; var channels = 1; var rate = 48000; var bits = 16
            var dataOff = -1; var dataLen = 0
            while (bb.remaining() >= 8) {
                val id = bb.int; val sz = bb.int
                when (id) {
                    0x20746d66 -> {                           // "fmt "
                        val start = bb.position()
                        audioFormat = bb.short.toInt() and 0xffff
                        channels = bb.short.toInt() and 0xffff
                        rate = bb.int
                        bb.int                                // byte rate
                        bb.short                              // block align
                        bits = bb.short.toInt() and 0xffff
                        bb.position(start + sz)
                        fmtFound = true
                    }
                    0x61746164 -> { dataOff = bb.position(); dataLen = sz; bb.position(bb.position() + sz) } // "data"
                    else -> bb.position(bb.position() + sz + (sz and 1))
                }
                if (dataOff >= 0 && fmtFound) break
            }
            if (!fmtFound || dataOff < 0 || channels !in 1..2) return null
            val bytesPerSample = bits / 8
            val frameSize = bytesPerSample * channels
            val frames = dataLen / frameSize
            val l = FloatArray(frames); val r = FloatArray(frames)
            val d = ByteBuffer.wrap(bytes, dataOff, dataLen).order(ByteOrder.LITTLE_ENDIAN)
            fun sample(): Float = when {
                audioFormat == 3 && bits == 32 -> d.float
                bits == 16 -> d.short / 32768f
                bits == 24 -> { val b0 = d.get().toInt() and 0xff; val b1 = d.get().toInt() and 0xff; val b2 = d.get().toInt(); ((b2 shl 16) or (b1 shl 8) or b0) / 8388608f }
                bits == 32 -> d.int / 2147483648f
                bits == 8 -> (d.get().toInt() and 0xff) / 128f - 1f
                else -> 0f
            }
            for (i in 0 until frames) {
                l[i] = sample()
                r[i] = if (channels == 2) sample() else l[i]
            }
            ImpulseResponse(l, r, rate)
        }.getOrNull()
    }
}
