package com.aurora.music.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurora.music.AuroraApplication
import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.LibraryFilter
import com.aurora.music.model.LibraryLayout
import com.aurora.music.model.LibrarySort
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LibraryUiState(
    val filter: LibraryFilter = LibraryFilter.ALL,
    val sort: LibrarySort = LibrarySort.RECENT,
    val layout: LibraryLayout = LibraryLayout.LIST,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val songs: List<Song> = emptyList(),
    // songs in a sort made off the main thread; sortedFor is the sort it was built with
    val sortedSongs: List<Song> = emptyList(),
    val sortedFor: LibrarySort = LibrarySort.RECENT,
    val songsLoadingMore: Boolean = false,
    val canLoadMoreSongs: Boolean = false,
    val downloadedRows: List<com.aurora.music.data.DownloadRow> = emptyList(),
    val likedSongCount: Int = 0,
    val likedCover: String = "",
    val supportsFolders: Boolean = false,
    // a guest source has no liked songs or playlists of its own
    val signedIn: Boolean = true,
    val smartPlaylists: List<com.aurora.music.data.SmartPlaylist> = emptyList(),
    // aurora's own local play tracking used to fill in "most played" where the server reports no playCount
    val localPlayCounts: Map<String, Int> = emptyMap(),
    // merged libraries, youtube music and spotify are browsed one at a time
    val views: List<com.aurora.music.data.LibraryViewChoice> = emptyList(),
    val view: String = "library",
) {
    // liked songs of the library on screen; "liked" is the whole backend's
    val likedId: String get() = if (views.isEmpty()) "liked" else view
}

// single source of truth for library song order so the on-screen list and the queue built on Play match
fun sortLibrarySongs(songs: List<Song>, sort: LibrarySort, localPlayCounts: Map<String, Int>): List<Song> = when (sort) {
    LibrarySort.ALPHABETICAL -> songs.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    LibrarySort.CREATOR -> songs.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.artist })
    // server play count wins when reported; local history fills the gap for local files/spotify
    LibrarySort.MOST_PLAYED -> songs.sortedByDescending { maxOf(it.playCount, localPlayCounts[it.id] ?: 0) }
    LibrarySort.RECENT -> songs.sortedByDescending { it.dateAddedSec }
}

