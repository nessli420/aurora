package com.aurora.music.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurora.music.data.ServerType
import com.aurora.music.data.Session
import com.aurora.music.desktop.DesktopContainer
import com.aurora.music.desktop.auth.AccountAuthenticator
import com.aurora.music.desktop.platform.WebCredentials
import com.aurora.music.R
import com.aurora.music.data.SoundCloudBackend
import com.aurora.music.data.SpotifyWebBackend
import com.aurora.music.data.SpotifyWebProfile
import com.aurora.music.data.YouTubeMusicBackend
import com.aurora.music.data.remote.SoundCloudClient
import com.aurora.music.data.remote.SoundCloudWebSession
import com.aurora.music.data.remote.YouTubeMusicClient
import com.aurora.music.data.remote.YouTubeMusicWebSession
import com.aurora.music.localization.appString
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AuthStep { TYPE, SERVER, CREDENTIALS, FOLDERS, YOUTUBE_MUSIC, SPOTIFY, SOUNDCLOUD }

data class AuthUiState(
    val step: AuthStep = AuthStep.TYPE,
    val type: ServerType = ServerType.SUBSONIC,
    val scheme: String = "http://",
    val host: String = "",
    val username: String = "",
    val password: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val quickConnectCode: String? = null,
)

class AuthViewModel(private val container: DesktopContainer) : ViewModel() {

    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    val musicFolders: StateFlow<List<String>> = container.desktopSettings.musicFolders
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var signInJob: Job? = null

    fun reset() {
        signInJob?.cancel()
        signInJob = null
        _state.update { AuthUiState() }
    }

