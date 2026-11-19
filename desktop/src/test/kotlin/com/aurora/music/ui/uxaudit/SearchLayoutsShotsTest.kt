package com.aurora.music.ui.uxaudit

import androidx.compose.foundation.layout.PaddingValues
import com.aurora.music.data.PageLayout
import com.aurora.music.data.SearchResults
import com.aurora.music.data.SearchSourceChoice
import com.aurora.music.data.ThemeMode
import com.aurora.music.data.ThemeStyle
import com.aurora.music.data.UiPrefs
import com.aurora.music.desktop.DesktopContainer
import com.aurora.music.navigation.Routes
import com.aurora.music.ui.home.BrowseScene
import com.aurora.music.ui.home.Sample
import com.aurora.music.ui.screens.search.SearchScreen
import com.aurora.music.viewmodel.SearchUiState
import com.aurora.music.viewmodel.SearchViewModel
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class SearchLayoutsShotsTest {
    @Before fun gate() = assumeTrue(System.getenv("AURORA_SHOTS") != null)

    // the click lands on the songs filter of each layout at 1440x900
    @Test fun spotlight() = tour("spotlight", PageLayout.SPOTLIGHT, 380f, 128f)

    @Test fun console() = tour("console", PageLayout.CONSOLE, 400f, 194f)

    @Test fun drift() = tour("drift", PageLayout.DRIFT, 352f, 209f)

    private fun tour(name: String, layout: Int, songsX: Float, songsY: Float) {
        app("search-$name", 1440, 900, layout).use { scene ->
            scene.open()
            scene.shot("empty", 2_500)
            scene.find("night")
            scene.shot("all", 3_000)
            scene.click(songsX, songsY)
            scene.shot("songs")
        }
        app("search-$name", 1000, 720, layout).use { scene ->
            scene.open()
            scene.shot("empty", 2_500)
            scene.find("night")
            scene.shot("all", 3_000)
        }
        app("search-$name-paper", 1440, 900, layout) { settingsStore.setThemeStyle(ThemeStyle.PAPER) }.use { scene ->
            scene.open()
            scene.find("night")
            scene.shot("all", 3_000)
        }
        app("search-$name-light", 1440, 900, layout) { settingsStore.setThemeMode(ThemeMode.LIGHT) }.use { scene ->
            scene.open()
            scene.find("night")
            scene.shot("all", 3_000)
        }
        states(name, layout)
    }

    private fun app(label: String, w: Int, h: Int, layout: Int, theme: suspend DesktopContainer.() -> Unit = {}) = AuditScene(label, w, h, setup = {
        settingsStore.setPageLayout(layout)
        listOf("harbour lights", "quiet city", "mara quinn", "northern nights", "lunar tide").forEach { settingsStore.addRecentSearch(it) }
        theme()
    })

    private fun AuditScene.open() {
        awaitRoute(Routes.HOME)
        navigate(Routes.SEARCH)
    }

    private fun AuditScene.find(query: String) = rootViewModel(SearchViewModel::class).onQuery(query)

    private fun states(name: String, layout: Int) {
        val sources = listOf(SearchSourceChoice("library", "Library"), SearchSourceChoice("discovery", "Discover"), SearchSourceChoice("spotify", "Spotify"),
            SearchSourceChoice("soundcloud", "SoundCloud"))
        val results = SearchResults(Sample.songs, Sample.albums.take(5), Sample.artists.take(4), Sample.playlists.take(3))
        val found = SearchUiState(query = "night", results = results, sources = sources, selectedSource = "library")
        val recents = listOf("lunar tide", "neon hours", "paper planes")
        page("$name-sources", 1208, 820, layout, found, recents)
        page("$name-column", 700, 820, layout, found, recents)
        page("$name-column-empty", 700, 820, layout, SearchUiState(sources = sources, selectedSource = "library"), recents, jump = true)
        page("$name-hint", 1208, 820, layout, SearchUiState())
        page("$name-loading", 1208, 820, layout, SearchUiState(query = "zzz", loading = true, sources = sources))
        page("$name-none", 1208, 820, layout, SearchUiState(query = "zzz", sources = sources))
        page("$name-error", 1208, 820, layout, SearchUiState(query = "zzz", error = "Could not search this source. Try again.", sources = sources))
        listOf("retro" to ThemeStyle.RETRO, "aero" to ThemeStyle.AERO, "glass" to ThemeStyle.GLASS).forEach { (style, id) ->
            page("$name-$style", 1208, 820, layout, found, recents, style = id)
        }
    }

    private fun page(name: String, w: Int, h: Int, layout: Int, state: SearchUiState, recents: List<String> = emptyList(), jump: Boolean = false,
                     style: Int = ThemeStyle.AURORA) {
        BrowseScene("ux-audit/${w}x$h-search-$name", w, h, UiPrefs(pageLayout = layout, themeStyle = style)) {
            SearchScreen(
                contentPadding = PaddingValues(), state = state, likedIds = setOf("s1", "s3"), currentSongId = "s2", isPlaying = true,
                onQuery = {}, onPlayAll = { _, _ -> }, onAddToQueue = {}, onPlayNext = {}, onToggleLike = {}, onOpenDetail = { _, _ -> },
                downloadedIds = setOf("s3"), onDownload = {}, onRemoveDownload = {}, recentSearches = recents,
                jumpBackIn = if (jump) Sample.albums else emptyList(), onPlayCollection = { _, _, _ -> },
            )
        }.use { it.shot() }
    }
}
