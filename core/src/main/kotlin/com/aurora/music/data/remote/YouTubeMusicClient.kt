package com.aurora.music.data.remote

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun JsonObject.string(key: String): String = get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
fun JsonObject.obj(key: String): JsonObject = get(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
fun JsonObject.array(key: String): JsonArray = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
fun json(vararg values: Pair<String, String>) = JsonObject().apply { values.forEach { addProperty(it.first, it.second) } }

fun JsonElement.objects(key: String): List<JsonObject> = buildList {
    fun visit(node: JsonElement) {
        if (node.isJsonObject) node.asJsonObject.entrySet().forEach { (name, value) ->
            if (name == key && value.isJsonObject) add(value.asJsonObject) else visit(value)
        } else if (node.isJsonArray) node.asJsonArray.forEach(::visit)
    }
    visit(this@objects)
}

fun JsonObject.label(): String = string("simpleText").ifBlank {
    array("runs").joinToString("") { if (it.isJsonObject) it.asJsonObject.string("text") else "" }
}

fun interface YouTubeMusicTransport {
    suspend fun request(endpoint: String, body: JsonObject): JsonObject

    suspend fun trackPlayback(url: String, parameters: Map<String, String>) {
        throw IOException("YouTube Music playback reporting is unavailable.")
    }

    // the account's comment pages and comment actions, which live on youtube's own web api
    suspend fun youtube(endpoint: String, body: JsonObject): JsonObject = throw IOException("Sign in to YouTube Music to do this.")
}

// a null session is a signed-out guest on the public web api
class YouTubeMusicClient(
    private val session: (() -> YouTubeMusicWebSession)?,
    http: OkHttpClient = AuroraHttp.base,
) : YouTubeMusicTransport {
    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(30, TimeUnit.SECONDS).build()

    override suspend fun request(endpoint: String, body: JsonObject): JsonObject {
        require(endpoint.matches(Regex("[a-zA-Z_/]+")))
        val auth = session?.invoke()?.validate()
        val version = auth?.clientVersion.orEmpty().ifBlank {
            "1.${LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE)}.01.00"
        }
        return call(ORIGIN, "WEB_REMIX", "67", version, "en", auth, endpoint, body)
    }

    // only the comment calls go to youtube.com, with the same google account
    override suspend fun youtube(endpoint: String, body: JsonObject): JsonObject {
        require(endpoint in YOUTUBE_ENDPOINTS)
        val auth = session?.invoke()?.validate() ?: throw IOException("Sign in to YouTube Music to do this.")
        val version = "2.${LocalDate.now(ZoneOffset.UTC).minusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE)}.00.00"
        return call(YOUTUBE, "WEB", "1", version, java.util.Locale.getDefault().language.ifBlank { "en" }, auth, endpoint, body)
    }

    private suspend fun call(origin: String, client: String, clientId: String, version: String, language: String,
        auth: YouTubeMusicWebSession?, endpoint: String, body: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val payload = body.deepCopy().apply {
            add("context", JsonObject().apply {
                add("client", json("clientName" to client, "clientVersion" to version, "hl" to language).apply {
                    auth?.let { addProperty("visitorData", it.visitorData) }
                })
                add("user", JsonObject().apply {
                    addProperty("lockedSafetyMode", false)
                    (auth?.pageId ?: auth?.dataSyncId)?.takeIf { it.isNotBlank() }?.let { addProperty("onBehalfOfUser", it) }
                })
            })
        }
        val request = authenticated(auth, version, origin, clientId).url("$origin/youtubei/v1/$endpoint?prettyPrint=false")
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        execute(request) { response ->
            val result = runCatching { JsonParser.parseReader(response.body?.charStream()).asJsonObject }
                .getOrElse { throw IOException("YouTube Music returned an unreadable response.") }
            if (result.has("error")) throw IOException("YouTube Music could not complete this request.")
            result
        }
    }

    override suspend fun trackPlayback(url: String, parameters: Map<String, String>) = withContext(Dispatchers.IO) {
        val target = validatedTrackingUrl(url)
        val auth = session?.invoke()?.validate() ?: throw IOException("YouTube Music playback reporting needs an account.")
        val request = authenticated(auth, auth.clientVersion).url(
            target.newBuilder().apply {
                parameters.forEach { (key, value) -> setQueryParameter(key, value) }
            }.build(),
        ).get().build()
        execute(request) { Unit }
    }

    private fun authenticated(auth: YouTubeMusicWebSession?, version: String, origin: String = ORIGIN, clientId: String = "67") = Request.Builder()
        .header("Origin", origin).header("X-Origin", origin).header("Referer", "$origin/")
        .header("User-Agent", auth?.userAgent.orEmpty().ifBlank { USER_AGENT })
        .header("X-YouTube-Client-Name", clientId).header("X-YouTube-Client-Version", version)
        .apply {
            if (auth == null) return@apply
            header("Cookie", auth.cookie).header("Authorization", auth.authorization(System.currentTimeMillis() / 1000, origin))
            header("X-Goog-AuthUser", auth.authUser).header("X-Goog-Visitor-Id", auth.visitorData)
            auth.pageId?.takeIf { it.isNotBlank() }?.let { header("X-Goog-PageId", it) }
        }

    private suspend fun <T> execute(request: Request, read: (Response) -> T): T {
        currentCoroutineContext().ensureActive()
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(
                        IOException("Could not connect to YouTube Music. Please retry."),
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            if (it.code == 401 && session != null) throw com.aurora.music.data.MediaAccountExpiredException()
                            if (it.code == 401) throw IOException("Sign in to YouTube Music to do this.")
                            if (it.code == 403) throw IOException("YouTube Music did not allow this request. Please try again.")
                            if (!it.isSuccessful) throw IOException("YouTube Music request failed (${it.code}). Try again later.")
                            read(it)
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }
    }

    companion object {
        const val ORIGIN = "https://music.youtube.com"
        private const val YOUTUBE = "https://www.youtube.com"
        private val YOUTUBE_ENDPOINTS = setOf("next", "comment/create_comment", "comment/perform_comment_action")
        const val USER_AGENT = "Aurora/1.0 (Android)"

        fun validatedTrackingUrl(value: String): okhttp3.HttpUrl {
            val target = value.toHttpUrlOrNull()
            require(target != null && target.scheme == "https" && target.port == 443 &&
                target.host in setOf("music.youtube.com", "www.youtube.com", "s.youtube.com") &&
                target.username.isEmpty() && target.password.isEmpty() && target.fragment == null &&
                target.encodedPath in setOf("/api/stats/playback", "/api/stats/watchtime")) {
                "YouTube Music returned an invalid playback reporting address."
            }
            // keep account cookies on the music origin
            return ORIGIN.toHttpUrl().newBuilder().encodedPath(target.encodedPath)
                .encodedQuery(target.encodedQuery).build()
        }
    }
}
