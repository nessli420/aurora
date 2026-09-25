package com.aurora.music.data.remote

import com.aurora.music.data.ServerType
import com.aurora.music.data.Session
<<<<<<< HEAD
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
=======
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
>>>>>>> 8524a99 (More in depth plex integration and fixes)
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
<<<<<<< HEAD
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
=======
import okhttp3.Response
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PlexClient(
    val session: Session,
    httpClient: OkHttpClient = defaultHttpClient(),
) {
    private val baseUrl = requireNotNull(normalizeServer(session.server).toHttpUrlOrNull())
    private val http = httpClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val gson = Gson()
    private val clientIdentifier = session.clientToken.ifBlank { "aurora-${UUID.randomUUID()}" }
    private val originals = ConcurrentHashMap<String, String>()
    private val artwork = ConcurrentHashMap<String, String>()

    suspend fun serverInfo(): PlexContainer = get("/")

    suspend fun musicSections(): List<PlexDirectory> = get("/library/sections").directories.orEmpty()
        .filter { it.type == "artist" && !it.key.isNullOrBlank() }

    suspend fun get(
        path: String,
        parameters: Map<String, String> = emptyMap(),
        offset: Int? = null,
        count: Int? = null,
    ): PlexContainer = request("GET", path, parameters, offset, count, expectJson = true)

    suspend fun post(path: String, parameters: Map<String, String>): PlexContainer =
        request("POST", path, parameters, expectJson = true)

    suspend fun getText(path: String): String = execute("GET", path, emptyMap(), accept = "text/plain, */*") { response ->
        checkResponse(response)
        val body = requireNotNull(response.body)
        body.source().request(1_048_577L)
        check(body.source().buffer.size <= 1_048_576L) { "Plex returned an oversized text response." }
        body.string()
    }

    suspend fun mutate(method: String, path: String, parameters: Map<String, String> = emptyMap()): Boolean {
        request(method, path, parameters, expectJson = false)
        return true
    }

    suspend fun createPlaylist(name: String, trackIds: List<String> = emptyList()): String? = post(
        "/playlists", buildMap {
            put("type", "audio")
            put("title", name)
            put("smart", "0")
            if (trackIds.isNotEmpty()) put("uri", sourceUri(trackIds))
        },
    ).metadata.orEmpty().firstOrNull()?.ratingKey?.takeIf { it.isNotBlank() }

    suspend fun metadata(id: String, playlist: Boolean = false, parameters: Map<String, String> = emptyMap()): PlexMetadata? = try {
        get(if (playlist) "/playlists/${validId(id)}" else "/library/metadata/${validId(id)}", parameters)
            .metadata.orEmpty().firstOrNull()
    } catch (e: PlexException) {
        if (e.statusCode == 404) null else throw e
    }

    // short pages can still have more results
    suspend fun allMetadata(
        path: String,
        parameters: Map<String, String> = emptyMap(),
        limit: Int = Int.MAX_VALUE,
    ): List<PlexMetadata> = metadataWindow(path, parameters, limit).metadata.orEmpty()

    suspend fun metadataWindow(
        path: String,
        parameters: Map<String, String> = emptyMap(),
        limit: Int = Int.MAX_VALUE,
        startOffset: Int = 0,
    ): PlexContainer {
        require(startOffset >= 0)
        if (limit <= 0) return PlexContainer(metadata = emptyList(), offset = startOffset)
        val result = mutableListOf<PlexMetadata>()
        var offset = startOffset
        var total: Int? = null
        while (result.size < limit) {
            val requested = minOf(PAGE_SIZE, limit - result.size)
            val page = get(path, parameters, offset, requested)
            val entries = page.metadata.orEmpty()
            total = page.totalSize ?: total
            check(page.offset == null || page.offset == offset) { "Plex returned an unexpected page. Please retry." }
            if (entries.isEmpty()) {
                check(total == null || offset >= total) { "Plex returned an incomplete collection. Please retry." }
                break
            }
            result += entries.take(limit - result.size)
            offset += entries.size
            if (total != null && offset >= total) break
            if (total == null && (page.offset == null || entries.size < requested)) break
        }
        return PlexContainer(size = result.size, totalSize = total, offset = startOffset, metadata = result)
    }

    fun remember(item: PlexMetadata) {
        val id = item.ratingKey?.takeIf { it.isNotBlank() } ?: return
        item.media.orEmpty().firstOrNull()?.parts.orEmpty().firstOrNull()?.key
            ?.let(::safeServerUrl)?.let { originals[id] = it.toString() }
        listOf(item.thumb, item.parentThumb, item.grandparentThumb, item.composite)
            .firstNotNullOfOrNull { it?.let(::safeServerUrl) }?.let { artwork[id] = it.toString() }
    }

    fun streamUrl(songId: String, maxBitrate: Int, lossless: Boolean): String {
        if (lossless || maxBitrate <= 0) return originals[songId]?.let { mediaUrl(it) }.orEmpty()
        return mediaUrl("/music/:/transcode/universal/start.mp3", mapOf(
            "path" to "/library/metadata/${validId(songId)}",
            "protocol" to "http",
            "directPlay" to "0",
            "directStream" to "0",
            "directStreamAudio" to "0",
            "musicBitrate" to maxBitrate.toString(),
            "mediaIndex" to "0",
            "partIndex" to "0",
            "X-Plex-Client-Profile-Name" to "generic",
            "X-Plex-Client-Profile-Extra" to
                "add-transcode-target(type=musicProfile&context=streaming&protocol=http&container=mp3&audioCodec=mp3&replace=true)",
        ))
    }

    fun coverArtUrl(id: String, @Suppress("UNUSED_PARAMETER") size: Int = 600): String {
        if (id.isBlank()) return ""
        val path = artwork[id] ?: if (ID.matches(id)) "/library/metadata/$id/thumb" else id
        return mediaUrl(path)
    }

    fun mediaUrl(path: String, parameters: Map<String, String> = emptyMap()): String {
        val url = safeServerUrl(path) ?: return ""
        return url.newBuilder().apply {
            parameters.forEach { (key, value) -> setQueryParameter(key, value) }
            setQueryParameter("X-Plex-Token", session.token)
            setQueryParameter("X-Plex-Client-Identifier", clientIdentifier)
            setQueryParameter("X-Plex-Product", "Aurora")
            setQueryParameter("X-Plex-Platform", "Android")
        }.build().toString()
    }

    fun sourceUri(ids: List<String>): String {
        require(session.userId.matches(ID)) { "Reconnect to Plex to refresh its server identity." }
        return "server://${session.userId}/com.plexapp.plugins.library/library/metadata/" + ids.joinToString(",") { validId(it) }
    }

    fun sourceUri(path: String): String {
        require(session.userId.matches(ID)) { "Reconnect to Plex to refresh its server identity." }
        require(path.startsWith('/') && !path.startsWith("//")) { "Invalid Plex station path." }
        val target = requireNotNull(safeServerUrl(path)) { "Invalid Plex station path." }
        val key = target.encodedPath.removePrefix(baseUrl.encodedPath.trimEnd('/'))
        return "server://${session.userId}/com.plexapp.plugins.library$key" +
            target.encodedQuery?.let { "?$it" }.orEmpty()
    }

    private fun safeServerUrl(path: String): HttpUrl? {
        if (path.isBlank() || path.startsWith("//") || '\\' in path) return null
        val target = when {
            path.startsWith("/") -> (baseUrl.toString().trimEnd('/') + path).toHttpUrlOrNull()
            else -> path.toHttpUrlOrNull()
        } ?: return null
        if (target.scheme != baseUrl.scheme || target.host != baseUrl.host || target.port != baseUrl.port) return null
        if (target.username.isNotEmpty() || target.password.isNotEmpty()) return null
        return target.newBuilder().fragment(null).build()
    }

    private suspend fun request(
        method: String,
        path: String,
        parameters: Map<String, String>,
        offset: Int? = null,
        count: Int? = null,
        expectJson: Boolean,
    ): PlexContainer = execute(method, path, parameters, offset, count) { readResponse(it, expectJson) }

    private suspend fun <T> execute(
        method: String,
        path: String,
        parameters: Map<String, String>,
        offset: Int? = null,
        count: Int? = null,
        accept: String = "application/json",
        read: (Response) -> T,
    ): T = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val target = requireNotNull(safeServerUrl(path)) { "Plex returned an invalid server path." }.newBuilder().apply {
            parameters.forEach { (key, value) -> setQueryParameter(key, value) }
            offset?.let { setQueryParameter("X-Plex-Container-Start", it.toString()) }
            count?.let { setQueryParameter("X-Plex-Container-Size", it.toString()) }
        }.build()
        val request = Request.Builder().url(target)
            .header("Accept", accept)
            .header("X-Plex-Token", session.token)
            .header("X-Plex-Client-Identifier", clientIdentifier)
            .header("X-Plex-Product", "Aurora")
            .header("X-Plex-Platform", "Android")
            .apply {
                offset?.let { header("X-Plex-Container-Start", it.toString()) }
                count?.let { header("X-Plex-Container-Size", it.toString()) }
                method(method, if (method == "PUT" || method == "POST") ByteArray(0).toRequestBody(null) else null)
            }.build()
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(
                        PlexException(null, "Could not connect to Plex. Check the server address and your connection."),
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val container = response.use(read)
                        if (continuation.isActive) continuation.resume(container)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(when (e) {
                            is PlexException -> e
                            is IOException -> PlexException(null, "Could not read the Plex response. Please retry.")
                            else -> IllegalStateException("The server did not return Plex JSON. Use the Plex Media Server address, not the Plex web app.")
                        })
                    }
                }
            })
        }
    }

    private fun checkResponse(response: Response) {
        if (!response.isSuccessful) throw PlexException(response.code, when (response.code) {
            401, 403 -> "Plex rejected this token or it cannot access this server. Check the token and library sharing permissions."
            in 300..399 -> "Plex redirected the connection. Enter the final Plex server address."
            404 -> "This Plex item or endpoint is unavailable."
            else -> "Plex could not complete the request (HTTP ${response.code})."
        })
    }

    private fun readResponse(response: Response, expectJson: Boolean): PlexContainer {
        checkResponse(response)
        if (!expectJson) return PlexContainer()
        val container = checkNotNull(gson.fromJson(response.body?.string().orEmpty(), PlexResponse::class.java)?.container)
        return container.copy(
            offset = container.offset ?: response.header("X-Plex-Container-Start")?.toIntOrNull(),
            totalSize = container.totalSize ?: response.header("X-Plex-Container-Total-Size")?.toIntOrNull(),
        ).also { it.metadata.orEmpty().forEach(::remember) }
    }

    companion object {
        const val PAGE_SIZE = 200
        private val ID = Regex("[A-Za-z0-9_-]+")

        fun validId(id: String): String = id.also {
            require(ID.matches(it)) { "Invalid Plex item identifier." }
        }

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

        fun normalizeServer(raw: String): String {
            val input = raw.trim().trimEnd('/')
            require(input.isNotBlank()) { "Enter your Plex Media Server address." }
            val url = (if ("://" in input) input else "http://$input").toHttpUrlOrNull()
            require(url != null && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
                "Enter a Plex server HTTP or HTTPS address without credentials, a query or a fragment."
            }
            return url.toString().trimEnd('/')
        }

        suspend fun authenticate(
            server: String,
            token: String,
            httpClient: OkHttpClient = defaultHttpClient(),
        ): Session {
            val credential = token.trim()
            require(credential.isNotBlank() && credential.all { it.code in 33..126 }) { "Enter a valid Plex token." }
            val normalized = normalizeServer(server)
            val initial = Session(normalized, "Plex", "", credential, ServerType.PLEX, clientToken = UUID.randomUUID().toString())
            val client = PlexClient(initial, httpClient)
            val info = client.serverInfo()
            val machineId = info.machineIdentifier?.takeIf { ID.matches(it) }
                ?: throw IllegalStateException("This address did not identify a Plex Media Server.")
            check(client.musicSections().isNotEmpty()) { "This Plex token has no accessible music libraries. Add or share a music library, then reconnect." }
            return initial.copy(
                username = info.friendlyName?.takeIf { it.isNotBlank() } ?: requireNotNull(normalized.toHttpUrlOrNull()).host,
                userId = machineId,
            )
        }
    }
}

