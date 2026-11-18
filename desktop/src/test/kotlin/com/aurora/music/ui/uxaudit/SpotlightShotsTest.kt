package com.aurora.music.ui.uxaudit

import androidx.compose.foundation.layout.PaddingValues
import com.aurora.music.data.HomeData
import com.aurora.music.data.HomeFeedChoice
import com.aurora.music.data.HomeFeedItem
import com.aurora.music.data.HomeFeedSection
import com.aurora.music.data.PageLayout
import com.aurora.music.data.ThemeMode
import com.aurora.music.data.ThemeStyle
import com.aurora.music.data.UiPrefs
import com.aurora.music.desktop.DesktopContainer
import com.aurora.music.navigation.Routes
import com.aurora.music.ui.home.BrowseScene
import com.aurora.music.ui.home.Sample
import com.aurora.music.ui.screens.home.HomeScreen
import com.aurora.music.viewmodel.HomeUiState
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class SpotlightShotsTest {
    @Before fun gate() = assumeTrue(System.getenv("AURORA_SHOTS") != null)

    @Test fun sizes() {
        tour(1440, 900, "spotlight")
        tour(1000, 720, "spotlight")
    }

    @Test fun paper() = tour(1440, 900, "spotlight-paper") { settingsStore.setThemeStyle(ThemeStyle.PAPER) }

    @Test fun light() = tour(1440, 900, "spotlight-light") { settingsStore.setThemeMode(ThemeMode.LIGHT) }

    // a feed-only source: feed pills in the header and the hero falling back to the first feed section
    @Test fun feedOnly() {
        val sections = listOf(
            HomeFeedSection("again", "Listen again", items = Sample.playlists.take(3).map { HomeFeedItem.Collection(it) } + Sample.albums.take(4).map { HomeFeedItem.Record(it) }),
            HomeFeedSection("quick", "Quick picks", "Based on your listening", Sample.songs.map { HomeFeedItem.Track(it) }),
        )
        val feeds = listOf(HomeFeedChoice("library", "Library"), HomeFeedChoice("discovery", "Discover"), HomeFeedChoice("spotify", "Spotify"))
        BrowseScene("ux-audit/1208x820-spotlight-home-feed", 1208, 820, UiPrefs(pageLayout = PageLayout.SPOTLIGHT)) {
            HomeScreen(
                contentPadding = PaddingValues(),
                state = HomeUiState(loading = false, data = HomeData(sections = sections), feeds = feeds, selectedFeed = "discovery"),
                username = "Maren", onOpenDrawer = {}, onOpenSettings = {}, onOpenDetail = { _, _ -> }, onPlayAlbum = {}, onPlayAll = { _, _ -> },
            )
        }.use { it.shot() }
    }

    private fun tour(w: Int, h: Int, label: String, theme: suspend DesktopContainer.() -> Unit = {}) {
        AuditScene(label, w, h, setup = { settingsStore.setPageLayout(PageLayout.SPOTLIGHT); theme() }).use { scene ->
            scene.awaitRoute(Routes.HOME)
            scene.shot("home", 4_500)
            scene.hover(w * 0.6f, 180f)
            scene.shot("home-hover", 300)
            repeat(2) { step ->
                scene.scroll(w / 2f, h / 2f, 12f)
                scene.shot("home-lower${step + 1}")
            }
            scene.navigate(Routes.LIBRARY)
            scene.shot("library", 2_000)
        }
    }
}
