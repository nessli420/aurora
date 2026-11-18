package com.aurora.music.ui.uxaudit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.aurora.music.data.HomeData
import com.aurora.music.data.HomeFeedChoice
import com.aurora.music.data.HomeFeedItem
import com.aurora.music.data.HomeFeedSection
import com.aurora.music.data.PageLayout
import com.aurora.music.data.Preservation
import com.aurora.music.data.SignalPath
import com.aurora.music.data.ThemeMode
import com.aurora.music.data.ThemeStyle
import com.aurora.music.desktop.DesktopContainer
import com.aurora.music.desktop.player.PlayerController
import com.aurora.music.desktop.player.PlayerUiState
import com.aurora.music.desktop.ui.LocalPlayer
import com.aurora.music.model.LibraryFilter
import com.aurora.music.navigation.Routes
import com.aurora.music.ui.components.AmbientBackground
import com.aurora.music.ui.home.Sample
import com.aurora.music.ui.screens.home.HomeScreen
import com.aurora.music.viewmodel.HomeUiState
import com.aurora.music.viewmodel.LibraryViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class ConsoleShotsTest {
    @Before fun gate() = assumeTrue(System.getenv("AURORA_SHOTS") != null)

    @Test fun sizes() {
        tour(1440, 900, "console", deep = true)
        tour(1000, 720, "console", deep = true)
    }

    @Test fun paper() = tour(1440, 900, "console-paper") { settingsStore.setThemeStyle(ThemeStyle.PAPER) }

    @Test fun light() = tour(1440, 900, "console-light") { settingsStore.setThemeMode(ThemeMode.LIGHT) }

    // a feed-only source with something playing: no chart or jump back in, feed segments in the header, the signal path filled in
    @Test fun feedOnly() {
        val sections = listOf(
            HomeFeedSection("again", "Listen again", items = Sample.playlists.take(3).map { HomeFeedItem.Collection(it) } + Sample.albums.take(4).map { HomeFeedItem.Record(it) }),
            HomeFeedSection("quick", "Quick picks", "Based on your listening", Sample.songs.map { HomeFeedItem.Track(it) }),
            HomeFeedSection("albums", "Albums for you", items = Sample.albums.map { HomeFeedItem.Record(it) }),
            HomeFeedSection("artists", "Similar artists", items = Sample.artists.map { HomeFeedItem.Performer(it) }),
        )
        val feeds = listOf(HomeFeedChoice("library", "Library"), HomeFeedChoice("discovery", "Discover"), HomeFeedChoice("spotify", "Spotify"))
        page("console-feed", HomeUiState(loading = false, data = HomeData(sections = sections), feeds = feeds, selectedFeed = "discovery"), "YouTube Music")
    }

    // a server that reports play counts, which the seeded folder library never does: the chart's bars
    @Test fun playCounts() = page("console-counts", HomeUiState(loading = false, data = HomeData(
        newReleases = Sample.albums, recentlyPlayed = Sample.albums.reversed(), playlists = Sample.playlists, artists = Sample.artists,
        mostPlayed = Sample.albums.take(5).mapIndexed { i, album -> album.copy(playCount = 96 - i * 17) },
    )), "Navidrome")

    private fun page(label: String, state: HomeUiState, source: String) {
        val base = AuditPlayer().apply { this.state.value = PlayerUiState(current = Sample.songs[0], isPlaying = true) }
        val player = object : PlayerController by base {
            override val signalPath: StateFlow<SignalPath> = MutableStateFlow(SignalPath(active = true, codec = "FLAC", sampleRateHz = 96_000, bitDepth = 24,
                output = "WASAPI exclusive", bitPerfect = true, preservation = Preservation.PRESERVED))
        }
        AuditScene(label, 1208, 820, setup = { settingsStore.setPageLayout(PageLayout.CONSOLE) }) {
            CompositionLocalProvider(LocalPlayer provides player) {
                Box(Modifier.fillMaxSize()) {
                    AmbientBackground()
                    HomeScreen(
                        contentPadding = PaddingValues(), state = state, username = "Maren", onOpenDrawer = {}, onOpenSettings = {}, onOpenDetail = { _, _ -> },
                        onPlayAlbum = {}, onPlayAll = { _, _ -> }, sourceLabel = source,
                    )
                }
            }
        }.use { scene ->
            scene.shot("home", 2_500)
            scene.scroll(600f, 400f, 12f)
            scene.shot("home-lower")
        }
    }

    private fun tour(w: Int, h: Int, label: String, deep: Boolean = false, theme: suspend DesktopContainer.() -> Unit = {}) {
        AuditScene(label, w, h, setup = { settingsStore.setPageLayout(PageLayout.CONSOLE); theme() }).use { scene ->
            scene.awaitRoute(Routes.HOME)
            scene.shot("home", 4_500)
            if (deep) {
                scene.hover(w * 0.6f, 260f)
                scene.shot("home-hover", 300)
                repeat(2) { step ->
                    scene.scroll(w / 2f, h / 2f, 12f)
                    scene.shot("home-lower${step + 1}")
                }
            }
            scene.navigate(Routes.LIBRARY)
            scene.shot("library", 2_000)
            if (!deep) return@use
            val library = scene.rootViewModel(LibraryViewModel::class)
            library.setFilter(LibraryFilter.ALBUMS)
            scene.shot("library-albums")
            library.toggleLayout()
            scene.shot("library-albums-list")
            // any row will do: it opens classic's split pane beside the list, which leaves console its narrow arrangement
            scene.click(w - 300f, 300f)
            scene.shot("library-split", 2_000)
            library.setFilter(LibraryFilter.ALL)
            scene.shot("library-split-all")
        }
    }
}