class PlexException(val statusCode: Int?, message: String) : IOException(message)

data class PlexResponse(@SerializedName("MediaContainer") val container: PlexContainer? = null)

data class PlexContainer(
    val size: Int? = null,
    val totalSize: Int? = null,
    val offset: Int? = null,
    val machineIdentifier: String? = null,
    val friendlyName: String? = null,
    @SerializedName("Directory") val directories: List<PlexDirectory>? = null,
    @SerializedName("Metadata") val metadata: List<PlexMetadata>? = null,
    @SerializedName("Hub") val hubs: List<PlexHub>? = null,
)

data class PlexHub(
    val hubIdentifier: String? = null,
    val title: String? = null,
    val type: String? = null,
    @SerializedName("Metadata") val metadata: List<PlexMetadata>? = null,
)

data class PlexDirectory(val key: String? = null, val type: String? = null, val title: String? = null)

data class PlexMetadata(
    val ratingKey: String? = null,
    val key: String? = null,
    val type: String? = null,
    val title: String? = null,
    val originalTitle: String? = null,
    val parentTitle: String? = null,
    val grandparentTitle: String? = null,
    val parentRatingKey: String? = null,
    val grandparentRatingKey: String? = null,
    val thumb: String? = null,
    val parentThumb: String? = null,
    val grandparentThumb: String? = null,
    val composite: String? = null,
    val duration: Long? = null,
    val year: Int? = null,
    val leafCount: Int? = null,
    val userRating: Float? = null,
    val viewCount: Int? = null,
    val addedAt: Long? = null,
    val lastViewedAt: Long? = null,
    val playlistType: String? = null,
    val playlistItemID: String? = null,
    val summary: String? = null,
    val smart: JsonElement? = null,
    @SerializedName("Media") val media: List<PlexMedia>? = null,
    @SerializedName("Genre") val genres: List<PlexTag>? = null,
    @SerializedName("Stations") val stations: PlexContainer? = null,
) {
    val isSmart: Boolean get() = smart?.takeIf { it.isJsonPrimitive }?.asString.let { it == "true" || it == "1" }
}

data class PlexMedia(
    val container: String? = null,
    val audioCodec: String? = null,
    val bitrate: Int? = null,
    @SerializedName("Part") val parts: List<PlexPart>? = null,
)

data class PlexPart(
    val key: String? = null,
    val file: String? = null,
    val container: String? = null,
    @SerializedName("Stream") val streams: List<PlexStream>? = null,
)

data class PlexStream(
    val streamType: Int? = null,
    val samplingRate: Int? = null,
    val bitDepth: Int? = null,
    val gain: Float? = null,
    val albumGain: Float? = null,
    val key: String? = null,
    val format: String? = null,
    val codec: String? = null,
)

data class PlexTag(val tag: String? = null)
>>>>>>> 8524a99 (More in depth plex integration and fixes)
