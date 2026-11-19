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
import kotlinx.coroutines.flow.first
import com.aurora.music.localization.localizedMediaType
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
    // null when the library has no narrower home; true while home shows only the device and servers
    val localHome: Boolean? = null,
) {
    // holding the library feed's switch flips it between every source and the device with servers alone
    fun holdFor(feed: String, select: (String) -> Unit): (() -> Unit)? =
        if (feed == "library" && localHome != null) ({ select(com.aurora.music.data.LOCAL_HOME_FEED) }) else null
}

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
        viewModelScope.launch { container.settingsStore.mergedLocalHome.drop(1).collect { load(clear = true) } }
    }

    fun load(clear: Boolean = false) {
        loadJob?.cancel()
        moreJob?.cancel()
        seenPages.clear()
        loadJob = viewModelScope.launch {
            val key = container.libraryReload.value to container.offline.value
            val local = container.repository.localHome
            val localOn = local != null && container.settingsStore.mergedLocalHome.first()
            val feeds = container.repository.homeFeeds.map { feed ->
                if (feed.id != "library" || local == null) feed
                else feed.copy(label = if (localOn) local.label.localizedMediaType() else appString(R.string.home_all_sources))
            }
            val selected = _state.value.selectedFeed.takeIf { id -> feeds.any { it.id == id } } ?: "library"
            val asked = if (selected == "library" && localOn) com.aurora.music.data.LOCAL_HOME_FEED else selected
            _state.update { it.copy(loading = true, loadingMore = false, error = null, feeds = feeds, selectedFeed = selected, localHome = local?.let { localOn },
                data = if (clear || selected != it.selectedFeed) HomeData() else it.data,
                allStarred = if (clear || selected != it.selectedFeed || starredFor != key) emptyList() else it.allStarred) }
            try {
                val shown = _state.value.data
                var latest = shown
                container.repository.homeUpdates(asked).collect { update ->
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
                if (data.starred.isNotEmpty()) allStarred(asked).let { all -> starredFor = key; _state.update { it.copy(allStarred = all) } }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(loading = false, error = appString(R.string.text_could_not_load_the_home_feed_try_again_f756b3)) } }
        }
    }

    private suspend fun allStarred(feed: String): List<Song> = try {
        container.repository.homeStarred(feed)
    } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }

    private suspend fun withHistory(data: HomeData): HomeData {
        // a catalogue's feed has its own picks; a home with your own music beside the shelves still gets the history
        if (data.recentlyPlayed.isNotEmpty() || data.sections.isNotEmpty() && data.copy(sections = emptyList()).isBlank) return data
        val ids = container.playHistory.snapshot().asSequence().map { it.albumId }.filter { it.isNotBlank() }
            .distinct().take(RECENT_ALBUMS * 2).toList()
        if (ids.isEmpty()) return data
        val known = (data.newReleases + data.mostPlayed + data.random).associateBy { it.id }
        val albums = if (ids.all { it in known }) known else {
            // online the catalogues stay out, so this fill never boots chromium
            val all = try { container.repository.allAlbums(if (container.offline.value) null else com.aurora.music.data.LOCAL_HOME_FEED) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
            known + all.associateBy { it.id }
        }
        return data.copy(recentlyPlayed = ids.mapNotNull { albums[it] }.take(RECENT_ALBUMS))
    }

    // once the user picks a feed it stays, even an empty one
    @Volatile private var feedChosen = false

    fun selectFeed(id: String) {
        if (id == com.aurora.music.data.LOCAL_HOME_FEED) return toggleLocalHome()
        if (id == _state.value.selectedFeed || _state.value.feeds.none { it.id == id }) return
        feedChosen = true
        _state.update { it.copy(selectedFeed = id) }
        load(clear = true)
    }

    private fun toggleLocalHome() {
        val on = _state.value.localHome ?: return
        feedChosen = true
        _state.update { it.copy(selectedFeed = "library") }
        viewModelScope.launch { container.settingsStore.setMergedLocalHome(!on) }
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
