package com.aurora.music.data.remote

import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

// ListenBrainz submit API

data class LbTrackMeta(
    val artist_name: String,
    val track_name: String,
    val release_name: String? = null,
)
data class LbListen(val listened_at: Long? = null, val track_metadata: LbTrackMeta)
data class LbSubmit(val listen_type: String, val payload: List<LbListen>)
data class LbValidate(val valid: Boolean = false, val user_name: String? = null)

interface ListenBrainzApi {
    @GET("1/validate-token")
    suspend fun validate(@Header("Authorization") auth: String): LbValidate

    @POST("1/submit-listens")
    suspend fun submit(@Header("Authorization") auth: String, @Body body: LbSubmit): Response<Unit>
}

/**
 * ListenBrainz scrobble submission. Token auth only: the user's own ListenBrainz user token.
 * `playing_now` for the now-playing ping; `single` for a completed listen.
 */
class ListenBrainzClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val api: ListenBrainzApi = Retrofit.Builder()
        .baseUrl("https://api.listenbrainz.org/")
        .client(http)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(ListenBrainzApi::class.java)

    private fun auth(token: String) = "Token $token"

    /** Returns the ListenBrainz username for a valid token, or null. */
    suspend fun validate(token: String): String? =
        runCatching { api.validate(auth(token)).takeIf { it.valid }?.user_name }.getOrNull()

    suspend fun playingNow(token: String, artist: String, track: String, release: String?) {
        runCatching { api.submit(auth(token), LbSubmit("playing_now", listOf(LbListen(track_metadata = LbTrackMeta(artist, track, release))))) }
    }

    suspend fun listen(token: String, artist: String, track: String, release: String?, listenedAtSec: Long) {
        runCatching { api.submit(auth(token), LbSubmit("single", listOf(LbListen(listenedAtSec, LbTrackMeta(artist, track, release))))) }
    }
}
