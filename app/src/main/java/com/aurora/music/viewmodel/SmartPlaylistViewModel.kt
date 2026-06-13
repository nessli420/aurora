package com.aurora.music.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurora.music.AuroraApplication
import com.aurora.music.data.SmartPlaylist
import com.aurora.music.data.SmartRule
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Edits one smart playlist (a new one when [load] gets a blank id). */
class SmartPlaylistViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as AuroraApplication).container
    private val _state = MutableStateFlow(SmartPlaylist())
    val state: StateFlow<SmartPlaylist> = _state.asStateFlow()

    private var loadedId: String? = null

    fun load(id: String) {
        if (id == loadedId) return
        loadedId = id
        viewModelScope.launch {
            _state.value = if (id.isBlank()) {
                SmartPlaylist(id = "smart-" + UUID.randomUUID().toString().take(8), name = "", rules = listOf(SmartRule()))
            } else {
                container.settingsStore.smartPlaylists.first().firstOrNull { it.id == id }
                    ?: SmartPlaylist(id = id, rules = listOf(SmartRule()))
            }
        }
    }

    fun update(transform: (SmartPlaylist) -> SmartPlaylist) = _state.update(transform)

    fun save(onDone: () -> Unit) {
        val sp = _state.value
        if (sp.id.isNullOrBlank()) return
        viewModelScope.launch {
            container.settingsStore.saveSmartPlaylist(sp.copy(name = sp.name?.trim()?.ifBlank { "Smart playlist" }))
            onDone()
        }
    }
}
