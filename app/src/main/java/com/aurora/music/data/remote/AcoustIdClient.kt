package com.aurora.music.data.remote

import com.aurora.music.data.Chromaprint
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

// nullable per gson rule
data class AcoustIdResult(val status: String? = "", val results: List<AcoustIdMatch>? = emptyList())
data class AcoustIdMatch(val id: String? = "", val score: Double? = 0.0, val recordings: List<AcoustIdRecording>? = emptyList())
data class AcoustIdRecording(
    val id: String? = "",
    val title: String? = "",
    val duration: Double? = null,
    val artists: List<AcoustIdArtist>? = emptyList(),
    @SerializedName("releasegroups") val releaseGroups: List<AcoustIdReleaseGroup>? = emptyList(),
)
data class AcoustIdArtist(val id: String? = "", val name: String? = "")
data class AcoustIdReleaseGroup(val id: String? = "", val title: String? = "", val type: String? = "")

interface AcoustIdApi {
    @GET("v2/lookup")
    suspend fun lookup(
        @Query("client") client: String,
        @Query("fingerprint") fingerprint: String,
        @Query("duration") duration: Int,
        @Query("meta") meta: String = "recordings releasegroups",
    ): AcoustIdResult
}

class AcoustIdClient(private val apiKeyProvider: () -> String) {

    private val apiKey: String get() = apiKeyProvider()

    val available: Boolean get() = Chromaprint.available

    val configured: Boolean get() = apiKey.isNotBlank()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val api: AcoustIdApi = Retrofit.Builder()
        .baseUrl("https://api.acoustid.org/")
        .client(http)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(AcoustIdApi::class.java)

    suspend fun fingerprint(path: String): String? =
        withContext(Dispatchers.Default) { Chromaprint.fingerprint(path) }

    suspend fun lookup(fingerprint: String, durationSec: Int): List<MetadataMatch> {
        val key = apiKey
        if (key.isBlank()) return emptyList()
        val resp = runCatching { api.lookup(key, fingerprint, durationSec.coerceAtLeast(1)) }.getOrNull() ?: return emptyList()
        return resp.results.orEmpty()
            .sortedByDescending { it.score ?: 0.0 }
            .flatMap { match -> match.recordings.orEmpty().map { it.toMatch(match.score ?: 0.0) } }
            .distinctBy { it.title + "|" + it.artist + "|" + it.album }
            .take(8)
    }

    private fun AcoustIdRecording.toMatch(matchScore: Double): MetadataMatch {
        val rg = releaseGroups.orEmpty().firstOrNull { it.type.equals("Album", true) } ?: releaseGroups.orEmpty().firstOrNull()
        val cover = rg?.id?.takeIf { it.isNotBlank() }?.let { "https://coverartarchive.org/release-group/$it/front-500" } ?: ""
        return MetadataMatch(
            title = title ?: "",
            artist = artists.orEmpty().joinToString(", ") { it.name ?: "" }.trim(),
            album = rg?.title ?: "",
            year = "",
            trackNumber = "",
            coverUrl = cover,
            score = (matchScore * 100).toInt(),
        )
    }
}
