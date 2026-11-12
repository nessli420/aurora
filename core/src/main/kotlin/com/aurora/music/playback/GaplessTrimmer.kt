package com.aurora.music.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Joins decoded tracks into one interleaved stream: drops each track's encoder delay and padding,
 * optionally trims silence below a level-relative threshold with raised-cosine fades at every cut,
 * and inserts optional gaps between tracks. Output comes out in runs that never span a cut, so
 * [readSource] maps every frame back to its input frame ([GAP] for inserted silence).
 */
class GaplessTrimmer(private val channels: Int, sampleRate: Int, referenceDb: Double = -20.0) {
    var skipSilence = false
    /** Frames kept back past the known padding so [boundary] can still drop a timeline overrun. */
    var tailHold = 0

    /** Slow peak-hold of the programme level; the silence threshold sits [RELATIVE_DB] below it. */
    var referenceDb = referenceDb
        private set

    private val window = max(1, sampleRate / 100)
    private val fade = max(1, sampleRate / 100)
    private val keep = sampleRate * 150 / 1000
    private val minSilence = sampleRate * 2
    private val decayPerWindow = DECAY_DB_PER_SECOND * window / sampleRate
    private var threshold = 0.0

    private val pend = Fifo(channels)
    private var pendSource = 0L
    private var decided = 0
    private var state = PASS

    private val out = Fifo(channels)
    private val runs = ArrayDeque<LongArray>()

    private var source = 0L
    private var delay = 0
    private var padding = 0
    private var streamFrames = 0L

    var readSource = 0L
        private set
    val available: Int get() = out.size
    val holding: Boolean get() = pend.size > 0 || out.size > 0
    /** Input index just past the current track's audio once its known padding is dropped. */
    val streamEnd: Long get() = source - min(padding, pend.size - decided)

    init { updateThreshold() }

    fun startStream(delayFrames: Int, paddingFrames: Int) {
        delay = delayFrames.coerceAtLeast(0)
        padding = paddingFrames.coerceAtLeast(0)
        streamFrames = 0
    }

    fun queue(input: DoubleArray, offset: Int, frames: Int) {
        if (frames <= 0) return
        streamFrames += frames
        val skip = min(delay, frames)
        delay -= skip
        source += skip
        val count = frames - skip
        if (count == 0) return
        if (pend.size == 0) pendSource = source
        pend.push(input, offset + skip * channels, count)
        source += count
        classify(final = false)
    }

    /** Ends the current track: drops its padding plus [overrun], trims trailing silence and arms leading trim. */
    fun boundary(gapFrames: Int = 0, overrun: Int = 0) {
        if (streamFrames == 0L) return
        pend.dropLast(min(padding + overrun.coerceIn(0, tailHold), pend.size - decided))
        classify(final = true)
        if (skipSilence) {
            if (state == PENDING) cut()
            dropFront(decided)
            state = SKIP
        }
        if (gapFrames > 0) {
            out.pushZeros(gapFrames)
            runs.addLast(longArrayOf(gapFrames.toLong(), GAP))
        }
        streamFrames = 0
        delay = 0
        padding = 0
    }

    fun endOfStream() = boundary()

    fun read(target: DoubleArray, offset: Int, maxFrames: Int): Int {
        val head = runs.firstOrNull() ?: return 0
        val frames = min(maxFrames.toLong(), head[0]).toInt()
        out.pop(target, offset, frames)
        readSource = head[1]
        head[0] -= frames
        if (head[1] != GAP) head[1] += frames
        if (head[0] == 0L) runs.removeFirst()
        return frames
    }

    /** Seek keeps the stream's padding; a delay survives only while nothing of the stream was queued. */
    fun flush() {
        pend.clear(); out.clear(); runs.clear()
        decided = 0
        state = PASS
        source = 0
        if (streamFrames > 0) delay = 0
        streamFrames = 0
    }

    private fun classify(final: Boolean) {
        while (true) {
            val undecided = pend.size - decided
            val frames = if (final) min(window, undecided) else if (undecided - padding - tailHold >= window) window else 0
            if (frames <= 0) return
            decide(frames)
        }
    }

