package com.aurora.music.data.remote

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

// nullable per gson rule
private class GeniusEnvelope<T>(val response: T? = null)
private class GeniusSearch(val sections: List<GeniusSection>? = emptyList())
private class GeniusSection(val hits: List<GeniusSearchHit>? = emptyList())
private class GeniusSearchHit(val result: GeniusHit? = null)
private class GeniusSongResponse(val song: GeniusSongDto? = null)
private class GeniusReferents(val referents: List<GeniusReferentDto>? = emptyList())
private class GeniusArtistSearch(val sections: List<GeniusArtistSection>? = emptyList())
private class GeniusArtistSection(val hits: List<GeniusArtistHit>? = emptyList())
private class GeniusArtistHit(val result: GeniusArtistDto? = null)
private class GeniusArtistSongs(val songs: List<GeniusHit>? = emptyList())

data class GeniusArtistDto(val id: Long? = 0, val name: String? = "")

data class GeniusHit(
    val id: Long? = 0,
    val title: String? = "",
    val url: String? = "",
    val instrumental: Boolean? = false,
    @SerializedName("artist_names") val artistNames: String? = "",
    @SerializedName("primary_artist") val primaryArtist: GeniusUserDto? = null,
    @SerializedName("annotation_count") val annotationCount: Int? = 0,
)

data class GeniusSongDto(
    val url: String? = "",
    val description: GeniusText? = null,
    @SerializedName("description_annotation") val descriptionAnnotation: GeniusReferentDto? = null,
)

data class GeniusReferentDto(
    val fragment: String? = "",
    val url: String? = "",
    @SerializedName("is_description") val isDescription: Boolean? = false,
    val annotations: List<GeniusAnnotationDto>? = emptyList(),
)

data class GeniusAnnotationDto(
    val body: GeniusText? = null,
    val state: String? = "",
    val verified: Boolean? = false,
    val authors: List<GeniusAuthorDto>? = emptyList(),
)

data class GeniusAuthorDto(val user: GeniusUserDto? = null)
data class GeniusUserDto(val name: String? = "", val login: String? = "")
data class GeniusText(val plain: String? = "")

// genius.com's own web api answers without a key; only the stock curl and okhttp agents are turned away
class GeniusClient(base: OkHttpClient = OkHttpClient()) {
    private val http = base.newBuilder()
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).header("Accept", "application/json").build())
        }
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    fun search(query: String): List<GeniusHit> =
        get(api("search/song").addQueryParameter("q", query).addQueryParameter("per_page", "10").build(),
            object : TypeToken<GeniusEnvelope<GeniusSearch>>() {})
            ?.sections.orEmpty().flatMap { it.hits.orEmpty() }.mapNotNull { it.result }

    fun artists(query: String): List<GeniusArtistDto> =
        get(api("search/artist").addQueryParameter("q", query).addQueryParameter("per_page", "5").build(),
            object : TypeToken<GeniusEnvelope<GeniusArtistSearch>>() {})
            ?.sections.orEmpty().flatMap { it.hits.orEmpty() }.mapNotNull { it.result }

    fun artistSongs(artistId: Long, query: String): List<GeniusHit> =
        get(api("artists/$artistId/songs/search").addQueryParameter("q", query).addQueryParameter("per_page", "10").build(),
            object : TypeToken<GeniusEnvelope<GeniusArtistSongs>>() {})?.songs.orEmpty()

    fun song(id: Long): GeniusSongDto? =
        get(api("songs/$id").addQueryParameter("text_format", "plain").build(), object : TypeToken<GeniusEnvelope<GeniusSongResponse>>() {})?.song

    // no page count comes back, so a short page is the last one
    fun referents(songId: Long): List<GeniusReferentDto> {
        val out = mutableListOf<GeniusReferentDto>()
        for (page in 1..MAX_PAGES) {
            val url = api("referents").addQueryParameter("song_id", songId.toString()).addQueryParameter("text_format", "plain")
                .addQueryParameter("per_page", PER_PAGE.toString()).addQueryParameter("page", page.toString()).build()
            val batch = get(url, object : TypeToken<GeniusEnvelope<GeniusReferents>>() {})?.referents.orEmpty()
            out += batch
            if (batch.size < PER_PAGE) break
        }
        return out
    }

    private fun api(path: String): HttpUrl.Builder = "https://genius.com/api/$path".toHttpUrl().newBuilder()

    private fun <T> get(url: HttpUrl, type: TypeToken<GeniusEnvelope<T>>): T? =
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (resp.code == 404) return null
            if (!resp.isSuccessful) throw IOException("genius ${resp.code}")
            val body = resp.body ?: return null
            gson.fromJson<GeniusEnvelope<T>>(body.charStream(), type.type)?.response
        }

    private companion object {
        const val USER_AGENT = "Aurora/3 (music player; lyric meanings)"
        const val PER_PAGE = 50
        const val MAX_PAGES = 4
    }
}
