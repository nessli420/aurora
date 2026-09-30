package com.aurora.music.ui.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.aurora.music.data.PlaybackCollectionIdentity
import com.aurora.music.desktop.DesktopContainer
import com.aurora.music.desktop.natives.AudioDevice
import com.aurora.music.desktop.platform.DesktopPaths
import com.aurora.music.desktop.player.PlayerController
import com.aurora.music.desktop.player.PlayerUiState
import com.aurora.music.desktop.ui.AuroraRoot
import com.aurora.music.desktop.ui.Shortcut
import com.aurora.music.model.Song
import com.aurora.music.ui.AuroraApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.GradientStyle
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Surface
import org.junit.Assert.fail
import java.io.File
import java.nio.file.Files
import javax.swing.SwingUtilities

internal object ShellFixtures {
    private val dir = Files.createTempDirectory("aurora-shell-art").toFile().apply { deleteOnExit() }

    private fun art(name: String, from: Int, to: Int): String {
        val surface = Surface.makeRasterN32Premul(256, 256)
        surface.canvas.drawRect(Rect.makeWH(256f, 256f), Paint().apply {
            shader = Shader.makeLinearGradient(0f, 0f, 256f, 256f, intArrayOf(from, to), null, GradientStyle.DEFAULT)
        })
        val file = File(dir, "$name.png").apply { deleteOnExit() }
        file.writeBytes(surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes)
        return file.absolutePath
    }

    val queue = listOf(
        Song("t0", "Midnight Bloom", "Lunar Tide", "Nocturne", art("t0", 0xFFFF2E7E.toInt(), 0xFF3B0764.toInt()), 214,
            accentArgb = 0xFFFF2E7E, albumId = "a1", artistId = "ar1"),
        Song("t1", "Velvet Skyline", "Mara Quinn", "Neon Hours", art("t1", 0xFFFF7A59.toInt(), 0xFF7C2D12.toInt()), 187, accentArgb = 0xFFFF7A59),
        Song("t2", "Saltwater", "Coastlines", "Tidal", art("t2", 0xFF06B6D4.toInt(), 0xFF083344.toInt()), 263, accentArgb = 0xFF06B6D4),
    )

    val playing = PlayerUiState(current = queue[0], queue = queue, currentIndex = 0, isPlaying = true, positionSec = 64f)
}

internal class ShellPlayer(initial: PlayerUiState = PlayerUiState()) : PlayerController {
    override val state = MutableStateFlow(initial)
    override val outputs = MutableStateFlow(emptyList<AudioDevice>())
    override val preferredOutput = MutableStateFlow<String?>(null)
    override val exclusiveOutput = MutableStateFlow(false)
    override val volume = MutableStateFlow(0.8f)
    val calls = mutableListOf<String>()

    override fun setExpanded(value: Boolean) = state.update { it.copy(expanded = value) }
    override fun togglePlay() {
        calls += "togglePlay"
        state.update { it.copy(isPlaying = !it.isPlaying) }
    }
    override fun next() { calls += "next" }
    override fun previous() { calls += "previous" }
    override fun toggleLikeCurrent() { calls += "like" }
    override fun refreshLikes() { calls += "refreshLikes" }
    override fun playAll(songs: List<Song>, startIndex: Int, collection: PlaybackCollectionIdentity?) { calls += "playAll" }
    override fun play(song: Song) { calls += "play" }
    override fun playCollection(kind: String, id: String, loaded: List<Song>, startIndex: Int, total: Int) { calls += "playCollection" }
    override fun shuffleCollection(kind: String, id: String, loaded: List<Song>, total: Int) { calls += "shuffleCollection" }
    override fun shufflePlay(songs: List<Song>, collection: PlaybackCollectionIdentity?) { calls += "shufflePlay" }
    override fun startSonicRadio(seed: Song, onResult: (String) -> Unit) = Unit
    override fun startAutoDj(seed: Song, onResult: (String) -> Unit) = Unit
    override fun addToQueue(song: Song) = Unit
    override fun playNext(song: Song) = Unit
    override fun jumpTo(index: Int) = Unit
    override fun removeFromQueue(index: Int) = Unit
    override fun clearQueue() = Unit
    override fun moveQueueItem(from: Int, to: Int) = Unit
    override fun saveQueueAsPlaylist(name: String, onResult: (String) -> Unit) = Unit
    override fun seekTo(fraction: Float) = Unit
    override fun toggleShuffle() = Unit
    override fun cycleRepeat() = Unit
    override fun checkLiked(ids: List<String>) = Unit
    override fun toggleLike(id: String, kind: String) = Unit
    override fun setSpeed(value: Float) = Unit
    override fun resetSpeedPitch() = Unit
    override fun setSleepTimer(minutes: Int) = Unit
    override fun setSleepEndOfTrack() = Unit
    override fun setPreferredDevice(deviceId: String?) = Unit
    override fun setExclusiveOutput(enabled: Boolean) = Unit
    override fun setVolume(value: Float) { volume.value = value }
    override fun stopPlayback() = Unit
}

