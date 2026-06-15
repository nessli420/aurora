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

// every field nullable-with-default per gson rule
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

class RadioBrowserClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    @Volatile private var preferredHost: String? = null

    // null on transport failure vs empty list when directory has no matches so ui can tell offline from no results

    suspend fun topStations(limit: Int = 80): List<RadioStation>? =
        get("json/stations/topclick/$limit", "hidebroken" to "true")

    suspend fun search(query: String, limit: Int = 80): List<RadioStation>? {
        if (query.isBlank()) return emptyList()
        return get(
            "json/stations/search",
            "name" to query, "limit" to limit.toString(), "hidebroken" to "true",
            "order" to "clickcount", "reverse" to "true",
        )
    }

    suspend fun byTag(tag: String, limit: Int = 80): List<RadioStation>? {
        if (tag.isBlank()) return emptyList()
        return get(
            "json/stations/search",
            "tag" to tag, "limit" to limit.toString(), "hidebroken" to "true",
            "order" to "clickcount", "reverse" to "true",
        )
    }

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
            // only accept a mirror that returns json body a 200 maintenance html page means it is effectively down
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
        val MIRRORS = listOf(
            "de2.api.radio-browser.info",
            "nl1.api.radio-browser.info",
            "at1.api.radio-browser.info",
            "all.api.radio-browser.info",
        )
    }
}
