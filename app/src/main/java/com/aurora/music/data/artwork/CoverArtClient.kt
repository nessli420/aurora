package com.aurora.music.data.artwork

import com.aurora.music.data.ArtistSeparators
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.abs

internal object CoverSearchRateLimit {
    private val lock = Mutex()
    private var last = 0L
    suspend fun awaitTurn() = lock.withLock {
        val now = System.nanoTime() / 1_000_000
        delay((1100 - (now - last)).coerceAtLeast(0))
        last = System.nanoTime() / 1_000_000
    }
}

internal class CoverArtClient(
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build(),
    private val beforeRequest: suspend () -> Unit = { CoverSearchRateLimit.awaitTurn() },
) {
    suspend fun candidates(request: ArtworkRequest, separators: ArtistSeparators): List<String> = withContext(Dispatchers.IO) {
        val fullArtist = request.artist.orEmpty()
        val artists = (listOf(fullArtist) + separators.split(fullArtist)).distinctBy(::artworkKey)
        for (artist in artists) {
            val album = request.album.orEmpty()
            val entity = if (album.isNotBlank()) "release-group" else "recording"
            val name = if (album.isNotBlank()) album else request.title.orEmpty()
            fun escaped(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")
            val query = "$entity:\"${escaped(name)}\" AND artist:\"${escaped(artist)}\""
            val url = "https://musicbrainz.org/ws/2/$entity".toHttpUrl().newBuilder()
                .addQueryParameter("query", query).addQueryParameter("fmt", "json").addQueryParameter("limit", "12").build()
            beforeRequest()
            val root = http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Artwork metadata HTTP ${response.code}")
                JsonParser.parseString(response.body?.string() ?: throw IOException("Empty metadata")).asJsonObject
            }
            val entries = root.getAsJsonArray(if (album.isNotBlank()) "release-groups" else "recordings") ?: continue
            val matches = entries.mapNotNull { value ->
                val item = value.asJsonObject
                if (artworkKey(item.text("title")) != artworkKey(name) || !artistMatches(item, artists)) return@mapNotNull null
                if (album.isBlank() && (request.duration ?: 0) > 0) {
                    val millis = item.get("length")?.takeUnless { it.isJsonNull }?.asLong
                    if (millis != null && abs(millis / 1000 - request.duration!!) > 8) return@mapNotNull null
                }
                if (album.isNotBlank()) item.text("id").takeIf(::isMbid)?.let { "https://coverartarchive.org/release-group/$it/front-500" }
                else item.getAsJsonArray("releases")?.firstNotNullOfOrNull { release ->
                    release.asJsonObject.text("id").takeIf(::isMbid)?.let { "https://coverartarchive.org/release/$it/front-500" }
                }
            }.distinct()
            if (matches.isNotEmpty()) return@withContext matches.take(4)
        }
        emptyList()
    }

    private fun artistMatches(item: JsonObject, artists: List<String>): Boolean {
        val expected = artists.map(::artworkKey).toSet()
        val credits = item.getAsJsonArray("artist-credit") ?: return false
        return credits.any { value ->
            val credit = value.asJsonObject
            artworkKey(credit.text("name").ifBlank { credit.getAsJsonObject("artist")?.text("name").orEmpty() }) in expected
        }
    }

    private fun JsonObject.text(name: String): String = get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
    private fun isMbid(value: String) = Regex("[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}").matches(value)
    companion object { const val USER_AGENT = "Aurora/1.0 ( https://github.com/nessli420/aurora )" }
}
