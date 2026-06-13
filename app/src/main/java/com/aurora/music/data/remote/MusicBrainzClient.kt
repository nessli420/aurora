package com.aurora.music.data.remote

import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

// --- DTOs (nullable per the project's Gson rule) ---------------------------------

data class MbRecordingResult(val recordings: List<MbRecording>? = emptyList())

data class MbRecording(
    val id: String? = "",
    val title: String? = "",
    val length: Long? = null,                                   // ms
    val score: Int? = 0,                                        // search relevance 0..100
    @SerializedName("artist-credit") val artistCredit: List<MbArtistCredit>? = emptyList(),
    val releases: List<MbRelease>? = emptyList(),
)

data class MbArtistCredit(val name: String? = "", val joinphrase: String? = "", val artist: MbArtist? = null)
data class MbArtist(val id: String? = "", val name: String? = "")

data class MbRelease(
    val id: String? = "",
    val title: String? = "",
    val date: String? = "",
    @SerializedName("release-group") val releaseGroup: MbReleaseGroup? = null,
    val media: List<MbMedia>? = emptyList(),
)

data class MbReleaseGroup(val id: String? = "", @SerializedName("primary-type") val primaryType: String? = "")
data class MbMedia(val position: Int? = null, val track: List<MbTrack>? = emptyList())
data class MbTrack(val number: String? = "", val title: String? = "")

interface MusicBrainzApi {
    @GET("ws/2/recording")
    suspend fun searchRecording(
        @Query("query") query: String,
        @Query("fmt") fmt: String = "json",
        @Query("limit") limit: Int = 8,
    ): MbRecordingResult
}

/** A resolved metadata candidate the tag editor can apply (4.4). */
data class MetadataMatch(
    val title: String,
    val artist: String,
    val album: String,
    val year: String,
    val trackNumber: String,
    val coverUrl: String,        // Cover Art Archive front image, or "" if none known
    val score: Int,
)

/**
 * MusicBrainz lookup (4.4): searches recordings by existing tags and maps each to a [MetadataMatch],
 * with a Cover Art Archive front-cover URL per release. MusicBrainz requires a descriptive
 * User-Agent and rate-limits to ~1 req/s, which is fine for interactive single-track lookups.
 */
class MusicBrainzClient {
    private val http = OkHttpClient.Builder()
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .build()
            )
        }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val api: MusicBrainzApi = Retrofit.Builder()
        .baseUrl("https://musicbrainz.org/")
        .client(http)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(MusicBrainzApi::class.java)

    /** Lucene-escape a user value going into a quoted query term. */
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    suspend fun search(title: String, artist: String, album: String = ""): List<MetadataMatch> {
        if (title.isBlank() && artist.isBlank()) return emptyList()
        val terms = buildList {
            if (title.isNotBlank()) add("recording:\"${esc(title)}\"")
            if (artist.isNotBlank()) add("artist:\"${esc(artist)}\"")
            if (album.isNotBlank()) add("release:\"${esc(album)}\"")
        }
        val query = terms.joinToString(" AND ")
        val result = runCatching { api.searchRecording(query) }.getOrNull() ?: return emptyList()
        return result.recordings.orEmpty().mapNotNull { it.toMatch() }
    }

    /** Download cover-art bytes (Cover Art Archive 302-redirects to the Internet Archive). */
    suspend fun fetchImage(url: String): ByteArray? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        runCatching {
            http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use {
                if (it.isSuccessful) it.body?.bytes() else null
            }
        }.getOrNull()
    }

    private fun MbRecording.toMatch(): MetadataMatch? {
        val t = title?.takeIf { it.isNotBlank() } ?: return null
        val artistName = artistCredit.orEmpty().joinToString("") { (it.name ?: it.artist?.name ?: "") + (it.joinphrase ?: "") }
            .ifBlank { artistCredit.orEmpty().firstOrNull()?.artist?.name ?: "" }
        // Prefer an official album release over singles/compilations for album + track number.
        val release = releases.orEmpty().firstOrNull { it.releaseGroup?.primaryType.equals("Album", true) }
            ?: releases.orEmpty().firstOrNull()
        val trackNo = release?.media.orEmpty().firstNotNullOfOrNull { m -> m.track.orEmpty().firstOrNull()?.number }
        val cover = release?.id?.takeIf { it.isNotBlank() }?.let { "https://coverartarchive.org/release/$it/front-500" } ?: ""
        return MetadataMatch(
            title = t,
            artist = artistName.trim(),
            album = release?.title ?: "",
            year = release?.date?.take(4) ?: "",
            trackNumber = trackNo ?: "",
            coverUrl = cover,
            score = score ?: 0,
        )
    }

    private companion object {
        const val USER_AGENT = "Aurora/1.0 ( https://github.com/aurora-music/aurora )"
    }
}
