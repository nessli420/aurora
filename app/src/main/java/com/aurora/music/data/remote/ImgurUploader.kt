package com.aurora.music.data.remote

import android.util.Base64
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Anonymous Imgur image host. Album art lives on a private music server that neither Discord nor
 * Imgur can fetch by URL, so we download the cover bytes in-app and upload them here to get a
 * public https link — which Discord *can* proxy for Rich Presence. Needs a free Imgur API
 * Client-ID (https://api.imgur.com/oauth2/addclient, "anonymous usage without user authorization").
 */
class ImgurUploader {

    private val http = OkHttpClient()
    private val gson = Gson()

    /** Fetch [imageUrl] (reachable from the device) and upload the bytes to Imgur. Returns the link. */
    suspend fun uploadFromUrl(imageUrl: String, clientId: String): String? = withContext(Dispatchers.IO) {
        if (clientId.isBlank() || imageUrl.isBlank()) return@withContext null
        runCatching {
            val bytes = http.newCall(Request.Builder().url(imageUrl).build()).execute().use { it.body?.bytes() }
                ?: return@runCatching null
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val form = FormBody.Builder().add("image", b64).add("type", "base64").build()
            val req = Request.Builder()
                .url("https://api.imgur.com/3/image")
                .addHeader("Authorization", "Client-ID $clientId")
                .post(form)
                .build()
            http.newCall(req).execute().use { resp ->
                gson.fromJson(resp.body?.string(), ImgurResp::class.java)?.data?.link
            }
        }.getOrNull()
    }

    private data class ImgurResp(val data: ImgurData? = null)
    private data class ImgurData(val link: String? = null)
}
