package com.aurora.music.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurora.music.AuroraApplication
import com.aurora.music.data.Podcast
import com.aurora.music.data.PodcastEpisode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PodcastsUiState(
    val loading: Boolean = false,
    val results: List<Podcast> = emptyList(),
    val query: String = "",
    val failed: Boolean = false,
)

data class EpisodesUiState(
    val loading: Boolean = true,
    val episodes: List<PodcastEpisode> = emptyList(),
    val channelTitle: String = "",
    val channelImage: String = "",
    val failed: Boolean = false,
)

class PodcastViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as AuroraApplication).container

    val subscriptions: StateFlow<List<Podcast>> =
        container.settingsStore.podcastSubs.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _state = MutableStateFlow(PodcastsUiState())
    val state: StateFlow<PodcastsUiState> = _state.asStateFlow()

    private val _episodes = MutableStateFlow(EpisodesUiState())
    val episodes: StateFlow<EpisodesUiState> = _episodes.asStateFlow()

    private var searchJob: Job? = null

    fun search(query: String) {
        val q = query.trim()
        searchJob?.cancel()
        if (q.isBlank()) { clearSearch(); return }
        _state.update { it.copy(loading = true, failed = false, query = q) }
        searchJob = viewModelScope.launch {
            delay(350)
            val list = container.podcastClient.search(q)
            _state.update { it.copy(results = list.orEmpty(), loading = false, failed = list == null) }
        }
    }

    fun clearSearch() { _state.update { it.copy(query = "", results = emptyList(), failed = false) } }

    fun loadEpisodes(feedUrl: String, fallbackTitle: String = "", fallbackImage: String = "") {
        _episodes.update { EpisodesUiState(loading = true, channelTitle = fallbackTitle, channelImage = fallbackImage) }
        viewModelScope.launch {
            val feed = container.podcastClient.episodes(feedUrl)
            _episodes.update {
                EpisodesUiState(
                    loading = false,
                    episodes = feed.episodes,
                    channelTitle = feed.title.ifBlank { fallbackTitle },
                    channelImage = feed.imageUrl.ifBlank { fallbackImage },
                    failed = feed.episodes.isEmpty(),
                )
            }
        }
    }

    fun isSubscribed(feedUrl: String): Boolean = subscriptions.value.any { it.feedUrl == feedUrl }

    fun toggleSubscribe(podcast: Podcast) {
        viewModelScope.launch {
            if (isSubscribed(podcast.feedUrl)) container.settingsStore.deletePodcast(podcast.feedUrl)
            else container.settingsStore.savePodcast(podcast)
        }
    }
}
