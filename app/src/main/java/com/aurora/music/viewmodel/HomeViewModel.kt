package com.aurora.music.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurora.music.AuroraApplication
import com.aurora.music.data.HomeData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val loading: Boolean = true,
    val data: HomeData = HomeData(),
    val loadingMore: Boolean = false,
    val error: String? = null,
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as AuroraApplication).container
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()
    private var loadJob: Job? = null
    private var moreJob: Job? = null
    private val seenPages = mutableSetOf<String>()

    init {
        viewModelScope.launch { container.offline.collect { load() } }
        viewModelScope.launch { container.accountEpoch.drop(1).collect { load(clear = true) } }
        viewModelScope.launch { container.libraryReload.drop(1).collect { load() } }
    }

    fun load(clear: Boolean = false) {
        loadJob?.cancel()
        moreJob?.cancel()
        seenPages.clear()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, loadingMore = false, error = null, data = if (clear) HomeData() else it.data) }
            try {
                val data = container.repository.home()
                _state.update { it.copy(loading = false, data = data) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(loading = false, error = "Could not load the home feed. Try again.") } }
        }
    }

    fun loadMore() {
        val before = _state.value
        val token = before.data.continuation ?: return
        if (before.loading || before.loadingMore) return
        _state.update { it.copy(loadingMore = true, error = null) }
        moreJob = viewModelScope.launch {
            try {
                val page = container.repository.homePage(token)
                seenPages += token
                _state.update { current ->
                    val sections = (current.data.sections + page.sections).groupBy { it.id }.values.map { parts ->
                        parts.first().copy(items = parts.flatMap { it.items }.distinctBy { it.key })
                    }
                    current.copy(loadingMore = false, data = current.data.copy(sections = sections,
                        continuation = page.continuation?.takeUnless { it in seenPages }))
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(loadingMore = false, error = "Could not load more recommendations. Try again.") } }
        }
    }
}
