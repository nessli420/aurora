package com.aurora.music.data.remote

import com.aurora.music.data.ServerType
import com.aurora.music.data.Session
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Authenticated Jellyfin client for a [Session]. The access token + client identity travel in the
 * `X-Emby-Authorization` header on every API request; stream and image URLs carry the token as an
 * `api_key` query param so ExoPlayer and Coil can fetch them directly.
 */
class JellyfinClient(val session: Session) {

    private val baseUrl: String = session.server.trimEnd('/')
    val userId: String = session.userId
    private val token: String = session.token

    val api: JellyfinApi = buildApi(baseUrl, token)

    /** Cover-art URL for an item (album/artist/playlist/song-with-art). "" when no id. */
    fun coverArtUrl(itemId: String?, size: Int = 600): String {
        if (itemId.isNullOrBlank()) return ""
        return "$baseUrl/Items/${enc(itemId)}/Images/Primary?fillHeight=$size&fillWidth=$size&quality=90&api_key=${enc(token)}"
    }

    /** Playable audio URL. [lossless] (or no bitrate cap) streams the original file untouched. */
    fun streamUrl(songId: String, maxBitrate: Int, lossless: Boolean): String =
        if (lossless || maxBitrate <= 0) {
            "$baseUrl/Audio/${enc(songId)}/stream?static=true&api_key=${enc(token)}"
        } else {
            "$baseUrl/Audio/${enc(songId)}/universal?UserId=${enc(userId)}&DeviceId=$DEVICE_ID" +
                "&MaxStreamingBitrate=${maxBitrate * 1000}&Container=mp3&AudioCodec=mp3&api_key=${enc(token)}"
        }

    companion object {
        const val CLIENT_NAME = "Aurora"
        const val DEVICE_ID = "aurora-android"
        const val VERSION = "1.0"

        private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

        private fun authHeader(token: String): String = buildString {
            append("MediaBrowser Client=\"$CLIENT_NAME\", Device=\"Android\", DeviceId=\"$DEVICE_ID\", Version=\"$VERSION\"")
            if (token.isNotBlank()) append(", Token=\"$token\"")
        }

        private fun buildApi(server: String, token: String): JellyfinApi {
            val interceptor = Interceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("X-Emby-Authorization", authHeader(token))
                    .build()
                chain.proceed(req)
            }
            val ok = OkHttpClient.Builder()
                .addInterceptor(interceptor)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            return Retrofit.Builder()
                .baseUrl("${server.trimEnd('/')}/")
                .client(ok)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(JellyfinApi::class.java)
        }

        /** Accept "host", "host:port" or a full URL; default to http scheme, no trailing slash. */
        fun normalizeServer(raw: String): String {
            var s = raw.trim().trimEnd('/')
            if (s.isEmpty()) return s
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
            return s.toHttpUrlOrNull()?.toString()?.trimEnd('/') ?: s
        }

        /**
         * Authenticate against a Jellyfin server and build a [Session]. Network call — run off the
         * main thread. Returns null on bad credentials / unreachable server.
         */
        suspend fun authenticate(server: String, username: String, password: String): Session {
            val norm = normalizeServer(server)
            val api = buildApi(norm, "")
            val res = api.authenticate(AuthRequest(username.trim(), password))
            val token = res.AccessToken ?: throw IllegalStateException("Login rejected")
            val uid = res.User?.Id ?: throw IllegalStateException("No user id returned")
            return Session(
                server = norm,
                username = res.User?.Name ?: username.trim(),
                salt = "",
                token = token,
                type = ServerType.JELLYFIN,
                userId = uid,
            )
        }
    }
}
