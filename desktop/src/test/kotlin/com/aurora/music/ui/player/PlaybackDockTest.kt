package com.aurora.music.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aurora.music.data.ThemeMode
import com.aurora.music.data.ThemeStyle
import com.aurora.music.data.UiPrefs
import com.aurora.music.desktop.player.PlayerUiState
import com.aurora.music.ui.components.PlaybackDock
import com.aurora.music.ui.screens.player.PlayerPane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackDockTest {
    private class Recorder {
        val events = mutableListOf<String>()
        var volume by mutableFloatStateOf(0.6f)
    }

    private fun dock(name: String, width: Int, state: PlayerUiState, rec: Recorder, openPane: PlayerPane? = null, prefs: UiPrefs = UiPrefs()) =
        PlayerScene(name, width, 110, prefs) {
            Box(Modifier.fillMaxSize()) {
                PlaybackDock(
                    state = state,
                    openPane = openPane,
                    volume = rec.volume,
                    onExpand = { rec.events += "expand" },
                    onTogglePlay = { rec.events += "play" },
                    onPrevious = { rec.events += "previous" },
                    onNext = { rec.events += "next" },
                    onSeek = { rec.events += "seek:$it" },
                    onToggleLike = { rec.events += "like" },
                    onToggleShuffle = { rec.events += "shuffle" },
                    onCycleRepeat = { rec.events += "repeat" },
                    onVolumeChange = { rec.volume = it },
                    onToggleMute = { rec.events += "mute" },
                    onOpenOutput = { rec.events += "output" },
                    onPane = { rec.events += "pane:$it" },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                )
            }
        }

    @Test fun roomyDockRoutesEveryControl() {
        val rec = Recorder()
        dock("dock-wide", 1440, PlayerFixtures.playing, rec, openPane = PlayerPane.LYRICS).use { scene ->
            assertTrue(scene.shot().distinctColors() > 30)
            listOf(720f to 46f, 612f to 46f, 668f to 46f, 772f to 46f, 828f to 46f, 225f to 58f, 1104f to 58f,
                1300f to 58f, 1348f to 58f, 1396f to 58f, 50f to 58f).forEach { (x, y) -> scene.click(x, y) }
            assertEquals(listOf("play", "shuffle", "previous", "next", "repeat", "like", "output",
                "pane:LYRICS", "pane:QUEUE", "expand", "expand"), rec.events)
            rec.events.clear()
            scene.click(717f, 81f)
            val seek = rec.events.single().removePrefix("seek:").toFloat()
            assertTrue("seek $seek", seek in 0.45f..0.55f)
            rec.events.clear()
            scene.scroll(1226f, 58f, -1f)
            assertEquals(0.65f, rec.volume, 0.001f)
            scene.click(1152f, 58f)
            scene.click(1152f, 58f)
            assertEquals(listOf("mute", "mute"), rec.events)
            assertEquals(0.65f, rec.volume, 0.001f)
        }
    }

    @Test fun compactDockKeepsTransportAndPaneToggles() {
        val rec = Recorder()
        dock("dock-compact", 720, PlayerFixtures.playing, rec).use { scene ->
            assertTrue(scene.shot().distinctColors() > 20)
            scene.click(676f, 58f)
            scene.click(628f, 58f)
            assertEquals(listOf("pane:QUEUE", "pane:LYRICS"), rec.events)
        }
    }

    @Test fun rendersLightGlassAndLiveStates() {
        dock("dock-glass-light", 1440, PlayerFixtures.playing.copy(isPlaying = false, shuffle = false), Recorder(),
            prefs = UiPrefs(themeMode = ThemeMode.LIGHT, themeStyle = ThemeStyle.GLASS)).use { assertTrue(it.shot().distinctColors() > 20) }
        val rec = Recorder()
        dock("dock-live", 1440, PlayerFixtures.playing.copy(isLive = true), rec).use { scene ->
            scene.shot()
            scene.click(717f, 81f)
            assertTrue(rec.events.isEmpty())
        }
    }
}
