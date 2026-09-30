package com.aurora.music.ui.settings

import com.aurora.music.data.ThemeStyle
import com.aurora.music.navigation.Routes
import com.aurora.music.ui.uxaudit.AuditScene
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class SettingsShotsTest {
    @Before fun gate() = assumeTrue(System.getenv("AURORA_SHOTS") != null)

    private val sizes = listOf(1280 to 800, 1440 to 900, 1920 to 1080, 960 to 600)

    @Test fun settingsPanes() {
        sizes.forEach { (w, h) -> AuditScene("settings", w, h).use { tour(it) } }
        AuditScene("settings-retro", 1440, 900, setup = { settingsStore.setThemeStyle(ThemeStyle.RETRO) }).use { tour(it, short = true) }
        AuditScene("settings-glass", 1440, 900, setup = { settingsStore.setThemeStyle(ThemeStyle.GLASS) }).use { tour(it, short = true) }
    }

    @Test fun statsPages() {
        sizes.forEach { (w, h) ->
            AuditScene("stats", w, h).use { scene ->
                scene.awaitRoute(Routes.HOME)
                scene.navigate(Routes.STATS)
                scene.shot("stats")
                scene.navigate(Routes.HISTORY)
                scene.shot("history")
                scene.navigate(Routes.NOTIFICATIONS)
                scene.shot("inbox")
            }
        }
    }

    @Test fun signIn() {
        listOf(1280 to 800, 1440 to 900, 1920 to 1080, 1100 to 900, 960 to 600).forEach { (w, h) ->
            AuditScene("signin", w, h, signedIn = false).use { scene ->
                scene.awaitRoute(Routes.SIGN_IN)
                scene.shot("type")
                scene.click(w / 2f + if (w >= 1200) 250f else 0f, if (w >= 1200) h / 2f - 110f else h / 2f - 20f)
                scene.shot("next")
            }
        }
    }

    private fun tour(scene: AuditScene, short: Boolean = false) {
        scene.awaitRoute(Routes.HOME)
        scene.navigate(Routes.SETTINGS)
        scene.shot("root")
        if (short) return
        val rows = mapOf(1440 to (527f to 594f), 1920 to (511f to 578f))[scene.width]
        if (rows != null) {
            scene.click(scene.navWidth + 160f, rows.first)
            scene.shot("pane-output")
            scene.click(scene.navWidth + 160f, rows.second)
            scene.shot("pane-eq")
        }
        scene.navigate(Routes.SETTINGS_OUTPUT)
        scene.shot("output-page")
        scene.scroll(scene.width * 0.6f, scene.height * 0.5f, 10f)
        scene.shot("output-page-scrolled")
        scene.navigate(Routes.SETTINGS_EQ)
        scene.shot("eq-page")
        scene.navigate(Routes.SETTINGS_APPEARANCE)
        scene.shot("appearance-page")
        scene.navigate(Routes.SETTINGS_ABOUT)
        scene.shot("about-page")
    }
}
