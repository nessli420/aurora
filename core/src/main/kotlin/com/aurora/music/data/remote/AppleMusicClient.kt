package com.aurora.music.data.remote

import com.aurora.music.data.MediaAccountExpiredException
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit

// apple keeps a library, history, ratings and lyrics only for subscribers
class AppleMusicSubscriptionException : IOException("Apple Music needs a subscription for this.")

interface AppleMusicTransport {
    // a /v1/ path on apple's api, or a next link from an earlier page
    suspend fun get(path: String): JsonObject
    suspend fun storefront(): String
    // a change to the signed-in library; apple answers most with an empty body
    suspend fun send(method: String, path: String, body: JsonObject? = null): JsonObject = throw IOException("Apple Music changes are unavailable.")
}

/** The music.apple.com login: its media-user-token cookie and the account's storefront; kept encrypted, never logged. */
class AppleMusicWebSession(val userToken: String, val storefront: String) {
    // a short stand-in for the account when apple shares no profile id
    val fingerprint: String get() = MessageDigest.getInstance("SHA-256").digest(userToken.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    fun validate(): AppleMusicWebSession {
        if (userToken.isBlank() || userToken.any { it == '\r' || it == '\n' || it == ';' } || !storefront.matches(STOREFRONT))
            throw IOException("Finish signing in to Apple Music, then try connecting again.")
        return this
    }

    fun encode(): String = JsonObject().apply { addProperty("userToken", userToken); addProperty("storefront", storefront) }.toString()

    companion object {
        const val LOGIN_URL = "https://music.apple.com/login"
        val STOREFRONT = Regex("[a-z]{2}")

        // the web player keeps its login in a readable cookie and the account's country in itua
        fun fromCookies(cookie: String): AppleMusicWebSession? {
            val jar = cookie.split(';').mapNotNull {
                val parts = it.trim().split('=', limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }.toMap()
            val token = jar["media-user-token"]?.takeIf { it.isNotBlank() } ?: return null
            return AppleMusicWebSession(token, jar["itua"].orEmpty().lowercase().takeIf { it.matches(STOREFRONT) } ?: "us")
        }

        fun decode(value: String): AppleMusicWebSession = try {
            val obj = JsonParser.parseString(value).asJsonObject
            AppleMusicWebSession(obj.string("userToken"), obj.string("storefront")).validate()
        } catch (_: Exception) {
            throw IOException("Reconnect your Apple Music account in Settings → Accounts.")
        }
    }
}

/** Apple Music's own web player api: the token its page ships with, plus the signed-in cookie; a null session is a guest. */
class AppleMusicClient(
    private val session: (() -> AppleMusicWebSession)?,
    http: OkHttpClient = OkHttpClient(),
    private val savedToken: suspend () -> String = { "" },
    private val onToken: suspend (String) -> Unit = {},
    private val country: () -> String = { Locale.getDefault().country },
) : AppleMusicTransport {
    private val http = http.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()
    private val tokenLock = Mutex()
    @Volatile private var token = ""
    @Volatile private var guestStorefront = ""
    @Volatile private var language: String? = null

    // a token another call already replaced isn't fetched again
    private suspend fun token(stale: String?): String = tokenLock.withLock {
        if (token.isBlank()) token = savedToken()
        if (token.isBlank() || token == stale) token = scrapeToken().also { onToken(it) }
        token
    }

    private fun scrapeToken(): String {
        val page = text("$WEB/us/new")
        Regex("""/assets/index[^"'\s]*\.js""").findAll(page).map { it.value }.distinct().forEach { bundle ->
            webPlayToken(text(WEB + bundle))?.let { return it }
        }
        throw IOException("Could not reach Apple Music. Please retry.")
    }

    private fun text(url: String): String = http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use {
        if (!it.isSuccessful) throw IOException("Could not reach Apple Music (${it.code}). Please retry.")
        it.body?.string().orEmpty()
    }

    override suspend fun storefront(): String {
        session?.let { return it().validate().storefront }
        if (guestStorefront.isBlank()) {
            val local = country().lowercase()
            guestStorefront = local.takeIf { it.matches(AppleMusicWebSession.STOREFRONT) && runCatching { request("/v1/storefronts/$it") }.isSuccess } ?: "us"
        }
        return guestStorefront
    }

    // apple answers in the storefront's own language unless asked for another it carries, such as en-GB in the netherlands
    override suspend fun get(path: String): JsonObject {
        val tag = language ?: runCatching {
            val attributes = request("/v1/storefronts/${storefront()}").array("data").first().asJsonObject.obj("attributes")
            languageFor(attributes.array("supportedLanguageTags").map { it.asString }, Locale.getDefault()).orEmpty()
        }.getOrDefault("").also { language = it }
        return request(if (tag.isBlank() || LANGUAGE.containsMatchIn(path)) path else path + (if ('?' in path) '&' else '?') + "l=" + tag)
    }

    // the web player sends its changes without a language
    override suspend fun send(method: String, path: String, body: JsonObject?): JsonObject {
        require(method in setOf("POST", "PATCH", "PUT", "DELETE") && session != null)
        return request(path, method, body)
    }

    private suspend fun request(path: String, method: String = "GET", payload: JsonObject? = null): JsonObject = withContext(Dispatchers.IO) {
        require(path.startsWith("/v1/"))
        val user = session?.invoke()?.validate()
        var dev = token(null)
        var renewed = false
        repeat(4) { attempt ->
            val request = Request.Builder().url((API + path).toHttpUrl())
                .header("Authorization", "Bearer $dev").header("Origin", WEB).header("Referer", "$WEB/")
                .header("Accept", "application/json").header("User-Agent", UA)
                .apply { user?.let { header("media-user-token", it.userToken) } }
                .method(method, if (method == "GET" || method == "DELETE" && payload == null) null else (payload ?: JsonObject()).toString().toRequestBody(JSON)).build()
            val (code, body) = try {
                http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { throw IOException("Could not connect to Apple Music. Please retry.") }
            when {
                code in 200..299 -> return@withContext if (body.isBlank()) JsonObject() else runCatching { JsonParser.parseString(body).asJsonObject }
                    .getOrElse { throw IOException("Apple Music returned an unreadable response.") }
                // the page's token changes with apple's releases; a signed-in 401 after a fresh one is the login
                code == 401 && !renewed -> { dev = token(dev); renewed = true }
                code == 401 -> throw if (user != null) MediaAccountExpiredException() else IOException("Apple Music did not allow this request.")
                // only a login apple no longer accepts is "invalid authentication"; other refusals leave the account alone
                code == 403 -> throw if (user != null && "Invalid authentication" in body) MediaAccountExpiredException() else IOException("Apple Music did not allow this request.")
                code == 400 && "\"40015\"" in body -> throw AppleMusicSubscriptionException()
                code == 404 -> throw java.io.FileNotFoundException("Apple Music could not find this item.")
                code == 429 -> delay(2_000L * (attempt + 1))
                else -> throw IOException("Apple Music request failed ($code). Try again later.")
            }
        }
        throw IOException("Apple Music is busy. Please retry.")
    }

    companion object {
        const val WEB = "https://music.apple.com"
        const val API = "https://amp-api.music.apple.com"
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
        private val JWT = Regex("""eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""")
        private val LANGUAGE = Regex("[?&]l=")
        private val JSON = "application/json".toMediaType()

        fun languageFor(supported: List<String>, locale: Locale): String? =
            supported.firstOrNull { it.equals(locale.toLanguageTag(), ignoreCase = true) }
                ?: supported.firstOrNull { it.substringBefore('-').equals(locale.language, ignoreCase = true) }

        // the bundle carries a few tokens; the web player's own is the one apple keys to it
        fun webPlayToken(script: String): String? {
            val all = JWT.findAll(script).map { it.value }.toList()
            return all.firstOrNull { jwt ->
                runCatching { String(Base64.getUrlDecoder().decode(jwt.substringBefore('.'))) }.getOrDefault("").contains("WebPlayKid")
            } ?: all.firstOrNull()
        }
    }
}
