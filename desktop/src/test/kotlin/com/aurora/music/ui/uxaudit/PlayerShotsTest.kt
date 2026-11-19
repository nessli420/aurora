package com.aurora.music.ui.uxaudit

import androidx.compose.runtime.CompositionLocalProvider
import com.aurora.music.data.FileLyrics
import com.aurora.music.data.PlayerStyle
import com.aurora.music.data.Preservation
import com.aurora.music.data.SeekStyle
import com.aurora.music.data.SignalPath
import com.aurora.music.data.ThemeMode
import com.aurora.music.data.ThemeStyle
import com.aurora.music.desktop.DesktopContainer
import com.aurora.music.desktop.player.PlayerController
import com.aurora.music.desktop.ui.LocalPlayer
import com.aurora.music.desktop.ui.Shortcut
import com.aurora.music.ui.AuroraApp
import com.aurora.music.ui.player.PlayerFixtures
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class PlayerShotsTest {
    private val shortcuts = MutableSharedFlow<Shortcut>(extraBufferCapacity = 8)
    private var sidecar: File? = null

    @Before fun gate() = assumeTrue(System.getenv("AURORA_SHOTS") != null)

    @After fun tidy() { sidecar?.delete() }

    @Test fun spotlight() = tour("spotlight", PlayerStyle.SPOTLIGHT, 1372f, 88f)

    @Test fun console() = tour("console", PlayerStyle.CONSOLE, 970f, 292f)

    @Test fun drift() = tour("drift", PlayerStyle.DRIFT, 300f, 522f)

    private fun tour(name: String, style: Int, lyricsX: Float, lyricsY: Float) {
        player("player-$name", 1440, 900) { settingsStore.setPlayerStyle(style) }.use { scene ->
            scene.shot("default", 6_000)
            scene.click(lyricsX, lyricsY)
            scene.shot("lyrics")
            check(shortcuts.tryEmit(Shortcut.QUEUE))
            scene.shot("queue")
        }
        player("player-$name", 1000, 720) { settingsStore.setPlayerStyle(style) }.use { it.shot("default", 6_000) }
        player("player-$name-bar", 1440, 900) { settingsStore.setPlayerStyle(style); settingsStore.setPlayerSeekStyle(SeekStyle.BAR) }.use { it.shot("default", 6_000) }
        player("player-$name-paper", 1440, 900) { settingsStore.setPlayerStyle(style); settingsStore.setThemeStyle(ThemeStyle.PAPER) }.use { it.shot("default", 6_000) }
        player("player-$name-light", 1440, 900) { settingsStore.setPlayerStyle(style); settingsStore.setThemeMode(ThemeMode.LIGHT) }.use { it.shot("default", 6_000) }
    }

    private fun player(label: String, w: Int, h: Int, setup: suspend DesktopContainer.() -> Unit): AuditScene {
        val base = AuditPlayer()
        val player = object : PlayerController by base {
            override val signalPath: StateFlow<SignalPath> = MutableStateFlow(SignalPath(active = true, codec = "FLAC", sampleRateHz = 96_000, bitDepth = 24,
                output = "WASAPI exclusive", bitPerfect = true, preservation = Preservation.PRESERVED))
        }
        return AuditScene(label, w, h, setup = {
            val playing = folderLibrary.songsByAlbumId(folderLibrary.albums.first { it.title == "Nocturne" }.id)[2]
            sidecar = FileLyrics.fileOf(playing)?.let { File(it.parentFile, "${it.nameWithoutExtension}.lrc") }?.apply {
                writeText(PlayerFixtures.lyrics.lines.joinToString("\n") { "[%02d:%02d.00]%s".format(it.timeSec / 60, it.timeSec % 60, it.text) })
            }
            setup()
        }) {
            CompositionLocalProvider(LocalPlayer provides player) { AuroraApp(shortcuts = shortcuts) }
        }.also { base.state.value = it.player.state.value.copy(expanded = true) }
    }
}
