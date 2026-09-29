package com.aurora.music.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurora.music.data.ServerType
import com.aurora.music.data.Session
import com.aurora.music.desktop.DesktopContainer
import com.aurora.music.desktop.auth.AccountAuthenticator
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

enum class AuthStep { TYPE, SERVER, CREDENTIALS, FOLDERS }

data class AuthUiState(
    val step: AuthStep = AuthStep.TYPE,
    val type: ServerType = ServerType.SUBSONIC,
    val scheme: String = "http://",
    val host: String = "",
    val username: String = "",
    val password: String = "",
    val loading: Boolean = false,
    val error: String? = null,
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
        _state.update { it.copy(type = type, step = if (type == ServerType.LOCAL) AuthStep.FOLDERS else AuthStep.SERVER, error = null) }
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
            AuthStep.SERVER, AuthStep.FOLDERS -> it.copy(step = AuthStep.TYPE, error = null)
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
