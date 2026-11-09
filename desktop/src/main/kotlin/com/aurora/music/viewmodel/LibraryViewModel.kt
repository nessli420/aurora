package com.aurora.music.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurora.music.desktop.DesktopContainer
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
import kotlinx.coroutines.flow.distinctUntilChangedBy
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
    val layouts: Map<LibraryFilter, LibraryLayout> = emptyMap(),
    val loading: Boolean = true,
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val songs: List<Song> = emptyList(),
    val songsLoadingMore: Boolean = false,
    val canLoadMoreSongs: Boolean = false,
    val downloadedRows: List<com.aurora.music.data.DownloadRow> = emptyList(),
    val likedSongCount: Int = 0,
    val likedCover: String = "",
    val supportsFolders: Boolean = false,
    // a guest source has no liked songs or playlists of its own
    val signedIn: Boolean = true,
    val smartPlaylists: List<com.aurora.music.data.SmartPlaylist> = emptyList(),
    val localPlayCounts: Map<String, Int> = emptyMap(),
    val views: List<com.aurora.music.data.LibraryViewChoice> = emptyList(),
    val view: String = "library",
) {
    val layout: LibraryLayout get() = layouts[filter] ?: defaultLibraryLayout(filter)
    val likedId: String get() = if (views.isEmpty()) "liked" else view
}

fun defaultLibraryLayout(filter: LibraryFilter): LibraryLayout =
    if (filter == LibraryFilter.ALBUMS || filter == LibraryFilter.ARTISTS) LibraryLayout.GRID else LibraryLayout.LIST

fun sortLibrarySongs(songs: List<Song>, sort: LibrarySort, localPlayCounts: Map<String, Int>): List<Song> = when (sort) {
    LibrarySort.ALPHABETICAL -> songs.sortedBy { it.title.lowercase() }
    LibrarySort.CREATOR -> songs.sortedBy { it.artist.lowercase() }
    LibrarySort.MOST_PLAYED -> songs.sortedByDescending { maxOf(it.playCount, localPlayCounts[it.id] ?: 0) }
    LibrarySort.RECENT -> songs.sortedByDescending { it.dateAddedSec }
}

class LibraryViewModel(private val container: DesktopContainer) : ViewModel() {
    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()
    // above init, whose first load stamps it
    private var loadedAt = 0L

