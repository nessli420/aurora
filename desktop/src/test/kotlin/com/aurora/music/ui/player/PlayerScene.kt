package com.aurora.music.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aurora.music.data.Lyrics
import com.aurora.music.data.UiPrefs
import com.aurora.music.desktop.DesktopContainer
import com.aurora.music.desktop.platform.DesktopPaths
import com.aurora.music.desktop.player.PlayerUiState
import com.aurora.music.desktop.player.RepeatMode
import com.aurora.music.desktop.ui.LocalDesktopContainer
import com.aurora.music.model.LyricLine
import com.aurora.music.model.Song
import com.aurora.music.ui.components.AmbientBackground
import com.aurora.music.ui.layout.LocalWindowLayout
import com.aurora.music.ui.layout.WindowLayout
import com.aurora.music.ui.testing.EdtScene
import com.aurora.music.ui.testing.edt
import com.aurora.music.ui.testing.saveTo
import com.aurora.music.ui.theme.AuroraTheme
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.GradientStyle
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Surface
import java.io.File
import java.nio.file.Files

internal object PlayerFixtures {
    private val dir = Files.createTempDirectory("aurora-art").toFile().apply { deleteOnExit() }

    private fun art(name: String, from: Int, to: Int): String {
        val surface = Surface.makeRasterN32Premul(320, 320)
        val paint = Paint().apply {
            shader = Shader.makeLinearGradient(0f, 0f, 320f, 320f, intArrayOf(from, to), null, GradientStyle.DEFAULT)
        }
        surface.canvas.drawRect(Rect.makeWH(320f, 320f), paint)
        val file = File(dir, "$name.png").apply { deleteOnExit() }
        file.writeBytes(surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes)
        return file.absolutePath
    }

    val queue = listOf(
        Song("q0", "Paper Planes", "The Foxgloves", "Wildflower", art("q0", 0xFF3B82F6.toInt(), 0xFF1E1B4B.toInt()), 241, accentArgb = 0xFF3B82F6),
        Song("q1", "Midnight Bloom", "Lunar Tide", "Nocturne", art("q1", 0xFFFF2E7E.toInt(), 0xFF3B0764.toInt()), 214,
            accentArgb = 0xFFFF2E7E, albumId = "a1", artistId = "ar1", suffix = "flac", sampleRateHz = 96_000, bitDepth = 24,
            bitrateKbps = 2_304, streamUrl = "https://music.example/stream/q1"),
        Song("q2", "Velvet Skyline", "Mara Quinn", "Neon Hours", art("q2", 0xFFFF7A59.toInt(), 0xFF7C2D12.toInt()), 187, accentArgb = 0xFFFF7A59),
        Song("q3", "Gravity", "Aerial", "Lightyears", art("q3", 0xFF22C55E.toInt(), 0xFF052E16.toInt()), 199, accentArgb = 0xFF22C55E),
        Song("q4", "Saltwater", "Coastlines", "Tidal", art("q4", 0xFF06B6D4.toInt(), 0xFF083344.toInt()), 263, accentArgb = 0xFF06B6D4),
        Song("q5", "Ember", "Wren Holloway", "Slow Burn", art("q5", 0xFFF59E0B.toInt(), 0xFF451A03.toInt()), 176, accentArgb = 0xFFF59E0B),
        Song("q6", "Cassette Dreams", "Polaroid Kids", "Analog", art("q6", 0xFFA855F7.toInt(), 0xFF2E1065.toInt()), 224, accentArgb = 0xFFA855F7),
        Song("q7", "Northern Lights", "Glacier", "Aurora", art("q7", 0xFF14B8A6.toInt(), 0xFF042F2E.toInt()), 252, accentArgb = 0xFF14B8A6),
    )

    val playing = PlayerUiState(
        current = queue[1],
        queue = queue,
        currentIndex = 1,
        isPlaying = true,
        positionSec = 64f,
        shuffle = true,
        repeat = RepeatMode.ONE,
        likedIds = setOf("q1"),
        speed = 1.25f,
        bpm = 118,
        camelot = "8A",
        keyName = "A minor",
    )

