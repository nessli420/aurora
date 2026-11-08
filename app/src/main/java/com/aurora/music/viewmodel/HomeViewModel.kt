package com.aurora.music.viewmodel

import com.aurora.music.localization.appString
import com.aurora.music.R

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurora.music.AuroraApplication
import com.aurora.music.data.HomeData
import com.aurora.music.data.filledFrom
import com.aurora.music.data.isBlank
import com.aurora.music.model.Song
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
    val feeds: List<com.aurora.music.data.HomeFeedChoice> = emptyList(),
    val selectedFeed: String = "library",
    // the favourites row is capped; the whole list lands here after home, for a tap to play
    val allStarred: List<Song> = emptyList(),
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as AuroraApplication).container
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()
    private var loadJob: Job? = null
    private var moreJob: Job? = null
    private val seenPages = mutableSetOf<String>()
    // the full liked list of another library or an offline flip must not back a tap
    private var starredFor: Triple<String, Int, Boolean>? = null

    init {
        // a recreated screen paints this account's last home at once and load() replaces it
        lastHome?.takeIf { it.first == homeKey() }?.let { _state.value = it.second.copy(loading = true) }
        viewModelScope.launch { container.repository.playlistChanges.collect { load() } }
        viewModelScope.launch { container.offline.collect { load() } }
        viewModelScope.launch { container.accountEpoch.drop(1).collect { lastHome = null; load(clear = true) } }
        viewModelScope.launch { container.libraryReload.drop(1).collect { load() } }
        viewModelScope.launch { container.audioCache.songs.collect { if (container.offline.value) load() } }
    }

    fun load(clear: Boolean = false) {
        loadJob?.cancel()
        moreJob?.cancel()
        seenPages.clear()
        loadJob = viewModelScope.launch {
            val key = homeKey()
            val feeds = container.repository.homeFeeds
            val selected = _state.value.selectedFeed.takeIf { id -> feeds.any { it.id == id } } ?: "library"
            _state.update { it.copy(loading = true, loadingMore = false, error = null, feeds = feeds, selectedFeed = selected,
                data = if (clear || selected != it.selectedFeed) HomeData() else it.data,
                allStarred = if (clear || selected != it.selectedFeed || starredFor != key) emptyList() else it.allStarred) }
            try {
                val shown = _state.value.data
                var latest = shown
                container.repository.homeUpdates(selected).collect { update ->
                    latest = update
                    _state.update { it.copy(data = shown.filledFrom(update)) }
                }
                // merged streaming accounts with no music on the device: open their feed instead of a blank page
                val fallback = com.aurora.music.data.EmptyViewFallback.pick(feeds.map { it.id }, selected, feedChosen, latest.isBlank)
                if (fallback != null) {
                    _state.update { it.copy(selectedFeed = fallback) }
                    load(clear = true)
                    return@launch
                }
                _state.update { it.copy(loading = false, data = latest) }
                lastHome = key to _state.value.copy(allStarred = emptyList())
                if (latest.starred.isNotEmpty()) allStarred(selected).let { all -> starredFor = key; _state.update { it.copy(allStarred = all) } }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(loading = false, error = appString(R.string.text_could_not_load_the_home_feed_try_again_f756b3)) } }
        }
    }

    // any library reload or offline flip since the snapshot may have changed what home holds
    private fun homeKey() = Triple(container.currentAccountKey(), container.libraryReload.value, container.offline.value)

    private suspend fun allStarred(feed: String): List<Song> = try {
        container.repository.homeStarred(feed)
    } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }

    // once the user picks a feed it stays, even an empty one
    @Volatile private var feedChosen = false

    fun selectFeed(id: String) {
        if (id == _state.value.selectedFeed || _state.value.feeds.none { it.id == id }) return
        feedChosen = true
        _state.update { it.copy(selectedFeed = id) }
        load(clear = true)
    }

    fun loadMore() {
        val before = _state.value
        val token = before.data.continuation ?: return
        if (before.loading || before.loadingMore) return
        _state.update { it.copy(loadingMore = true, error = null) }
        moreJob = viewModelScope.launch {
            try {
                val page = container.repository.homePage(token, before.selectedFeed)
                seenPages += token
                _state.update { current ->
                    val sections = (current.data.sections + page.sections).groupBy { it.id }.values.map { parts ->
                        parts.first().copy(items = parts.flatMap { it.items }.distinctBy { it.key })
                    }
                    current.copy(loadingMore = false, data = current.data.copy(sections = sections,
                        continuation = page.continuation?.takeUnless { it in seenPages }))
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(loadingMore = false, error = appString(R.string.text_could_not_load_more_recommendations_try_again_552ce1)) } }
        }
    }

    // outlives the activity, not the process
    private companion object { var lastHome: Pair<Triple<String, Int, Boolean>, HomeUiState>? = null }
}