    fun useSaved(session: Session, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            container.applySession(session)
            onDone()
        }
    }

    fun selectType(type: ServerType) {
        if (type !in AccountAuthenticator.SUPPORTED) return
        _state.update { if (it.type != type) it.copy(password = "") else it }
        val step = when (type) {
            ServerType.LOCAL -> AuthStep.FOLDERS
            ServerType.YOUTUBE_MUSIC -> AuthStep.YOUTUBE_MUSIC
            ServerType.SPOTIFY -> AuthStep.SPOTIFY
            ServerType.SOUNDCLOUD -> AuthStep.SOUNDCLOUD
            else -> AuthStep.SERVER
        }
        _state.update { it.copy(type = type, step = step, error = null) }
    }

    fun connectYouTubeMusic(webSession: String, onDone: () -> Unit = {}) = connectWeb(container.youtubeMusicCredentials, onDone,
        failure = R.string.text_could_not_connect_youtube_music_please_try_again_e327a1) { save ->
        val auth = YouTubeMusicWebSession.decode(webSession)
        val client = YouTubeMusicClient(session = { auth }, http = container.http)
        val (name, handle, photo) = YouTubeMusicBackend.account(client, auth.dataSyncId)
        Session(YouTubeMusicClient.ORIGIN, name, "", save(auth.encode()), ServerType.YOUTUBE_MUSIC, userId = handle, imageUrl = photo)
    }

    fun connectSoundCloud(webSession: String, onDone: () -> Unit = {}) = connectWeb(container.soundcloudCredentials, onDone,
        failure = R.string.soundcloud_connect_failed) { save ->
        var auth = SoundCloudWebSession.decode(webSession)
        val client = SoundCloudClient({ auth }, { auth = it }, container.http)
        val (id, name, avatar) = SoundCloudBackend.profile(client)
        Session(SoundCloudClient.WEB, name, "", save(auth.encode()), ServerType.SOUNDCLOUD, userId = id, imageUrl = avatar)
    }

    fun continueAsGuest(onDone: () -> Unit = {}) {
        val type = _state.value.type
        if (_state.value.loading || !type.supportsGuest) return
        viewModelScope.launch {
            container.applySession(Session.guest(type, appString(R.string.guest_name)))
            onDone()
        }
    }

    fun connectSpotifyWeb(sessionCookie: String, profile: SpotifyWebProfile?, onDone: () -> Unit = {}) {
        if (sessionCookie.isBlank() || profile == null) {
            _state.update { it.copy(loading = false, error = appString(R.string.spotify_web_failed)) }
            return
        }
        connectWeb(container.spotifyCredentials, onDone, failure = R.string.spotify_web_failed) { save ->
            SpotifyWebBackend.session(profile, save(sessionCookie))
        }
    }

    // signing the same account in again replaces its saved login instead of leaving the old one behind
    private fun connectWeb(credentials: WebCredentials, onDone: () -> Unit, failure: Int, build: suspend (save: (String) -> String) -> Session) {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        signInJob = viewModelScope.launch {
            var reference: String? = null
            try {
                val session = build { value -> credentials.save(value).also { reference = it } }
                val previous = container.settingsStore.savedSessions.first()
                    .filter { it.type == session.type && it.userId == session.userId && it.salt == session.salt }
                container.applySession(session)
                previous.filter { it.token != reference }.forEach { credentials.remove(it.token) }
                _state.update { it.copy(loading = false, error = null) }
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reference?.let(credentials::remove)
                _state.update { it.copy(loading = false, error = if (e is java.io.IOException) container.authenticator.errorMessage(e, it.type) else appString(failure)) }
            }
        }
    }

    fun quickConnect(onDone: () -> Unit) {
        val s = _state.value
        if (s.loading || s.host.isBlank()) return
        _state.update { it.copy(loading = true, error = null) }
        signInJob = viewModelScope.launch {
            try {
                val session = container.authenticator.jellyfinQuickConnect(s.scheme + s.host.trim()) { code -> _state.update { it.copy(quickConnectCode = code) } }
                ensureActive()
                container.applySession(session)
                ensureActive()
                _state.update { it.copy(loading = false, error = null, quickConnectCode = null) }
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, quickConnectCode = null, error = container.authenticator.errorMessage(e, s.type)) }
            }
        }
    }

    fun cancelQuickConnect() {
        signInJob?.cancel()
        _state.update { it.copy(loading = false, quickConnectCode = null) }
    }

    fun addMusicFolder(path: String) {
        viewModelScope.launch { container.desktopSettings.addMusicFolder(path) }
    }

    fun removeMusicFolder(path: String) {
        viewModelScope.launch { container.desktopSettings.removeMusicFolder(path) }
    }

    fun signInLocal() {
        if (_state.value.loading) return
        _state.update { it.copy(type = ServerType.LOCAL, loading = true, error = null) }
        viewModelScope.launch {
            container.applySession(container.authenticator.local())
            _state.update { it.copy(loading = false, error = null) }
        }
    }

    fun onScheme(scheme: String) = _state.update { it.copy(scheme = scheme, error = null) }
    fun onHost(v: String) = _state.update {
        val value = v.trim()
        val scheme = if (it.type == ServerType.PLEX) {
            listOf("https://", "http://").firstOrNull { prefix -> value.startsWith(prefix, ignoreCase = true) }
        } else null
        it.copy(scheme = scheme ?: it.scheme, host = if (scheme != null) value.drop(scheme.length) else value, error = null)
    }
    fun onUsername(v: String) = _state.update { it.copy(username = v, error = null) }
    fun onPassword(v: String) = _state.update { it.copy(password = v, error = null) }

    fun back() = _state.update {
        when (it.step) {
            AuthStep.CREDENTIALS -> it.copy(step = AuthStep.SERVER, error = null)
            AuthStep.SERVER, AuthStep.FOLDERS, AuthStep.YOUTUBE_MUSIC, AuthStep.SPOTIFY, AuthStep.SOUNDCLOUD -> it.copy(step = AuthStep.TYPE, error = null)
            AuthStep.TYPE -> it
        }
    }

    val canContinueServer: Boolean get() = _state.value.host.isNotBlank()
    fun continueToCredentials() {
        if (canContinueServer) _state.update { it.copy(step = AuthStep.CREDENTIALS, error = null) }
    }

    val canSubmit: Boolean
        get() = with(_state.value) {
            (type == ServerType.PLEX || username.isNotBlank()) && password.isNotBlank() && !loading
        }

    fun signIn(onDone: () -> Unit) {
        val s = _state.value
        if (s.loading || !canSubmit) return
        _state.update { it.copy(loading = true, error = null) }
        signInJob = viewModelScope.launch {
            try {
                val session = container.authenticator.signIn(s.type, s.scheme + s.host.trim(), s.username, s.password)
                ensureActive()
                container.applySession(session)
                ensureActive()
                _state.update { it.copy(loading = false, error = null, password = "") }
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = container.authenticator.errorMessage(e, s.type)) }
            }
        }
    }
}
