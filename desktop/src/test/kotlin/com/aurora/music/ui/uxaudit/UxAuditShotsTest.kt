package com.aurora.music.ui.uxaudit

import com.aurora.music.desktop.ui.Shortcut
import com.aurora.music.model.LibraryFilter
import com.aurora.music.navigation.Routes
import com.aurora.music.viewmodel.LibraryViewModel
import com.aurora.music.viewmodel.SearchViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class UxAuditShotsTest {
    @Before fun gate() = assumeTrue(System.getenv("AURORA_SHOTS") != null)

    @Test fun signIn() {
        listOf(1280 to 800, 1440 to 900, 1920 to 1080).forEach { (w, h) ->
            AuditScene("signed-out", w, h, signedIn = false).use { scene ->
                scene.awaitRoute(Routes.SIGN_IN)
                scene.shot("sign-in")
            }
        }
    }

    @Test fun wide1280() { tour(1280, 800) }

    @Test fun wide1440() { tour(1440, 900) }

    @Test fun wide1920() { tour(1920, 1080) }

    @Test fun narrow960() {
        AuditScene("rail", 960, 600).use { scene -> rail(scene) }
    }

    private fun rail(scene: AuditScene) {
        scene.awaitRoute(Routes.HOME)
        scene.shot("home")
        scene.navigate(Routes.LIBRARY)
        scene.shot("library-all")
        scene.entryViewModel(LibraryViewModel::class).setFilter(LibraryFilter.ALBUMS)
        scene.shot("library-albums")
        scene.navigate(Routes.detail("album", scene.songs.first { it.album == "Nocturne" }.albumId), Routes.DETAIL)
        scene.shot("album")
        scene.navigate(Routes.SETTINGS)
        scene.shot("settings")
        scene.press(Shortcut.QUEUE)
        scene.shot("player-queue")
        scene.player.setExpanded(false)
        scene.settle()
        scene.player.setExpanded(true)
        scene.shot("player")
    }

    private fun tour(w: Int, h: Int) {
        AuditScene("shell", w, h).use { scene -> tour(scene) }
    }

    private fun tour(scene: AuditScene) {
        scene.awaitRoute(Routes.HOME)
        scene.shot("01-home", 2_500)
        scene.press(Shortcut.QUEUE)
        scene.shot("02-home-queue-panel")
        scene.press(Shortcut.QUEUE)

        scene.navigate(Routes.SEARCH)
        scene.rootViewModel(SearchViewModel::class).onQuery("night")
        scene.shot("03-search", 2_000)

        scene.navigate(Routes.LIBRARY)
        val library = scene.entryViewModel(LibraryViewModel::class)
        scene.shot("04-library-all")
        library.setFilter(LibraryFilter.ALBUMS)
        scene.shot("05-library-albums")
        library.setFilter(LibraryFilter.ARTISTS)
        scene.shot("06-library-artists")
        library.setFilter(LibraryFilter.SONGS)
        scene.shot("07-library-songs")
        library.setFilter(LibraryFilter.PLAYLISTS)
        scene.shot("08-library-playlists")

        val album = scene.songs.first { it.album == "Nocturne" }
        scene.navigate(Routes.detail("album", album.albumId), Routes.DETAIL)
        scene.shot("09-album")
        val artistId = scene.container.folderLibrary.artists.first { it.name == "Lunar Tide" }.id
        scene.navigate(Routes.detail("artist", artistId), Routes.DETAIL)
        scene.shot("10-artist", 2_000)
        val playlist = runBlocking { scene.container.repository.allPlaylists() }.first()
        scene.navigate(Routes.detail("playlist", playlist.id), Routes.DETAIL)
        scene.shot("11-playlist")

        scene.navigate(Routes.PROFILE)
        scene.shot("12-profile")

        scene.navigate(Routes.SETTINGS)
        scene.shot("13-settings")
        scene.navigate(Routes.SETTINGS_OUTPUT)
        scene.shot("14-settings-output-page")
        scene.navigate(Routes.SETTINGS_EQ)
        scene.shot("15-settings-eq-page")

        scene.navigate(Routes.HOME)
        scene.player.setExpanded(true)
        scene.shot("16-player-lyrics", 2_000)
        scene.press(Shortcut.QUEUE)
        scene.shot("17-player-queue")
        scene.player.setExpanded(false)
        scene.settle()
    }
}