// counts only matter to most played, so a listening tick doesn't re-sort the other orders
private fun LibraryUiState.sortCounts(): Map<String, Int> = if (sort == LibrarySort.MOST_PLAYED) localPlayCounts else emptyMap()

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as AuroraApplication).container
    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()
    // the inputs sortedSongs was built from, so Play queues exactly what the list shows
    private var sortedFrom: Triple<List<Song>, LibrarySort, Map<String, Int>>? = null

    init {
        viewModelScope.launch { container.repository.playlistChanges.collect {
            val playlists = container.repository.allPlaylists(scope())
            _state.update { it.copy(playlists = playlists) }
        } }
        viewModelScope.launch { container.offline.collect { load() } }
        // the count lands on its own, so the old account's number goes now
        viewModelScope.launch { container.accountEpoch.drop(1).collect { _state.update { it.copy(likedSongCount = 0) }; load() } }
        viewModelScope.launch { container.libraryReload.drop(1).collect { if (!_state.value.refreshing) load() } }
        viewModelScope.launch { container.audioCache.songs.collect { if (container.offline.value) load() } }
        viewModelScope.launch {
            combine(container.downloadManager.downloads, container.downloadManager.collections) { _, _ -> }.collectLatest {
                val epoch = container.accountEpoch.value
                val rows = withContext(Dispatchers.Default) { container.repository.downloadedLibrary() }
                // a switch meanwhile leaves the rows to the reload it starts
                if (epoch == container.accountEpoch.value) _state.update { s -> s.copy(downloadedRows = rows) }
            }
        }
        viewModelScope.launch {
            // the library opens on the first tab in the user's order until they pick one
            val first = container.settingsStore.uiPrefs.first().libraryTabs.visible.firstOrNull()
            if (first != null && !filterChosen) _state.update { it.copy(filter = first) }
        }
        viewModelScope.launch {
            container.settingsStore.smartPlaylists.collect { sps ->
                _state.update { s -> s.copy(smartPlaylists = sps) }
            }
        }
        viewModelScope.launch {
            // listening ticks only grow the newest event so counts change with size or a new head
            container.playHistory.history
                .distinctUntilChanged { a, b -> a.size == b.size && a.firstOrNull()?.songId == b.firstOrNull()?.songId && a.firstOrNull()?.timestamp == b.firstOrNull()?.timestamp }
                .map { events -> events.groupingBy { it.songId }.eachCount() }
                .distinctUntilChanged()
                .flowOn(Dispatchers.Default)
                .collect { counts -> _state.update { s -> s.copy(localPlayCounts = counts) } }
        }
        viewModelScope.launch {
            // list equals is O(n), so inputs compare by identity
            _state.map { s -> Triple(s.songs, s.sort, s.sortCounts()) }
                .distinctUntilChanged { a, b -> a.first === b.first && a.second == b.second && a.third === b.third }
                .collectLatest { inputs ->
                    val sorted = withContext(Dispatchers.Default) { sortLibrarySongs(inputs.first, inputs.second, inputs.third) }
                    // a reload or view switch meanwhile owns the list now
                    if (_state.value.songs !== inputs.first) return@collectLatest
                    sortedFrom = inputs
                    _state.update { it.copy(sortedSongs = sorted, sortedFor = inputs.second) }
                }
        }
    }

    // offset advances by the requested page size not the returned size merged sources dedup within a page
    private var songsOffset = 0

    // the complete library, loaded on demand for playback so Play/Shuffle/tap cover every song
    // (the on-screen list only holds what's been scrolled). invalidated whenever the library reloads.
    @Volatile private var fullSongsJob: Deferred<List<Song>>? = null

    private var reloadJob: kotlinx.coroutines.Job? = null
    private var countJob: kotlinx.coroutines.Job? = null

    fun load() {
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch { reload(showLoading = true) }
    }

    // the library view to ask for, null when the backend has only one library
    fun scope(): String? = _state.value.let { s -> s.view.takeIf { s.views.isNotEmpty() } }

    // once the user picks a view it stays, even an empty one
    @Volatile private var viewChosen = false

    fun cycleView() {
        val s = _state.value
        if (s.views.size < 2) return
        viewChosen = true
        val next = s.views[(s.views.indexOfFirst { it.id == s.view } + 1).mod(s.views.size)].id
        _state.update { it.copy(view = next, playlists = emptyList(), albums = emptyList(), artists = emptyList(), songs = emptyList(),
            likedSongCount = 0, likedCover = "", canLoadMoreSongs = false) }
        load()
    }

    fun refresh() {
        if (_state.value.refreshing) return
        _state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            try {
                container.rescanLocalLibrary()
                reload(showLoading = false)
            } finally {
                _state.update { it.copy(refreshing = false) }
            }
        }
    }

    private suspend fun reload(showLoading: Boolean) {
        // the walk isn't a child of this reload, so an old account's or view's walk must stop here
        fullSongsJob?.cancel()
        fullSongsJob = null
        countJob?.cancel()
        val views = container.repository.libraryViews
        val view = _state.value.view.takeIf { id -> views.any { it.id == id } } ?: views.firstOrNull()?.id ?: "library"
        _state.update { it.copy(views = views, view = view, loading = if (showLoading) true else it.loading) }
        val scope = scope()
        val repo = container.repository
        // this number can take every source's whole liked list, so the lists do not wait for it
        val epoch = container.accountEpoch.value
        countJob = viewModelScope.launch {
            val count = try { repo.starredCount(scope) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (count != null && scope() == scope && epoch == container.accountEpoch.value) _state.update { it.copy(likedSongCount = count) }
        }
        // together, so one slow or unreachable server costs a single timeout rather than one per section
        val (lists, songs) = kotlinx.coroutines.coroutineScope {
            val playlists = async { repo.allPlaylists(scope) }
            val albums = async { repo.allAlbums(scope) }
            val artists = async { repo.allArtists(scope) }
            val songs = async { repo.songsPage(0, SONG_PAGE, scope) }
            Triple(playlists.await(), albums.await(), artists.await()) to songs.await()
        }
        val (playlists, albums, artists) = lists
        songsOffset = SONG_PAGE
        val rows = withContext(Dispatchers.Default) { repo.downloadedLibrary() }
        // a view switch while this was loading belongs to the newer load
        if (scope() != scope) return
        // merged streaming accounts with no music on the device: open their library instead of a blank one
        val empty = playlists.isEmpty() && albums.isEmpty() && artists.isEmpty() && songs.isEmpty()
        val fallback = com.aurora.music.data.EmptyViewFallback.pick(views.map { it.id }, view, viewChosen, empty)
        if (fallback != null) {
            _state.update { it.copy(view = fallback) }
            return reload(showLoading)
        }
        _state.update {
            it.copy(loading = false, playlists = playlists, albums = albums, artists = artists, songs = songs, songsLoadingMore = false, canLoadMoreSongs = songs.size >= SONG_PAGE, downloadedRows = rows, likedCover = songs.firstOrNull()?.artworkUrl ?: "", supportsFolders = container.repository.supportsFolders, signedIn = repo.signedIn(scope))
        }
        // warm the full-library cache in the background so the first Play is instant
        fullSongsAsync()
    }

    fun loadMoreSongs() {
        val s = _state.value
        if (s.songsLoadingMore || !s.canLoadMoreSongs || s.loading) return
        _state.update { it.copy(songsLoadingMore = true) }
        viewModelScope.launch {
            val off = songsOffset
            val scope = scope()
            val more = container.repository.songsPage(off, SONG_PAGE, scope)
            if (scope() != scope) {
                _state.update { it.copy(songsLoadingMore = false) }
                return@launch
            }
            songsOffset = off + SONG_PAGE
            _state.update { cur ->
                // servers can return dupes at page seams keep the list id-unique
                val merged = (cur.songs + more).distinctBy { it.id }
                cur.copy(songs = merged, songsLoadingMore = false, canLoadMoreSongs = more.isNotEmpty())
            }
        }
    }

    private fun fullSongsAsync(): Deferred<List<Song>> {
        // reuse an in-flight or successful load; retry only if a previous attempt came back empty
        fullSongsJob?.let { if (!it.isCompleted || it.getCompleted().isNotEmpty()) return it }
        val scope = scope()
        return viewModelScope.async {
            val all = container.repository.allLibrarySongs(view = scope)
            if (scope() != scope) return@async emptyList()
            // once the whole library is in, show it all so the on-screen order matches what Play queues
            if (all.isNotEmpty()) _state.update { it.copy(songs = all, canLoadMoreSongs = false, songsLoadingMore = false) }
            all
        }.also { fullSongsJob = it }
    }

    // the whole library in the current sort order; awaits the full load if it hasn't finished yet
    suspend fun fullSortedSongs(): List<Song> {
        val all = try { fullSongsAsync().await() } catch (_: CancellationException) {
            // a reload cancelled the walk, not this play; wait for the new one
            currentCoroutineContext().ensureActive()
            fullSongsAsync().await()
        }.ifEmpty { _state.value.songs }
        val s = _state.value
        val counts = s.sortCounts()
        val memo = sortedFrom
        if (memo != null && memo.first === all && memo.second == s.sort && memo.third === counts) return s.sortedSongs
        return withContext(Dispatchers.Default) { sortLibrarySongs(all, s.sort, counts) }
    }

    @Volatile private var filterChosen = false

    fun setFilter(f: LibraryFilter) {
        filterChosen = true
        _state.update { it.copy(filter = f) }
    }

    fun setTabs(tabs: com.aurora.music.data.LibraryTabs) {
        viewModelScope.launch { container.settingsStore.setLibraryTabs(tabs) }
    }
    fun setSort(s: LibrarySort) = _state.update { it.copy(sort = s) }
    fun toggleLayout() = _state.update {
        it.copy(layout = if (it.layout == LibraryLayout.LIST) LibraryLayout.GRID else LibraryLayout.LIST)
    }

    private companion object { const val SONG_PAGE = 100 }
}
