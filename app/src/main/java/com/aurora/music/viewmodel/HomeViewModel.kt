package com.aurora.music.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurora.music.AuroraApplication
import com.aurora.music.data.HomeData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val loading: Boolean = true,
    val data: HomeData = HomeData(),
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as AuroraApplication).container
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        // Reloads on first emit and whenever offline mode toggles.
        viewModelScope.launch { container.offline.collect { load() } }
        // Reload for the new server after an account switch.
        viewModelScope.launch { container.accountEpoch.drop(1).collect { load() } }
        // Reload when the unified-library settings change (7.1b).
        viewModelScope.launch { container.libraryReload.drop(1).collect { load() } }
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val data = container.repository.home()
            _state.update { it.copy(loading = false, data = data) }
        }
    }
}
