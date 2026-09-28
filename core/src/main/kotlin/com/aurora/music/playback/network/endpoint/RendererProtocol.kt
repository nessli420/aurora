package com.aurora.music.playback.network.endpoint

import java.net.URI

const val RENDERER_PROTOCOL_VERSION = 1

data class EndpointTrack(
    val id: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
    val mimeType: String = "audio/wav",
    val sourceUrl: String,
    val rgTrack: Float = 0f,
    val rgAlbum: Float = 0f,
) {
    override fun toString(): String = "EndpointTrack(id=$id, title=$title, mimeType=$mimeType)"
}

data class EndpointQueueItem(val id: String, val title: String, val artist: String = "", val durationMs: Long = 0)

data class EndpointRoute(
    val id: String = "local",
    val name: String = "This device",
    val state: String = "ready",
    val sampleRateHz: Int = 0,
    val bitDepth: Int = 0,
    val processed: Boolean = false,
)

data class RendererStatus(
    val protocolVersion: Int = RENDERER_PROTOCOL_VERSION,
    val role: String = "renderer",
    val rendererId: String = "",
    val name: String = "Aurora",
    val playing: Boolean = false,
    val playWhenReady: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volume: Float = 1f,
    val volumeSteps: Int = 100,
    val queueIndex: Int = 0,
    val repeatMode: Int = 0,
    val queue: List<EndpointQueueItem> = emptyList(),
    val route: EndpointRoute = EndpointRoute(),
    val error: String? = null,
)

sealed interface RendererCommand {
    data class Queue(
        val tracks: List<EndpointTrack>,
        val startIndex: Int = 0,
        val positionMs: Long = 0,
        val playWhenReady: Boolean = true,
        val repeatMode: Int = 0,
        val sourceHost: String? = null,
        @Transient val authorizationValid: () -> Boolean = { true },
    ) : RendererCommand
    data class Playing(val value: Boolean) : RendererCommand
    data class Seek(val positionMs: Long, val index: Int? = null) : RendererCommand
    data class Volume(val value: Float) : RendererCommand
    data class Route(val id: String) : RendererCommand
    data class Repeat(val mode: Int) : RendererCommand
    data class Reorder(val ids: List<String>) : RendererCommand
    data object Next : RendererCommand
    data object Previous : RendererCommand
    data object Stop : RendererCommand
}

interface RendererPlayback {
    fun status(): RendererStatus
    fun execute(command: RendererCommand): RendererStatus
    fun executeAuthorized(command: RendererCommand, authorized: () -> Boolean): RendererStatus {
        if (!authorized()) throw RendererException("unauthorized", "Controller access ended.", 401)
        return execute(command)
    }
}

class RendererException(val code: String, message: String, val httpStatus: Int = 400) : Exception(message)

data class EndpointAddress(val address: String, val fingerprint: String) {
    val pairingAddress: String get() = "$address#$fingerprint"

    companion object {
        fun parse(value: String): EndpointAddress {
            val uri = runCatching { URI(value.trim()) }.getOrNull()
                ?: throw RendererException("invalid_address", "Enter the renderer's pairing address.")
            val fingerprint = uri.fragment.orEmpty().replace(":", "").lowercase()
            if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
                uri.rawQuery != null || uri.path.orEmpty().trim('/').isNotEmpty() ||
                fingerprint.length != 64 || fingerprint.any { it !in "0123456789abcdef" }
            ) throw RendererException("invalid_address", "Copy the full pairing address from the renderer.")
            return EndpointAddress(URI("https", null, uri.host, uri.port, null, null, null).toASCIIString(), fingerprint)
        }
    }
}

data class PairingWindow(val code: String, val expiresAtMs: Long) {
    override fun toString(): String = "PairingWindow(expiresAtMs=$expiresAtMs)"
}
data class PairedController(val id: String, val name: String, val pairedAtMs: Long)
data class PairedRenderer(
    val id: String,
    val name: String,
    val address: String,
    val fingerprint: String,
    val clientId: String,
    val token: String,
) {
    override fun toString(): String = "PairedRenderer(id=$id, name=$name, address=$address)"
}
