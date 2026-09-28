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

internal fun JsonObject.string(key: String): String = get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
internal fun JsonObject.obj(key: String): JsonObject = get(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
internal fun JsonObject.array(key: String): JsonArray = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
internal fun json(vararg values: Pair<String, String>) = JsonObject().apply { values.forEach { addProperty(it.first, it.second) } }

internal fun JsonElement.objects(key: String): List<JsonObject> = buildList {
    fun visit(node: JsonElement) {
        if (node.isJsonObject) node.asJsonObject.entrySet().forEach { (name, value) ->
            if (name == key && value.isJsonObject) add(value.asJsonObject) else visit(value)
        } else if (node.isJsonArray) node.asJsonArray.forEach(::visit)
    }
    visit(this@objects)
}

internal fun JsonObject.label(): String = string("simpleText").ifBlank {
    array("runs").joinToString("") { if (it.isJsonObject) it.asJsonObject.string("text") else "" }
}

fun interface YouTubeMusicTransport {
    suspend fun request(endpoint: String, body: JsonObject): JsonObject

    suspend fun trackPlayback(url: String, parameters: Map<String, String>) {
        throw IOException("YouTube Music playback reporting is unavailable.")
    }
}

class YouTubeMusicClient(
    private val session: () -> YouTubeMusicWebSession,
    http: OkHttpClient = OkHttpClient(),
) : YouTubeMusicTransport {
    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(30, TimeUnit.SECONDS).build()

    override suspend fun request(endpoint: String, body: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        require(endpoint.matches(Regex("[a-zA-Z_/]+")))
        val auth = session().validate()
        val version = auth.clientVersion.ifBlank {
            "1.${LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE)}.01.00"
        }
        val payload = body.deepCopy().apply {
            add("context", JsonObject().apply {
                add("client", json("clientName" to "WEB_REMIX", "clientVersion" to version,
                    "hl" to "en", "visitorData" to auth.visitorData))
                add("user", JsonObject().apply {
                    addProperty("lockedSafetyMode", false)
                    (auth.pageId ?: auth.dataSyncId).takeIf { it.isNotBlank() }?.let { addProperty("onBehalfOfUser", it) }
                })
            })
        }
        val request = authenticated(auth, version).url("$ORIGIN/youtubei/v1/$endpoint?prettyPrint=false")
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        execute(request) { response ->
            val result = runCatching { JsonParser.parseString(response.body?.string()).asJsonObject }
                .getOrElse { throw IOException("YouTube Music returned an unreadable response.") }
            if (result.has("error")) throw IOException("YouTube Music could not complete this request.")
            result
        }
    }

    override suspend fun trackPlayback(url: String, parameters: Map<String, String>) = withContext(Dispatchers.IO) {
        val target = validatedTrackingUrl(url)
        val auth = session().validate()
        val request = authenticated(auth, auth.clientVersion).url(
            target.newBuilder().apply {
                parameters.forEach { (key, value) -> setQueryParameter(key, value) }
            }.build(),
        ).get().build()
        execute(request) { Unit }
    }

    private fun authenticated(auth: YouTubeMusicWebSession, version: String) = Request.Builder()
        .header("Origin", ORIGIN).header("X-Origin", ORIGIN).header("Referer", "$ORIGIN/")
        .header("User-Agent", auth.userAgent.ifBlank { USER_AGENT })
        .header("Cookie", auth.cookie).header("Authorization", auth.authorization(System.currentTimeMillis() / 1000))
        .header("X-Goog-AuthUser", auth.authUser).header("X-Goog-Visitor-Id", auth.visitorData)
        .header("X-YouTube-Client-Name", "67").header("X-YouTube-Client-Version", version)
        .apply { auth.pageId?.takeIf { it.isNotBlank() }?.let { header("X-Goog-PageId", it) } }

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
                            if (it.code == 401) throw com.aurora.music.data.MediaAccountExpiredException()
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
        const val USER_AGENT = "Aurora/1.0 (Android)"

        internal fun validatedTrackingUrl(value: String): okhttp3.HttpUrl {
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
