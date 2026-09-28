package com.aurora.music.data.remote

import com.aurora.music.util.AppLog
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64

// private server art is unreachable by discord so upload to imgur for a proxyable public link
class ImgurUploader {

    private val http = OkHttpClient()
    private val gson = Gson()

    suspend fun uploadFromUrl(imageUrl: String, clientId: String): String? = withContext(Dispatchers.IO) {
        if (clientId.isBlank() || imageUrl.isBlank()) return@withContext null
        runCatching {
            val bytes = http.newCall(Request.Builder().url(imageUrl).build()).execute().use { it.body?.bytes() }
            if (bytes == null) { AppLog.w(TAG, "could not fetch image bytes from $imageUrl"); return@runCatching null }
            uploadBytes(bytes, clientId)
        }.getOrNull()
    }

    suspend fun uploadBytes(bytes: ByteArray, clientId: String): String? = withContext(Dispatchers.IO) {
        if (clientId.isBlank() || bytes.isEmpty()) return@withContext null
        runCatching {
            val b64 = Base64.getEncoder().encodeToString(bytes)
            val form = FormBody.Builder().add("image", b64).add("type", "base64").build()
            val req = Request.Builder()
                .url("https://api.imgur.com/3/image")
                .addHeader("Authorization", "Client-ID $clientId")
                .post(form)
                .build()
            http.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string()
                val link = gson.fromJson(bodyStr, ImgurResp::class.java)?.data?.link
                if (link == null) AppLog.w(TAG, "imgur upload HTTP ${resp.code}: ${bodyStr?.take(300)}")
                link
            }
        }.getOrNull()
    }

    private data class ImgurResp(val data: ImgurData? = null)
    private data class ImgurData(val link: String? = null)

    private companion object { const val TAG = "ImgurUploader" }
}