    val lyrics = Lyrics(
        listOf(
            LyricLine(0, "Neon rivers under a paper moon"),
            LyricLine(12, "We were counting every falling star"),
            LyricLine(24, "Hold the silence like a secret tune"),
            LyricLine(36, "Midnight blooming where the wild things are"),
            LyricLine(52, "Say my name before the morning comes"),
            LyricLine(62, "Every heartbeat is a distant drum"),
            LyricLine(75, "Light the city with a single spark"),
            LyricLine(88, "Follow the echo home through the dark"),
            LyricLine(101, "We will bloom again"),
        ),
        synced = true,
        source = "LRCLIB",
    )
}

internal class PlayerScene(
    private val name: String,
    val width: Int,
    val height: Int,
    prefs: UiPrefs = UiPrefs(),
    content: @Composable () -> Unit,
) : AutoCloseable {
    private val root = Files.createTempDirectory("aurora-player").toFile()
    private val container = DesktopContainer(DesktopPaths(File(root, "Roaming"), File(root, "Local")))
    private val lifecycle = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    }
    private val ui: EdtScene

    init {
        runBlocking { container.settingsStore.setLrclibEnabled(false) }
        ui = EdtScene(width, height) {
            CompositionLocalProvider(
                LocalWindowLayout provides WindowLayout(1440, 900),
                LocalLifecycleOwner provides lifecycle,
                LocalDesktopContainer provides container,
            ) {
                AuroraTheme(prefs) {
                    Box(Modifier.fillMaxSize()) {
                        AmbientBackground()
                        content()
                    }
                }
            }
        }
    }

    fun frames(count: Int = 6, realMillis: Long = 0): Image {
        var image = ui.frame(0, applyChanges = false)
        repeat(count) {
            if (realMillis > 0) {
                edt { Snapshot.sendApplyNotifications() }
                Thread.sleep(realMillis)
                image = ui.frame(120, applyChanges = false)
            } else image = ui.frame(120)
        }
        return image
    }

    private fun move(x: Float, y: Float) = ui.input { sendPointerEvent(PointerEventType.Move, Offset(x, y), timeMillis = ui.millis) }

    fun hover(x: Float, y: Float) {
        move(x, y)
        frames(4)
    }

    fun click(x: Float, y: Float) {
        ui.click(x, y)
        frames(4)
    }

    fun rightClick(x: Float, y: Float) {
        ui.click(x, y, PointerButton.Secondary, PointerButtons(isSecondaryPressed = true))
        frames(4)
    }

    fun drag(x: Float, y: Float, dx: Float, dy: Float, steps: Int = 12) {
        move(x, y)
        ui.input {
            sendPointerEvent(PointerEventType.Press, Offset(x, y), timeMillis = ui.millis,
                buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        }
        for (step in 1..steps) {
            ui.millis += 16
            ui.input {
                sendPointerEvent(PointerEventType.Move, Offset(x + dx * step / steps, y + dy * step / steps), timeMillis = ui.millis,
                    buttons = PointerButtons(isPrimaryPressed = true))
                render(ui.millis * 1_000_000)
            }
        }
        ui.millis += 16
        ui.input {
            sendPointerEvent(PointerEventType.Release, Offset(x + dx, y + dy), timeMillis = ui.millis,
                buttons = PointerButtons(), button = PointerButton.Primary)
        }
        frames(4)
    }

    fun scroll(x: Float, y: Float, delta: Float) {
        move(x, y)
        ui.input { sendPointerEvent(PointerEventType.Scroll, Offset(x, y), scrollDelta = Offset(0f, delta), timeMillis = ui.millis) }
        frames(2)
    }

    fun shot(suffix: String = "", realMillis: Long = 40): Image =
        frames(20, realMillis).saveTo(System.getenv("AURORA_SHOTS")?.let(::File), "$name$suffix")

    override fun close() {
        ui.close()
        container.close()
        runBlocking { container.scope.coroutineContext.job.join() }
        root.deleteRecursively()
    }
}
