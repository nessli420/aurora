package com.aurora.music.data.remote

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest

/** Linked Last.fm session (from the auth flow). */
data class LastfmSession(val name: String, val key: String)

/** Public Last.fm profile snapshot. */
data class LastfmUser(val name: String, val imageUrl: String, val playcount: Long)

/**
 * Minimal Last.fm API 2.0 client. Implements the desktop "browser authorize" auth flow
 * (auth.getToken → user authorizes in browser → auth.getSession) plus scrobbling
 * (track.updateNowPlaying / track.scrobble) and user.getInfo.
 *
 * Every authenticated call is signed: `api_sig = md5(<params sorted by name as key+value> + secret)`,
 * excluding `format`. Requires an [apiKey] + shared [secret] from a registered Last.fm API account.
 */
class LastfmClient(private val apiKey: String, private val secret: String) {

    private val http = OkHttpClient()
    private val gson = Gson()

    val configured: Boolean get() = apiKey.isNotBlank() && !apiKey.startsWith("YOUR_")

    /** Step 1 of auth: request a token to be authorized in the browser. */
    suspend fun getToken(): String? = withContext(Dispatchers.IO) {
        val params = sortedMapOf("api_key" to apiKey, "method" to "auth.getToken")
        runCatching {
            val resp = get(params)
            gson.fromJson(resp, TokenResp::class.java)?.token
        }.getOrNull()
    }

    /** The URL to open in a browser so the user can grant access for [token]. */
    fun authorizeUrl(token: String): String = "https://www.last.fm/api/auth/?api_key=$apiKey&token=$token"

    /** Step 2 of auth: exchange an authorized [token] for a permanent session key. Null until allowed. */
    suspend fun getSession(token: String): LastfmSession? = withContext(Dispatchers.IO) {
        val params = sortedMapOf("api_key" to apiKey, "method" to "auth.getSession", "token" to token)
        runCatching {
            val resp = get(params)
            val s = gson.fromJson(resp, SessionResp::class.java)?.session
            if (s?.name != null && s.key != null) LastfmSession(s.name, s.key) else null
        }.getOrNull()
    }

    suspend fun userInfo(name: String): LastfmUser? = withContext(Dispatchers.IO) {
        runCatching {
            // user.getInfo doesn't require a signature, only the api key.
            val url = BASE.toHttpUrl().newBuilder()
                .addQueryParameter("method", "user.getInfo")
                .addQueryParameter("user", name)
                .addQueryParameter("api_key", apiKey)
                .addQueryParameter("format", "json")
                .build()
            val body = http.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string() }
            val u = gson.fromJson(body, UserResp::class.java)?.user ?: return@runCatching null
            LastfmUser(
                name = u.name ?: name,
                imageUrl = u.image?.lastOrNull { !it.text.isNullOrBlank() }?.text ?: "",
                playcount = u.playcount?.toLongOrNull() ?: 0L,
            )
        }.getOrNull()
    }

    suspend fun updateNowPlaying(sk: String, artist: String, track: String, album: String?) {
        withContext(Dispatchers.IO) {
            runCatching {
                post("track.updateNowPlaying", buildMap {
                    put("artist", artist); put("track", track)
                    if (!album.isNullOrBlank()) put("album", album)
                    put("sk", sk)
                })
            }
        }
    }

    suspend fun scrobble(sk: String, artist: String, track: String, album: String?, timestampSec: Long) {
        withContext(Dispatchers.IO) {
            runCatching {
                post("track.scrobble", buildMap {
                    put("artist", artist); put("track", track)
                    if (!album.isNullOrBlank()) put("album", album)
                    put("timestamp", timestampSec.toString())
                    put("sk", sk)
                })
            }
        }
    }

    // --- internals ---

    /** Signed GET with json format. [params] are the signature inputs (api_sig + format added here). */
    private fun get(params: Map<String, String>): String? {
        val sig = sign(params)
        val builder = BASE.toHttpUrl().newBuilder()
        params.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        builder.addQueryParameter("api_sig", sig)
        builder.addQueryParameter("format", "json")
        return http.newCall(Request.Builder().url(builder.build()).build()).execute().use { it.body?.string() }
    }

    /** Signed POST (write methods). [extra] excludes method/api_key, which are added here. */
    private fun post(method: String, extra: Map<String, String>) {
        val params = sortedMapOf<String, String>()
        params["method"] = method
        params["api_key"] = apiKey
        params.putAll(extra)
        val sig = sign(params)
        val form = FormBody.Builder()
        params.forEach { (k, v) -> form.add(k, v) }
        form.add("api_sig", sig)
        form.add("format", "json")
        http.newCall(Request.Builder().url(BASE).post(form.build()).build()).execute().use { it.body?.string() }
    }

    /** api_sig = md5(concat of params sorted by name as key+value, then + shared secret). */
    private fun sign(params: Map<String, String>): String {
        val sb = StringBuilder()
        params.toSortedMap().forEach { (k, v) -> sb.append(k).append(v) }
        sb.append(secret)
        return md5(sb.toString())
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private data class TokenResp(val token: String? = null)
    private data class SessionResp(val session: Sess? = null)
    private data class Sess(val name: String? = null, val key: String? = null)
    private data class UserResp(val user: UserObj? = null)
    private data class UserObj(val name: String? = null, val playcount: String? = null, val image: List<Img>? = null)
    private data class Img(@SerializedName("#text") val text: String? = null, val size: String? = null)

    private companion object { const val BASE = "https://ws.audioscrobbler.com/2.0/" }
}
