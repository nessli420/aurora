package com.aurora.music.desktop.audio.decode

import java.io.IOException

enum class SampleKind { INTEGER, FLOAT, LOSSY }

data class SourceSampleFormat(val kind: SampleKind, val bits: Int) {
    val lossless: Boolean get() = kind != SampleKind.LOSSY
}

data class StreamInfo(
    val codec: String,
    val container: String,
    val sampleRate: Int,
    val channels: Int,
    val sampleFormat: SourceSampleFormat,
    val bitrate: Long,
    val durationMs: Long,
    val seekable: Boolean,
)

data class AudioTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val track: Int? = null,
    val disc: Int? = null,
    val date: String? = null,
    val genre: String? = null,
    val trackGainDb: Float? = null,
    val albumGainDb: Float? = null,
    val trackPeak: Float? = null,
    val albumPeak: Float? = null,
    val r128TrackGainDb: Float? = null,
    val r128AlbumGainDb: Float? = null,
    val all: Map<String, String> = emptyMap(),
)

data class HttpOptions(
    val headers: Map<String, String> = emptyMap(),
    val userAgent: String? = null,
    val timeoutMs: Int = 15_000,
    val reconnect: Boolean = true,
) {
    init {
        require(timeoutMs > 0)
        require((headers.keys + headers.values + listOfNotNull(userAgent)).none { '\r' in it || '\n' in it }) {
            "Headers cannot contain line breaks"
        }
    }
}

class ProbeResult(val info: StreamInfo, val tags: AudioTags, val cover: ByteArray?)

open class DecoderException(message: String, val code: Int = 0) : IOException(message)

class DecoderInterruptedException : DecoderException("Decoding was interrupted")
