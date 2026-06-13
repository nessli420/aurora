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
    val downloadedRows: List<com.aurora.music.data.DownloadRow> = emptyList(),
    val likedSongCount: Int = 0,
    val likedCover: String = "",
    val supportsFolders: Boolean = false,
    val smartPlaylists: List<com.aurora.music.data.SmartPlaylist> = emptyList(),
)

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as AuroraApplication).container
    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { container.offline.collect { load() } }
        viewModelScope.launch { container.accountEpoch.drop(1).collect { load() } }
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
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val playlists = container.repository.allPlaylists()
            val albums = container.repository.allAlbums()
            val artists = container.repository.allArtists()
            val songs = container.repository.allSongs()
            val likedCount = container.repository.starredCount()
            _state.update {
                it.copy(loading = false, playlists = playlists, albums = albums, artists = artists, songs = songs, downloadedRows = container.repository.downloadedLibrary(), likedSongCount = likedCount, likedCover = songs.firstOrNull()?.artworkUrl ?: "", supportsFolders = container.repository.supportsFolders)
            }
        }
    }

    fun setFilter(f: LibraryFilter) = _state.update { it.copy(filter = f) }
    fun setSort(s: LibrarySort) = _state.update { it.copy(sort = s) }
    fun toggleLayout() = _state.update {
        it.copy(layout = if (it.layout == LibraryLayout.LIST) LibraryLayout.GRID else LibraryLayout.LIST)
    }
}
