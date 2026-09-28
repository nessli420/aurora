package com.aurora.music.desktop.audio.decode

import org.bytedeco.ffmpeg.avcodec.AVCodecParameters
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOInterruptCB
import org.bytedeco.ffmpeg.avformat.AVStream
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVDictionaryEntry
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_PROP_LOSSLESS
import org.bytedeco.ffmpeg.global.avcodec.av_get_bits_per_sample
import org.bytedeco.ffmpeg.global.avcodec.avcodec_descriptor_get
import org.bytedeco.ffmpeg.global.avcodec.avcodec_get_name
import org.bytedeco.ffmpeg.global.avformat.AVIO_SEEKABLE_NORMAL
import org.bytedeco.ffmpeg.global.avformat.AV_DISPOSITION_ATTACHED_PIC
import org.bytedeco.ffmpeg.global.avformat.av_find_best_stream
import org.bytedeco.ffmpeg.global.avformat.avformat_alloc_context
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info
import org.bytedeco.ffmpeg.global.avformat.avformat_free_context
import org.bytedeco.ffmpeg.global.avformat.avformat_open_input
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EXIT
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_AUDIO
import org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_DBL
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_FLT
import org.bytedeco.ffmpeg.global.avutil.av_dict_free
import org.bytedeco.ffmpeg.global.avutil.av_dict_get
import org.bytedeco.ffmpeg.global.avutil.av_dict_iterate
import org.bytedeco.ffmpeg.global.avutil.av_dict_set
import org.bytedeco.ffmpeg.global.avutil.av_get_bytes_per_sample
import org.bytedeco.ffmpeg.global.avutil.av_get_packed_sample_fmt
import org.bytedeco.ffmpeg.global.avutil.av_strerror
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacpp.PointerPointer
import java.io.File
import java.net.URI
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

internal class FfmpegInput(source: String, http: HttpOptions, private val interrupt: AtomicBoolean) : AutoCloseable {
    val description = describe(source)
    private val callback = InterruptCallback(interrupt)
    val format: AVFormatContext = avformat_alloc_context() ?: throw DecoderException("Could not allocate a demuxer")
    private var opened = false
    private var closed = false
    val streamIndex: Int
    val stream: AVStream
    val parameters: AVCodecParameters
    val info: StreamInfo
    val tags: AudioTags

