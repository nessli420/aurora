package com.aurora.music.data.remote

import com.aurora.music.data.RadioStation
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Raw Radio-Browser station DTO. Every field nullable-with-default per the Gson rule. */
private data class RbStationDto(
    @SerializedName("stationuuid") val stationUuid: String? = "",
    val name: String? = "",
    val url: String? = "",
    @SerializedName("url_resolved") val urlResolved: String? = "",
    val favicon: String? = "",
    val tags: String? = "",
    val country: String? = "",
    val codec: String? = "",
    val bitrate: Int? = 0,
    val homepage: String? = "",
)

/**
 * The Radio-Browser directory (https://www.radio-browser.info) — a free, keyless community index of
 * Icecast/Shoutcast stations. No account or API key is needed; it only asks for a descriptive
 * User-Agent. Mirrors occasionally go down, so requests fall through a small host list and the first
 * one that answers is cached. Failures degrade to an empty list (the codebase's no-throw convention).
 */
class RadioBrowserClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    @Volatile private var preferredHost: String? = null

    // Each query returns null on transport failure (all mirrors unreachable) vs an empty list when the
    // directory genuinely has no matches — so the UI can tell "offline" apart from "no results".

    /** Most-clicked stations worldwide — the default "popular" browse view. */
    suspend fun topStations(limit: Int = 80): List<RadioStation>? =
        get("json/stations/topclick/$limit", "hidebroken" to "true")

    /** Free-text search by station name. */
    suspend fun search(query: String, limit: Int = 80): List<RadioStation>? {
        if (query.isBlank()) return emptyList()
        return get(
            "json/stations/search",
            "name" to query, "limit" to limit.toString(), "hidebroken" to "true",
            "order" to "clickcount", "reverse" to "true",
        )
    }

    /** Stations carrying an exact genre/topic tag (e.g. "jazz", "news"). */
    suspend fun byTag(tag: String, limit: Int = 80): List<RadioStation>? {
        if (tag.isBlank()) return emptyList()
        return get(
            "json/stations/search",
            "tag" to tag, "limit" to limit.toString(), "hidebroken" to "true",
            "order" to "clickcount", "reverse" to "true",
        )
    }

    /**
     * Tell Radio-Browser a station was played (credits its click count, good directory etiquette).
     * Best-effort and fire-and-forget — ignores the response.
     */
    suspend fun registerClick(uuid: String) {
        if (uuid.isBlank() || uuid.startsWith("custom:")) return
        runCatching { request("json/url/$uuid") }
    }

    private suspend fun get(path: String, vararg params: Pair<String, String>): List<RadioStation>? =
        withContext(Dispatchers.IO) {
            val body = request(path, *params) ?: return@withContext null
            runCatching {
                gson.fromJson(body, Array<RbStationDto>::class.java)?.mapNotNull { it.toStation() }.orEmpty()
            }.getOrDefault(emptyList())
        }

    /** Fetch [path] from the first reachable mirror; caches the winner in [preferredHost]. */
    private fun request(path: String, vararg params: Pair<String, String>): String? {
        val hosts = buildList {
            preferredHost?.let { add(it) }
            MIRRORS.forEach { if (it != preferredHost) add(it) }
        }
        for (host in hosts) {
            val base = "https://$host/$path".toHttpUrlOrNull() ?: continue
            val url = base.newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
            val ok = runCatching {
                http.newCall(
                    Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
                ).execute().use { resp -> if (resp.isSuccessful) resp.body?.string() else null }
            }.getOrNull()
            // Only accept (and cache) a mirror that returns a JSON body — a 200 maintenance/HTML page
            // or an empty body means this mirror is effectively down; fall through to the next.
            val trimmed = ok?.trimStart()
            if (trimmed != null && (trimmed.startsWith("[") || trimmed.startsWith("{"))) {
                preferredHost = host
                return ok
            }
        }
        return null
    }

    private fun RbStationDto.toStation(): RadioStation? {
        val stream = urlResolved?.takeIf { it.isNotBlank() } ?: url?.takeIf { it.isNotBlank() } ?: return null
        val id = stationUuid?.takeIf { it.isNotBlank() } ?: return null
        return RadioStation(
            uuid = id,
            name = name,
            streamUrl = stream,
            faviconUrl = favicon,
            tags = tags,
            country = country,
            codec = codec,
            bitrate = bitrate,
            homepage = homepage,
            custom = false,
        )
    }

    private companion object {
        const val USER_AGENT = "Aurora/1.0 ( https://github.com/aurora-music/aurora )"
        // Named mirrors; the all-round-robin host is last as a fallback.
        val MIRRORS = listOf(
            "de2.api.radio-browser.info",
            "nl1.api.radio-browser.info",
            "at1.api.radio-browser.info",
            "all.api.radio-browser.info",
        )
    }
}
