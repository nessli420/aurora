package com.aurora.music.desktop.audio

import com.aurora.music.desktop.audio.decode.AudioTags
import com.aurora.music.desktop.audio.decode.FfmpegDecoder
import com.aurora.music.desktop.audio.decode.SampleKind
import com.aurora.music.desktop.audio.decode.StreamInfo
import com.aurora.music.playback.PcmLevelMeter
import com.aurora.music.playback.chain.ChainFormat
import com.aurora.music.playback.chain.DspChain
import com.aurora.music.playback.engine.SamplePrecision
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

internal const val BLOCK_FRAMES = 1024
internal const val DECODE_FRAMES = 1024

internal class OpenTrack(val entry: QueueEntry, val decoder: FfmpegDecoder, val interrupt: AtomicBoolean?) {
    val info: StreamInfo = decoder.info
    val tags: AudioTags = decoder.tags
    val rate: Int = info.sampleRate
    val durationMs: Long = info.durationMs.takeIf { it > 0 } ?: (entry.song.durationSec * 1000L).takeIf { it > 0 } ?: -1
    val precision: SamplePrecision = info.sampleFormat.let { format ->
        when {
            format.kind == SampleKind.INTEGER && format.bits in 1..8 -> SamplePrecision.PCM_SIGNED_8
            format.kind == SampleKind.INTEGER && format.bits in 9..16 -> SamplePrecision.PCM_SIGNED_16
            format.kind == SampleKind.INTEGER && format.bits in 17..24 -> SamplePrecision.PCM_SIGNED_24
            format.kind == SampleKind.INTEGER -> SamplePrecision.PCM_SIGNED_32
            format.kind == SampleKind.FLOAT && format.bits == 32 -> SamplePrecision.FLOAT_32
            else -> SamplePrecision.FLOAT_64
        }
    }
    var startFrame = 0L; private set
    private var gainMode = -1
    private var gain = 1.0

    fun replayGain(mode: Int): Double {
        if (mode != gainMode) {
            gainMode = mode
            gain = ReplayGain.multiplier(ReplayGain.gainDb(entry.song, tags, mode))
        }
        return gain
    }

    fun position(frame: Long) {
        if (info.seekable) {
            if (decoder.positionFrames != frame) decoder.seekToFrame(frame)
            startFrame = frame
        } else startFrame = decoder.positionFrames
    }

    fun close() = decoder.close()
}

internal class PendingStart(val track: OpenTrack, val reason: TransitionReason, val format: ChainFormat)

internal class ReadWatch {
    @Volatile private var decoder: FfmpegDecoder? = null
    @Volatile private var since = 0L

    fun begin(decoder: FfmpegDecoder) {
        since = System.nanoTime()
        this.decoder = decoder
    }

    fun end() { decoder = null }

    fun interruptIfStalled(limitNanos: Long) {
        val blocked = decoder ?: return
        if (System.nanoTime() - since > limitNanos) blocked.interrupt()
    }
}

internal class FloatTap(private val meter: PcmLevelMeter) {
    private val bytes = ByteBuffer.allocate(DECODE_FRAMES * 8).order(ByteOrder.LITTLE_ENDIAN)

    fun observe(samples: DoubleArray, frames: Int, timeUs: Long) {
        for (i in 0 until frames * 2) bytes.putFloat(i * 4, samples[i].toFloat())
        meter.observe(bytes, 0, frames * 8, timeUs)
    }
}

internal class Deck(val chain: DspChain, private val watch: ReadWatch) {
    val out = DoubleArray(BLOCK_FRAMES * 2)
    private val decoded = DoubleArray(DECODE_FRAMES * 2)
    private var decodedCount = 0
    private var decodedOffset = 0
    private var configuredAt = 0L
    private var gainTrack: OpenTrack? = null
    private var nextGainTrack: OpenTrack? = null
    private var nextGainFrame = Long.MAX_VALUE
    var track: OpenTrack? = null; private set
    var pending: PendingStart? = null
    var fill = 0; private set
    var decoderEnded = false; private set
    var draining = false; private set
    var failure: Exception? = null
    var trackFed = 0L; private set
    var produced = 0L; private set
    var mixed = 0L; private set
    var streamStart = 0L; private set
    var tap: FloatTap? = null

