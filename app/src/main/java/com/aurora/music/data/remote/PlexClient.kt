package com.aurora.music.data.remote

import com.aurora.music.data.ServerType
import com.aurora.music.data.Session
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class PlexException(val statusCode: Int, message: String) : IOException(message)

data class PlexPage(val items: List<JsonObject>, val total: Int)

class PlexClient(val session: Session) {
    private val base: HttpUrl = requireNotNull(session.server.trimEnd('/').toHttpUrlOrNull())
    private val http = OkHttpClient.Builder().followRedirects(false).connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).build()

    private suspend fun container(path: String, params: Map<String, String> = emptyMap(),
        start: Int? = null, size: Int? = null, method: String = "GET"): JsonObject = withContext(Dispatchers.IO) {
        val url = requireNotNull(base.resolve(path.trimStart('/'))).newBuilder().apply {
            params.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val request = Request.Builder().url(url).header("X-Plex-Token", session.token)
            .header("X-Plex-Product", "Aurora")
            .header("X-Plex-Client-Identifier", "aurora-android")
            .header("Accept", "application/json")
            .apply {
                if (start != null) header("X-Plex-Container-Start", start.toString())
                if (size != null) header("X-Plex-Container-Size", size.toString())
                if (method != "GET") method(method, ByteArray(0).toRequestBody())
            }.build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw PlexException(response.code, "Plex returned ${response.code}")
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return@withContext JsonObject()
            val root = JsonParser.parseString(body).asJsonObject
            root.getAsJsonObject("MediaContainer") ?: root
        }
    }

    private fun JsonObject.array(name: String): List<JsonObject> =
        getAsJsonArray(name)?.mapNotNull { it.takeIf { value -> value.isJsonObject }?.asJsonObject }.orEmpty()

    private fun JsonObject.number(name: String): Int =
        runCatching { get(name)?.asInt ?: 0 }.getOrDefault(0)

    suspend fun identity(): JsonObject = container("/")

    suspend fun musicSections(): List<String> = container("/library/sections").array("Directory")
        .filter { it.get("type")?.asString == "artist" }
        .mapNotNull { it.get("key")?.asString }

    suspend fun page(path: String, params: Map<String, String> = emptyMap(),
        start: Int = 0, size: Int = 200): PlexPage {
        val data = container(path, params, start, size)
        val items = data.array("Metadata")
        return PlexPage(items, data.number("totalSize").takeIf { it > 0 } ?: items.size)
    }

    suspend fun library(type: Int, start: Int = 0, size: Int = 200): PlexPage {
        val results = mutableListOf<JsonObject>()
        var skip = start
        var total = 0
        for (section in musicSections()) {
            val path = "/library/sections/$section/all"
            val params = mapOf("type" to type.toString())
            val first = page(path, params, 0, 1)
            total += first.total
            if (skip >= first.total) {
                skip -= first.total
                continue
            }
            if (results.size < size) {
                results += page(path, params, skip, size - results.size).items
                skip = 0
            }
        }
        return PlexPage(results, total)
    }

    suspend fun metadata(id: String): JsonObject? = page("/library/metadata/$id", size = 1).items.firstOrNull()

    suspend fun children(id: String, start: Int = 0, size: Int = 200): PlexPage =
        page("/library/metadata/$id/children", start = start, size = size)

    suspend fun playlists(): PlexPage = page("/playlists", mapOf("playlistType" to "audio"))

    suspend fun playlistItems(id: String, start: Int = 0, size: Int = 200): PlexPage =
        page("/playlists/$id/items", start = start, size = size)

    suspend fun search(query: String): PlexPage = page("/search", mapOf("query" to query), size = 100)

    suspend fun scrobble(id: String) {
        container("/:/scrobble", mapOf("key" to id, "identifier" to "com.plexapp.plugins.library"))
    }

    suspend fun rate(id: String, starred: Boolean): Boolean = runCatching {
        container("/:/rate", mapOf("key" to id, "rating" to if (starred) "10" else "0"))
        true
    }.getOrDefault(false)

    fun url(path: String?): String {
        if (path.isNullOrBlank()) return ""
        val resolved = base.resolve(path.trimStart('/')) ?: return ""
        if (resolved.scheme != base.scheme || resolved.host != base.host || resolved.port != base.port) return ""
        return resolved.newBuilder().addQueryParameter("X-Plex-Token", session.token).build().toString()
    }

    companion object {
        suspend fun authenticate(server: String, token: String): Session {
            val normalized = (if (server.startsWith("http://") || server.startsWith("https://")) server
                else "http://$server").trimEnd('/').toHttpUrlOrNull()
                ?: throw IllegalArgumentException("Enter a valid Plex server address")
            val provisional = Session(normalized.toString().trimEnd('/'), "Plex", "", token,
                ServerType.PLEX)
            val client = PlexClient(provisional)
            val identity = client.identity()
            client.musicSections()
            val username = identity.get("myPlexUsername")?.asString?.takeIf { it.isNotBlank() }
                ?: identity.get("friendlyName")?.asString?.takeIf { it.isNotBlank() } ?: "Plex"
            val machineId = identity.get("machineIdentifier")?.asString.orEmpty()
            return provisional.copy(username = username, userId = machineId)
        }
    }
}
