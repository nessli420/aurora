package com.aurora.music.data.remote

import com.aurora.music.data.Session
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class SubsonicClient(val session: Session) {

    private val baseUrl: String = session.server.trimEnd('/')

    private val authParams: Map<String, String> = mapOf(
        "u" to session.username,
        "t" to session.token,
        "s" to session.salt,
        "v" to API_VERSION,
        "c" to CLIENT_NAME,
        "f" to "json",
    )

    private val authInterceptor = Interceptor { chain ->
        val original = chain.request()
        val builder = original.url.newBuilder()
        authParams.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        chain.proceed(original.newBuilder().url(builder.build()).build())
    }

    private val okhttp: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val api: SubsonicApi = Retrofit.Builder()
        .baseUrl("$baseUrl/")
        .client(okhttp)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(SubsonicApi::class.java)

    private fun authQuery(): String =
        authParams.entries.joinToString("&") { "${it.key}=${enc(it.value)}" }

    fun coverArtUrl(coverArt: String?, size: Int = 600): String {
        if (coverArt.isNullOrBlank()) return ""
        return "$baseUrl/rest/getCoverArt.view?id=${enc(coverArt)}&size=$size&${authQuery()}"
    }

    // format "raw" serves the original untouched file true lossless
    fun streamUrl(songId: String, maxBitrate: Int = 0, format: String? = null): String {
        val params = buildList {
            if (maxBitrate > 0) add("maxBitRate=$maxBitrate")
            if (!format.isNullOrBlank()) add("format=$format")
        }.joinToString("") { "&$it" }
        return "$baseUrl/rest/stream.view?id=${enc(songId)}$params&${authQuery()}"
    }

    suspend fun songInfo(songId: String): SongDto? = runCatching {
        api.getSong(songId).response.song
    }.getOrNull()

    companion object {
        const val API_VERSION = "1.16.1"
        const val CLIENT_NAME = "Aurora"

        private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

        private fun md5(input: String): String {
            val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
            return bytes.joinToString("") { "%02x".format(it) }
        }

        // password is never stored
        fun buildSession(server: String, username: String, password: String): Session {
            val salt = (1..16).map { "0123456789abcdef".random() }.joinToString("")
            val token = md5(password + salt)
            val normalized = normalizeServer(server)
            return Session(normalized, username.trim(), salt, token)
        }

        fun normalizeServer(raw: String): String {
            var s = raw.trim().trimEnd('/')
            if (s.isEmpty()) return s
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
            val url: HttpUrl? = s.toHttpUrlOrNull()
            return url?.toString()?.trimEnd('/') ?: s
        }
    }
}
