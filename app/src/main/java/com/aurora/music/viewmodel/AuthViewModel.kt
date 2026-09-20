package com.aurora.music.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurora.music.AuroraApplication
import com.aurora.music.data.ServerType
import com.aurora.music.data.Session
import com.aurora.music.data.remote.JellyfinClient
import com.aurora.music.data.remote.SpotifyAuth
import com.aurora.music.data.remote.SpotifyClient
import com.aurora.music.data.remote.SubsonicClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AuthStep { TYPE, SERVER, CREDENTIALS, SPOTIFY, YOUTUBE_MUSIC }

data class AuthUiState(
    val step: AuthStep = AuthStep.TYPE,
    val type: ServerType = ServerType.SUBSONIC,
    val scheme: String = "http://",
    val host: String = "",
    val username: String = "",
    val password: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val pendingAuthUrl: String? = null,
)

class AuthViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as AuroraApplication).container

    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    private var spotifyVerifier: String? = null

    init {
        // aurora://spotify redirect caught in MainActivity delivers the oauth code here
        viewModelScope.launch { container.spotifyRedirect.collect { code -> completeSpotify(code) } }
    }

    private var spotifyClientId: String? = null

    fun reset() = _state.update { AuthUiState() }

    fun useSaved(session: Session, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            container.applySession(session)
            onDone()
        }
    }

    fun selectType(type: ServerType) {
        if (type == ServerType.YOUTUBE_MUSIC) {
            _state.update { it.copy(type = type, step = AuthStep.YOUTUBE_MUSIC, error = null) }
            return
        }
        if (type == ServerType.SPOTIFY) {
            _state.update { it.copy(type = ServerType.SPOTIFY, step = AuthStep.SPOTIFY, error = null) }
            return
        }
        _state.update { it.copy(type = type, step = AuthStep.SERVER, error = null) }
    }

    fun connectSpotify(clientId: String) {
        val id = clientId.trim()
        if (id.isBlank()) { _state.update { it.copy(error = "Paste your Spotify app's Client ID first.") }; return }
        spotifyClientId = id
        viewModelScope.launch { runCatching { container.settingsStore.setSpotifyClientId(id) } }
        val verifier = SpotifyAuth.newVerifier()
        spotifyVerifier = verifier
        _state.update { it.copy(error = null, pendingAuthUrl = SpotifyAuth.authorizeUrl(id, verifier, "aurora")) }
    }

    fun authUrlOpened() = _state.update { it.copy(pendingAuthUrl = null) }

    fun connectYouTubeMusic(webSession: String) {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            var reference: String? = null
            try {
                val auth = com.aurora.music.data.remote.YouTubeMusicWebSession.decode(webSession)
                val client = com.aurora.music.data.remote.YouTubeMusicClient(session = { auth })
                val (name, handle, photo) = com.aurora.music.data.YouTubeMusicBackend.account(client, auth.dataSyncId)
                reference = withContext(Dispatchers.IO) { container.youtubeMusicCredentials.save(auth.encode()) }
                val session = Session(com.aurora.music.data.remote.YouTubeMusicClient.ORIGIN, name, "", reference,
                    ServerType.YOUTUBE_MUSIC, userId = handle, imageUrl = photo)
                val previous = container.settingsStore.savedSessions.first().filter { it.type == ServerType.YOUTUBE_MUSIC && it.userId == handle }
                container.applySession(session)
                previous.filter { it.token != reference }.forEach { container.youtubeMusicCredentials.remove(it.token) }
                _state.update { it.copy(loading = false, error = null) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                reference?.let { container.youtubeMusicCredentials.remove(it) }
                _state.update { it.copy(loading = false, error = if (e is java.io.IOException) friendlyError(e) else "Could not connect YouTube Music. Please try again.") }
            }
        }
    }

    fun signInLocal() {
        if (_state.value.loading) return
        _state.update { it.copy(type = ServerType.LOCAL, loading = true, error = null) }
        viewModelScope.launch {
            val session = Session(
                server = "On this device",
                username = "Local Library",
                salt = "",
                token = "local",
                type = ServerType.LOCAL,
            )
            container.applySession(session)
            _state.update { it.copy(loading = false, error = null) }
        }
    }

    private fun completeSpotify(code: String) {
        val verifier = spotifyVerifier ?: return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val clientId = spotifyClientId ?: container.spotifyClientId
            val session = withContext(Dispatchers.IO) { SpotifyClient.login(clientId, code, verifier) }
            if (session != null) {
                container.applySession(session)
                _state.update { it.copy(loading = false, error = null) }
            } else {
                _state.update { it.copy(loading = false, error = "Spotify sign-in failed — try again.") }
            }
        }
    }
    fun onScheme(scheme: String) = _state.update { it.copy(scheme = scheme, error = null) }
    fun onHost(v: String) = _state.update { it.copy(host = v.trim(), error = null) }
    fun onUsername(v: String) = _state.update { it.copy(username = v, error = null) }
    fun onPassword(v: String) = _state.update { it.copy(password = v, error = null) }

    fun back() = _state.update {
        when (it.step) {
            AuthStep.CREDENTIALS -> it.copy(step = AuthStep.SERVER, error = null)
            AuthStep.SERVER -> it.copy(step = AuthStep.TYPE, error = null)
            AuthStep.SPOTIFY -> it.copy(step = AuthStep.TYPE, error = null, pendingAuthUrl = null)
            AuthStep.YOUTUBE_MUSIC -> it.copy(step = AuthStep.TYPE, error = null)
            AuthStep.TYPE -> it
        }
    }

    val canContinueServer: Boolean get() = _state.value.host.isNotBlank()
    fun continueToCredentials() {
        if (canContinueServer) _state.update { it.copy(step = AuthStep.CREDENTIALS, error = null) }
    }

    val canSubmit: Boolean
        get() = with(_state.value) { username.isNotBlank() && password.isNotBlank() && !loading }

    fun signIn(onDone: () -> Unit) {
        val s = _state.value
        if (s.loading || !canSubmit) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val server = s.scheme + s.host.trim()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    when (s.type) {
                        ServerType.SUBSONIC -> {
                            val session = SubsonicClient.buildSession(server, s.username, s.password)
                            val resp = SubsonicClient(session).api.ping().response
                            if (!resp.isOk) throw IllegalStateException(resp.error?.message ?: "Login rejected")
                            session
                        }
                        ServerType.JELLYFIN -> JellyfinClient.authenticate(server, s.username, s.password)
                        ServerType.SPOTIFY -> throw IllegalStateException("Spotify uses the connect button, not this form")
                        ServerType.YOUTUBE_MUSIC -> throw IllegalStateException("YouTube Music uses Google sign-in.")
                        ServerType.LOCAL -> throw IllegalStateException("Local mode doesn't use this form")
                        ServerType.EXTENSION -> throw IllegalStateException("Enable this source in Advanced audio → Extensions.")
                    }
                }
            }
            result.onSuccess { session ->
                container.applySession(session)
                _state.update { it.copy(loading = false, error = null, password = "") }
                onDone()
            }.onFailure { e ->
                _state.update { it.copy(loading = false, error = friendlyError(e)) }
            }
        }
    }

    private fun friendlyError(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "Server not found — check the address."
        is java.net.ConnectException -> "Can't reach the server."
        is java.net.SocketTimeoutException -> "Connection timed out."
        is retrofit2.HttpException -> if (e.code() == 401) "Wrong username or password." else "Server error (${e.code()})."
        else -> e.message ?: "Sign-in failed."
    }
}
