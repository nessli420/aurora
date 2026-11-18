package com.aurora.music.ui.uxaudit

import androidx.compose.runtime.CompositionLocalProvider
import com.aurora.music.data.BarStyle
import com.aurora.music.data.PageLayout
import com.aurora.music.data.Preservation
import com.aurora.music.data.SignalPath
import com.aurora.music.data.ThemeMode
import com.aurora.music.data.ThemeStyle
import com.aurora.music.desktop.DesktopContainer
import com.aurora.music.desktop.player.PlayerController
import com.aurora.music.desktop.ui.LocalPlayer
import com.aurora.music.desktop.ui.Shortcut
import com.aurora.music.ui.AuroraApp
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class BarsShotsTest {
    private val shortcuts = MutableSharedFlow<Shortcut>(extraBufferCapacity = 8)

    @Before fun gate() = assumeTrue(System.getenv("AURORA_SHOTS") != null)

    @Test fun spotlight() = tour("spotlight", BarStyle.SPOTLIGHT)

    @Test fun console() = tour("console", BarStyle.CONSOLE)

    @Test fun drift() {
        tour("drift", BarStyle.DRIFT)
        // the capsule's last button, which holds the controls that did not fit
        shell("bars-drift", 1440, 900) { settingsStore.setBarStyle(BarStyle.DRIFT) }.use { scene ->
            scene.settle(4_000)
            scene.click(1144f, 846f)
            scene.shot("more")
        }
    }

    // the bar style is left on classic and follows the console page layout
    @Test fun matchLayout() {
        shell("bars-match", 1440, 900) {
            settingsStore.setPageLayout(PageLayout.CONSOLE)
            settingsStore.setBarsMatchLayout(true)
        }.use { it.shot("home", 6_000) }
    }

    private fun tour(name: String, style: Int) {
        shell("bars-$name", 1440, 900) { settingsStore.setBarStyle(style) }.use { scene ->
            scene.shot("home", 6_000)
            check(shortcuts.tryEmit(Shortcut.QUEUE))
            scene.shot("queue")
        }
        shell("bars-$name", 1000, 720) { settingsStore.setBarStyle(style) }.use { it.shot("home", 6_000) }
        shell("bars-$name-paper", 1440, 900) { settingsStore.setBarStyle(style); settingsStore.setThemeStyle(ThemeStyle.PAPER) }.use { it.shot("home", 6_000) }
        shell("bars-$name-light", 1440, 900) { settingsStore.setBarStyle(style); settingsStore.setThemeMode(ThemeMode.LIGHT) }.use { it.shot("home", 6_000) }
    }

    // the seeded player with a live signal path, which console's dock reads its chips from
    private fun shell(label: String, w: Int, h: Int, setup: suspend DesktopContainer.() -> Unit): AuditScene {
        val base = AuditPlayer()
        val player = object : PlayerController by base {
            override val signalPath: StateFlow<SignalPath> = MutableStateFlow(SignalPath(active = true, codec = "FLAC", sampleRateHz = 96_000, bitDepth = 24,
                output = "WASAPI exclusive", bitPerfect = true, preservation = Preservation.PRESERVED))
        }
        return AuditScene(label, w, h, setup = setup) {
            CompositionLocalProvider(LocalPlayer provides player) { AuroraApp(shortcuts = shortcuts) }
        }.also { base.state.value = it.player.state.value }
    }
}
