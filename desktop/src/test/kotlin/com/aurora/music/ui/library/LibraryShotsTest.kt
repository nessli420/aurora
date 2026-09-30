package com.aurora.music.ui.library

import com.aurora.music.data.ThemeStyle
import com.aurora.music.model.LibraryFilter
import com.aurora.music.model.LibrarySort
import com.aurora.music.navigation.Routes
import com.aurora.music.ui.layout.PageMetrics
import com.aurora.music.ui.uxaudit.AuditScene
import com.aurora.music.viewmodel.LibraryViewModel
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class LibraryShotsTest {
    @Before fun gate() = assumeTrue(System.getenv("AURORA_SHOTS") != null)

    @Test fun wide() {
        listOf(1280 to 800, 1440 to 900, 1920 to 1080).forEach { (w, h) -> tour(w, h, ThemeStyle.AURORA, "library") }
    }

    @Test fun rail() = tour(960, 600, ThemeStyle.AURORA, "library-rail")

    @Test fun themes() {
        tour(1440, 900, ThemeStyle.RETRO, "library-retro", full = false)
        tour(1440, 900, ThemeStyle.GLASS, "library-glass", full = false)
    }

    private fun tour(w: Int, h: Int, style: Int, label: String, full: Boolean = true) {
        AuditScene(label, w, h, setup = { settingsStore.setThemeStyle(style) }).use { scene ->
            scene.awaitRoute(Routes.HOME)
            scene.navigate(Routes.LIBRARY)
            val library = scene.entryViewModel(LibraryViewModel::class)
            val left = scene.contentLeft
            val top = PageMetrics.HeaderTop.value + PageMetrics.HeaderHeight.value + 52f
            scene.shot("all", 2_000)
            library.setFilter(LibraryFilter.ALBUMS)
            scene.shot("albums")
            scene.hover(left + 60f, top + 60f)
            scene.shot("albums-hover")
            if (full) {
                library.setFilter(LibraryFilter.ARTISTS)
                scene.shot("artists")
                library.setFilter(LibraryFilter.SONGS)
                scene.shot("songs")
                library.setSort(LibrarySort.ALPHABETICAL)
                scene.shot("songs-alpha")
                library.setSort(LibrarySort.RECENT)
                library.setFilter(LibraryFilter.PLAYLISTS)
                scene.shot("playlists")
                library.setFilter(LibraryFilter.ALBUMS)
                library.toggleLayout()
                scene.shot("albums-list")
                library.toggleLayout()
                scene.settle()
            }
            scene.click(left + 60f, top + 60f)
            scene.shot("split-albums", 2_000)
            if (full) {
                library.setFilter(LibraryFilter.SONGS)
                scene.shot("split-songs")
                library.setFilter(LibraryFilter.ALL)
                scene.shot("split-all")
                scene.navigate(Routes.folders(), Routes.FOLDERS)
                scene.shot("folders", 2_000)
                scene.navigate(Routes.DUPLICATES)
                scene.shot("duplicates", 3_000)
                scene.navigate(Routes.smartEdit(), Routes.SMART_EDIT)
                scene.shot("smart-edit")
            }
        }
    }
}
