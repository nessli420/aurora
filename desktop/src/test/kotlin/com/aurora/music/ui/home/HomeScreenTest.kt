package com.aurora.music.ui.home

import androidx.compose.foundation.layout.PaddingValues
import com.aurora.music.data.HomeData
import com.aurora.music.data.HomeFeedChoice
import com.aurora.music.data.HomeFeedItem
import com.aurora.music.data.HomeFeedSection
import com.aurora.music.data.ThemeMode
import com.aurora.music.data.ThemeStyle
import com.aurora.music.data.UiPrefs
import com.aurora.music.ui.screens.home.HomeScreen
import com.aurora.music.ui.testing.distinctColors
import com.aurora.music.viewmodel.HomeUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeScreenTest {
    private val library = HomeData(
        newReleases = Sample.albums.take(5),
        recentlyPlayed = Sample.albums.drop(2),
        mostPlayed = Sample.albums.reversed(),
        random = Sample.albums.shuffled(java.util.Random(4)),
        playlists = Sample.playlists,
        artists = Sample.artists,
        starred = Sample.songs.filter { it.liked },
    )

    private fun home(name: String, state: HomeUiState, prefs: UiPrefs = UiPrefs(), events: MutableList<String> = mutableListOf()) =
        BrowseScene(name, 1208, 820, prefs) {
            HomeScreen(
                contentPadding = PaddingValues(),
                state = state,
                username = "Maren",
                onOpenDrawer = { events += "drawer" },
                onOpenSettings = { events += "settings" },
                onOpenDetail = { kind, id -> events += "$kind:$id" },
                onPlayAlbum = { events += "play:$it" },
                onPlayAll = { songs, index -> events += "all:${songs.size}:$index" },
                onSelectFeed = { events += "feed:$it" },
                onAddSource = { events += "add" },
                onOpenNotifications = { events += "inbox" },
            )
        }

    @Test fun libraryHomeRendersAndRoutesClicks() {
        val events = mutableListOf<String>()
        home("home-dark", HomeUiState(loading = false, data = library), events = events).use { scene ->
            val image = scene.shot()
            assertTrue(image.distinctColors(6) > 40)
            scene.click(1164f, 52f)
            scene.click(30f, 40f)
            scene.click(120f, 150f)
            scene.click(356f, 263f)
            assertEquals(listOf("inbox", "album:a1", "play:a1"), events)
        }
    }

    @Test fun rendersFeedSectionsAndLightStyles() {
        val sections = listOf(
            HomeFeedSection("quick", "Quick picks", "Based on your listening", Sample.songs.map { HomeFeedItem.Track(it) }),
            HomeFeedSection("mixed", "Mixed for you", items = Sample.albums.take(3).map { HomeFeedItem.Record(it) } +
                Sample.playlists.take(2).map { HomeFeedItem.Collection(it) } + Sample.artists.take(2).map { HomeFeedItem.Performer(it) }),
        )
        val feeds = listOf(HomeFeedChoice("library", "Library"), HomeFeedChoice("discovery", "Discover"))
        val events = mutableListOf<String>()
        home("home-feed-light", HomeUiState(loading = false, data = HomeData(sections = sections, continuation = null), feeds = feeds),
            UiPrefs(themeMode = ThemeMode.LIGHT), events).use { scene ->
            assertTrue(scene.shot().distinctColors(6) > 40)
            scene.click(120f, 220f)
            assertEquals("all:8:0", events.last())
            scene.click(1116f, 52f)
            assertEquals("feed:discovery", events.last())
        }
        home("home-glass", HomeUiState(loading = false, data = library), UiPrefs(themeStyle = ThemeStyle.GLASS)).use { it.shot() }
    }

    @Test fun rendersLoadingAndEmptyStates() {
        home("home-loading", HomeUiState(loading = true)).use { assertTrue(it.shot().distinctColors(6) > 5) }
        home("home-empty", HomeUiState(loading = false)).use { assertTrue(it.shot().distinctColors(6) > 5) }
    }
}
