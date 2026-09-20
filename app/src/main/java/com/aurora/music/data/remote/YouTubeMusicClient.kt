package com.aurora.music.data.remote

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

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
}

/** Browser credentials are sent only to the fixed YouTube Music API origin. */
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
                    if (auth.dataSyncId.isNotBlank()) addProperty("onBehalfOfUser", auth.dataSyncId)
                })
            })
        }
        val request = Request.Builder().url("$ORIGIN/youtubei/v1/$endpoint?prettyPrint=false")
            .header("Origin", ORIGIN).header("X-Origin", ORIGIN).header("Referer", "$ORIGIN/")
            .header("User-Agent", auth.userAgent.ifBlank { USER_AGENT })
            .header("Cookie", auth.cookie).header("Authorization", auth.authorization(System.currentTimeMillis() / 1000))
            .header("X-Goog-AuthUser", auth.authUser).header("X-Goog-Visitor-Id", auth.visitorData)
            .header("X-YouTube-Client-Name", "67").header("X-YouTube-Client-Version", version)
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            if (response.code == 401) throw com.aurora.music.data.MediaAccountExpiredException()
            if (response.code == 403) throw IOException("YouTube Music did not allow this request. Please try again.")
            if (!response.isSuccessful) throw IOException("YouTube Music request failed (${response.code}). Try again later.")
            val result = runCatching { JsonParser.parseString(response.body?.string()).asJsonObject }
                .getOrElse { throw IOException("YouTube Music returned an unreadable response.") }
            if (result.has("error")) throw IOException("YouTube Music could not complete this request.")
            result
        }
    }

    companion object {
        const val ORIGIN = "https://music.youtube.com"
        const val USER_AGENT = "Aurora/1.0 (Android)"
    }
}
