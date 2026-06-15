package com.aurora.music.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurora.music.AuroraApplication
import com.aurora.music.data.DetailData
import com.aurora.music.data.remote.ArtistInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DetailUiState(
    val loading: Boolean = true,
    val data: DetailData? = null,
    val loadingMore: Boolean = false,
    val canLoadMore: Boolean = false,
    /** 6.5 artist enrichment (bio + image), null until/unless resolved. */
    val artistInfo: ArtistInfo? = null,
)

class DetailViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as AuroraApplication).container
    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    private var loadedKey: String? = null
    private var curKind: String? = null
    private var curId: String? = null

    fun load(kind: String, id: String) {
        val key = "$kind/$id"
        if (key == loadedKey) return
        loadedKey = key
        fetch(kind, id)
    }

    fun reload(kind: String, id: String) = fetch(kind, id)

    private fun fetch(kind: String, id: String) {
        curKind = kind; curId = id
        viewModelScope.launch {
            _state.update { it.copy(loading = true, data = null, loadingMore = false, canLoadMore = false, artistInfo = null) }
            val data = container.repository.detail(kind, id)
            val canMore = data != null && data.tracks.size < data.info.songCount
            _state.update { it.copy(loading = false, data = data, canLoadMore = canMore) }
            if (kind == "artist" && data != null) enrichArtist(data.info.title)
        }
    }

    /** Fetch a bio + image for the artist (cached, off-thread). No-op when disabled or nothing found. */
    private fun enrichArtist(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            if (!container.settingsStore.artistEnrichment.first()) return@launch
            val info = container.artistInfoStore.get(name)
                ?: runCatching { container.artistInfoClient.lookup(name) }.getOrNull()?.also {
                    container.artistInfoStore.put(name, it)
                }
            // Guard against a race: only apply if this artist is still the one on screen.
            if (info != null && info.found && _state.value.data?.info?.title == name) {
                _state.update { it.copy(artistInfo = info) }
            }
        }
    }

    /** Lazy-load the next page of tracks (infinite scroll). */
    fun loadMore() {
        val kind = curKind ?: return
        val id = curId ?: return
        val s = _state.value
        val data = s.data ?: return
        if (s.loadingMore || !s.canLoadMore) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            val more = container.repository.detailPage(kind, id, data.tracks.size)
            _state.update { cur ->
                val d = cur.data ?: return@update cur.copy(loadingMore = false)
                val newTracks = d.tracks + more
                cur.copy(
                    data = d.copy(tracks = newTracks),
                    loadingMore = false,
                    canLoadMore = more.isNotEmpty() && newTracks.size < d.info.songCount,
                )
            }
        }
    }
}
