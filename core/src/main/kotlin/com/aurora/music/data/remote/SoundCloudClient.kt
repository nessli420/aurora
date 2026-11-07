package com.aurora.music.data.remote

import com.aurora.music.data.MediaAccountExpiredException
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

fun interface SoundCloudTransport {
    // path is an api-v2 path or an absolute next_href from a previous page
    suspend fun get(path: String): JsonElement

    // form is for the endpoints the web player posts form-encoded, such as comments
    suspend fun send(method: String, path: String, body: JsonObject? = null, form: Map<String, String>? = null): JsonElement =
        throw IOException("SoundCloud changes are unavailable.")

    // the one change that may open soundcloud's bot check for the user to pass; every other change is refused quietly
    suspend fun sendWithCheck(method: String, path: String): JsonElement = send(method, path)
}

class SoundCloudCheckException : IOException("SoundCloud wants to check you're human. Go to Settings → Accounts and choose Verify SoundCloud.")

// soundcloud's bot check turns away writes that don't come from a real browser, so the app can hand them to one
fun interface SoundCloudBrowser {
    suspend fun fetch(origin: String, method: String, url: String, token: String, body: String?, contentType: String, prompt: Boolean): Pair<Int, String>
}

/** SoundCloud's web api: the signed-in web player's oauth token plus the client id its scripts carry; a null session is a guest. */
class SoundCloudClient(
    private val session: (() -> SoundCloudWebSession)?,
    private val onRefreshed: (SoundCloudWebSession) -> Unit = {},
    http: OkHttpClient = OkHttpClient(),
    private val browser: SoundCloudBrowser? = null,
    private val savedClientId: suspend () -> String = { "" },
    private val onClientId: suspend (String) -> Unit = {},
) : SoundCloudTransport {
    private val http = http.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()
    private val idLock = Mutex()
    private val loginLock = Mutex()
    @Volatile private var clientId = ""
    @Volatile private var current: SoundCloudWebSession? = null
    private fun auth() = session?.let { (current ?: it()).validate() }

    private suspend fun clientId(refresh: Boolean): String = idLock.withLock {
        if (!refresh && clientId.isBlank()) clientId = savedClientId()
        if (refresh || clientId.isBlank()) clientId = scrapeClientId().also { onClientId(it) }
        clientId
    }

    private fun scrapeClientId(): String {
        val page = fetch(Request.Builder().url(WEB).header("User-Agent", DESKTOP_UA).build())
        // the id sits in one of the last asset bundles
        val scripts = Regex("""https://a-v2\.sndcdn\.com/assets/[^"]+\.js""").findAll(page).map { it.value }.toList().asReversed()
        for (script in scripts) {
            Regex("""client_id[:=]"?([a-zA-Z0-9]{32})""").find(fetch(Request.Builder().url(script).build()))?.let { return it.groupValues[1] }
        }
        throw IOException("Could not reach SoundCloud. Please retry.")
    }

    private fun fetch(request: Request): String = http.newCall(request).execute().use {
        if (!it.isSuccessful) throw IOException("SoundCloud request failed (${it.code}). Try again later.")
        it.body?.string().orEmpty()
    }

    // a stale client id is refused like a bad login, so retry once with a fresh one before refreshing the login
    override suspend fun get(path: String): JsonElement = send("GET", path)

    override suspend fun send(method: String, path: String, body: JsonObject?, form: Map<String, String>?): JsonElement = send(method, path, body, form, false)

    override suspend fun sendWithCheck(method: String, path: String): JsonElement = send(method, path, null, null, true)

    private suspend fun send(method: String, path: String, body: JsonObject?, form: Map<String, String>?, prompt: Boolean): JsonElement = withContext(Dispatchers.IO) {
        require(method in setOf("GET", "POST", "PUT", "DELETE"))
        val base = url(path)
        auth()?.let { if (it.expiresAt - System.currentTimeMillis() / 1000 < 300) refreshLogin(it.token) }
        val payload = form?.entries?.joinToString("&") { "${java.net.URLEncoder.encode(it.key, "UTF-8")}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
            ?.let { it to FORM } ?: body?.let { it.toString() to JSON }
        attempt(base, false, method, payload, prompt) ?: attempt(base, true, method, payload, prompt)
            ?: (auth()?.let { if (refreshLogin(it.token)) attempt(base, false, method, payload, prompt) else null })
            ?: throw if (session == null) IOException("Sign in to SoundCloud to do this.") else MediaAccountExpiredException()
    }

    // the web player swaps its http-only refresh cookie for a new day-long token the same way
    private suspend fun refreshLogin(stale: String): Boolean = loginLock.withLock {
        val auth = auth() ?: return@withLock false
        if (auth.token != stale) return@withLock true
        if (!auth.canRefresh) return@withLock false
        val request = Request.Builder().url(auth.refreshUrl).post(ByteArray(0).toRequestBody())
            .header("Cookie", auth.cookie).header("Origin", auth.origin).header("Referer", "${auth.origin}/")
            .apply { auth.userAgent.takeIf { it.isNotBlank() }?.let { header("User-Agent", it) } }.build()
        val refreshed = runCatching { http.newCall(request).execute() }.getOrNull()?.use {
            if (it.isSuccessful) auth.refreshed(it.headers("Set-Cookie")) else null
        }?.takeIf { it.token.isNotBlank() && it.token != stale } ?: return@withLock false
        current = refreshed
        onRefreshed(refreshed)
        true
    }

    private suspend fun attempt(base: HttpUrl, refresh: Boolean, method: String, payload: Pair<String, String>?, prompt: Boolean): JsonElement? {
        val auth = auth()
        val origin = auth?.origin ?: WEB
        val url = base.newBuilder().setQueryParameter("client_id", clientId(refresh)).build()
        val (code, text) = if (method != "GET" && browser != null && auth != null) browser.fetch(origin, method, url.toString(), auth.token, payload?.first, payload?.second ?: JSON, prompt)
        else {
            val request = Request.Builder().url(url)
                .header("Accept", "application/json").header("Origin", origin).header("Referer", "$origin/")
                .apply { auth?.let { header("Authorization", "OAuth ${it.token}") } }
                .apply { auth?.userAgent?.takeIf { it.isNotBlank() }?.let { header("User-Agent", it) } }
                .method(method, if (method == "GET" || method == "DELETE" && payload == null) null
                    else (payload?.first ?: "{}").toRequestBody((payload?.second ?: JSON).toMediaType())).build()
            runCatching { http.newCall(request).execute() }.getOrElse { throw IOException("Could not connect to SoundCloud. Please retry.") }
                .use { it.code to it.body?.string().orEmpty() }
        }
        if (method != "GET" && code !in 200..299) com.aurora.music.util.AppLog.d("SoundCloud", "$method ${base.encodedPath} -> $code ${text.take(120)}")
        // a fresh client id won't pass the bot check either, and asking again would open its window a second time
        if (code == 403 && "captcha-delivery.com" in text) throw SoundCloudCheckException()
        if (code == 401 || (code == 403 && !refresh)) return null
        if (code == 403) throw IOException("SoundCloud did not allow this request. Please try again.")
        if (code == 404) throw IOException("SoundCloud could not find this item.")
        if (code !in 200..299) throw IOException("SoundCloud request failed ($code). Try again later.")
        if (text.isBlank()) return JsonNull.INSTANCE
        return runCatching { JsonParser.parseString(text) }.getOrElse { throw IOException("SoundCloud returned an unreadable response.") }
    }

    companion object {
        const val WEB = "https://soundcloud.com"
        const val API = "https://api-v2.soundcloud.com"
        private const val JSON = "application/json"
        private const val FORM = "application/x-www-form-urlencoded; charset=UTF-8"
        // the mobile user agent is redirected to m.soundcloud.com, whose page carries no asset list
        private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

        // next_href links are followed only on the api host
        fun url(path: String): HttpUrl {
            if (!path.startsWith("https://")) {
                require(path.startsWith("/") && ".." !in path)
                return "$API$path".toHttpUrl()
            }
            val url = path.toHttpUrlOrNull()
            require(url != null && url.scheme == "https" && url.host == "api-v2.soundcloud.com" && url.port == 443)
            return url
        }
    }
}
