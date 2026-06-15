package com.aurora.music.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurora.music.AuroraApplication
import com.aurora.music.data.SearchResults
import kotlinx.coroutines.Job
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
            container.offline.collect { if (_state.value.query.isNotBlank()) onQuery(_state.value.query) }
        }
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
        _state.update { it.copy(query = q) }
        searchJob?.cancel()
        if (q.isBlank()) {
            _state.update { it.copy(results = SearchResults(), loading = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(300)
            _state.update { it.copy(loading = true) }
            val r = container.repository.search(q)
            _state.update { it.copy(loading = false, results = r) }
        }
    }
}
