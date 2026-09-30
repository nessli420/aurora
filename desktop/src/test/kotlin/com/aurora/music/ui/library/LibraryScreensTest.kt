package com.aurora.music.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.aurora.music.model.LibraryFilter
import com.aurora.music.model.LibraryLayout
import com.aurora.music.model.LibrarySort
import com.aurora.music.ui.screens.detail.DetailScreen
import com.aurora.music.ui.screens.library.AlphabetRail
import com.aurora.music.ui.screens.library.DuplicatesScreen
import com.aurora.music.ui.screens.library.FolderScreen
import com.aurora.music.ui.screens.library.LibraryScreen
import com.aurora.music.ui.screens.library.SmartPlaylistEditScreen
import com.aurora.music.ui.testing.distinctColors
import com.aurora.music.ui.testing.region
import com.aurora.music.viewmodel.DetailUiState
import com.aurora.music.viewmodel.LibraryUiState
import com.aurora.music.viewmodel.sortLibrarySongs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryScreensTest {
    private val label = "browse-b"
    private val padding = PaddingValues(bottom = 96.dp)

    @Composable
    private fun Library(state: LibraryUiState, selected: String? = null, onOpenDetail: (String, String) -> Unit = { _, _ -> }) = LibraryScreen(
        contentPadding = padding, state = state, username = "Mara", likedIds = setOf("s1", "s3", "a1"), currentSongId = "s2", isPlaying = true,
        onFilter = {}, onSort = {}, onToggleLayout = {}, onOpenDrawer = {}, onPlayAll = { _, _ -> }, onAddToQueue = {}, onPlayNext = {},
        onToggleLike = {}, onOpenDetail = onOpenDetail, downloadedIds = setOf("s4"), onDownload = {}, onRemoveDownload = {}, onOpenSearch = {},
        onCreatePlaylist = { true }, onCreateSmart = {}, onEditSmart = {}, onDeleteSmart = {}, onImportM3u = {}, onExportPlaylist = { _, _, _ -> },
        onOpenFolders = {}, onPlayCollection = { _, _ -> }, onShuffleCollection = { _, _ -> }, onQueueCollection = { _, _ -> },
        onToggleLikeKind = { _, _ -> }, onDeletePlaylist = {}, pins = Samples.pins, selectedItem = selected,
    )

    private fun shot(name: String, width: Int = 1180, height: Int = 860, dark: Boolean = true, content: @Composable () -> Unit) =
        Harness(width, height, dark, content).use { it.settle().save(label, name) }

    @Test fun sortKeepsLibraryAndQueueOrderInSync() {
        val songs = Samples.songs
        assertEquals(songs.map { it.title }.sortedBy { it.lowercase() }, sortLibrarySongs(songs, LibrarySort.ALPHABETICAL, emptyMap()).map { it.title })
        assertEquals(songs.maxBy { it.dateAddedSec }.id, sortLibrarySongs(songs, LibrarySort.RECENT, emptyMap()).first().id)
        assertEquals("s5", sortLibrarySongs(songs, LibrarySort.MOST_PLAYED, mapOf("s5" to 999)).first().id)
    }

    @Test fun overviewRendersTilesAndShelves() {
        val dark = shot("library-all") { Library(Samples.library(LibraryFilter.ALL)) }
        val light = shot("library-all-light", dark = false) { Library(Samples.library(LibraryFilter.ALL)) }
        assertTrue(dark.distinctColors(6) > 200)
        assertFalse(dark.pixels().contentEquals(light.pixels()))
    }

    @Test fun loadingShowsOnlyTheLoader() {
        val loading = shot("library-loading") { Library(LibraryUiState(loading = true)) }
        val loaded = shot("library-empty-playlists") { Library(LibraryUiState(filter = LibraryFilter.PLAYLISTS, loading = false)) }
        assertFalse(loading.pixels().contentEquals(loaded.pixels()))
    }

    @Test fun gridAndListLayoutsDiffer() {
        val grid = shot("library-albums-grid") { Library(Samples.library(LibraryFilter.ALBUMS, LibraryLayout.GRID)) }
        val list = shot("library-albums-list") { Library(Samples.library(LibraryFilter.ALBUMS, LibraryLayout.LIST)) }
        val artists = shot("library-artists-grid") { Library(Samples.library(LibraryFilter.ARTISTS, LibraryLayout.GRID)) }
        val playlists = shot("library-playlists-list") { Library(Samples.library(LibraryFilter.PLAYLISTS, LibraryLayout.LIST)) }
        val downloads = shot("library-downloads") { Library(Samples.library(LibraryFilter.DOWNLOADED, LibraryLayout.LIST)) }
        listOf(grid, list, artists, playlists, downloads).map { it.pixels().contentHashCode() }.let { assertEquals(it.size, it.distinct().size) }
    }

    @Test fun selectedItemIsTinted() {
        val plain = shot("library-grid-unselected") { Library(Samples.library(LibraryFilter.ALBUMS, LibraryLayout.GRID, LibrarySort.ALPHABETICAL)) }
        val picked = shot("library-grid-selected") { Library(Samples.library(LibraryFilter.ALBUMS, LibraryLayout.GRID, LibrarySort.ALPHABETICAL), selected = "album:a6") }
        assertFalse(plain.region(0, 170, 400, 460).contentEquals(picked.region(0, 170, 400, 460)))
    }

    @Test fun songsTabShowsAlphabetRail() {
        val alpha = shot("library-songs-alpha") { Library(Samples.library(LibraryFilter.SONGS, sort = LibrarySort.ALPHABETICAL)) }
        val recent = shot("library-songs-recent") { Library(Samples.library(LibraryFilter.SONGS)) }
        assertFalse(alpha.region(1180 - 40, 300, 1180 - 12, 700).contentEquals(recent.region(1180 - 40, 300, 1180 - 12, 700)))
    }

    @Test fun alphabetRailJumpsWithTheLatestList() {
        val jumps = mutableListOf<String>()
        var list by mutableStateOf("albums")
        Harness(40, 540) {
            val current = list
            AlphabetRail(onJump = { jumps += "$current:$it" }, modifier = Modifier.fillMaxHeight())
        }.use { h ->
            h.settle(100)
            h.click(12f, 10f)
            list = "artists"
            h.settle(100)
            h.click(12f, 250f)
            h.settle(100)
        }
        assertEquals(listOf("albums:A", "artists:M"), jumps)
    }

    @Test fun rightClickOpensCollectionMenu() {
        Harness(1180, 860) { Library(Samples.library(LibraryFilter.ALBUMS, LibraryLayout.LIST)) }.use { h ->
            val before = h.settle().save(label, "library-context-before")
            h.rightClick(420f, 280f)
            val after = h.settle(400).save(label, "library-context-menu")
            assertFalse(before.region(420, 280, 640, 520).contentEquals(after.region(420, 280, 640, 520)))
        }
    }

    @Test fun splitPaneHostsDetailBesideLibrary() {
        shot("library-split", width = 1352) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) { Library(Samples.library(LibraryFilter.ALBUMS, LibraryLayout.GRID), selected = "album:a0") }
                Spacer(Modifier.width(16.dp))
                Box(
                    Modifier.weight(1.1f).fillMaxHeight().padding(top = 8.dp)
                        .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.35f)),
                ) {
                    DetailScreen(
                        contentPadding = padding, state = DetailUiState(loading = false, data = Samples.albumDetail), likedIds = setOf("s1"),
                        currentSongId = "s2", isPlaying = true, onBack = {}, onPlayAll = { _, _ -> }, onShufflePlay = {}, onAddToQueue = {},
                        onPlayNext = {}, onToggleLike = {}, onOpenDetail = { _, _ -> }, itemKind = "album", isItemLiked = true, onToggleItemLike = {},
                        downloadedIds = emptySet(), onDownload = {}, onRemoveDownload = {}, onDownloadAll = {}, onRemoveDownloads = {},
                        onEditPlaylist = { _, _ -> true }, onDeletePlaylist = {},
                    )
                }
            }
        }.let { assertTrue(it.distinctColors(6) > 200) }
    }

    @Test fun folderBrowserListsFoldersThenTracks() {
        val full = shot("folder") {
            FolderScreen(
                contentPadding = padding, title = "", loading = false, content = Samples.folder, likedIds = setOf("s1"), currentSongId = "s2",
                isPlaying = true, onBack = {}, onOpenFolder = { _, _ -> }, onPlayAll = { _, _ -> }, onShufflePlay = {}, onAddToQueue = {},
                onPlayNext = {}, onToggleLike = {}, onOpenDetail = { _, _ -> }, downloadedIds = emptySet(), onDownload = {}, onRemoveDownload = {},
            )
        }
        val empty = shot("folder-empty") {
            FolderScreen(
                contentPadding = padding, title = "Empty", loading = false, content = null, likedIds = emptySet(), currentSongId = "",
                isPlaying = false, onBack = {}, onOpenFolder = { _, _ -> }, onPlayAll = { _, _ -> }, onShufflePlay = {}, onAddToQueue = {},
                onPlayNext = {}, onToggleLike = {}, onOpenDetail = { _, _ -> }, downloadedIds = emptySet(), onDownload = {}, onRemoveDownload = {},
            )
        }
        assertTrue(full.distinctColors(6) > empty.distinctColors(6))
    }

    @Test fun duplicatesListsGroups() {
        val found = shot("duplicates") {
            DuplicatesScreen(contentPadding = padding, loading = false, scanned = 412, groups = Samples.duplicates, currentSongId = "s0", onBack = {}, onPlay = {})
        }
        val none = shot("duplicates-none") {
            DuplicatesScreen(contentPadding = padding, loading = false, scanned = 412, groups = emptyList(), currentSongId = "", onBack = {}, onPlay = {})
        }
        assertTrue(found.distinctColors(6) > none.distinctColors(6))
    }

    @Test fun smartPlaylistEditorShowsRules() {
        val edit = shot("smart-edit") {
            SmartPlaylistEditScreen(contentPadding = padding, playlist = Samples.smart, isNew = false, onUpdate = {}, onSave = {}, onBack = {})
        }
        val new = shot("smart-new") {
            SmartPlaylistEditScreen(contentPadding = padding, playlist = com.aurora.music.data.SmartPlaylist(id = "x", name = "", rules = listOf(com.aurora.music.data.SmartRule())), isNew = true, onUpdate = {}, onSave = {}, onBack = {})
        }
        assertFalse(edit.pixels().contentEquals(new.pixels()))
    }
}
