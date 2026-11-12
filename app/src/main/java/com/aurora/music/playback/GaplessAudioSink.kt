package com.aurora.music.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.extractor.OpusUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToLong

/**
 * Outermost sink: presents consecutive same-format tracks to the inner sinks as one continuous stream,
 * so DSP state, resamplers, USB transports and the AudioTrack carry across joins. It trims encoder
 * delay/padding for every path (Media3 only trims on PCM16), cuts a track that overruns its timeline end
 * (untrimmed Opus or AAC padding), runs [GaplessTrimmer] silence trimming, inserts the gapless-off pause,
 * and maps the continuous inner clock back to media time.
 * [inert] keeps the direct bit-perfect USB driver on its own per-track handling.
 */
@UnstableApi
class GaplessAudioSink(
    delegate: AudioSink,
    private val gapMs: () -> Int,
    private val inert: Boolean = false,
) : ForwardingAudioSink(delegate) {
    private class Pending(val format: Format, val bufferSize: Int, val channels: IntArray?, val delay: Int, val padding: Int, val lead: Int)

    /** Frames the decoder dropped that the timeline still counts, set by [GaplessAudioRenderer] before configure. */
    var timelineLead = 0
    /** Exact end padding of the track now ending (WebM DiscardPadding), set by [GaplessAudioRenderer]. */
    var discardPadding = 0

    private var trimmer: GaplessTrimmer? = null
    private var forwarded: Format? = null
    private var forwardedChannels: IntArray? = null
    private var pending: Pending? = null
    private var boundaryPending = false
    private var nextStream: IntArray? = null
    private var streamLead = 0
    private var skip = false
    private var referenceDb = -20.0
    private var encoding = C.ENCODING_INVALID
    private var channels = 0
    private var rate = 0
    private var sampleBytes = 0
    private var block = DoubleArray(0)
    private var staging: ByteBuffer = ByteBuffer.allocateDirect(0)
    private var stagingTimeUs = 0L
    private var source = 0L
    private var streamDelay = 0
    private val anchors = ArrayDeque<LongArray>()
    private var anchorPending = true
    private var baseUs = C.TIME_UNSET
    private var written = 0L
    private var nextSource = NONE
    private var holdUs = 0L
    private val segments = ArrayDeque<LongArray>()

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        if (inert) return super.configure(inputFormat, specifiedBufferSize, outputChannels)
        val stripped = inputFormat.buildUpon().setEncoderDelay(0).setEncoderPadding(0).build()
        val delay = inputFormat.encoderDelay.coerceAtLeast(0)
        val padding = inputFormat.encoderPadding.coerceAtLeast(0)
        // a format change without a discontinuity still ends the previous stream
        boundaryPending = true
        anchorPending = true
        streamDelay = delay
        val current = forwarded
        val t = trimmer
        if (t != null && pending == null && current != null && current.pcmEncoding == stripped.pcmEncoding &&
            current.channelCount == stripped.channelCount && current.sampleRate == stripped.sampleRate &&
            forwardedChannels.contentEquals(outputChannels)) {
            nextStream = intArrayOf(delay, padding, timelineLead)
            return
        }
        nextStream = null
        pending = Pending(stripped, specifiedBufferSize, outputChannels?.copyOf(), delay, padding, timelineLead)
        if (t?.holding != true && !staging.hasRemaining()) applyPending()
    }

    private fun applyPending() {
        val next = pending ?: return
        pending = null
        super.configure(next.format, next.bufferSize, next.channels)
        forwarded = next.format
        forwardedChannels = next.channels
        if (baseUs != C.TIME_UNSET && rate > 0) { baseUs += written * 1_000_000L / rate; written = 0 }
        trimmer?.let { referenceDb = it.referenceDb }
        val bytes = sampleBytes(next.format.pcmEncoding)
        val format = next.format
        if (format.sampleMimeType != MimeTypes.AUDIO_RAW || bytes == 0 || format.channelCount <= 0 || format.sampleRate <= 0) {
            trimmer = null
            return
        }
        encoding = format.pcmEncoding
        channels = format.channelCount
        rate = format.sampleRate
        sampleBytes = bytes
        trimmer = GaplessTrimmer(channels, rate, referenceDb).also {
            it.skipSilence = skip
            it.tailHold = rate * END_TRIM_MS / 1000
            it.startStream(next.delay, next.padding)
        }
        streamLead = next.lead
        block = DoubleArray(BLOCK_FRAMES * channels)
        staging = ByteBuffer.allocateDirect(BLOCK_FRAMES * channels * bytes).order(ByteOrder.LITTLE_ENDIAN).apply { limit(0) }
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (inert) return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        if (pending != null) {
            finishBoundary(presentationTimeUs)
            if (!drain()) return false
            applyPending()
        }
        val t = trimmer ?: return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        finishBoundary(presentationTimeUs)
        if (!drain()) return false
        val frameBytes = sampleBytes * channels
        require(buffer.remaining() % frameBytes == 0) { "Incomplete PCM frame" }
        if (anchorPending && buffer.hasRemaining()) {
            // media3 keeps trimmed priming out of media time, so the first kept frame sits at the buffer time
            anchors.addLast(longArrayOf(source + streamDelay, presentationTimeUs, rate.toLong()))
            anchorPending = false
            streamDelay = 0
        }
        val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        while (buffer.hasRemaining()) {
            val frames = minOf(buffer.remaining() / frameBytes, BLOCK_FRAMES)
            decode(view, buffer.position(), frames)
            buffer.position(buffer.position() + frames * frameBytes)
            t.queue(block, 0, frames)
            source += frames
            if (!drain()) return !buffer.hasRemaining()
        }
        return true
    }

    // the next track's first timestamp is where this one ends on the timeline; audio past it is codec padding
    private fun finishBoundary(nextUs: Long) {
        if (!boundaryPending) return
        boundaryPending = false
        val t = trimmer ?: return
        val anchor = anchors.lastOrNull()
        val overrun = if (discardPadding > 0) discardPadding.toLong() else if (nextUs == C.TIME_UNSET || anchor == null) 0L
            else t.streamEnd - anchor[0] - Math.round((nextUs - anchor[1]) * anchor[2] / 1e6) + streamLead
        discardPadding = 0
        t.boundary(gapFrames(), if (overrun >= 2) overrun.toInt() else 0)
        nextStream?.let { t.startStream(it[0], it[1]); streamLead = it[2] }
        nextStream = null
    }

    private fun drain(): Boolean {
        while (true) {
            if (staging.hasRemaining() && !super.handleBuffer(staging, stagingTimeUs, 1)) return false
            val t = trimmer ?: return true
            val frames = t.read(block, 0, BLOCK_FRAMES)
            if (frames == 0) return true
            val from = t.readSource
            if (baseUs == C.TIME_UNSET) baseUs = if (from == GaplessTrimmer.GAP) 0 else mediaUs(from).also { nextSource = from }
            val now = baseUs + written * 1_000_000L / rate
            if (from == GaplessTrimmer.GAP) {
                if (nextSource != NONE) holdUs = mediaUs(nextSource)
                segments.addLast(longArrayOf(now, holdUs, now + frames * 1_000_000L / rate))
                nextSource = NONE
            } else {
                if (from != nextSource) segments.addLast(longArrayOf(now, mediaUs(from), now))
                nextSource = from + frames
            }
            encode(frames)
            stagingTimeUs = now
            written += frames
        }
    }

    private fun mediaUs(at: Long): Long {
        while (anchors.size > 1 && anchors[1][0] <= at) anchors.removeFirst()
        val anchor = anchors.firstOrNull() ?: return at * 1_000_000L / rate
        return anchor[1] + (at - anchor[0]) * 1_000_000L / anchor[2]
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val position = super.getCurrentPositionUs(sourceEnded)
        if (inert || position == AudioSink.CURRENT_POSITION_NOT_SET || segments.isEmpty()) return position
        while (segments.size > 1 && segments[1][0] <= position) segments.removeFirst()
        val segment = segments.first()
        if (segment[0] > position) return position
        return segment[1] + maxOf(0L, position - segment[2])
    }

    override fun handleDiscontinuity() {
        if (inert || trimmer == null) return super.handleDiscontinuity()
        boundaryPending = true
        anchorPending = true
    }

    override fun playToEndOfStream() {
        if (inert) return super.playToEndOfStream()
        finishBoundary(C.TIME_UNSET)
        if (pending != null) {
            if (!drain()) return
            applyPending()
        }
        trimmer?.boundary(overrun = discardPadding)
        discardPadding = 0
        if (!drain()) return
        super.playToEndOfStream()
    }

    override fun isEnded(): Boolean = if (inert) super.isEnded()
        else pending == null && !staging.hasRemaining() && trimmer?.holding != true && super.isEnded()

    override fun hasPendingData(): Boolean = if (inert) super.hasPendingData()
        else staging.hasRemaining() || trimmer?.holding == true || super.hasPendingData()

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        if (inert) return super.setSkipSilenceEnabled(skipSilenceEnabled)
        skip = skipSilenceEnabled
        trimmer?.skipSilence = skipSilenceEnabled
        super.setSkipSilenceEnabled(false)
    }

    override fun getSkipSilenceEnabled(): Boolean = if (inert) super.getSkipSilenceEnabled() else skip

    override fun flush() {
        super.flush()
        if (inert) return
        trimmer?.flush()
        clearTimeline()
    }

    override fun reset() {
        super.reset()
        if (inert) return
        trimmer?.let { referenceDb = it.referenceDb }
        trimmer = null
        forwarded = null
        forwardedChannels = null
        pending = null
        boundaryPending = false
        nextStream = null
        streamLead = 0
        streamDelay = 0
        clearTimeline()
    }

    private fun clearTimeline() {
        discardPadding = 0
        staging.limit(0)
        source = 0
        anchors.clear()
        anchorPending = true
        baseUs = C.TIME_UNSET
        written = 0
        nextSource = NONE
        segments.clear()
    }

    private fun gapFrames(): Int = if (rate > 0) (gapMs().toLong() * rate / 1000).toInt() else 0

    private fun decode(view: ByteBuffer, start: Int, frames: Int) {
        var at = start
        for (i in 0 until frames * channels) {
            block[i] = when (encoding) {
                C.ENCODING_PCM_16BIT -> view.getShort(at) / 32768.0
                C.ENCODING_PCM_24BIT -> ((view.get(at).toInt() and 0xff) or ((view.get(at + 1).toInt() and 0xff) shl 8) or
                    (view.get(at + 2).toInt() shl 16)) / 8388608.0
                C.ENCODING_PCM_32BIT -> view.getInt(at) / 2147483648.0
                else -> view.getFloat(at).toDouble()
            }
            at += sampleBytes
        }
    }

    private fun encode(frames: Int) {
        staging.clear()
        for (i in 0 until frames * channels) {
            val v = block[i]
            when (encoding) {
                C.ENCODING_PCM_16BIT -> staging.putShort((v * 32768.0).roundToLong().coerceIn(-32768L, 32767L).toInt().toShort())
                C.ENCODING_PCM_24BIT -> (v * 8388608.0).roundToLong().coerceIn(-8388608L, 8388607L).toInt()
                    .let { staging.put(it.toByte()).put((it shr 8).toByte()).put((it shr 16).toByte()) }
                C.ENCODING_PCM_32BIT -> staging.putInt((v * 2147483648.0).roundToLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt())
                else -> staging.putFloat(v.toFloat())
            }
        }
        staging.flip()
    }

    companion object {
        const val GAP_MS = 1000
        private const val END_TRIM_MS = 50
        private const val BLOCK_FRAMES = 2048
        private const val NONE = -2L

        private fun sampleBytes(encoding: Int): Int = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> 0
        }
    }
}

