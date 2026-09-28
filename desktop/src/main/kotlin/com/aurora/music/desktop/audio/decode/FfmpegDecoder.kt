package com.aurora.music.desktop.audio.decode

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVChannelLayout
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.AVDISCARD_ALL
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_flush_buffers
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_to_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet
import org.bytedeco.ffmpeg.global.avformat.AVSEEK_FLAG_BACKWARD
import org.bytedeco.ffmpeg.global.avformat.av_read_frame
import org.bytedeco.ffmpeg.global.avformat.av_seek_frame
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EAGAIN
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF
import org.bytedeco.ffmpeg.global.avutil.AVERROR_INVALIDDATA
import org.bytedeco.ffmpeg.global.avutil.AV_CHANNEL_ORDER_NATIVE
import org.bytedeco.ffmpeg.global.avutil.AV_CHANNEL_ORDER_UNSPEC
import org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_DBL
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_DBLP
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_copy
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_default
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_uninit
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_frame_unref
import org.bytedeco.ffmpeg.global.avutil.av_opt_set_double
import org.bytedeco.ffmpeg.global.avutil.av_opt_set_sample_fmt
import org.bytedeco.ffmpeg.global.avutil.av_sample_fmt_is_planar
import org.bytedeco.ffmpeg.global.swresample.swr_alloc_set_opts2
import org.bytedeco.ffmpeg.global.swresample.swr_convert
import org.bytedeco.ffmpeg.global.swresample.swr_free
import org.bytedeco.ffmpeg.global.swresample.swr_get_delay
import org.bytedeco.ffmpeg.global.swresample.swr_get_out_samples
import org.bytedeco.ffmpeg.global.swresample.swr_init
import org.bytedeco.ffmpeg.global.swresample.swr_set_matrix
import org.bytedeco.ffmpeg.swresample.SwrContext
import org.bytedeco.javacpp.DoublePointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacpp.PointerPointer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class FfmpegDecoder private constructor(
    private val input: FfmpegInput,
    private val codec: AVCodecContext,
    private val interrupt: AtomicBoolean,
) : AutoCloseable {
    val info: StreamInfo get() = input.info
    val tags: AudioTags get() = input.tags
    var positionFrames = 0L
        private set
    val positionMs: Long get() = positionFrames * 1000 / rate

    private val rate = input.info.sampleRate
    private val again = AVERROR_EAGAIN()
    private val timeBaseNum: Long
    private val timeBaseDen: Long
    private val startPts: Long
    private val preroll = if (input.info.sampleFormat.lossless) 0L else maxOf(input.parameters.seek_preroll().toLong(), rate / 5L)
    private val packet: AVPacket = av_packet_alloc()
    private val frame: AVFrame = av_frame_alloc()
    private val framePlanes: PointerPointer<*> = frame.data()
    private val frameLayout: AVChannelLayout = frame.ch_layout()
    private val stereo = AVChannelLayout().also { av_channel_layout_default(it, 2) }
    private val swr = SwrContext(null as Pointer?)
    private var swrFormat = -1
    private var swrRate = 0
    private var swrChannels = 0
    private var swrMask = 0L
    private var inlinePlanes = true
    private var capacity = 8192
    private var output = DoublePointer(capacity * 2L)
    private var outputPlanes = PointerPointer<DoublePointer>(1L).put(0, output)
    private var pending = DoubleArray(capacity * 2)
    private var pendingOffset = 0
    private var pendingFrames = 0
    private var nextFrame = 0L
    private var seekTarget = -1L
    private var ended = false
    private var closed = false

    init {
        val timeBase = input.stream.time_base()
        timeBaseNum = timeBase.num().toLong()
        timeBaseDen = timeBase.den().toLong()
        startPts = input.stream.start_time().takeIf { it != AV_NOPTS_VALUE } ?: 0L
        for (i in 0 until input.format.nb_streams()) if (i != input.streamIndex) input.format.streams(i).discard(AVDISCARD_ALL)
    }

    fun read(target: DoubleArray, maxFrames: Int = target.size / 2): Int {
        check(!closed) { "Decoder is closed" }
        require(maxFrames in 0..target.size / 2)
        if (interrupt.get()) throw DecoderInterruptedException()
        while (pendingFrames == 0) if (!decode()) return -1
        val frames = min(maxFrames, pendingFrames)
        System.arraycopy(pending, pendingOffset * 2, target, 0, frames * 2)
        pendingOffset += frames
        pendingFrames -= frames
        positionFrames += frames
        return frames
    }

    fun seek(positionMs: Long) = seekToFrame(positionMs.coerceAtLeast(0) * rate / 1000)

    fun seekToFrame(frame: Long) {
        check(!closed) { "Decoder is closed" }
        if (interrupt.get()) throw DecoderInterruptedException()
        val target = frame.coerceAtLeast(0)
        val from = (target - preroll).coerceAtLeast(0)
        val timestamp = startPts + Math.floorDiv(from * timeBaseDen, rate * timeBaseNum)
        input.ok(av_seek_frame(input.format, input.streamIndex, timestamp, AVSEEK_FLAG_BACKWARD)) { "Could not seek ${input.description}" }
        avcodec_flush_buffers(codec)
        if (!swr.isNull) swr_free(swr)
        pendingOffset = 0
        pendingFrames = 0
        nextFrame = from
        seekTarget = target
        positionFrames = target
        ended = false
    }

    fun interrupt() = interrupt.set(true)

    override fun close() {
        if (closed) return
        closed = true
        av_packet_free(packet)
        av_frame_free(frame)
        avcodec_free_context(codec)
        if (!swr.isNull) swr_free(swr)
        av_channel_layout_uninit(stereo)
        stereo.close()
        outputPlanes.close()
        output.close()
        input.close()
    }

    private fun decode(): Boolean {
        if (ended) return false
        while (true) {
            val received = avcodec_receive_frame(codec, frame)
            when {
                received >= 0 -> {
                    val produced = try { convert() } finally { av_frame_unref(frame) }
                    if (produced) return true
                }
                received == again -> feed()
                received == AVERROR_EOF -> {
                    if (flushResampler()) return true
                    ended = true
                    return false
                }
                received != AVERROR_INVALIDDATA -> throw input.failure(received, "Could not decode ${input.description}")
            }
        }
    }

    private fun feed() {
        while (true) {
            val read = av_read_frame(input.format, packet)
            if (read < 0) {
                if (interrupt.get() || !input.endOfInput(read)) throw input.failure(read, "Could not read ${input.description}")
                avcodec_send_packet(codec, null as AVPacket?)
                return
            }
            if (packet.stream_index() != input.streamIndex) {
                av_packet_unref(packet)
                continue
            }
            val sent = avcodec_send_packet(codec, packet)
            av_packet_unref(packet)
            if (sent < 0 && sent != AVERROR_INVALIDDATA) throw input.failure(sent, "Could not decode ${input.description}")
            return
        }
    }

    private fun convert(): Boolean {
        val samples = frame.nb_samples()
        if (samples <= 0) return false
        configureResampler()
        ensureCapacity(swr_get_out_samples(swr, samples))
        val planes = if (inlinePlanes) framePlanes else frame.extended_data()
        val converted = swr_convert(swr, outputPlanes, capacity, planes, samples)
        if (converted < 0) throw input.failure(converted, "Could not convert ${input.description}")
        val timestamp = frame.best_effort_timestamp()
        return accept(converted, if (timestamp == AV_NOPTS_VALUE) nextFrame else framePosition(timestamp))
    }

    private fun flushResampler(): Boolean {
        if (swr.isNull || swr_get_delay(swr, rate.toLong()) <= 0) return false
        ensureCapacity(swr_get_out_samples(swr, 0))
        val flushed = swr_convert(swr, outputPlanes, capacity, null as PointerPointer<*>?, 0)
        return flushed > 0 && accept(flushed, nextFrame)
    }

    private fun accept(frames: Int, start: Long): Boolean {
        nextFrame = start + frames
        var skip = 0
        if (seekTarget >= 0) {
            skip = (seekTarget - start).coerceIn(0, frames.toLong()).toInt()
            if (skip == frames) return false
            positionFrames = start + skip
            seekTarget = -1
        }
        output.get(pending, 0, frames * 2)
        pendingOffset = skip
        pendingFrames = frames - skip
        return pendingFrames > 0
    }

    private fun framePosition(timestamp: Long): Long =
        Math.floorDiv((timestamp - startPts) * rate * timeBaseNum * 2 + timeBaseDen, timeBaseDen * 2)

    private fun configureResampler() {
        val format = frame.format()
        val inputRate = frame.sample_rate().takeIf { it > 0 } ?: codec.sample_rate()
        val channels = frameLayout.nb_channels()
        val mask = if (frameLayout.order() == AV_CHANNEL_ORDER_NATIVE) frameLayout.u_mask() else 0L
        if (!swr.isNull && format == swrFormat && inputRate == swrRate && channels == swrChannels && mask == swrMask) return
        if (channels <= 0) throw DecoderException("Unknown channel layout in ${input.description}")
        if (!swr.isNull) swr_free(swr)
        val layout = AVChannelLayout()
        try {
            if (channels > 2 && frameLayout.order() != AV_CHANNEL_ORDER_UNSPEC) {
                input.ok(av_channel_layout_copy(layout, frameLayout)) { "Could not read the channel layout of ${input.description}" }
            } else {
                av_channel_layout_default(layout, channels)
            }
            input.ok(swr_alloc_set_opts2(swr, stereo, AV_SAMPLE_FMT_DBL, rate, layout, format, inputRate, 0, null)) {
                "Could not configure the resampler for ${input.description}"
            }
            av_opt_set_sample_fmt(swr, "internal_sample_fmt", AV_SAMPLE_FMT_DBLP, 0)
            if (channels == 1) swr_set_matrix(swr, doubleArrayOf(1.0, 1.0), 1)
            if (channels > 2) av_opt_set_double(swr, "rematrix_maxval", 1.0, 0)
            input.ok(swr_init(swr)) { "Could not configure the resampler for ${input.description}" }
        } finally {
            av_channel_layout_uninit(layout)
            layout.close()
        }
        swrFormat = format
        swrRate = inputRate
        swrChannels = channels
        swrMask = mask
        inlinePlanes = av_sample_fmt_is_planar(format) == 0 || channels <= AVFrame.AV_NUM_DATA_POINTERS
    }

    private fun ensureCapacity(frames: Int) {
        if (frames <= capacity) return
        outputPlanes.close()
        output.close()
        capacity = maxOf(frames, capacity * 2)
        output = DoublePointer(capacity * 2L)
        outputPlanes = PointerPointer<DoublePointer>(1L).put(0, output)
        pending = DoubleArray(capacity * 2)
    }

    companion object {
        fun open(source: String, http: HttpOptions = HttpOptions(), interrupt: AtomicBoolean = AtomicBoolean()): FfmpegDecoder {
            FfmpegRuntime.init()
            val input = FfmpegInput(source, http, interrupt)
            try {
                val decoder = avcodec_find_decoder(input.parameters.codec_id())
                    ?: throw DecoderException("No decoder for ${input.info.codec} in ${input.description}")
                val codec = avcodec_alloc_context3(decoder) ?: throw DecoderException("Could not allocate a decoder")
                try {
                    input.ok(avcodec_parameters_to_context(codec, input.parameters)) { "Could not configure the ${input.info.codec} decoder" }
                    codec.pkt_timebase(input.stream.time_base())
                    input.ok(avcodec_open2(codec, decoder, null as AVDictionary?)) { "Could not open the ${input.info.codec} decoder" }
                    return FfmpegDecoder(input, codec, interrupt)
                } catch (e: Throwable) {
                    avcodec_free_context(codec)
                    throw e
                }
            } catch (e: Throwable) {
                input.close()
                throw e
            }
        }

        fun probe(file: File): ProbeResult {
            FfmpegRuntime.init()
            return FfmpegInput(file.path, HttpOptions(), AtomicBoolean()).use { ProbeResult(it.info, it.tags, it.cover()) }
        }
    }
}
