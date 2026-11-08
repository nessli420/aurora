package com.aurora.music.viewmodel

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurora.music.data.HomeData
import com.aurora.music.data.filledFrom
import com.aurora.music.data.isBlank
import com.aurora.music.desktop.DesktopContainer
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

private const val RECENT_ALBUMS = 12

class HomeViewModel(private val container: DesktopContainer) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()
    private var loadJob: Job? = null
    private var moreJob: Job? = null
    private val seenPages = mutableSetOf<String>()
    // the full liked list of another library or an offline flip must not back a tap
    private var starredFor: Pair<Int, Boolean>? = null

    init {
        viewModelScope.launch { container.repository.playlistChanges.collect { load() } }
        viewModelScope.launch { container.offline.collect { load() } }
        viewModelScope.launch { container.accountEpoch.drop(1).collect { load(clear = true) } }
        viewModelScope.launch { container.libraryReload.drop(1).collect { load() } }
    }

    fun load(clear: Boolean = false) {
        loadJob?.cancel()
        moreJob?.cancel()
        seenPages.clear()
        loadJob = viewModelScope.launch {
            val key = container.libraryReload.value to container.offline.value
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
                val data = withHistory(latest)
                // merged streaming accounts with no music on this computer: open their feed instead of a blank page
                val fallback = com.aurora.music.data.EmptyViewFallback.pick(feeds.map { it.id }, selected, feedChosen, data.isBlank)
                if (fallback != null) {
                    _state.update { it.copy(selectedFeed = fallback) }
                    load(clear = true)
                    return@launch
                }
                _state.update { it.copy(loading = false, data = data) }
                if (data.starred.isNotEmpty()) allStarred(selected).let { all -> starredFor = key; _state.update { it.copy(allStarred = all) } }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(loading = false, error = appString(R.string.text_could_not_load_the_home_feed_try_again_f756b3)) } }
        }
    }

    private suspend fun allStarred(feed: String): List<Song> = try {
        container.repository.homeStarred(feed)
    } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }

    private suspend fun withHistory(data: HomeData): HomeData {
        if (data.recentlyPlayed.isNotEmpty() || data.sections.isNotEmpty()) return data
        val ids = container.playHistory.snapshot().asSequence().map { it.albumId }.filter { it.isNotBlank() }
            .distinct().take(RECENT_ALBUMS * 2).toList()
        if (ids.isEmpty()) return data
        val known = (data.newReleases + data.mostPlayed + data.random).associateBy { it.id }
        val albums = if (ids.all { it in known }) known else {
            // online the catalogues stay out, so this fill never boots chromium
            val all = try { container.repository.allAlbums(if (container.offline.value) null else "library") } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
            known + all.associateBy { it.id }
        }
        return data.copy(recentlyPlayed = ids.mapNotNull { albums[it] }.take(RECENT_ALBUMS))
    }

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
}
