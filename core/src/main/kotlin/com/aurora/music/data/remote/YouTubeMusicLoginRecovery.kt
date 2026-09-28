package com.aurora.music.data.remote

import java.net.URI

/** Cookie presence is only a navigation hint; the Music API must still validate the account. */
class YouTubeMusicLoginRecovery {
    private var readySince: Long? = null
    private var attempted = false

    fun destination(pageUrl: String?, googleCookies: String, musicCookies: String, elapsedMillis: Long): String? {
        if (attempted) return null
        val onGoogle = runCatching {
            val uri = URI(pageUrl.orEmpty())
            uri.scheme == "https" && uri.host == "accounts.google.com" &&
                (uri.port == -1 || uri.port == 443) && uri.userInfo == null
        }.getOrDefault(false)
        val google = cookieNames(googleCookies)
        val music = cookieNames(musicCookies)
        val musicReady = music.any { it in setOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID") }
        val googleReady = google.containsAll(setOf("SID", "HSID", "SSID", "SAPISID"))
        if (!onGoogle || (!googleReady && !musicReady)) {
            readySince = null
            return null
        }
        val since = readySince ?: elapsedMillis.also { readySince = it }
        // Give the normal redirect time to finish, and never retry verification in a loop.
        if (elapsedMillis - since < 3_000) return null
        attempted = true
        return if (musicReady) YouTubeMusicClient.ORIGIN else YouTubeMusicWebSession.LOGIN_URL
    }

    private fun cookieNames(value: String): Set<String> = value.split(';').mapNotNull {
        val parts = it.trim().split('=', limit = 2)
        parts.firstOrNull()?.takeIf { parts.size == 2 && parts[1].isNotBlank() }
    }.toSet()
}
