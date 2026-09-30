package com.aurora.music.ui.settings.audio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aurora.music.data.SettingsStore
import com.aurora.music.data.UiPrefs
import com.aurora.music.desktop.DesktopContainer
import com.aurora.music.desktop.platform.DesktopPaths
import com.aurora.music.desktop.ui.LocalDesktopContainer
import com.aurora.music.ui.layout.LocalWindowLayout
import com.aurora.music.ui.layout.WindowLayout
import com.aurora.music.ui.testing.EdtScene
import com.aurora.music.ui.testing.saveTo
import com.aurora.music.ui.theme.AuroraTheme
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import org.junit.Assert.fail
import java.io.File
import java.nio.file.Files

internal class AudioSettingsScene(
    private val name: String,
    val width: Int = 840,
    val height: Int = 900,
    prefs: UiPrefs = UiPrefs(),
    seed: suspend (SettingsStore) -> Unit = {},
    content: @Composable () -> Unit,
) : AutoCloseable {
    private val root = Files.createTempDirectory("aurora-audio-settings").toFile()
    val container = DesktopContainer(DesktopPaths(File(root, "Roaming"), File(root, "Local")))
    val store: SettingsStore get() = container.settingsStore
    private val lifecycleOwner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    }
    private val ui: EdtScene

    init {
        runBlocking { seed(container.settingsStore) }
        ui = EdtScene(width, height) {
            CompositionLocalProvider(
                LocalWindowLayout provides WindowLayout(1440, 900),
                LocalLifecycleOwner provides lifecycleOwner,
                LocalDesktopContainer provides container,
            ) {
                AuroraTheme(prefs) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
                }
            }
        }
        settle()
    }

    fun settle(realMillis: Long = 500): Image {
        val until = System.currentTimeMillis() + realMillis
        var image = ui.frame(32)
        while (System.currentTimeMillis() < until) {
            Thread.sleep(15)
            image = ui.frame(32)
        }
        return image
    }

    fun click(x: Float, y: Float) {
        ui.click(x, y)
        settle(300)
    }

    fun scroll(x: Float, y: Float, delta: Float) {
        ui.input { sendPointerEvent(PointerEventType.Scroll, Offset(x, y), scrollDelta = Offset(0f, delta), timeMillis = ui.millis) }
        settle(250)
    }

    fun shot(suffix: String = ""): Image = settle().saveTo(System.getenv("AURORA_SHOTS")?.let(::File), "$name$suffix")

    fun <T> await(timeoutMillis: Long = 4_000, read: suspend (SettingsStore) -> T, done: (T) -> Boolean): T {
        val until = System.currentTimeMillis() + timeoutMillis
        while (true) {
            val value = runBlocking { read(store) }
            if (done(value)) return value
            if (System.currentTimeMillis() > until) fail("store never reached the expected state, last value: $value")
            settle(50)
        }
    }

    override fun close() {
        ui.close()
        container.close()
        runBlocking { container.scope.coroutineContext.job.join() }
        root.deleteRecursively()
    }
}