    private fun decide(frames: Int) {
        var peak = 0.0
        for (f in decided until decided + frames) for (c in 0 until channels) peak = max(peak, abs(pend[f, c]))
        val silent = peak < threshold
        if (!skipSilence || !silent) {
            if (!silent) level(peak)
            if (state == SKIP) fadeIn(min(decided, fade))
            release(decided + frames)
            state = PASS
            return
        }
        decided += frames
        when (state) {
            PASS -> state = PENDING
            PENDING -> if (decided >= minSilence) cut()
            SKIP -> if (decided > keep) dropFront(decided - keep)
        }
    }

    private fun cut() {
        val lead = min(keep, decided)
        val faded = min(fade, lead)
        for (i in 0 until faded) scale(lead - faded + i, 0.5 + 0.5 * cos(PI * (i + 0.5) / faded))
        release(lead)
        dropFront(decided - min(keep, decided))
        state = SKIP
    }

    private fun fadeIn(frames: Int) {
        for (i in 0 until frames) scale(i, 0.5 - 0.5 * cos(PI * (i + 0.5) / frames))
    }

    private fun scale(frame: Int, gain: Double) {
        for (c in 0 until channels) pend[frame, c] = pend[frame, c] * gain
    }

    private fun release(frames: Int) {
        if (frames == 0) return
        val last = runs.lastOrNull()
        if (last != null && last[1] != GAP && last[1] + last[0] == pendSource) last[0] += frames.toLong()
        else runs.addLast(longArrayOf(frames.toLong(), pendSource))
        pend.moveTo(out, frames)
        pendSource += frames
        decided = max(0, decided - frames)
    }

    private fun dropFront(frames: Int) {
        if (frames <= 0) return
        pend.drop(frames)
        pendSource += frames
        decided -= frames
    }

    private fun level(peak: Double) {
        referenceDb = max(referenceDb - decayPerWindow, 20 * log10(max(peak, 1e-12)))
        updateThreshold()
    }

    private fun updateThreshold() {
        threshold = 10.0.pow(max(referenceDb - RELATIVE_DB, FLOOR_DB) / 20)
    }

    private class Fifo(private val channels: Int) {
        private var data = DoubleArray(channels * 4096)
        private var head = 0
        var size = 0
            private set
        private val capacity get() = data.size / channels

        operator fun get(frame: Int, channel: Int) = data[(head + frame) % capacity * channels + channel]
        operator fun set(frame: Int, channel: Int, value: Double) { data[(head + frame) % capacity * channels + channel] = value }

        fun push(source: DoubleArray, offset: Int, frames: Int) {
            ensure(size + frames)
            var done = 0
            while (done < frames) {
                val tail = (head + size) % capacity
                val count = min(frames - done, capacity - tail)
                System.arraycopy(source, offset + done * channels, data, tail * channels, count * channels)
                size += count; done += count
            }
        }

        fun pushZeros(frames: Int) {
            ensure(size + frames)
            var done = 0
            while (done < frames) {
                val tail = (head + size) % capacity
                val count = min(frames - done, capacity - tail)
                data.fill(0.0, tail * channels, (tail + count) * channels)
                size += count; done += count
            }
        }

        fun pop(target: DoubleArray, offset: Int, frames: Int) {
            var done = 0
            while (done < frames) {
                val count = min(frames - done, capacity - head)
                System.arraycopy(data, head * channels, target, offset + done * channels, count * channels)
                head = (head + count) % capacity; size -= count; done += count
            }
        }

        fun moveTo(other: Fifo, frames: Int) {
            var done = 0
            while (done < frames) {
                val count = min(frames - done, capacity - head)
                other.push(data, head * channels, count)
                head = (head + count) % capacity; size -= count; done += count
            }
        }

        fun drop(frames: Int) { head = (head + frames) % capacity; size -= frames }
        fun dropLast(frames: Int) { size -= frames }
        fun clear() { head = 0; size = 0 }

        private fun ensure(frames: Int) {
            if (frames <= capacity) return
            val grown = DoubleArray(max(frames, capacity * 2) * channels)
            var done = 0
            while (done < size) {
                val count = min(size - done, capacity - (head + done) % capacity)
                System.arraycopy(data, (head + done) % capacity * channels, grown, done * channels, count * channels)
                done += count
            }
            data = grown
            head = 0
        }
    }

    companion object {
        const val GAP = -1L
        const val RELATIVE_DB = 50.0
        const val FLOOR_DB = -80.0
        private const val DECAY_DB_PER_SECOND = 3.0
        private const val PASS = 0
        private const val PENDING = 1
        private const val SKIP = 2
    }
}
