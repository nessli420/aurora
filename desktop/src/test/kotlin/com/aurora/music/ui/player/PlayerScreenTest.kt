package com.aurora.music.ui.player

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.aurora.music.data.Lyrics
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
import com.aurora.music.ui.testing.differsFrom
import com.aurora.music.ui.testing.distinctColors
import com.aurora.music.ui.testing.pixel
import com.aurora.music.ui.theme.LocalUiPrefs
import com.aurora.music.ui.theme.rememberPlayerColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerScreenTest {
    private class Recorder {
        val events = mutableListOf<String>()
        var split = -1f
        var volume by mutableFloatStateOf(0.6f)
    }

    @Composable
    private fun Player(state: PlayerUiState, rec: Recorder, lyrics: Lyrics? = PlayerFixtures.lyrics, onSaved: (Float) -> Unit = {}) {
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
                            modifier = modifier.clip(RoundedCornerShape(20.dp)), loadLyrics = { lyrics })
                        PlayerPane.QUEUE -> QueueContent(
                            queue = state.queue, currentIndex = state.currentIndex, isPlaying = state.isPlaying,
                            onJump = { rec.events += "jump:$it" }, onRemove = { rec.events += "remove:$it" },
                            onMove = { from, to -> rec.events += "move:$from:$to" }, editable = true,
                            modifier = modifier.padding(horizontal = 4.dp),
                        )
                    }
                },
                paneActions = { pane ->
                    if (pane == PlayerPane.QUEUE) QueueActions(state.queue, state.currentIndex, true,
                        onClear = { rec.events += "clear" }, onSaveAsPlaylist = { rec.events += "save:$it" })
                },
                onSplitChange = { rec.split = it; onSaved(it) },
                volume = rec.volume,
                onVolumeChange = { rec.volume = it },
                onToggleMute = { rec.events += "mute" },
            )
        }
    }

    @Test fun landscapePlayerRoutesControlsAndSwitchesPanes() {
        val rec = Recorder()
        PlayerScene("player-lyrics", 1440, 900) { Player(PlayerFixtures.playing, rec) }.use { scene ->
            val lyrics = scene.shot()
            assertTrue(lyrics.distinctColors(6) > 60)
            listOf(900f to 385f, 370f to 780f, 176f to 780f, 265f to 780f, 474f to 780f, 564f to 780f, 563f to 552f,
                200f to 612f, 205f to 852f, 325f to 852f, 443f to 852f, 1356f to 36f, 44f to 36f).forEach { (x, y) -> scene.click(x, y) }
            assertEquals(listOf("lyric-seek", "play", "shuffle", "previous", "next", "repeat", "like",
                "signal", "speed", "sleep", "mute", "output", "collapse"), rec.events)
            scene.click(585f, 852f)
            assertEquals(1f, rec.volume, 0.01f)
            scene.scroll(530f, 852f, 2f)
            assertEquals(0.9f, rec.volume, 0.001f)

            scene.click(855f, 94f)
            val queue = scene.shot("-queue")
            assertTrue(queue.differsFrom(lyrics))

            scene.click(1396f, 36f)
            scene.shot("-menu")
        }
    }

    @Test fun missingLyricsFallBackToTheQueueUntilLyricsArePicked() {
        val rec = Recorder()
        PlayerScene("player-no-lyrics", 1440, 900) { Player(PlayerFixtures.playing, rec, lyrics = null) }.use { scene ->
            val fallback = scene.shot()
            scene.click(1000f, 312f)
            assertEquals(listOf("jump:2"), rec.events)
            scene.click(785f, 94f)
            val picked = scene.shot("-picked")
            assertTrue(picked.differsFrom(fallback))
            scene.click(1000f, 312f)
            assertEquals(listOf("jump:2"), rec.events)
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

    @Test fun dividerFollowsTheSavedSplitAfterItChanges() {
        val rec = Recorder()
        var saved by mutableFloatStateOf(0.5f)
        PlayerScene("player-split-saved", 1440, 900) {
            CompositionLocalProvider(LocalUiPrefs provides LocalUiPrefs.current.copy(tabletPlayerSplit = saved)) {
                Player(PlayerFixtures.playing, rec) { saved = it }
            }
        }.use { scene ->
            scene.shot()
            scene.drag(720f, 470f, 138f, 0f)
            assertEquals(0.6f, rec.split, 0.01f)
            saved = 0.45f
            scene.shot()
            scene.drag(32f + saved * 1352f + 12f, 470f, 138f, 0f)
            assertEquals(0.55f, rec.split, 0.01f)
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
        }.use { assertTrue(it.shot().distinctColors(6) > 40) }
        PlayerScene("player-small", 960, 600) { Player(PlayerFixtures.playing, Recorder()) }.use { it.shot() }
    }
}
