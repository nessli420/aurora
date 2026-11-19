package com.aurora.music.ui.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.unit.dp
import com.aurora.music.data.PageLayout
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
                       events: MutableList<String> = mutableListOf(), width: Int = 1208, jump: Boolean = false) =
        BrowseScene(name, width, 820, prefs) {
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
                onRemoveRecent = { events += "remove:$it" },
                onClearRecents = { events += "clear" },
                onCommitSearch = { events += "commit" },
                onSelectSource = { events += "source:$it" },
                jumpBackIn = if (jump) Sample.albums else emptyList(),
                onPlayCollection = { kind, id, shuffle -> events += "collection:$kind:$id:$shuffle" },
            )
        }

    // where each layout puts what the test clicks, at 1208 wide for results and 700 wide before typing
    private class Spots(val layout: Int, val source: Pair<Float, Float>, val play: Pair<Float, Float>, val top: Pair<Float, Float>, val song: Pair<Float, Float>,
                        val recent: Pair<Float, Float>, val remove: Pair<Float, Float>, val clear: Pair<Float, Float>, val jump: Pair<Float, Float>)

    @Test fun layoutsKeepEveryAction() {
        val sources = listOf(SearchSourceChoice("library", "Library"), SearchSourceChoice("discovery", "Discover"), SearchSourceChoice("spotify", "Spotify"),
            SearchSourceChoice("soundcloud", "SoundCloud"))
        val found = SearchUiState(query = "night", results = results.copy(albums = Sample.albums.take(5), artists = Sample.artists.take(4)), sources = sources, selectedSource = "library")
        val recents = listOf("lunar tide", "neon hours", "paper planes")
        listOf(
            Spots(PageLayout.SPOTLIGHT, 923f to 58f, 423f to 445f, 250f to 300f, 700f to 248f, 95f to 236f, 147f to 236f, 647f to 187f, 180f to 358f),
            Spots(PageLayout.CONSOLE, 102f to 460f, 949f to 353f, 1034f to 150f, 500f to 133f, 150f to 220f, 650f to 220f, 642f to 180f, 97f to 444f),
            Spots(PageLayout.DRIFT, 149f to 262f, 971f to 452f, 850f to 220f, 250f to 320f, 100f to 312f, 658f to 312f, 647f to 271f, 188f to 765f),
        ).forEach { spots ->
            val prefs = UiPrefs(pageLayout = spots.layout)
            val events = mutableListOf<String>()
            fun MutableList<String>.drain(): List<String> = toList().also { clear() }
            search("search-layout-${spots.layout}-typing", SearchUiState(), prefs = prefs, events = events).use { scene ->
                scene.frames()
                scene.type("ab")
                assertEquals(listOf("query:a", "query:ab"), events.drain())
            }
            search("search-layout-${spots.layout}-results", found, recents, prefs, events).use { scene ->
                assertTrue(scene.shot().distinctColors(6) > 40)
                scene.click(spots.source.first, spots.source.second)
                assertEquals(listOf("source:discovery"), events.drain())
                scene.click(spots.play.first, spots.play.second)
                assertEquals(listOf("commit", "collection:artist:ar1:false"), events.drain())
                scene.click(spots.top.first, spots.top.second)
                assertEquals(listOf("commit", "artist:ar1"), events.drain())
                scene.click(spots.song.first, spots.song.second)
                assertEquals(listOf("commit", "all:8:0"), events.drain())
                scene.click(spots.song.first, spots.song.second, PointerButton.Secondary)
                scene.click(spots.song.first + 60f, spots.song.second + 8 + 48 + 24)
                assertEquals(listOf("next:s1"), events.drain())
            }
            search("search-layout-${spots.layout}-idle", SearchUiState(sources = sources, selectedSource = "library"), recents, prefs, events, width = 700, jump = true).use { scene ->
                scene.shot()
                scene.click(spots.recent.first, spots.recent.second)
                assertEquals(listOf("recent:lunar tide"), events.drain())
                scene.click(spots.remove.first, spots.remove.second)
                assertEquals(listOf("remove:lunar tide"), events.drain())
                scene.click(spots.clear.first, spots.clear.second)
                assertEquals(listOf("clear"), events.drain())
                scene.click(spots.jump.first, spots.jump.second)
                assertEquals(listOf("album:a1"), events.drain())
            }
        }
    }

    @Test fun rendersResultsRecentsAndHints() {
        val sources = listOf(SearchSourceChoice("library", "Library"), SearchSourceChoice("discovery", "Discover"))
        search("search-results", SearchUiState(query = "night", results = results, sources = sources)).use {
            assertTrue(it.shot().distinctColors(6) > 40)
        }
        search("search-results-light", SearchUiState(query = "night", results = results), prefs = UiPrefs(themeMode = ThemeMode.LIGHT)).use {
            assertTrue(it.shot().distinctColors(6) > 40)
        }
        // every catalogue has its own icon in the source picker, in paper's square tiles too
        val everySource = sources + listOf(SearchSourceChoice("spotify", "Spotify"), SearchSourceChoice("soundcloud", "SoundCloud"))
        search("search-sources-paper", SearchUiState(query = "night", results = results, sources = everySource, selectedSource = "soundcloud"),
            prefs = UiPrefs(themeStyle = com.aurora.music.data.ThemeStyle.PAPER)).use { assertTrue(it.shot().distinctColors(6) > 20) }
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
