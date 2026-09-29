package com.aurora.music.ui.player

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.aurora.music.data.ThemeMode
import com.aurora.music.data.ThemeStyle
import com.aurora.music.data.UiPrefs
import com.aurora.music.desktop.player.PlayerUiState
import com.aurora.music.model.accent
import com.aurora.music.ui.screens.player.LyricsPane
import com.aurora.music.ui.screens.player.PlayerPane
import com.aurora.music.ui.screens.player.PlayerScreen
import com.aurora.music.ui.screens.player.QueueActions
import com.aurora.music.ui.screens.player.QueueContent
import com.aurora.music.ui.theme.rememberPlayerColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerScreenTest {
    private class Recorder {
        val events = mutableListOf<String>()
        var split = -1f
    }

    @Composable
    private fun Player(state: PlayerUiState, rec: Recorder) {
        MaterialTheme(colorScheme = rememberPlayerColorScheme(state.current.artworkUrl, state.current.accent)) {
            PlayerScreen(
                state = state,
                onCollapse = { rec.events += "collapse" },
                onTogglePlay = { rec.events += "play" },
                onNext = { rec.events += "next" },
                onPrevious = { rec.events += "previous" },
                onSeek = { rec.events += "seek" },
                onToggleLike = { rec.events += "like" },
                onToggleShuffle = { rec.events += "shuffle" },
                onCycleRepeat = { rec.events += "repeat" },
                onOpenSpeedPitch = { rec.events += "speed" },
                onGoToAlbum = { rec.events += "album" },
                onGoToArtist = { rec.events += "artist" },
                onOpenOutput = { rec.events += "output" },
                onOpenSleep = { rec.events += "sleep" },
                onOpenVisualizer = { rec.events += "visualizer" },
                onOpenSignalPath = { rec.events += "signal" },
                onSonicRadio = { rec.events += "radio" },
                onAutoDj = { rec.events += "autodj" },
                paneContent = { pane, modifier ->
                    when (pane) {
                        PlayerPane.LYRICS -> LyricsPane(state, onSeek = { rec.events += "lyric-seek" },
                            modifier = modifier.clip(RoundedCornerShape(20.dp)), loadLyrics = { PlayerFixtures.lyrics })
                        PlayerPane.QUEUE -> QueueContent(
                            queue = state.queue, currentIndex = state.currentIndex, isPlaying = state.isPlaying,
                            onJump = { rec.events += "jump:$it" }, onRemove = { rec.events += "remove:$it" },
                            onMove = { from, to -> rec.events += "move:$from:$to" }, onClear = { rec.events += "clear" },
                            onSaveAsPlaylist = { rec.events += "save:$it" }, editable = true,
                            showTitle = false, showActions = false, modifier = modifier.padding(horizontal = 4.dp),
                        )
                    }
                },
                paneActions = { pane ->
                    if (pane == PlayerPane.QUEUE) QueueActions(state.queue, state.currentIndex, true,
                        onClear = { rec.events += "clear" }, onSaveAsPlaylist = { rec.events += "save:$it" })
                },
                onSplitChange = { rec.split = it },
            )
        }
    }

    @Test fun landscapePlayerRoutesControlsAndSwitchesPanes() {
        val rec = Recorder()
        PlayerScene("player-lyrics", 1440, 900) { Player(PlayerFixtures.playing, rec) }.use { scene ->
            val lyrics = scene.shot()
            assertTrue(lyrics.distinctColors() > 60)
            listOf(900f to 385f, 370f to 795f, 132f to 795f, 244f to 795f, 496f to 795f, 608f to 795f, 606f to 579f,
                150f to 639f, 165f to 859f, 280f to 859f, 1348f to 36f, 52f to 36f).forEach { (x, y) -> scene.click(x, y) }
            assertEquals(listOf("lyric-seek", "play", "shuffle", "previous", "next", "repeat", "like",
                "signal", "speed", "sleep", "output", "collapse"), rec.events)

            scene.click(855f, 94f)
            val queue = scene.shot("-queue")
            assertTrue(queue.differsFrom(lyrics))

            scene.click(1388f, 36f)
            scene.shot("-menu")
        }
    }

    @Test fun dividerResizesThePanesWithinRange() {
        val rec = Recorder()
        PlayerScene("player-split", 1440, 900) { Player(PlayerFixtures.playing, rec) }.use { scene ->
            scene.shot()
            scene.drag(720f, 470f, 138f, 0f)
            assertEquals(0.6f, rec.split, 0.01f)
            val moved = scene.shot()
            scene.drag(32f + rec.split * 1352f + 12f, 470f, -600f, 0f)
            assertEquals(0.35f, rec.split, 0.0001f)
            assertTrue(scene.shot().differsFrom(moved))
        }
    }

    @Test fun fullscreenLyricsOverlayOpensAndCloses() {
        PlayerScene("player-overlay", 1440, 900) { Player(PlayerFixtures.playing, Recorder()) }.use { scene ->
            val before = scene.shot("-before")
            scene.click(1378f, 94f)
            val overlay = scene.shot()
            assertTrue(overlay.differsFrom(before))
            scene.click(36f, 36f)
            val after = scene.shot("-after")
            assertEquals(before.pixel(370, 300), after.pixel(370, 300))
        }
    }

    @Test fun rendersOtherStyles() {
        val state = PlayerFixtures.playing.copy(isPlaying = false, shuffle = false)
        PlayerScene("player-glass-light", 1440, 900, UiPrefs(themeMode = ThemeMode.LIGHT, themeStyle = ThemeStyle.GLASS)) {
            Player(state, Recorder())
        }.use { assertTrue(it.shot().distinctColors() > 40) }
        PlayerScene("player-small", 960, 600) { Player(PlayerFixtures.playing, Recorder()) }.use { it.shot() }
    }
}
