package com.aurora.music.data.remote

import com.google.gson.JsonParser
import java.io.IOException
import java.net.URI
import java.security.MessageDigest

/** Kept in encrypted storage; never put this payload in navigation state or logs. */
class YouTubeMusicWebSession(
    val cookie: String,
    val visitorData: String,
    val dataSyncId: String = "",
    val authUser: String = "0",
    val clientVersion: String = "",
    val userAgent: String = "",
    val pageId: String? = null,
) {
    private fun sapisid(): String = cookie.split(';').mapNotNull {
        val parts = it.trim().split('=', limit = 2)
        if (parts.size == 2) parts[0] to parts[1] else null
    }.toMap().let { it["SAPISID"] ?: it["__Secure-3PAPISID"] ?: it["__Secure-1PAPISID"] }.orEmpty()

    fun validate(): YouTubeMusicWebSession {
        if (sapisid().isBlank() || visitorData.isBlank() || !authUser.matches(Regex("[0-9]+")) ||
            listOf(cookie, visitorData, userAgent, clientVersion, pageId.orEmpty()).any { '\r' in it || '\n' in it }) {
            throw IOException("Finish signing in to YouTube Music, then tap Connect this account.")
        }
        return this
    }

    fun authorization(timestamp: Long): String {
        validate()
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("$timestamp ${sapisid()} ${YouTubeMusicClient.ORIGIN}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${timestamp}_$digest"
    }

    fun encode(): String = json("cookie" to cookie, "visitorData" to visitorData, "dataSyncId" to dataSyncId,
        "authUser" to authUser, "clientVersion" to clientVersion, "userAgent" to userAgent).apply {
        pageId?.let { addProperty("pageId", it) }
    }.toString()

    companion object {
        const val LOGIN_URL = "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"

        fun isMusicPage(url: String?): Boolean = runCatching {
            val uri = URI(url.orEmpty())
            uri.scheme == "https" && uri.host == "music.youtube.com" &&
                (uri.port == -1 || uri.port == 443) && uri.userInfo == null
        }.getOrDefault(false)

        fun decode(value: String): YouTubeMusicWebSession = try {
            val obj = JsonParser.parseString(value).asJsonObject
            YouTubeMusicWebSession(obj.string("cookie"), obj.string("visitorData"),
                obj.string("dataSyncId").substringBefore("||"), obj.string("authUser").ifBlank { "0" },
                obj.string("clientVersion"), obj.string("userAgent"),
                obj.get("pageId")?.takeIf { !it.isJsonNull }?.asString).validate()
        } catch (_: Exception) {
            throw IOException("Reconnect your YouTube Music account in Settings → Accounts.")
        }
    }
}