    val finished: Boolean get() = draining && pending == null && chain.isEnded
    val renderPositionMs: Long get() = track?.let { (it.startFrame + trackFed) * 1000 / it.rate } ?: 0

    fun start(track: OpenTrack, format: ChainFormat, streamStart: Long): Long {
        chain.configure(format)
        this.track = track
        this.streamStart = streamStart
        produced = 0
        mixed = 0
        configuredAt = 0
        fill = 0
        draining = false
        resetInput()
        gainTrack = track
        nextGainTrack = null
        nextGainFrame = Long.MAX_VALUE
        return chain.latencyFrames.toLong()
    }

    fun switchTo(track: OpenTrack, format: ChainFormat): Long {
        val boundary = configuredAt + ceilDiv(chain.inputFrames * format.outputRate, format.resampleRate.toLong()) + chain.latencyFrames
        chain.continueWith(format)
        this.track?.close()
        this.track = track
        resetInput()
        scheduleGain(track, boundary)
        return boundary
    }

    fun restartAfterDrain(): Long {
        val next = checkNotNull(pending)
        pending = null
        chain.configure(next.format)
        configuredAt = produced
        draining = false
        track?.close()
        track = next.track
        resetInput()
        val boundary = produced + chain.latencyFrames
        scheduleGain(next.track, boundary)
        return boundary
    }

    fun endInput() {
        if (draining) return
        chain.queueEndOfStream()
        draining = true
    }

    fun fill(max: Int = BLOCK_FRAMES): Boolean {
        var progress = false
        while (fill < max) {
            val count = chain.readOutput(out, fill, max - fill)
            if (count > 0) {
                fill += count
                produced += count
                progress = true
                continue
            }
            val track = track ?: break
            if (draining || decoderEnded) break
            if (decodedOffset == decodedCount && !read(track)) break
            val accepted = chain.queueInput(decoded, decodedOffset, decodedCount - decodedOffset)
            if (accepted == 0) break
            decodedOffset += accepted
            trackFed += accepted
            progress = true
        }
        return progress
    }

    fun gain(frame: Long, mode: Int): Double {
        if (frame >= nextGainFrame) {
            gainTrack = nextGainTrack
            nextGainTrack = null
            nextGainFrame = Long.MAX_VALUE
        }
        return gainTrack?.replayGain(mode) ?: 1.0
    }

    fun consume(frames: Int) {
        val count = minOf(frames, fill)
        if (count < fill) System.arraycopy(out, count * 2, out, 0, (fill - count) * 2)
        fill -= count
        mixed += frames
    }

    fun detach(): OpenTrack? {
        val detached = track
        track = null
        release()
        return detached
    }

    fun release() {
        track?.close()
        pending?.track?.close()
        track = null
        pending = null
        gainTrack = null
        nextGainTrack = null
        nextGainFrame = Long.MAX_VALUE
        chain.flush()
        fill = 0
        draining = false
        tap = null
        resetInput()
    }

    private fun read(track: OpenTrack): Boolean {
        watch.begin(track.decoder)
        val count = try {
            track.decoder.read(decoded, DECODE_FRAMES)
        } catch (e: Exception) {
            failure = e
            -1
        } finally {
            watch.end()
        }
        if (count < 0) {
            decoderEnded = true
            return false
        }
        tap?.observe(decoded, count, (track.startFrame + trackFed) * 1_000_000L / track.rate)
        decodedCount = count
        decodedOffset = 0
        return true
    }

    private fun scheduleGain(track: OpenTrack, frame: Long) {
        if (frame <= mixed) {
            gainTrack = track
            return
        }
        if (nextGainTrack != null) gainTrack = nextGainTrack
        nextGainTrack = track
        nextGainFrame = frame
    }

    private fun resetInput() {
        decodedCount = 0
        decodedOffset = 0
        decoderEnded = false
        trackFed = 0
        failure = null
    }

    private fun ceilDiv(a: Long, b: Long) = (a + b - 1) / b
}
