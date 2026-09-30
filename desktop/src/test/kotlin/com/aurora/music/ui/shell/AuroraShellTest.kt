package com.aurora.music.ui.shell

import androidx.compose.ui.input.pointer.PointerButton
import com.aurora.music.desktop.ui.Shortcut
import com.aurora.music.localization.AppStrings
import com.aurora.music.navigation.Routes
import com.aurora.music.ui.testing.region
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuroraShellTest {
    private fun localScene(name: String, width: Int = 1440, height: Int = 900) =
        ShellScene(name, ShellPlayer(ShellFixtures.playing), width, height, seed = { applySession(authenticator.local()) })

    @Test fun signedOutStartsOnSignInAndMovesHomeOnceASessionIsReady() {
        ShellScene("signed-out").use { scene ->
            scene.awaitRoute(Routes.SIGN_IN)
            val signIn = scene.shot("sign-in")
            assertEquals(false, scene.container.sessionReady.value)
            assertTrue(signIn.region(0, 0, 232, 900).distinct().size < 40)
            runBlocking { scene.container.applySession(scene.container.authenticator.local()) }
            scene.awaitRoute(Routes.HOME)
            scene.shot("after-sign-in")
        }
    }

    @Test fun sidebarDockAndSettingsPanesNavigate() {
        localScene("local").use { scene ->
            scene.awaitRoute(Routes.HOME)
            scene.shot("home")
            scene.click(721f, 837f)
            assertEquals(listOf("togglePlay"), scene.player.calls.filter { it == "togglePlay" })

            scene.click(107f, 211f)
            scene.awaitRoute(Routes.LIBRARY)
            scene.shot("library")

            scene.click(116f, 272f)
            scene.shot("more")

            scene.click(89f, 767f)
            scene.awaitRoute(Routes.SETTINGS)
            val playback = scene.shot("settings")
            scene.click(450f, 543f)
            val output = scene.shot("settings-output")
            assertEquals(Routes.SETTINGS, scene.route)
            assertFalse(playback.region(660, 0, 1400, 700).contentEquals(output.region(660, 0, 1400, 700)))

            scene.click(1396f, 848f)
            assertTrue(scene.player.state.value.expanded)
            scene.shot("player")
            scene.press(Shortcut.BACK)
            assertFalse(scene.player.state.value.expanded)
            assertEquals(Routes.SETTINGS, scene.route)
        }
    }

    @Test fun shortcutsDriveThePlayerAndTheShell() {
        localScene("shortcuts").use { scene ->
            scene.awaitRoute(Routes.HOME)
            scene.press(Shortcut.PLAY_PAUSE)
            scene.press(Shortcut.NEXT)
            scene.press(Shortcut.PREVIOUS)
            scene.press(Shortcut.LIKE)
            assertEquals(listOf("togglePlay", "next", "previous", "like"), scene.player.calls.filter { it != "refreshLikes" })

            scene.press(Shortcut.SEARCH)
            scene.awaitRoute(Routes.SEARCH)
            scene.shot("search")
            scene.press(Shortcut.BACK)
            scene.awaitRoute(Routes.HOME)

            val closed = scene.shot("home")
            scene.press(Shortcut.QUEUE)
            val queue = scene.shot("queue-panel")
            assertFalse(closed.region(1040, 60, 1420, 780).contentEquals(queue.region(1040, 60, 1420, 780)))
            scene.press(Shortcut.QUEUE)
            assertTrue(scene.shot("queue-closed").region(1040, 60, 1420, 780).contentEquals(closed.region(1040, 60, 1420, 780)))

            scene.press(Shortcut.FULLSCREEN)
            assertTrue(scene.fullscreen)
            assertTrue(scene.player.state.value.expanded)
            scene.press(Shortcut.BACK)
            assertFalse(scene.player.state.value.expanded)
            assertFalse(scene.fullscreen)

            scene.navigate(Routes.LIBRARY)
            scene.click(700f, 500f, PointerButton.Back)
            scene.awaitRoute(Routes.HOME)
        }
    }

    @Test fun refocusingTheWindowDoesNotRefetchLikesEveryTime() {
        localScene("likes-refresh").use { scene ->
            scene.awaitRoute(Routes.HOME)
            val refreshes = scene.player.calls.count { it == "refreshLikes" }
            assertTrue(refreshes > 0)
            repeat(3) { scene.refocus() }
            assertEquals(refreshes, scene.player.calls.count { it == "refreshLikes" })
        }
    }

    @Test fun languageChangeRerendersAndKeepsTheRoute() {
        localScene("language").use { scene ->
            scene.awaitRoute(Routes.HOME)
            scene.navigate(Routes.SETTINGS)
            val english = scene.shot("settings-en")
            try {
                AppStrings.setLocale("ru")
                val russian = scene.shot("settings-ru")
                assertEquals(Routes.SETTINGS, scene.route)
                assertFalse(english.region(280, 0, 640, 500).contentEquals(russian.region(280, 0, 640, 500)))
            } finally {
                AppStrings.setLocale("")
            }
        }
    }

    @Test fun narrowWindowUsesTheRailAndDrawer() {
        localScene("narrow", 960, 600).use { scene ->
            scene.awaitRoute(Routes.HOME)
            val rail = scene.shot("rail")
            scene.click(44f, 32f)
            val drawer = scene.shot("drawer")
            assertFalse(rail.region(0, 0, 300, 500).contentEquals(drawer.region(0, 0, 300, 500)))
            scene.press(Shortcut.BACK)
            assertTrue(scene.shot("drawer-closed").region(0, 0, 300, 500).contentEquals(rail.region(0, 0, 300, 500)))
        }
    }
}
