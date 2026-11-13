package com.aurora.music.data.remote

import com.aurora.music.data.ServerType
import com.aurora.music.data.Session
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

// token in the Authorization header on api calls, ApiKey query param on stream/image urls; 12.0+ rejects X-Emby-Authorization/api_key by default
class JellyfinClient(val session: Session, clientInfo: ClientInfo) {

    private val baseUrl: String = session.server.trimEnd('/')
    val userId: String = session.userId
    private val token: String = session.token

    val deviceId: String = session.clientToken.orEmpty().ifBlank {
        "aurora-${UUID.nameUUIDFromBytes("$baseUrl\n$userId\n$token".toByteArray(Charsets.UTF_8))}"
    }

    val api: JellyfinApi = buildApi(baseUrl, token, deviceId, clientInfo)

    fun coverArtUrl(itemId: String?, size: Int = 600): String {
        if (itemId.isNullOrBlank()) return ""
        return "$baseUrl/Items/${enc(itemId)}/Images/Primary?fillHeight=$size&fillWidth=$size&quality=90&ApiKey=${enc(token)}"
    }

    // lossless or no bitrate cap streams the original file untouched
    fun streamUrl(songId: String, maxBitrate: Int, lossless: Boolean): String =
        if (lossless || maxBitrate <= 0) {
            "$baseUrl/Audio/${enc(songId)}/stream?static=true&DeviceId=${enc(deviceId)}&ApiKey=${enc(token)}"
        } else {
            "$baseUrl/Audio/${enc(songId)}/universal?UserId=${enc(userId)}&DeviceId=${enc(deviceId)}" +
                "&MaxStreamingBitrate=${maxBitrate * 1000}&Container=mp3&AudioCodec=mp3&ApiKey=${enc(token)}"
        }

    companion object {
        private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

        private fun authHeader(token: String, deviceId: String, clientInfo: ClientInfo): String = buildString {
            append("MediaBrowser Client=\"${clientInfo.product}\", Device=\"${clientInfo.device}\", DeviceId=\"$deviceId\", Version=\"${clientInfo.version}\"")
            if (token.isNotBlank()) append(", Token=\"$token\"")
        }

        private fun buildApi(server: String, token: String, deviceId: String, clientInfo: ClientInfo): JellyfinApi {
            val interceptor = Interceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("Authorization", authHeader(token, deviceId, clientInfo))
                    .build()
                chain.proceed(req)
            }
            val ok = AuroraHttp.base.newBuilder()
                .addInterceptor(interceptor)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            return Retrofit.Builder()
                .baseUrl("${server.trimEnd('/')}/")
                .client(ok)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(JellyfinApi::class.java)
        }

        fun normalizeServer(raw: String): String {
            var s = raw.trim().trimEnd('/')
            if (s.isEmpty()) return s
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
            return s.toHttpUrlOrNull()?.toString()?.trimEnd('/') ?: s
        }

        suspend fun authenticate(server: String, username: String, password: String, clientInfo: ClientInfo): Session {
            val norm = normalizeServer(server)
            val deviceId = "aurora-${UUID.randomUUID()}"
            val api = buildApi(norm, "", deviceId, clientInfo)
            return session(norm, api.authenticate(AuthRequest(username.trim(), password)), username.trim(), deviceId)
        }

        // sso accounts have no password, so the user approves the code from a jellyfin session they're already signed in to
        suspend fun quickConnect(server: String, clientInfo: ClientInfo, pollMillis: Long = 3_000, onCode: (String) -> Unit): Session {
            val norm = normalizeServer(server)
            val deviceId = "aurora-${UUID.randomUUID()}"
            val api = buildApi(norm, "", deviceId, clientInfo)
            if (!api.quickConnectEnabled()) throw QuickConnectException(expired = false)
            val request = api.quickConnectInitiate()
            val secret = request.Secret ?: throw IllegalStateException("No quick connect secret returned")
            onCode(request.Code.orEmpty())
            while (true) {
                val status = try {
                    api.quickConnectStatus(secret)
                } catch (e: retrofit2.HttpException) {
                    throw if (e.code() == 404) QuickConnectException(expired = true) else e
                }
                if (status.Authenticated) break
                delay(pollMillis)
            }
            return session(norm, api.authenticateWithQuickConnect(QuickConnectRequest(secret)), "", deviceId)
        }

        private fun session(server: String, res: AuthResult, username: String, deviceId: String): Session {
            val token = res.AccessToken ?: throw IllegalStateException("Login rejected")
            val uid = res.User?.Id ?: throw IllegalStateException("No user id returned")
            return Session(
                server = server,
                username = res.User?.Name ?: username,
                salt = "",
                token = token,
                type = ServerType.JELLYFIN,
                userId = uid,
                clientToken = deviceId,
            )
        }
    }
}

// expired = the code timed out (jellyfin keeps it 10 minutes), otherwise quick connect is off on the server
class QuickConnectException(val expired: Boolean) : Exception(if (expired) "Quick Connect code expired" else "Quick Connect is disabled")
