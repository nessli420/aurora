package com.aurora.music.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurora.music.AuroraApplication
import com.aurora.music.data.RadioStation
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RadioUiState(
    val loading: Boolean = false,
    val popular: List<RadioStation> = emptyList(),
    val results: List<RadioStation> = emptyList(),
    val query: String = "",
    val activeTag: String = "",
    val failed: Boolean = false,
)

/** Drives the internet-radio screen: the Radio-Browser directory + the user's saved stations. */
class RadioViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as AuroraApplication).container

    private val _state = MutableStateFlow(RadioUiState())
    val state: StateFlow<RadioUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    /** Favourited + custom stations, persisted in settings. */
    val favorites: StateFlow<List<RadioStation>> =
        container.settingsStore.radioFavorites.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init { loadPopular() }

    fun loadPopular() {
        _state.update { it.copy(loading = true, failed = false, query = "", activeTag = "") }
        viewModelScope.launch {
            val list = container.radioBrowser.topStations()
            _state.update { it.copy(popular = list.orEmpty(), results = emptyList(), loading = false, failed = list == null) }
        }
    }

    /** Debounced free-text search (fires ~350ms after the user stops typing). */
    fun search(query: String) {
        val q = query.trim()
        searchJob?.cancel()
        if (q.isBlank()) { clearSearch(); return }
        _state.update { it.copy(loading = true, failed = false, query = q, activeTag = "") }
        searchJob = viewModelScope.launch {
            delay(350)
            val list = container.radioBrowser.search(q)
            _state.update { it.copy(results = list.orEmpty(), loading = false, failed = list == null) }
        }
    }

    fun byTag(tag: String) {
        _state.update { it.copy(loading = true, failed = false, query = "", activeTag = tag) }
        viewModelScope.launch {
            val list = container.radioBrowser.byTag(tag)
            _state.update { it.copy(results = list.orEmpty(), loading = false, failed = list == null) }
        }
    }

    fun clearSearch() {
        _state.update { it.copy(query = "", activeTag = "", results = emptyList(), failed = false) }
    }

    fun isFavorite(uuid: String): Boolean = favorites.value.any { it.uuid == uuid }

    fun toggleFavorite(station: RadioStation) {
        viewModelScope.launch {
            if (isFavorite(station.uuid)) container.settingsStore.deleteRadioStation(station.uuid)
            else container.settingsStore.saveRadioStation(station)
        }
    }

    /** Add a hand-entered stream URL as a custom (always-favourited) station. */
    fun addCustom(name: String, url: String) {
        val clean = url.trim()
        if (clean.isBlank()) return
        val station = RadioStation(
            uuid = "custom:${clean.hashCode()}",
            name = name.trim().ifBlank { clean.toHttpHostOrUrl() },
            streamUrl = clean,
            tags = "Custom",
            custom = true,
        )
        viewModelScope.launch { container.settingsStore.saveRadioStation(station) }
    }

    /** Credit the directory with a play (best-effort, ignored for custom stations). */
    fun registerPlay(station: RadioStation) {
        viewModelScope.launch { runCatching { container.radioBrowser.registerClick(station.uuid) } }
    }

    private fun String.toHttpHostOrUrl(): String =
        runCatching { android.net.Uri.parse(this).host ?: this }.getOrDefault(this)
}
