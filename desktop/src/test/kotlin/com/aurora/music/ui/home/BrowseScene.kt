package com.aurora.music.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aurora.music.data.UiPrefs
import com.aurora.music.desktop.DesktopContainer
import com.aurora.music.desktop.platform.DesktopPaths
import com.aurora.music.desktop.ui.LocalDesktopContainer
import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import com.aurora.music.ui.components.AmbientBackground
import com.aurora.music.ui.layout.LocalWindowLayout
import com.aurora.music.ui.layout.WindowLayout
import com.aurora.music.ui.testing.EdtScene
import com.aurora.music.ui.testing.saveTo
import com.aurora.music.ui.theme.AuroraTheme
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import java.io.File
import java.nio.file.Files

internal object Sample {
    private val accents = listOf(0xFFFF2E7E, 0xFFFF7A59, 0xFFC24CE0, 0xFFFB7185, 0xFFF7B733, 0xFFA855F7, 0xFFFF5C8A, 0xFFFF8E6E)

    val songs = listOf(
        Song("s1", "Midnight Bloom", "Lunar Tide", "Nocturne", "", 214, liked = true, accentArgb = accents[0], albumId = "a1", artistId = "ar1"),
        Song("s2", "Velvet Skyline", "Mara Quinn", "Neon Hours", "", 187, accentArgb = accents[1], albumId = "a2", artistId = "ar2"),
        Song("s3", "Paper Planes", "The Foxgloves", "Wildflower", "", 241, liked = true, accentArgb = accents[2]),
        Song("s4", "Gravity", "Aerial", "Lightyears", "", 199, explicit = true, accentArgb = accents[3]),
        Song("s5", "Saltwater", "Coastlines", "Tidal", "", 263, accentArgb = accents[4]),
        Song("s6", "Ember", "Wren Holloway", "Slow Burn", "", 176, liked = true, accentArgb = accents[5]),
        Song("s7", "Cassette Dreams", "Polaroid Kids", "Analog", "", 224, accentArgb = accents[6]),
        Song("s8", "Northern Lights", "Glacier", "Aurora", "", 252, accentArgb = accents[7]),
    )

    val playlists = listOf(
        Playlist("p1", "Daily Mix 1", "Lunar Tide, Aerial and more", "", 50, accents[0]),
        Playlist("p2", "Late Night Drive", "Synthwave after dark", "", 42, accents[1]),
        Playlist("p3", "Focus Flow", "Instrumental concentration", "", 80, accents[3]),
        Playlist("p4", "Morning Coffee", "Easy acoustic mornings", "", 36, accents[4]),
        Playlist("p5", "Throwback Gold", "Hits you forgot you loved", "", 64, accents[2]),
        Playlist("p6", "Rainy Day", "Mellow & moody", "", 28, accents[6]),
    )

    val albums = listOf(
        Album("a1", "Nocturne", "Lunar Tide", "", 2024, 11),
        Album("a2", "Neon Hours", "Mara Quinn", "", 2023, 12),
        Album("a3", "Wildflower", "The Foxgloves", "", 2022, 2),
        Album("a4", "Lightyears", "Aerial", "", 2024, 5),
        Album("a5", "Tidal", "Coastlines", "", 2021, 13),
        Album("a6", "Analog", "Polaroid Kids", "", 2023, 8),
        Album("a7", "Sundrop", "Marigold", "", 2020, 10),
        Album("a8", "Frequency", "Neon Vows", "", 2019, 14),
    )

    val artists = listOf(
        Artist("ar1", "Lunar Tide", "", 2_480_000),
        Artist("ar2", "Mara Quinn", "", 5_120_000),
        Artist("ar3", "The Foxgloves", "", 980_000),
        Artist("ar4", "Aerial", "", 3_340_000),
        Artist("ar5", "Coastlines", "", 1_760_000),
        Artist("ar6", "Glacier", "", 640_000),
    )
}

internal class BrowseScene(
    private val name: String,
    val width: Int,
    val height: Int,
    prefs: UiPrefs = UiPrefs(),
    content: @Composable () -> Unit,
) : AutoCloseable {
    private val root = Files.createTempDirectory("aurora-browse").toFile()
    val container = DesktopContainer(DesktopPaths(File(root, "Roaming"), File(root, "Local")))
    private val lifecycle = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    }
    private val ui = EdtScene(width, height) {
        AuroraTheme(prefs) {
            CompositionLocalProvider(
                LocalWindowLayout provides WindowLayout(1440, 900),
                LocalLifecycleOwner provides lifecycle,
                LocalDesktopContainer provides container,
            ) {
                Box(Modifier.fillMaxSize()) {
                    AmbientBackground()
                    content()
                }
            }
        }
    }

    fun frames(count: Int = 6): Image {
        var image = ui.frame(0, applyChanges = false)
        repeat(count) { image = ui.frame(120) }
        return image
    }

    fun click(x: Float, y: Float, button: PointerButton = PointerButton.Primary) {
        val pressed = if (button == PointerButton.Secondary) PointerButtons(isSecondaryPressed = true) else PointerButtons(isPrimaryPressed = true)
        ui.click(x, y, button, pressed)
        frames(3)
    }

    fun shot(suffix: String = ""): Image = frames().saveTo(System.getenv("AURORA_SHOTS")?.let(::File), "$name$suffix")

    override fun close() {
        ui.close()
        container.close()
        runBlocking { container.scope.coroutineContext.job.join() }
        root.deleteRecursively()
    }
}