internal class ShellScene(
    private val name: String,
    val player: ShellPlayer = ShellPlayer(),
    width: Int = 1440,
    height: Int = 900,
    seed: suspend DesktopContainer.() -> Unit = {},
) : AutoCloseable {
    private val root = Files.createTempDirectory("aurora-shell").toFile()
    val container = DesktopContainer(DesktopPaths(File(root, "Roaming"), File(root, "Local")))
    val shortcuts = MutableSharedFlow<Shortcut>(extraBufferCapacity = 8)
    var fullscreen by mutableStateOf(false)
        private set
    lateinit var nav: NavHostController
        private set
    private val lifecycle = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    }
    private val scene: ImageComposeScene
    private var millis = 0L

    val route: String? get() = edt { nav.currentDestination?.route }

    init {
        runBlocking {
            container.settingsStore.setLrclibEnabled(false)
            container.seed()
        }
        scene = edt { ImageComposeScene(width, height, Density(1f), coroutineContext = Dispatchers.Main) {
            val controller = rememberNavController()
            nav = controller
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                AuroraRoot(container, player) {
                    AuroraApp(navController = controller, shortcuts = shortcuts, fullscreen = fullscreen, onFullscreenChange = { fullscreen = it })
                }
            }
        } }
    }

    fun <T> edt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun render(): Image = edt {
        Snapshot.sendApplyNotifications()
        millis += 32
        scene.render(millis * 1_000_000)
    }

    fun settle(realMillis: Long = 600): Image {
        val until = System.currentTimeMillis() + realMillis
        var image = render()
        while (System.currentTimeMillis() < until) {
            Thread.sleep(15)
            image = render()
        }
        return image
    }

    fun awaitRoute(expected: String, timeoutMillis: Long = 8_000) {
        val until = System.currentTimeMillis() + timeoutMillis
        while (route != expected) {
            if (System.currentTimeMillis() > until) fail("route never became $expected, last $route")
            settle(50)
        }
        settle(400)
    }

    fun click(x: Float, y: Float, button: PointerButton = PointerButton.Primary) {
        val pressed = if (button == PointerButton.Back) PointerButtons(isBackPressed = true) else PointerButtons(isPrimaryPressed = true)
        edt {
            scene.sendPointerEvent(PointerEventType.Move, Offset(x, y), timeMillis = millis)
            scene.sendPointerEvent(PointerEventType.Press, Offset(x, y), timeMillis = millis, buttons = pressed, button = button)
            millis += 40
            scene.sendPointerEvent(PointerEventType.Release, Offset(x, y), timeMillis = millis, buttons = PointerButtons(), button = button)
        }
        settle(400)
    }

    fun navigate(route: String) {
        edt { nav.navigate(route) }
        awaitRoute(route)
    }

    fun press(shortcut: Shortcut) {
        check(shortcuts.tryEmit(shortcut))
        settle(400)
    }

    fun refocus() {
        edt {
            lifecycle.lifecycle.currentState = Lifecycle.State.STARTED
            lifecycle.lifecycle.currentState = Lifecycle.State.RESUMED
        }
        settle(100)
    }

    fun shot(suffix: String): Image {
        val image = settle()
        System.getenv("AURORA_SHOTS")?.let { File(it, "shell") }?.let { dir ->
            File(dir.apply { mkdirs() }, "$name-$suffix.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
        return image
    }

    override fun close() {
        edt { scene.close() }
        container.close()
        runBlocking { container.scope.coroutineContext.job.join() }
        root.deleteRecursively()
    }
}

internal fun Image.region(x0: Int, y0: Int, x1: Int, y1: Int): IntArray {
    val bitmap = Bitmap.makeFromImage(this)
    return IntArray((x1 - x0) * (y1 - y0)) { i -> bitmap.getColor(x0 + i % (x1 - x0), y0 + i / (x1 - x0)) }
}
