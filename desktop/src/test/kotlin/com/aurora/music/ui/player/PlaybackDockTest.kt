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
                    onSeek = { rec.events += "seek:%.2f".format(it) },
                    onToggleLike = { rec.events += "like" },
                    onToggleShuffle = { rec.events += "shuffle" },
                    onCycleRepeat = { rec.events += "repeat" },
                    onVolumeChange = { rec.volume = it },
                    onOpenOutput = { rec.events += "output" },
                    onPane = { rec.events += "pane:$it" },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                )
            }
        }

    @Test fun roomyDockRendersEveryControl() {
        val rec = Recorder()
        dock("dock-wide", 1440, PlayerFixtures.playing, rec, openPane = PlayerPane.LYRICS).use { scene ->
            assertTrue(scene.shot().distinctColors() > 30)
        }
    }

    @Test fun compactDockAndOtherStyles() {
        dock("dock-compact", 720, PlayerFixtures.playing, Recorder()).use { assertTrue(it.shot().distinctColors() > 20) }
        dock("dock-glass-light", 1440, PlayerFixtures.playing.copy(isPlaying = false, shuffle = false), Recorder(),
            prefs = UiPrefs(themeMode = ThemeMode.LIGHT, themeStyle = ThemeStyle.GLASS)).use { it.shot() }
        dock("dock-live", 1440, PlayerFixtures.playing.copy(isLive = true), Recorder()).use { it.shot() }
    }
}
