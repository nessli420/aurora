package com.aurora.music.desktop.auth

import com.aurora.music.R
import com.aurora.music.data.ServerType
import com.aurora.music.data.Session
import com.aurora.music.data.remote.ClientInfo
import com.aurora.music.data.remote.JellyfinClient
import com.aurora.music.data.remote.PlexClient
import com.aurora.music.data.remote.PlexException
import com.aurora.music.data.remote.SubsonicClient
import com.aurora.music.localization.appString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class SignInException(message: String, cause: Throwable) : Exception(message, cause)

class AccountAuthenticator(private val clientInfo: ClientInfo) {

    suspend fun signIn(type: ServerType, server: String, username: String, password: String): Session = try {
        withContext(Dispatchers.IO) {
            when (type) {
                ServerType.SUBSONIC -> subsonicSession(server, username, password)
                ServerType.JELLYFIN -> JellyfinClient.authenticate(server, username, password, clientInfo)
                ServerType.PLEX -> PlexClient.authenticate(server, password.trim(), clientInfo)
                ServerType.SPOTIFY -> throw IllegalStateException(appString(R.string.text_spotify_uses_the_connect_button_not_this_form_17d56a))
                ServerType.YOUTUBE_MUSIC -> throw IllegalStateException(appString(R.string.text_youtube_music_uses_google_sign_in_0ba1e7))
                ServerType.LOCAL -> throw IllegalStateException(appString(R.string.text_local_mode_doesn_t_use_this_form_78c2dc))
                ServerType.EXTENSION -> throw IllegalStateException(appString(R.string.text_enable_this_source_in_advanced_audio_extensions_579558))
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw SignInException(errorMessage(e, type), e)
    }

    suspend fun subsonic(server: String, username: String, password: String): Session = signIn(ServerType.SUBSONIC, server, username, password)

    suspend fun jellyfin(server: String, username: String, password: String): Session = signIn(ServerType.JELLYFIN, server, username, password)

    suspend fun plex(server: String, token: String): Session = signIn(ServerType.PLEX, server, "", token)

    fun local(): Session = LOCAL_SESSION

    fun errorMessage(error: Throwable, type: ServerType? = null): String = when (error) {
        is SignInException -> error.message.orEmpty()
        is UnknownHostException -> appString(R.string.text_server_not_found_check_the_address_24bc0a)
        is ConnectException -> appString(R.string.text_can_t_reach_the_server_1371a9)
        is SocketTimeoutException -> appString(R.string.text_connection_timed_out_647bf9)
        is PlexException -> if (error.statusCode in listOf(401, 403)) appString(R.string.plex_token_rejected)
            else error.message ?: appString(R.string.text_sign_in_failed_49b78c)
        is HttpException -> when {
            type == ServerType.PLEX && error.code() in listOf(401, 403) -> appString(R.string.plex_token_rejected)
            error.code() == 401 -> appString(R.string.text_wrong_username_or_password_85b465)
            else -> appString(R.string.text_server_error_c28ed6, error.code())
        }
        else -> error.message ?: appString(R.string.text_sign_in_failed_49b78c)
    }

    private suspend fun subsonicSession(server: String, username: String, password: String): Session {
        val session = SubsonicClient.buildSession(server, username, password)
        val response = SubsonicClient(session).api.ping().response
        if (!response.isOk) throw IllegalStateException(response.error?.message?.takeIf(String::isNotBlank) ?: appString(R.string.text_login_rejected_3f73ee))
        return session
    }

    companion object {
        val SUPPORTED: Set<ServerType> = setOf(ServerType.SUBSONIC, ServerType.JELLYFIN, ServerType.PLEX, ServerType.LOCAL)
        val LOCAL_SESSION = Session(server = "On this device", username = "Local Library", salt = "", token = "local", type = ServerType.LOCAL)
    }
}
