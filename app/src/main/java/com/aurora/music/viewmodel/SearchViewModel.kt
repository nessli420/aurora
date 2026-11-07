package com.aurora.music.viewmodel

import com.aurora.music.localization.appString
import com.aurora.music.R

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurora.music.AuroraApplication
import com.aurora.music.data.SearchResults
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val loading: Boolean = false,
    val results: SearchResults = SearchResults(),
    val sources: List<com.aurora.music.data.SearchSourceChoice> = emptyList(),
    val selectedSource: String = "discovery",
    val error: String? = null,
)

class SearchViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as AuroraApplication).container
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    val recentSearches: StateFlow<List<String>> =
        container.settingsStore.recentSearches.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            container.offline.collect { refreshSources() }
        }
        viewModelScope.launch { container.accountEpoch.drop(1).collect { refreshSources() } }
        viewModelScope.launch { container.libraryReload.drop(1).collect { refreshSources() } }
    }

    private fun refreshSources() {
        val sources = container.repository.searchSources
        // a missing catalogue falls back to the next one, so a spotify-only merge searches spotify
        // another account's results must not stay tappable while the new search runs
        _state.update { it.copy(sources = sources, results = SearchResults(), selectedSource = it.selectedSource.takeIf { id -> sources.any { s -> s.id == id } }
            ?: sources.firstOrNull { s -> s.id != "library" }?.id ?: "discovery") }
        onQuery(_state.value.query)
    }

    fun selectSource(id: String) {
        if (id == _state.value.selectedSource || _state.value.sources.none { it.id == id }) return
        _state.update { it.copy(selectedSource = id, results = SearchResults()) }
        onQuery(_state.value.query)
    }

    // a spoken search looks in your own library first and lands in recents like a typed one
    fun voiceSearch(query: String) {
        if (_state.value.sources.any { it.id == "library" }) _state.update { it.copy(selectedSource = "library", results = SearchResults()) }
        onQuery(query)
        commit()
    }

    fun commit() {
        viewModelScope.launch { container.settingsStore.addRecentSearch(_state.value.query) }
    }

    fun removeRecent(query: String) {
        viewModelScope.launch { container.settingsStore.removeRecentSearch(query) }
    }

    fun clearRecents() {
        viewModelScope.launch { container.settingsStore.clearRecentSearches() }
    }

    fun onQuery(q: String) {
        // old results stay up while typing so the list isn't torn down per keystroke
        _state.update { it.copy(query = q, loading = q.isNotBlank(), error = null) }
        searchJob?.cancel()
        if (q.isBlank()) {
            _state.update { it.copy(results = SearchResults(), loading = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(300)
            try {
                val r = container.repository.search(q, _state.value.selectedSource)
                ensureActive()
                _state.update { it.copy(loading = false, results = r) }
                val enriched = container.repository.enrichSearchDurations(r)
                ensureActive()
                _state.update { it.copy(results = enriched) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(loading = false, error = appString(R.string.text_could_not_search_this_source_try_again_a811fe)) } }
        }
    }
}
