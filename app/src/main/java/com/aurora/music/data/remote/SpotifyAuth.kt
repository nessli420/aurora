package com.aurora.music.data.remote

import android.net.Uri
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Spotify OAuth 2.0 with PKCE (no client secret — suitable for a mobile app). Flow:
 *   1. [authorizeUrl] opened in the browser; user logs in + approves.
 *   2. Spotify redirects to [REDIRECT] (`aurora://spotify?code=...`).
 *   3. [exchangeCode] swaps the code (+ PKCE verifier) for access + refresh tokens.
 *   4. [refresh] renews the access token when it expires (~1h).
 */
object SpotifyAuth {
    const val REDIRECT = "aurora://spotify"
    const val AUTH_HOST = "https://accounts.spotify.com"

    /** Full read/write scopes (library + playlists + follows + top/recent). */
    val SCOPES = listOf(
        "user-read-private", "user-read-email",
        "user-library-read", "user-library-modify",
        "playlist-read-private", "playlist-read-collaborative",
        "playlist-modify-private", "playlist-modify-public",
        "user-follow-read", "user-follow-modify",
        "user-top-read", "user-read-recently-played",
    ).joinToString(" ")

    private val http = OkHttpClient()
    private val gson = Gson()
    private val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    fun newVerifier(): String {
        val rnd = SecureRandom()
        return (1..96).map { UNRESERVED[rnd.nextInt(UNRESERVED.length)] }.joinToString("")
    }

    fun challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    fun authorizeUrl(clientId: String, verifier: String, state: String): String =
        Uri.parse("$AUTH_HOST/authorize").buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge(verifier))
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("state", state)
            .build().toString()

    suspend fun exchangeCode(clientId: String, code: String, verifier: String): TokenResp? = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", REDIRECT)
            .add("client_id", clientId)
            .add("code_verifier", verifier)
            .build()
        post(form)
    }

    suspend fun refresh(clientId: String, refreshToken: String): TokenResp? = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", clientId)
            .build()
        post(form)
    }

    private fun post(form: FormBody): TokenResp? = runCatching {
        val req = Request.Builder().url("$AUTH_HOST/api/token").post(form).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                android.util.Log.d("SpotifyOAuth", "token exchange HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")
                return null
            }
            gson.fromJson(resp.body?.string(), TokenResp::class.java)
        }
    }.getOrNull()

    data class TokenResp(
        @SerializedName("access_token") val accessToken: String? = null,
        @SerializedName("refresh_token") val refreshToken: String? = null,
        @SerializedName("expires_in") val expiresIn: Int = 0,
        @SerializedName("token_type") val tokenType: String? = null,
    )
}