    init {
        viewModelScope.launch { container.repository.playlistChanges.collect {
            val playlists = container.repository.allPlaylists(scope())
            _state.update { it.copy(playlists = playlists) }
        } }
        viewModelScope.launch { container.offline.collect { load() } }
        viewModelScope.launch { container.accountEpoch.drop(1).collect { reload() } }
        viewModelScope.launch { container.libraryReload.drop(1).collect { reload() } }
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
            // listening ticks keep the size and head timestamp, so only new plays regroup
            container.playHistory.history
                .distinctUntilChangedBy { it.size to it.firstOrNull()?.timestamp }
                .map { e -> e.groupingBy { it.songId }.eachCount() }
                .flowOn(Dispatchers.Default)
                .collect { counts -> _state.update { s -> s.copy(localPlayCounts = counts) } }
        }
    }

    // offset advances by the requested page size not the returned size merged sources dedup within a page
    private var songsOffset = 0

    // full library for play and shuffle cleared on reload
    @Volatile private var fullSongsJob: Deferred<List<Song>>? = null

    // the library view to ask for, null when the backend has only one library
    fun scope(): String? = _state.value.let { s -> s.view.takeIf { s.views.isNotEmpty() } }

    // once the user picks a view it stays, even an empty one
    @Volatile private var viewChosen = false

    fun selectView(id: String) {
        val s = _state.value
        if (id == s.view || s.views.none { it.id == id }) return
        viewChosen = true
        _state.update { it.copy(view = id, playlists = emptyList(), albums = emptyList(), artists = emptyList(), songs = emptyList(),
            likedSongCount = 0, likedCover = "", canLoadMoreSongs = false) }
        load()
    }

    private var loadJob: kotlinx.coroutines.Job? = null
    private var countJob: kotlinx.coroutines.Job? = null
    private var loadFailed = false

    // the model lives all session, so a later visit picks up server changes in the background
    fun refreshIfStale() {
        if (loadJob?.isActive != true && (loadFailed || System.nanoTime() - loadedAt > STALE_NS)) load(keepSongs = true)
    }

    // a new account or merge renames every id, so old rows go first
    private fun reload() {
        _state.update { it.copy(playlists = emptyList(), albums = emptyList(), artists = emptyList(), songs = emptyList(),
            downloadedRows = emptyList(), likedSongCount = 0, likedCover = "", canLoadMoreSongs = false) }
        load()
    }

    fun load(keepSongs: Boolean = false) {
        loadedAt = System.nanoTime()
        fullSongsJob?.cancel()
        fullSongsJob = null
        loadJob?.cancel()
        countJob?.cancel()
        loadJob = viewModelScope.launch {
            val views = container.repository.libraryViews
            val view = _state.value.view.takeIf { id -> views.any { it.id == id } } ?: views.firstOrNull()?.id ?: "library"
            _state.update { it.copy(loading = true, views = views, view = view) }
            val scope = scope()
            val repo = container.repository
            // this number can take every source's whole liked list, so the lists do not wait for it
            val epoch = container.accountEpoch.value
            countJob = viewModelScope.launch {
                // the Liked Songs tile shows the art its page opens on, so it reads the liked list, not the library's first song
                launch {
                    val likedId = if (views.isEmpty()) "liked" else view
                    val cover = try { repo.likedCover(likedId) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                    if (cover != null && scope() == scope && epoch == container.accountEpoch.value) _state.update { it.copy(likedCover = cover) }
                }
                val count = try { repo.starredCount(scope) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                if (count != null && scope() == scope && epoch == container.accountEpoch.value) _state.update { it.copy(likedSongCount = count) }
            }
            // together, so one slow or unreachable server costs a single timeout rather than one per section
            val (lists, songs) = try {
                kotlinx.coroutines.coroutineScope {
                    val playlists = async { repo.allPlaylists(scope) }
                    val albums = async { repo.allAlbums(scope) }
                    val artists = async { repo.allArtists(scope) }
                    val songs = async { repo.songsPage(0, SONG_PAGE, scope) }
                    Triple(playlists.await(), albums.await(), artists.await()) to songs.await()
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                loadFailed = true
                if (scope() == scope) _state.update { it.copy(loading = false) }
                return@launch
            }
            val (playlists, albums, artists) = lists
            // an empty answer is retried on the next visit
            loadFailed = playlists.isEmpty() && albums.isEmpty() && artists.isEmpty() && songs.isEmpty()
            val rows = withContext(Dispatchers.Default) { repo.downloadedLibrary() }
            // a view switch while this was loading belongs to the newer load
            if (scope() != scope) return@launch
            // merged streaming accounts with no music on this computer: open their library instead of a blank one
            val fallback = com.aurora.music.data.EmptyViewFallback.pick(views.map { it.id }, view, viewChosen, loadFailed)
            if (fallback != null) {
                _state.update { it.copy(view = fallback) }
                load()
                return@launch
            }
            // a background refresh keeps the walked list until the new walk lands
            val keep = keepSongs && _state.value.songs.size > songs.size
            if (!keep) songsOffset = SONG_PAGE
            _state.update {
                it.copy(loading = false, playlists = playlists, albums = albums, artists = artists, songs = if (keep) it.songs else songs, songsLoadingMore = false, canLoadMoreSongs = if (keep) it.canLoadMoreSongs else songs.size >= SONG_PAGE, downloadedRows = rows, supportsFolders = container.repository.supportsFolders, signedIn = container.repository.signedIn(scope))
            }
            fullSongsAsync()
        }
    }

    fun loadMoreSongs() {
        val s = _state.value
        if (s.songsLoadingMore || !s.canLoadMoreSongs || s.loading) return
        _state.update { it.copy(songsLoadingMore = true) }
        viewModelScope.launch {
            val off = songsOffset
            val more = container.repository.songsPage(off, SONG_PAGE, scope())
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
        return viewModelScope.async {
            val all = container.repository.allLibrarySongs(view = scope())
            if (all.isNotEmpty()) _state.update { it.copy(songs = all, canLoadMoreSongs = false, songsLoadingMore = false) }
            all
        }.also { fullSongsJob = it }
    }

    suspend fun fullSortedSongs(): List<Song> {
        val all = try { fullSongsAsync().await() } catch (_: CancellationException) {
            // a reload cancelled the walk, not this play; wait for the new one
            currentCoroutineContext().ensureActive()
            fullSongsAsync().await()
        }.ifEmpty { _state.value.songs }
        val s = _state.value
        return withContext(Dispatchers.Default) { sortLibrarySongs(all, s.sort, s.localPlayCounts) }
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
        it.copy(layouts = it.layouts + (it.filter to if (it.layout == LibraryLayout.LIST) LibraryLayout.GRID else LibraryLayout.LIST))
    }

    private companion object {
        const val SONG_PAGE = 100
        const val STALE_NS = 10 * 60_000_000_000L
    }
}
