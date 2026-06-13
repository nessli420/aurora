package com.aurora.music.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurora.music.AuroraApplication
import com.aurora.music.data.AudioTags
import com.aurora.music.data.remote.MetadataMatch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TagEditState(
    val loading: Boolean = true,
    val songId: String = "",
    val path: String = "",
    val artUrl: String = "",                 // current artwork (for display)
    val tags: AudioTags = AudioTags(),
    val matching: Boolean = false,
    val matches: List<MetadataMatch> = emptyList(),
    val matchError: String? = null,
    val pickedCoverUrl: String = "",         // chosen replacement cover (CAA), embedded on save
    val identifying: Boolean = false,        // AcoustID fingerprint lookup in flight
    val durationSec: Int = 0,                // true track length, sent to AcoustID for matching
    val localFile: Boolean = false,          // on-device file (JAudiotagger) vs server item (backend API)
)

/** Backs the tag-edit screen: loads current tags, runs MusicBrainz matches, edits fields. */
class TagEditViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as AuroraApplication).container
    private val _state = MutableStateFlow(TagEditState())
    val state: StateFlow<TagEditState> = _state.asStateFlow()

    private var loaded: String? = null

    fun load(songId: String) {
        if (songId == loaded) return
        loaded = songId
        viewModelScope.launch {
            val song = container.repository.songFor(songId)
            val path = song?.path.orEmpty()
            val localFile = song?.streamUrl?.startsWith("content://") == true
            // Local file → read tags off disk; server item → read the server's full current metadata
            // (so fields the editor doesn't surface aren't wiped on save).
            val sourceTags = if (localFile) {
                if (path.isNotBlank()) container.tagEditor.read(path) else null
            } else {
                container.repository.readMetadata(songId)
            }
            val tags = sourceTags ?: AudioTags(
                title = song?.title.orEmpty(),
                artist = song?.artist.orEmpty(),
                album = song?.album.orEmpty(),
            )
            _state.update {
                it.copy(loading = false, songId = songId, path = path, artUrl = song?.artworkUrl.orEmpty(), tags = tags, durationSec = song?.durationSec ?: 0, localFile = localFile)
            }
        }
    }

    fun edit(transform: (AudioTags) -> AudioTags) = _state.update { it.copy(tags = transform(it.tags)) }

    fun matchOnline() {
        val t = _state.value.tags
        _state.update { it.copy(matching = true, matchError = null, matches = emptyList()) }
        viewModelScope.launch {
            val results = runCatching { container.musicBrainz.search(t.title, t.artist, t.album) }.getOrDefault(emptyList())
            _state.update {
                it.copy(matching = false, matches = results, matchError = if (results.isEmpty()) "No matches found" else null)
            }
        }
    }

    /** Identify the track by its acoustic fingerprint: Chromaprint, AcoustID, MusicBrainz. */
    fun identify() {
        val path = _state.value.path
        if (path.isBlank()) return
        _state.update { it.copy(identifying = true, matchError = null, matches = emptyList()) }
        val durationSec = _state.value.durationSec
        viewModelScope.launch {
            val fingerprint = runCatching { container.acoustId.fingerprint(path) }.getOrNull()
            android.util.Log.i("AuroraFp", "fingerprint(${path.substringAfterLast('/')}) len=${fingerprint?.length ?: -1}")
            when {
                fingerprint == null -> _state.update { it.copy(identifying = false, matchError = "Couldn't fingerprint this file") }
                !container.acoustId.configured -> _state.update {
                    it.copy(identifying = false, matchError = "Fingerprint ready (${fingerprint.length} chars). Add an AcoustID API key to fetch matches.")
                }
                else -> {
                    val results = runCatching { container.acoustId.lookup(fingerprint, durationSec) }.getOrDefault(emptyList())
                    _state.update {
                        it.copy(identifying = false, matches = results, matchError = if (results.isEmpty()) "No AcoustID match" else null)
                    }
                }
            }
        }
    }

    /** Apply a chosen MusicBrainz candidate to the editable fields (and stage its cover for save). */
    fun applyMatch(m: MetadataMatch) = _state.update {
        it.copy(
            tags = it.tags.copy(
                title = m.title.ifBlank { it.tags.title },
                artist = m.artist.ifBlank { it.tags.artist },
                album = m.album.ifBlank { it.tags.album },
                year = m.year.ifBlank { it.tags.year },
                trackNumber = m.trackNumber.ifBlank { it.tags.trackNumber },
            ),
            pickedCoverUrl = m.coverUrl,
            matches = emptyList(),
        )
    }
}
