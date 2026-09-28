package com.aurora.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import com.aurora.music.data.PcmLevels
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

// one audio writer; bounded analysis buffers and lock-free snapshots
class PcmLevelMeter {
    internal val spectrum = PcmSpectrumTap()
    private var encoding = 0
    private var channels = 0
    private var rate = 0
    private var bytes = 0
    private var window = 0
    private var count = 0
    private var frames = 0L
    private var fullScale = 0L
    private var invalid = 0L
    private var peakL = 0.0
    private var peakR = 0.0
    private var sumL = 0.0
    private var sumR = 0.0
    @Volatile private var revision = 0L
    @Volatile private var publishedRate = 0
    @Volatile private var publishedChannels = 0
    @Volatile private var publishedFrames = 0L
    @Volatile private var publishedWindow = 0
    @Volatile private var publishedPeakL = 0.0
    @Volatile private var publishedPeakR = 0.0
    @Volatile private var publishedSumL = 0.0
    @Volatile private var publishedSumR = 0.0
    @Volatile private var publishedFullScale = 0L
    @Volatile private var publishedInvalid = 0L
    @Volatile private var publishedTimeUs = C.TIME_UNSET
    @Volatile private var publishedAt = 0L

    @OptIn(UnstableApi::class)
    fun configure(encoding: Int, channels: Int, sampleRate: Int) {
        this.encoding = encoding
        this.channels = channels
        rate = sampleRate
        bytes = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> 0
        }
        window = max(1, sampleRate / 10)
        reset()
    }

    fun reset() {
        spectrum.reset()
        count = 0; frames = 0; fullScale = 0; invalid = 0
        peakL = 0.0; peakR = 0.0; sumL = 0.0; sumR = 0.0
        revision++
        publishedFrames = 0; publishedAt = 0
        revision++
    }

    /** Observe only complete frames in the consumed range. Absolute reads leave the buffer intact. */
    @OptIn(UnstableApi::class)
    fun observe(buffer: ByteBuffer, start: Int, end: Int, presentationTimeUs: Long = C.TIME_UNSET) {
        if (bytes == 0 || channels !in 1..2 || rate <= 0 || start < 0 || end > buffer.limit()) return
        val stride = bytes * channels
        if ((end - start) % stride != 0) return
        var position = start
        var offsetFrames = 0
        val positiveMax = when (encoding) {
            C.ENCODING_PCM_16BIT -> 32767.0 / 32768.0
            C.ENCODING_PCM_24BIT -> 8388607.0 / 8388608.0
            C.ENCODING_PCM_32BIT -> 2147483647.0 / 2147483648.0
            else -> 1.0
        }
        while (position + stride <= end) {
            var left = sample(buffer, position)
            var right = if (channels == 2) sample(buffer, position + bytes) else left
            if (!left.isFinite()) { invalid++; left = 0.0 }
            if (channels == 2 && !right.isFinite()) { invalid++; right = 0.0 }
            if (channels == 1) right = left
            spectrum.observe(left, right, rate, if (presentationTimeUs == C.TIME_UNSET) C.TIME_UNSET
                else presentationTimeUs + offsetFrames * 1_000_000L / rate)
            if (left >= positiveMax || left <= -1.0) fullScale++
            if (channels == 2 && (right >= positiveMax || right <= -1.0)) fullScale++
            peakL = max(peakL, abs(left)); peakR = max(peakR, abs(right))
            sumL += left * left; sumR += right * right
            count++; frames++; offsetFrames++
            if (count == window) {
                revision++
                publishedRate = rate; publishedChannels = channels
                publishedFrames = frames; publishedWindow = count
                publishedPeakL = peakL; publishedPeakR = peakR
                publishedSumL = sumL; publishedSumR = sumR
                publishedFullScale = fullScale; publishedInvalid = invalid
                publishedTimeUs = if (presentationTimeUs == C.TIME_UNSET) C.TIME_UNSET
                    else presentationTimeUs + offsetFrames * 1_000_000L / rate
                publishedAt = System.nanoTime()
                revision++
                count = 0; peakL = 0.0; peakR = 0.0; sumL = 0.0; sumR = 0.0
            }
            position += stride
        }
    }

    // Android's decoded little-endian PCM, independent of ByteBuffer.order().
    @OptIn(UnstableApi::class)
    private fun sample(buffer: ByteBuffer, p: Int): Double {
        val lo = buffer.get(p).toInt() and 255
        val hi = buffer.get(p + 1).toInt()
        if (bytes == 2) return ((hi shl 8) or lo) / 32768.0
        val third = buffer.get(p + 2).toInt()
        if (bytes == 3) return ((third shl 16) or ((hi and 255) shl 8) or lo) / 8388608.0
        val bits = (buffer.get(p + 3).toInt() shl 24) or ((third and 255) shl 16) or ((hi and 255) shl 8) or lo
        return if (encoding == C.ENCODING_PCM_FLOAT) Float.fromBits(bits).toDouble() else bits / 2147483648.0
    }

    /** Called by the service/UI thread; all allocation and square roots happen here. */
    fun snapshot(): PcmLevels? {
        repeat(3) {
            val stamp = revision
            if (stamp and 1L != 0L) return@repeat
            val result = PcmLevels(publishedRate, publishedChannels, publishedWindow, publishedFrames,
                publishedPeakL, publishedPeakR, sqrt(publishedSumL / max(1, publishedWindow)),
                sqrt(publishedSumR / max(1, publishedWindow)), publishedFullScale, publishedInvalid,
                publishedTimeUs.takeUnless { it == C.TIME_UNSET }, publishedAt)
            if (revision == stamp) return result.takeIf { it.framesSinceReset > 0 }
        }
        return null
    }
}