/** Tells [GaplessAudioSink] about Opus pre-skip, which Media3's Ogg and WebM timelines count but MediaCodec drops. */
@UnstableApi
class GaplessAudioRenderer(context: android.content.Context, factory: MediaCodecAdapter.Factory, selector: MediaCodecSelector,
    fallback: Boolean, handler: android.os.Handler?, listener: AudioRendererEventListener?, private val sink: AudioSink,
) : MediaCodecAudioRenderer(context, factory, selector, fallback, handler, listener, sink) {
    override fun onOutputFormatChanged(format: Format, mediaFormat: android.media.MediaFormat?) {
        (sink as? GaplessAudioSink)?.timelineLead = format.initializationData.firstOrNull()
            ?.takeIf { format.sampleMimeType == MimeTypes.AUDIO_OPUS }?.let(OpusUtil::getPreSkipSamples) ?: 0
        super.onOutputFormatChanged(format, mediaFormat)
    }

    override fun handleInputBufferSupplementalData(buffer: androidx.media3.decoder.DecoderInputBuffer) {
        // media3 only attaches 8-byte audio supplemental data for matroska opus discard padding
        val data = buffer.supplementalData
        if (data != null && data.remaining() == 8) {
            val nanos = data.duplicate().order(ByteOrder.LITTLE_ENDIAN).long
            (sink as? GaplessAudioSink)?.discardPadding = Math.round(nanos * OpusUtil.SAMPLE_RATE / 1e9).toInt().coerceAtLeast(0)
        }
        super.handleInputBufferSupplementalData(buffer)
    }

    companion object {
        fun install(out: MutableList<androidx.media3.exoplayer.Renderer>, context: android.content.Context, factory: MediaCodecAdapter.Factory,
            selector: MediaCodecSelector, fallback: Boolean, handler: android.os.Handler, listener: AudioRendererEventListener, sink: AudioSink) {
            val index = out.indexOfFirst { it.javaClass == MediaCodecAudioRenderer::class.java }
            if (index >= 0) out[index] = GaplessAudioRenderer(context, factory, selector, fallback, handler, listener, sink)
        }
    }
}