    init {
        try {
            format.interrupt_callback().callback(callback)
            val options = AVDictionary(null as Pointer?)
            if (isHttp(source)) httpOptions(http).forEach { (key, value) -> av_dict_set(options, key, value, 0) }
            val url = BytePointer(ffmpegUrl(source), Charsets.UTF_8)
            val result = try {
                avformat_open_input(format, url, null, options)
            } finally {
                av_dict_free(options)
                url.close()
            }
            ok(result) { "Could not open $description" }
            opened = true
            ok(avformat_find_stream_info(format, null as PointerPointer<*>?)) { "Could not read $description" }
            if (interrupt.get()) throw DecoderInterruptedException()
            streamIndex = ok(av_find_best_stream(format, AVMEDIA_TYPE_AUDIO, -1, -1, null as PointerPointer<*>?, 0)) {
                "No audio stream in $description"
            }
            stream = format.streams(streamIndex)
            parameters = stream.codecpar()
            info = readInfo()
            tags = parseTags(readMetadata())
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    inline fun ok(code: Int, what: () -> String): Int = if (code >= 0) code else throw failure(code, what())

    fun failure(code: Int, what: String): DecoderException =
        if (interrupt.get() || code == AVERROR_EXIT) DecoderInterruptedException() else DecoderException("$what: ${errorText(code)}", code)

    fun endOfInput(code: Int): Boolean {
        val io = format.pb() ?: return code == AVERROR_EOF
        return (code == AVERROR_EOF || io.eof_reached() != 0) && io.error() == 0
    }

    fun cover(): ByteArray? {
        val pictures = (0 until format.nb_streams()).map { format.streams(it) }
            .filter { it.disposition() and AV_DISPOSITION_ATTACHED_PIC != 0 && it.attached_pic().size() > 0 }
        val front = pictures.firstOrNull { av_dict_get(it.metadata(), "comment", null, 0)?.value()?.string == "Cover (front)" }
            ?: pictures.firstOrNull()
            ?: return null
        val packet = front.attached_pic()
        return ByteArray(packet.size()).also { packet.data().get(it) }
    }

    override fun close() {
        if (closed) return
        closed = true
        if (opened) avformat_close_input(format) else if (!format.isNull) avformat_free_context(format)
        callback.close()
    }

    private fun readInfo(): StreamInfo {
        val rate = parameters.sample_rate()
        if (rate <= 0) throw DecoderException("Unknown sample rate in $description")
        val timeBase = stream.time_base()
        val duration = stream.duration()
        val durationMs = when {
            duration != AV_NOPTS_VALUE && duration > 0 -> duration * 1000 * timeBase.num() / timeBase.den()
            format.duration() != AV_NOPTS_VALUE && format.duration() > 0 -> format.duration() / 1000
            else -> -1L
        }
        return StreamInfo(
            codec = avcodec_get_name(parameters.codec_id()).string,
            container = format.iformat().name().string,
            sampleRate = rate,
            channels = parameters.ch_layout().nb_channels(),
            sampleFormat = sampleFormat(),
            bitrate = parameters.bit_rate().takeIf { it > 0 } ?: format.bit_rate().coerceAtLeast(0),
            durationMs = durationMs,
            seekable = format.pb()?.let { it.seekable() and AVIO_SEEKABLE_NORMAL != 0 } ?: false,
        )
    }

    private fun sampleFormat(): SourceSampleFormat {
        val codec = parameters.codec_id()
        val props = avcodec_descriptor_get(codec)?.props() ?: 0
        if (props and AV_CODEC_PROP_LOSSLESS == 0) return SourceSampleFormat(SampleKind.LOSSY, 0)
        val packed = av_get_packed_sample_fmt(parameters.format())
        val containerBits = av_get_bytes_per_sample(packed) * 8
        if (packed == AV_SAMPLE_FMT_FLT || packed == AV_SAMPLE_FMT_DBL) return SourceSampleFormat(SampleKind.FLOAT, containerBits)
        val bits = parameters.bits_per_raw_sample().takeIf { it > 0 } ?: av_get_bits_per_sample(codec).takeIf { it > 0 } ?: containerBits
        return SourceSampleFormat(SampleKind.INTEGER, bits)
    }

    private fun readMetadata(): Map<String, String> {
        val all = LinkedHashMap<String, String>()
        for (dictionary in listOf(format.metadata(), stream.metadata())) {
            var entry: AVDictionaryEntry? = null
            while (true) {
                entry = av_dict_iterate(dictionary, entry) ?: break
                all.putIfAbsent(entry.key().getString(Charsets.UTF_8).lowercase(Locale.ROOT), entry.value().getString(Charsets.UTF_8))
            }
        }
        return all
    }

    private class InterruptCallback(private val flag: AtomicBoolean) : AVIOInterruptCB.Callback_Pointer() {
        override fun call(opaque: Pointer?): Int = if (flag.get()) 1 else 0
    }
}

internal fun isHttp(source: String) = source.startsWith("http://", ignoreCase = true) || source.startsWith("https://", ignoreCase = true)

internal fun localPath(fileUri: String): String =
    try {
        Path.of(URI(fileUri)).toString()
    } catch (e: Exception) {
        throw DecoderException("Invalid file URI: $fileUri")
    }

internal fun ffmpegUrl(source: String): String = when {
    isHttp(source) -> source
    source.startsWith("file:", ignoreCase = true) -> "file:" + localPath(source)
    else -> "file:" + File(source).absolutePath
}

internal fun describe(source: String): String =
    if (!isHttp(source)) source
    else runCatching { URI(source).let { "${it.scheme}://${it.host}${it.rawPath.orEmpty()}" } }.getOrDefault("stream")

internal fun httpOptions(http: HttpOptions): List<Pair<String, String>> = buildList {
    if (http.headers.isNotEmpty()) add("headers" to http.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" })
    http.userAgent?.let { add("user_agent" to it) }
    add("rw_timeout" to (http.timeoutMs * 1000L).toString())
    if (http.reconnect) {
        add("reconnect" to "1")
        add("reconnect_on_network_error" to "1")
        add("reconnect_delay_max" to "5")
    }
}

internal fun errorText(code: Int): String {
    val buffer = ByteArray(256)
    av_strerror(code, buffer, buffer.size.toLong())
    val end = buffer.indexOf(0).takeIf { it >= 0 } ?: buffer.size
    return String(buffer, 0, end, Charsets.UTF_8)
}

private val DECIMAL = Regex("[-+]?\\d+(?:[.,]\\d+)?")
private val INDEX = Regex("\\d+")

internal fun parseTags(all: Map<String, String>): AudioTags {
    fun text(vararg keys: String) = keys.firstNotNullOfOrNull { key -> all[key]?.trim()?.takeIf { it.isNotEmpty() } }
    fun decimal(vararg keys: String) = text(*keys)?.let { DECIMAL.find(it)?.value?.replace(',', '.')?.toFloatOrNull() }
    fun index(vararg keys: String) = text(*keys)?.let { INDEX.find(it)?.value?.toIntOrNull() }
    fun q78(key: String) = text(key)?.toIntOrNull()?.let { it / 256f }
    return AudioTags(
        title = text("title"),
        artist = text("artist"),
        album = text("album"),
        albumArtist = text("album_artist", "albumartist", "album artist"),
        track = index("track", "tracknumber"),
        disc = index("disc", "discnumber"),
        date = text("date", "year"),
        genre = text("genre"),
        trackGainDb = decimal("replaygain_track_gain"),
        albumGainDb = decimal("replaygain_album_gain"),
        trackPeak = decimal("replaygain_track_peak"),
        albumPeak = decimal("replaygain_album_peak"),
        r128TrackGainDb = q78("r128_track_gain"),
        r128AlbumGainDb = q78("r128_album_gain"),
        all = all,
    )
}
