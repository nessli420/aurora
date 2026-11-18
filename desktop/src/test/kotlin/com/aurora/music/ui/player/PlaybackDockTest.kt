package com.aurora.music.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aurora.music.data.BarStyle
import com.aurora.music.data.ThemeMode
import com.aurora.music.data.ThemeStyle
import com.aurora.music.data.UiPrefs
import com.aurora.music.desktop.player.PlayerUiState
import com.aurora.music.desktop.ui.LocalPlayer
import com.aurora.music.ui.components.StyledDock
import com.aurora.music.ui.screens.player.PlayerPane
import com.aurora.music.ui.uxaudit.AuditPlayer
import com.aurora.music.ui.testing.distinctColors
import com.aurora.music.ui.testing.region
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackDockTest {
    private class Recorder {
        val events = mutableListOf<String>()
        var volume by mutableFloatStateOf(0.6f)
    }

    private fun dock(name: String, width: Int, state: PlayerUiState, rec: Recorder, openPane: PlayerPane? = null, prefs: UiPrefs = UiPrefs(),
        style: Int = BarStyle.CLASSIC, height: Int = 110) =
        PlayerScene(name, width, height, prefs) {
            // console's dock reads the signal path from the player
            CompositionLocalProvider(LocalPlayer provides AuditPlayer()) { Box(Modifier.fillMaxSize()) {
                StyledDock(
                    style = style,
                    djMode = false,
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
                    onOpenArtist = { rec.events += "artist" },
                )
            } }
        }

    @Test fun consoleDockRoutesEveryControl() {
        val rec = Recorder()
        dock("dock-console", 1440, PlayerFixtures.playing, rec, style = BarStyle.CONSOLE).use { scene ->
            assertTrue(scene.shot().distinctColors(6) > 20)
            listOf(630f, 683f, 735f, 960f, 1008f, 1056f, 1104f, 1152f, 1200f, 1388f, 50f).forEach { scene.click(it, 66f) }
            assertEquals(listOf("previous", "play", "next", "shuffle", "repeat", "pane:LYRICS", "pane:QUEUE", "output", "mute", "expand", "expand"), rec.events)
            rec.events.clear()
            // the seek bar rides the dock's top edge, half of it above the dock
            scene.click(720f, 28f)
            val seek = rec.events.single().removePrefix("seek:").toFloat()
            assertTrue("seek $seek", seek in 0.45f..0.55f)
        }
    }

    @Test fun driftDockKeepsTheRestBehindMore() {
        val rec = Recorder()
        dock("dock-drift", 1440, PlayerFixtures.playing, rec, style = BarStyle.DRIFT, height = 220).use { scene ->
            assertTrue(scene.shot().distinctColors(6) > 20)
            listOf(487f, 539f, 592f, 912f, 960f, 1008f, 292f).forEach { scene.click(it, 166f) }
            assertEquals(listOf("previous", "play", "next", "like", "pane:QUEUE", "mute", "expand"), rec.events)
            rec.events.clear()
            scene.click(749f, 166f)
            val seek = rec.events.single().removePrefix("seek:").toFloat()
            assertTrue("seek $seek", seek in 0.4f..0.6f)
            rec.events.clear()
            // shuffle and repeat leave the menu open; lyrics, output and expand close it
            listOf(1148f, 1196f, 1244f, 1292f, 1340f).forEachIndexed { i, x ->
                if (i == 0 || i > 2) scene.click(1144f, 166f)
                scene.click(x, 110f)
            }
            assertEquals(listOf("shuffle", "repeat", "pane:LYRICS", "output", "expand"), rec.events)
        }
    }

    @Test fun spotlightDockKeepsTheClassicControls() {
        val rec = Recorder()
        dock("dock-spotlight", 1440, PlayerFixtures.playing, rec, style = BarStyle.SPOTLIGHT).use { scene ->
            assertTrue(scene.shot().distinctColors(6) > 20)
            listOf(720f, 612f, 828f).forEach { scene.click(it, 46f) }
            assertEquals(listOf("play", "shuffle", "repeat"), rec.events)
        }
    }

    @Test fun roomyDockRoutesEveryControl() {
        val rec = Recorder()
        dock("dock-wide", 1440, PlayerFixtures.playing, rec, openPane = PlayerPane.LYRICS).use { scene ->
            val idle = scene.shot()
            assertTrue(idle.distinctColors(6) > 30)
            listOf(720f to 46f, 612f to 46f, 668f to 46f, 772f to 46f, 828f to 46f, 225f to 58f, 1160f to 58f,
                1064f to 58f, 1112f to 58f, 1396f to 58f, 50f to 58f, 110f to 68f).forEach { (x, y) -> scene.click(x, y) }
            assertEquals(listOf("play", "shuffle", "previous", "next", "repeat", "like", "output",
                "pane:LYRICS", "pane:QUEUE", "expand", "expand", "artist"), rec.events)
            rec.events.clear()
            scene.click(717f, 81f)
            val seek = rec.events.single().removePrefix("seek:").toFloat()
            assertTrue("seek $seek", seek in 0.45f..0.55f)
            rec.events.clear()
            scene.scroll(1302f, 58f, -1f)
            assertEquals(0.65f, rec.volume, 0.001f)
            val tip = scene.shot("-volume-tip")
            assertFalse(idle.region(1232, 0, 1372, 44).contentEquals(tip.region(1232, 0, 1372, 44)))
            scene.click(1208f, 58f)
            scene.click(1208f, 58f)
            assertEquals(listOf("mute", "mute"), rec.events)
            assertEquals(0.65f, rec.volume, 0.001f)
        }
    }

    @Test fun volumeSliderKeepsItsWidthAndHidesExpandBelow1400() {
        val rec = Recorder()
        dock("dock-1280", 1280, PlayerFixtures.playing, rec).use { scene ->
            scene.shot()
            scene.click(1258f, 58f)
            assertEquals(1f, rec.volume, 0.01f)
            scene.click(1122f, 58f)
            assertEquals(0f, rec.volume, 0.01f)
            scene.click(1096f, 58f)
            scene.click(1048f, 58f)
            assertEquals(listOf("mute", "output"), rec.events)
        }
        val narrow = Recorder()
        dock("dock-960", 960, PlayerFixtures.playing, narrow).use { scene ->
            scene.shot()
            scene.click(938f, 58f)
            assertEquals(1f, narrow.volume, 0.01f)
            scene.click(838f, 58f)
            assertEquals(0f, narrow.volume, 0.01f)
            scene.click(812f, 58f)
            scene.click(764f, 58f)
            assertEquals(listOf("mute", "output"), narrow.events)
        }
    }

    @Test fun compactDockKeepsTransportAndPaneToggles() {
        val rec = Recorder()
        dock("dock-compact", 720, PlayerFixtures.playing, rec).use { scene ->
            assertTrue(scene.shot().distinctColors(6) > 20)
            scene.click(676f, 58f)
            scene.click(628f, 58f)
            assertEquals(listOf("pane:QUEUE", "pane:LYRICS"), rec.events)
        }
    }

    @Test fun rendersLightGlassAndLiveStates() {
        dock("dock-glass-light", 1440, PlayerFixtures.playing.copy(isPlaying = false, shuffle = false), Recorder(),
            prefs = UiPrefs(themeMode = ThemeMode.LIGHT, themeStyle = ThemeStyle.GLASS)).use { assertTrue(it.shot().distinctColors(6) > 20) }
        val rec = Recorder()
        dock("dock-live", 1440, PlayerFixtures.playing.copy(isLive = true), rec).use { scene ->
            scene.shot()
            scene.click(717f, 81f)
            assertTrue(rec.events.isEmpty())
        }
    }
}
