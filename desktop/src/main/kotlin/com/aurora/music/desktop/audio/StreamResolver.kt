package com.aurora.music.desktop.audio

import com.aurora.music.model.Song
import java.io.IOException

data class ResolvedStream(val url: String, val headers: Map<String, String> = emptyMap(), val userAgent: String? = null)

class UnsupportedStreamException(message: String) : IOException(message)

fun interface StreamResolver {
    fun resolve(song: Song): ResolvedStream

    companion object {
        val Default = StreamResolver { song ->
            val url = song.streamUrl.trim()
            val scheme = url.substringBefore(':', "").lowercase()
            when {
                url.isEmpty() -> throw UnsupportedStreamException("${song.title} has no playable stream")
                scheme == "file" || scheme == "http" || scheme == "https" || scheme.length <= 1 -> ResolvedStream(url)
                else -> throw UnsupportedStreamException("$scheme streams cannot be played on this device")
            }
        }
    }
}
