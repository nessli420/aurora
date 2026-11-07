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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

    init {
        viewModelScope.launch { container.repository.playlistChanges.collect {
            val playlists = container.repository.allPlaylists(scope())
            _state.update { it.copy(playlists = playlists) }
        } }
        viewModelScope.launch { container.offline.collect { load() } }
        viewModelScope.launch { container.accountEpoch.drop(1).collect { load() } }
        viewModelScope.launch { container.libraryReload.drop(1).collect { load() } }
        viewModelScope.launch {
            container.downloadManager.downloads.collect {
                _state.update { s -> s.copy(downloadedRows = container.repository.downloadedLibrary()) }
            }
        }
        viewModelScope.launch {
            container.downloadManager.collections.collect {
                _state.update { s -> s.copy(downloadedRows = container.repository.downloadedLibrary()) }
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
            container.playHistory.history.collect { events ->
                _state.update { s -> s.copy(localPlayCounts = events.groupingBy { it.songId }.eachCount()) }
            }
        }
    }

    // offset advances by the requested page size not the returned size merged sources dedup within a page
    private var songsOffset = 0

    // full library for play and shuffle cleared on reload
    @Volatile private var fullSongsJob: Deferred<List<Song>>? = null

    // the library view to ask for, null when the backend has only one library
    fun scope(): String? = _state.value.let { s -> s.view.takeIf { s.views.isNotEmpty() } }

    fun selectView(id: String) {
        val s = _state.value
        if (id == s.view || s.views.none { it.id == id }) return
        _state.update { it.copy(view = id, playlists = emptyList(), albums = emptyList(), artists = emptyList(), songs = emptyList(),
            likedSongCount = 0, likedCover = "", canLoadMoreSongs = false) }
        load()
    }

    private var loadJob: kotlinx.coroutines.Job? = null

    fun load() {
        fullSongsJob = null
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val views = container.repository.libraryViews
            val view = _state.value.view.takeIf { id -> views.any { it.id == id } } ?: views.firstOrNull()?.id ?: "library"
            _state.update { it.copy(loading = true, views = views, view = view) }
            val scope = scope()
            val playlists = container.repository.allPlaylists(scope)
            val albums = container.repository.allAlbums(scope)
            val artists = container.repository.allArtists(scope)
            val songs = container.repository.songsPage(0, SONG_PAGE, scope)
            songsOffset = SONG_PAGE
            val likedCount = container.repository.starredCount(scope)
            // a view switch while this was loading belongs to the newer load
            if (scope() != scope) return@launch
            _state.update {
                it.copy(loading = false, playlists = playlists, albums = albums, artists = artists, songs = songs, songsLoadingMore = false, canLoadMoreSongs = songs.size >= SONG_PAGE, downloadedRows = container.repository.downloadedLibrary(), likedSongCount = likedCount, likedCover = songs.firstOrNull()?.artworkUrl ?: "", supportsFolders = container.repository.supportsFolders, signedIn = container.repository.signedIn(scope))
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
        val all = fullSongsAsync().await().ifEmpty { _state.value.songs }
        val s = _state.value
        return sortLibrarySongs(all, s.sort, s.localPlayCounts)
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

    private companion object { const val SONG_PAGE = 100 }
}
