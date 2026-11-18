package com.aurora.music.ui.uxaudit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.aurora.music.data.HomeData
import com.aurora.music.data.HomeFeedChoice
import com.aurora.music.data.HomeFeedItem
import com.aurora.music.data.HomeFeedSection
import com.aurora.music.data.PageLayout
import com.aurora.music.data.ThemeMode
import com.aurora.music.data.ThemeStyle
import com.aurora.music.desktop.DesktopContainer
import com.aurora.music.model.LibraryFilter
import com.aurora.music.navigation.Routes
import com.aurora.music.ui.components.AmbientBackground
import com.aurora.music.ui.home.Sample
import com.aurora.music.ui.screens.home.HomeScreen
import com.aurora.music.ui.screens.library.LibraryScreen
import com.aurora.music.viewmodel.HomeUiState
import com.aurora.music.viewmodel.LibraryUiState
import com.aurora.music.viewmodel.LibraryViewModel
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class DriftShotsTest {
    @Before fun gate() = assumeTrue(System.getenv("AURORA_SHOTS") != null)

    @Test fun sizes() {
        tour(1440, 900, "drift", deep = true)
        tour(1000, 720, "drift", deep = true)
    }

    @Test fun paper() = tour(1440, 900, "drift-paper") { settingsStore.setThemeStyle(ThemeStyle.PAPER) }

    @Test fun retro() = tour(1440, 900, "drift-retro") { settingsStore.setThemeStyle(ThemeStyle.RETRO) }

    @Test fun light() = tour(1440, 900, "drift-light") { settingsStore.setThemeMode(ThemeMode.LIGHT) }

    // a feed-only source: feed tabs under the greeting and a single cover from the first feed section
    @Test fun feedOnly() {
        val sections = listOf(
            HomeFeedSection("again", "Listen again", items = Sample.playlists.take(3).map { HomeFeedItem.Collection(it) } + Sample.albums.take(4).map { HomeFeedItem.Record(it) }),
            HomeFeedSection("quick", "Quick picks", "Based on your listening", Sample.songs.map { HomeFeedItem.Track(it) }),
            HomeFeedSection("albums", "Albums for you", items = Sample.albums.map { HomeFeedItem.Record(it) }),
            HomeFeedSection("artists", "Similar artists", items = Sample.artists.map { HomeFeedItem.Performer(it) }),
        )
        val feeds = listOf(HomeFeedChoice("library", "Library"), HomeFeedChoice("discovery", "Discover"), HomeFeedChoice("spotify", "Spotify"))
        AuditScene("drift-feed", 1208, 820, setup = { settingsStore.setPageLayout(PageLayout.DRIFT) }) {
            Box(Modifier.fillMaxSize()) {
                AmbientBackground()
                HomeScreen(
                    contentPadding = PaddingValues(),
                    state = HomeUiState(loading = false, data = HomeData(sections = sections), feeds = feeds, selectedFeed = "discovery"),
                    username = "Maren", onOpenDrawer = {}, onOpenSettings = {}, onOpenDetail = { _, _ -> }, onPlayAlbum = {}, onPlayAll = { _, _ -> },
                    sourceLabel = "YouTube Music",
                )
            }
        }.use { scene ->
            scene.shot("home", 2_500)
            scene.scroll(600f, 400f, 16f)
            scene.shot("home-lower")
        }
    }

    private fun tour(w: Int, h: Int, label: String, deep: Boolean = false, theme: suspend DesktopContainer.() -> Unit = {}) {
        AuditScene(label, w, h, setup = { settingsStore.setPageLayout(PageLayout.DRIFT); theme() }).use { scene ->
            scene.awaitRoute(Routes.HOME)
            scene.shot("home", 4_500)
            if (deep) {
                // the cover right of the centre one, which a click brings to the centre
                scene.click((scene.contentLeft + w - scene.gutter) / 2f + 300f, 380f)
                scene.shot("home-neighbour")
                repeat(3) { step ->
                    scene.scroll(w / 2f, h / 2f, 16f)
                    scene.shot("home-lower${step + 1}")
                }
            }
            scene.navigate(Routes.LIBRARY)
            scene.shot("library", 2_000)
            if (!deep) return@use
            repeat(2) { step ->
                scene.scroll(w / 2f, h / 2f, 20f)
                scene.shot("library-lower${step + 1}")
            }
            val library = scene.rootViewModel(LibraryViewModel::class)
            library.setFilter(LibraryFilter.ALBUMS)
            scene.shot("library-albums")
            // any album will do: it opens classic's split pane beside the list, which leaves drift its narrow arrangement
            scene.click(scene.contentLeft + 80f, 340f)
            library.setFilter(LibraryFilter.ALL)
            scene.shot("library-split", 2_000)
        }
    }

    // a guest with nothing to show: the large-type empty state and its sign in action
    @Test fun guest() {
        AuditScene("drift-guest", 1208, 820, setup = { settingsStore.setPageLayout(PageLayout.DRIFT) }) {
            Box(Modifier.fillMaxSize()) {
                AmbientBackground()
                LibraryScreen(
                    contentPadding = PaddingValues(), state = LibraryUiState(loading = false, signedIn = false), username = "", likedIds = emptySet(), currentSongId = "", isPlaying = false,
                    onFilter = {}, onSort = {}, onToggleLayout = {}, onOpenDrawer = {}, onPlayAll = { _, _ -> }, onAddToQueue = {}, onPlayNext = {}, onToggleLike = {},
                    onOpenDetail = { _, _ -> }, downloadedIds = emptySet(), onDownload = {}, onRemoveDownload = {}, onOpenSearch = {}, onCreatePlaylist = { _, _, _ -> true },
                    onCreateSmart = {}, onEditSmart = {}, onDeleteSmart = {}, onImportM3u = {}, onExportPlaylist = { _, _, _ -> }, onOpenFolders = {},
                    onPlayCollection = { _, _ -> }, onShuffleCollection = { _, _ -> }, onQueueCollection = { _, _ -> }, onToggleLikeKind = { _, _ -> }, onDeletePlaylist = {},
                )
            }
        }.use { it.shot("library", 2_000) }
    }
}
