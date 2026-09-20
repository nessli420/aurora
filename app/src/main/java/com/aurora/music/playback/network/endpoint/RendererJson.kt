package com.aurora.music.playback.network.endpoint

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI

internal object RendererJson {
    val gson = Gson()

    fun parse(bytes: ByteArray): JsonObject = try {
        JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
    } catch (_: Exception) { throw RendererException("invalid_json", "Invalid request.") }

    fun command(value: JsonObject): RendererCommand {
        if (value.int("version") != RENDERER_PROTOCOL_VERSION) {
            throw RendererException("unsupported_version", "Update both Aurora devices.", 409)
        }
        return when (value.text("type", 24)) {
            "queue" -> {
                val items = value.getAsJsonArray("tracks") ?: invalid()
                if (items.size() !in 1..100) invalid()
                val tracks = items.map { item ->
                    val track = item.asJsonObject
                    val url = track.text("sourceUrl", 2048)
                    validateSource(url)
                    EndpointTrack(track.text("id", 128).also { if (it.isEmpty()) invalid() }, track.text("title", 512), track.text("artist", 512),
                        track.text("album", 512), track.long("durationMs").also { if (it !in 0..MAX_POSITION_MS) invalid() },
                        track.text("mimeType", 128).also { if (!it.startsWith("audio/")) invalid() }, url,
                        track.replayGain("rgTrack"), track.replayGain("rgAlbum"))
                }
                val index = value.int("startIndex")
                val position = value.long("positionMs")
                if (index !in tracks.indices || position !in 0..MAX_POSITION_MS || tracks.map { it.id }.distinct().size != tracks.size) invalid()
                RendererCommand.Queue(tracks, index, position, value.bool("playWhenReady"), value.repeatMode())
            }
            "playing" -> RendererCommand.Playing(value.bool("value"))
            "seek" -> {
                val position = value.long("positionMs")
                val index = value.get("index")?.takeUnless { it.isJsonNull }?.let { value.int("index") }
                if (position !in 0..MAX_POSITION_MS || (index != null && index !in 0..99)) invalid()
                RendererCommand.Seek(position, index)
            }
            "volume" -> RendererCommand.Volume(value.number("value").toFloat().also {
                if (!it.isFinite() || it !in 0f..1f) invalid()
            })
            "route" -> RendererCommand.Route(value.text("id", 64))
            "repeat" -> RendererCommand.Repeat(value.repeatMode())
            "reorder" -> {
                val entries = value.getAsJsonArray("ids") ?: invalid()
                if (entries.size() > 100) invalid()
                val ids = entries.map { entry ->
                    if (!entry.isJsonPrimitive || !entry.asJsonPrimitive.isString) invalid()
                    entry.asString.also { if (it.isEmpty() || it.length > 128 || it.any(Char::isISOControl)) invalid() }
                }
                if (ids.distinct().size != ids.size) invalid()
                RendererCommand.Reorder(ids)
            }
            "next" -> RendererCommand.Next
            "previous" -> RendererCommand.Previous
            "stop" -> RendererCommand.Stop
            else -> throw RendererException("unsupported_command", "Unsupported renderer command.")
        }
    }

    fun encode(command: RendererCommand): JsonObject = JsonObject().apply {
        addProperty("version", RENDERER_PROTOCOL_VERSION)
        when (command) {
            is RendererCommand.Queue -> {
                addProperty("type", "queue")
                add("tracks", gson.toJsonTree(command.tracks))
                addProperty("startIndex", command.startIndex)
                addProperty("positionMs", command.positionMs)
                addProperty("playWhenReady", command.playWhenReady)
                addProperty("repeatMode", command.repeatMode)
            }
            is RendererCommand.Playing -> { addProperty("type", "playing"); addProperty("value", command.value) }
            is RendererCommand.Seek -> {
                addProperty("type", "seek"); addProperty("positionMs", command.positionMs)
                command.index?.let { addProperty("index", it) }
            }
            is RendererCommand.Volume -> { addProperty("type", "volume"); addProperty("value", command.value) }
            is RendererCommand.Route -> { addProperty("type", "route"); addProperty("id", command.id) }
            is RendererCommand.Repeat -> { addProperty("type", "repeat"); addProperty("repeatMode", command.mode) }
            is RendererCommand.Reorder -> { addProperty("type", "reorder"); add("ids", gson.toJsonTree(command.ids)) }
            RendererCommand.Next -> addProperty("type", "next")
            RendererCommand.Previous -> addProperty("type", "previous")
            RendererCommand.Stop -> addProperty("type", "stop")
        }
    }

    fun validateSource(url: String) {
        val uri = runCatching { URI(url) }.getOrNull() ?: invalid()
        if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null ||
            !uri.rawPath.orEmpty().matches(Regex("/media/[A-Za-z0-9_-]{43}"))
        ) throw RendererException("invalid_source", "Send audio through Aurora's media relay.")
    }

    fun JsonObject.text(key: String, max: Int): String {
        val value = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: invalid()
        if (value.length > max || value.any(Char::isISOControl)) invalid()
        return value
    }
    fun JsonObject.int(key: String): Int = try { number(key).intValueExact() } catch (_: ArithmeticException) { invalid() }
    fun JsonObject.long(key: String): Long = try { number(key).longValueExact() } catch (_: ArithmeticException) { invalid() }
    private fun JsonObject.number(key: String): java.math.BigDecimal {
        val value = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber } ?: invalid()
        return try { value.asBigDecimal } catch (_: NumberFormatException) { invalid() }
    }
    fun JsonObject.bool(key: String): Boolean = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: invalid()
    private fun JsonObject.replayGain(key: String): Float {
        if (get(key) == null || get(key).isJsonNull) return 0f
        val value = number(key).toFloat()
        if (!value.isFinite() || value !in -60f..30f) invalid()
        return value
    }
    private fun JsonObject.repeatMode(): Int = get("repeatMode")?.takeUnless { it.isJsonNull }?.let { int("repeatMode") }
        ?.also { if (it !in 0..2) invalid() } ?: 0
    private fun invalid(): Nothing = throw RendererException("invalid_request", "Invalid renderer request.")
    private const val MAX_POSITION_MS = 7 * 24 * 60 * 60 * 1000L
}
