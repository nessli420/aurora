package com.aurora.music.ui.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.unit.dp
import com.aurora.music.data.SearchResults
import com.aurora.music.data.SearchSourceChoice
import com.aurora.music.data.ThemeMode
import com.aurora.music.data.UiPrefs
import com.aurora.music.ui.components.SongRow
import com.aurora.music.ui.home.BrowseScene
import com.aurora.music.ui.home.Sample
import com.aurora.music.ui.screens.search.SearchScreen
import com.aurora.music.ui.testing.differsFrom
import com.aurora.music.ui.testing.distinctColors
import com.aurora.music.viewmodel.SearchUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchScreenTest {
    private val results = SearchResults(
        songs = Sample.songs,
        albums = Sample.albums.take(4),
        artists = Sample.artists.take(3),
        playlists = Sample.playlists.take(2),
    )

    private fun search(name: String, state: SearchUiState, recents: List<String> = emptyList(), prefs: UiPrefs = UiPrefs(),
                       events: MutableList<String> = mutableListOf()) =
        BrowseScene(name, 1208, 820, prefs) {
            SearchScreen(
                contentPadding = PaddingValues(),
                state = state,
                likedIds = Sample.songs.filter { it.liked }.map { it.id }.toSet(),
                currentSongId = "s2",
                isPlaying = true,
                onQuery = { events += "query:$it" },
                onPlayAll = { songs, index -> events += "all:${songs.size}:$index" },
                onAddToQueue = { events += "queue:${it.id}" },
                onPlayNext = { events += "next:${it.id}" },
                onToggleLike = { events += "like:$it" },
                onOpenDetail = { kind, id -> events += "$kind:$id" },
                downloadedIds = setOf("s3"),
                onDownload = { events += "download:${it.id}" },
                onRemoveDownload = { events += "undownload:$it" },
                recentSearches = recents,
                onRecentClick = { events += "recent:$it" },
                onCommitSearch = { events += "commit" },
                onSelectSource = { events += "source:$it" },
            )
        }

    @Test fun rendersResultsRecentsAndHints() {
        val sources = listOf(SearchSourceChoice("library", "Library"), SearchSourceChoice("discovery", "Discover"))
        search("search-results", SearchUiState(query = "night", results = results, sources = sources)).use {
            assertTrue(it.shot().distinctColors(6) > 40)
        }
        search("search-results-light", SearchUiState(query = "night", results = results), prefs = UiPrefs(themeMode = ThemeMode.LIGHT)).use {
            assertTrue(it.shot().distinctColors(6) > 40)
        }
        search("search-recents", SearchUiState(), recents = listOf("lunar tide", "neon hours", "paper planes")).use { it.shot() }
        search("search-empty", SearchUiState(query = "zzz")).use { assertTrue(it.shot().distinctColors(6) > 5) }
        search("search-loading", SearchUiState(query = "zzz", loading = true)).use { it.shot() }
    }

    @Test fun songRowRightClickOpensContextMenu() {
        val events = mutableListOf<String>()
        val song = Sample.songs[1]
        BrowseScene("song-row-menu", 1000, 700) {
            Column(Modifier.padding(horizontal = 8.dp)) {
                Spacer(Modifier.height(100.dp))
                SongRow(song, isPlaying = false, isLiked = false, onClick = { events += "play" }, onToggleLike = { events += "like" },
                    onPlayNext = { events += "next" }, onAddToQueue = { events += "queue" },
                    onGoToAlbum = { events += "album" }, onGoToArtist = { events += "artist" }, onDownload = { events += "download" })
            }
        }.use { scene ->
            val closed = scene.shot("-closed")
            scene.click(300f, 134f)
            assertEquals(listOf("play"), events)
            scene.click(300f, 134f, PointerButton.Secondary)
            assertEquals(listOf("play"), events)
            assertTrue(scene.shot().differsFrom(closed))
            scene.click(360f, 134f + 8 + 48 + 24)
            assertEquals(listOf("play", "next"), events)
            scene.click(300f, 134f, PointerButton.Secondary)
            scene.click(360f, 134f + 8 + 4 * 48 + 24)
            scene.shot("-playlists")
            assertEquals(listOf("play", "next"), events)
        }
    }
}
