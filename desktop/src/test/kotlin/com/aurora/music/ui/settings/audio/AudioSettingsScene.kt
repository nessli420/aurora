package com.aurora.music.ui.settings.audio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
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
import com.aurora.music.ui.theme.AuroraTheme
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.Assert.fail
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext

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
    private var millis = 0L
    private val testThread = TestThreadDispatcher()
    private val scene: ImageComposeScene

    init {
        runBlocking { seed(container.settingsStore) }
        scene = ImageComposeScene(width, height, Density(1f), coroutineContext = testThread, content = {
            CompositionLocalProvider(
                LocalWindowLayout provides WindowLayout(1440, 900),
                LocalLifecycleOwner provides lifecycleOwner,
                LocalDesktopContainer provides container,
            ) {
                AuroraTheme(prefs) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
                }
            }
        })
        settle()
    }

    private fun render(): Image {
        testThread.drain()
        Snapshot.sendApplyNotifications()
        millis += 32
        return scene.render(millis * 1_000_000)
    }

    fun settle(realMillis: Long = 500): Image {
        val until = System.currentTimeMillis() + realMillis
        var image = render()
        while (System.currentTimeMillis() < until) {
            Thread.sleep(15)
            image = render()
        }
        return image
    }

    fun click(x: Float, y: Float) {
        scene.sendPointerEvent(PointerEventType.Move, Offset(x, y), timeMillis = millis)
        scene.sendPointerEvent(PointerEventType.Press, Offset(x, y), timeMillis = millis, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        millis += 40
        scene.sendPointerEvent(PointerEventType.Release, Offset(x, y), timeMillis = millis, buttons = PointerButtons(), button = PointerButton.Primary)
        settle(300)
    }

    fun scroll(x: Float, y: Float, delta: Float) {
        scene.sendPointerEvent(PointerEventType.Scroll, Offset(x, y), scrollDelta = Offset(0f, delta), timeMillis = millis)
        settle(250)
    }

    fun shot(suffix: String = ""): Image {
        val image = settle()
        System.getenv("AURORA_SHOTS")?.let(::File)?.let { dir ->
            File(dir.apply { mkdirs() }, "$name$suffix.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
        return image
    }

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
        scene.close()
        container.close()
        runBlocking { container.scope.coroutineContext.job.join() }
        root.deleteRecursively()
    }
}

internal fun Image.distinctColors(): Int {
    val bitmap = Bitmap.makeFromImage(this)
    val colors = HashSet<Int>()
    for (y in 0 until height step 5) for (x in 0 until width step 5) colors += bitmap.getColor(x, y)
    return colors.size
}

internal fun Image.differsFrom(other: Image): Boolean =
    !Bitmap.makeFromImage(this).readPixels()!!.contentEquals(Bitmap.makeFromImage(other).readPixels()!!)

internal fun Image.inkRows(x0: Int, x1: Int): Int {
    val bitmap = Bitmap.makeFromImage(this)
    val background = bitmap.getColor(width - 3, height - 3)
    return (0 until height).count { y -> (x0 until x1 step 3).any { x -> bitmap.getColor(x, y) != background } }
}

// runs composition effects on the rendering thread like the swing dispatcher does in the app
private class TestThreadDispatcher : CoroutineDispatcher() {
    private val queue = ConcurrentLinkedQueue<Runnable>()
    override fun dispatch(context: CoroutineContext, block: Runnable) { queue += block }
    fun drain() { while (true) (queue.poll() ?: return).run() }
}
