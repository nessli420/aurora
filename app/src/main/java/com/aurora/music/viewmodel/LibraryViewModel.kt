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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryUiState(
    val filter: LibraryFilter = LibraryFilter.ALL,
    val sort: LibrarySort = LibrarySort.RECENT,
    val layout: LibraryLayout = LibraryLayout.LIST,
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
    val smartPlaylists: List<com.aurora.music.data.SmartPlaylist> = emptyList(),
    // aurora's own local play tracking used to fill in "most played" where the server reports no playCount
    val localPlayCounts: Map<String, Int> = emptyMap(),
)

// single source of truth for library song order so the on-screen list and the queue built on Play match
fun sortLibrarySongs(songs: List<Song>, sort: LibrarySort, localPlayCounts: Map<String, Int>): List<Song> = when (sort) {
    LibrarySort.ALPHABETICAL -> songs.sortedBy { it.title.lowercase() }
    LibrarySort.CREATOR -> songs.sortedBy { it.artist.lowercase() }
    // server play count wins when reported; local history fills the gap for local files/spotify
    LibrarySort.MOST_PLAYED -> songs.sortedByDescending { maxOf(it.playCount, localPlayCounts[it.id] ?: 0) }
    LibrarySort.RECENT -> songs.sortedByDescending { it.dateAddedSec }
}

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as AuroraApplication).container
    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    init {
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

    // the complete library, loaded on demand for playback so Play/Shuffle/tap cover every song
    // (the on-screen list only holds what's been scrolled). invalidated whenever the library reloads.
    @Volatile private var fullSongsJob: Deferred<List<Song>>? = null

    fun load() {
        fullSongsJob = null
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val playlists = container.repository.allPlaylists()
            val albums = container.repository.allAlbums()
            val artists = container.repository.allArtists()
            val songs = container.repository.songsPage(0, SONG_PAGE)
            songsOffset = SONG_PAGE
            val likedCount = container.repository.starredCount()
            _state.update {
                it.copy(loading = false, playlists = playlists, albums = albums, artists = artists, songs = songs, songsLoadingMore = false, canLoadMoreSongs = songs.size >= SONG_PAGE, downloadedRows = container.repository.downloadedLibrary(), likedSongCount = likedCount, likedCover = songs.firstOrNull()?.artworkUrl ?: "", supportsFolders = container.repository.supportsFolders)
            }
            // warm the full-library cache in the background so the first Play is instant
            fullSongsAsync()
        }
    }

    fun loadMoreSongs() {
        val s = _state.value
        if (s.songsLoadingMore || !s.canLoadMoreSongs || s.loading) return
        _state.update { it.copy(songsLoadingMore = true) }
        viewModelScope.launch {
            val off = songsOffset
            val more = container.repository.songsPage(off, SONG_PAGE)
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
            val all = container.repository.allLibrarySongs()
            // once the whole library is in, show it all so the on-screen order matches what Play queues
            if (all.isNotEmpty()) _state.update { it.copy(songs = all, canLoadMoreSongs = false, songsLoadingMore = false) }
            all
        }.also { fullSongsJob = it }
    }

    // the whole library in the current sort order; awaits the full load if it hasn't finished yet
    suspend fun fullSortedSongs(): List<Song> {
        val all = fullSongsAsync().await().ifEmpty { _state.value.songs }
        val s = _state.value
        return sortLibrarySongs(all, s.sort, s.localPlayCounts)
    }

    fun setFilter(f: LibraryFilter) = _state.update { it.copy(filter = f) }
    fun setSort(s: LibrarySort) = _state.update { it.copy(sort = s) }
    fun toggleLayout() = _state.update {
        it.copy(layout = if (it.layout == LibraryLayout.LIST) LibraryLayout.GRID else LibraryLayout.LIST)
    }

    private companion object { const val SONG_PAGE = 100 }
}
