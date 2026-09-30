package com.aurora.music.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
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

internal val accountShots: File? = System.getenv("AURORA_SHOTS")?.let { File(it, "auth-profile") }

internal class AccountScene(
    private val name: String,
    val width: Int = 960,
    val height: Int = 900,
    prefs: UiPrefs = UiPrefs(),
    seed: suspend DesktopContainer.() -> Unit = {},
    content: @Composable () -> Unit,
) : AutoCloseable {
    val root: File = Files.createTempDirectory("aurora-account").toFile()
    val container = DesktopContainer(DesktopPaths(File(root, "Roaming"), File(root, "Local")))
    private val lifecycleOwner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    }
    private val storeOwner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    private val ui: EdtScene

    init {
        runBlocking { container.seed() }
        ui = EdtScene(width, height) {
            CompositionLocalProvider(
                LocalWindowLayout provides WindowLayout(1440, 900),
                LocalLifecycleOwner provides lifecycleOwner,
                LocalViewModelStoreOwner provides storeOwner,
                LocalDesktopContainer provides container,
            ) {
                AuroraTheme(prefs) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
                }
            }
        }
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

    fun shot(suffix: String = ""): Image = settle().saveTo(accountShots, "$name$suffix")

    fun <T> await(timeoutMillis: Long = 5_000, read: suspend DesktopContainer.() -> T, done: (T) -> Boolean): T {
        val until = System.currentTimeMillis() + timeoutMillis
        while (true) {
            val value = runBlocking { container.read() }
            if (done(value)) return value
            if (System.currentTimeMillis() > until) fail("never reached the expected state, last value: $value")
            settle(50)
        }
    }

    override fun close() {
        ui.close()
        storeOwner.viewModelStore.clear()
        container.close()
        runBlocking { container.scope.coroutineContext.job.join() }
        root.deleteRecursively()
    }
}
