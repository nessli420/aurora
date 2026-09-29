package com.aurora.music.ui.settings.general

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
import com.aurora.music.data.PlaybackCollectionIdentity
import com.aurora.music.data.SignalPath
import com.aurora.music.data.UiPrefs
import com.aurora.music.desktop.DesktopContainer
import com.aurora.music.desktop.natives.AudioDevice
import com.aurora.music.desktop.natives.DeviceKind
import com.aurora.music.desktop.platform.DesktopPaths
import com.aurora.music.desktop.player.PlayerController
import com.aurora.music.desktop.player.PlayerUiState
import com.aurora.music.desktop.ui.LocalDesktopContainer
import com.aurora.music.desktop.ui.LocalPlayer
import com.aurora.music.model.Song
import com.aurora.music.ui.layout.LocalWindowLayout
import com.aurora.music.ui.layout.WindowLayout
import com.aurora.music.ui.theme.AuroraTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.Assert.fail
import java.io.File
import java.nio.file.Files

internal val shotsDir: File? = System.getenv("AURORA_SHOTS")?.let { File(it, "settings-general") }

internal class FakePlayer(devices: List<AudioDevice> = SampleDevices) : PlayerController {
    override val state = MutableStateFlow(PlayerUiState())
    override val outputs = MutableStateFlow(devices)
    override val preferredOutput = MutableStateFlow<String?>(null)
    override val exclusiveOutput = MutableStateFlow(false)
    override val volume = MutableStateFlow(1f)
    override val signalPath = MutableStateFlow(SignalPath())

    override fun setPreferredDevice(deviceId: String?) { preferredOutput.value = deviceId }
    override fun setExclusiveOutput(enabled: Boolean) { exclusiveOutput.value = enabled }
    override fun setVolume(value: Float) { volume.value = value }

    override fun playAll(songs: List<Song>, startIndex: Int, collection: PlaybackCollectionIdentity?) = Unit
    override fun play(song: Song) = Unit
    override fun playCollection(kind: String, id: String, loaded: List<Song>, startIndex: Int, total: Int) = Unit
    override fun shuffleCollection(kind: String, id: String, loaded: List<Song>, total: Int) = Unit
    override fun shufflePlay(songs: List<Song>, collection: PlaybackCollectionIdentity?) = Unit
    override fun startSonicRadio(seed: Song, onResult: (String) -> Unit) = Unit
    override fun startAutoDj(seed: Song, onResult: (String) -> Unit) = Unit
    override fun addToQueue(song: Song) = Unit
    override fun playNext(song: Song) = Unit
    override fun jumpTo(index: Int) = Unit
    override fun removeFromQueue(index: Int) = Unit
    override fun clearQueue() = Unit
    override fun moveQueueItem(from: Int, to: Int) = Unit
    override fun saveQueueAsPlaylist(name: String, onResult: (String) -> Unit) = Unit
    override fun togglePlay() = Unit
    override fun seekTo(fraction: Float) = Unit
    override fun next() = Unit
    override fun previous() = Unit
    override fun toggleShuffle() = Unit
    override fun cycleRepeat() = Unit
    override fun toggleLikeCurrent() = Unit
    override fun refreshLikes() = Unit
    override fun checkLiked(ids: List<String>) = Unit
    override fun toggleLike(id: String, kind: String) = Unit
    override fun setExpanded(value: Boolean) = Unit
    override fun setSpeed(value: Float) = Unit
    override fun setPitch(value: Float) = Unit
    override fun setMatchPitch(match: Boolean) = Unit
    override fun resetSpeedPitch() = Unit
    override fun setSleepTimer(minutes: Int) = Unit
    override fun setSleepEndOfTrack() = Unit
    override fun stopPlayback() = Unit
}

internal val SampleDevices = listOf(
    AudioDevice("speakers", "Speakers (Realtek High Definition Audio)", DeviceKind.SPEAKERS, isDefault = true),
    AudioDevice("dac", "Topping E30 II", DeviceKind.DIGITAL_PASSTHROUGH, isDefault = false),
    AudioDevice("headphones", "WH-1000XM5", DeviceKind.HEADPHONES, isDefault = false),
)

internal class GeneralSettingsScene(
    private val name: String,
    val width: Int = 840,
    val height: Int = 900,
    val player: FakePlayer = FakePlayer(),
    prefs: UiPrefs = UiPrefs(),
    seed: suspend DesktopContainer.() -> Unit = {},
    content: @Composable () -> Unit,
) : AutoCloseable {
    val root: File = Files.createTempDirectory("aurora-general-settings").toFile()
    val container = DesktopContainer(DesktopPaths(File(root, "Roaming"), File(root, "Local")))
    private val lifecycleOwner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    }
    private var millis = 0L
    private val scene: ImageComposeScene

    init {
        runBlocking { container.seed() }
        scene = ImageComposeScene(width, height, Density(1f), content = {
            CompositionLocalProvider(
                LocalWindowLayout provides WindowLayout(1440, 900),
                LocalLifecycleOwner provides lifecycleOwner,
                LocalDesktopContainer provides container,
                LocalPlayer provides player,
            ) {
                AuroraTheme(prefs) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
                }
            }
        })
    }

    private fun render(): Image {
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

    fun shot(suffix: String = ""): Image {
        val image = settle()
        shotsDir?.let { dir -> File(dir.apply { mkdirs() }, "$name$suffix.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes) }
        return image
    }

    fun <T> await(timeoutMillis: Long = 4_000, read: suspend DesktopContainer.() -> T, done: (T) -> Boolean): T {
        val until = System.currentTimeMillis() + timeoutMillis
        while (true) {
            val value = runBlocking { container.read() }
            if (done(value)) return value
            if (System.currentTimeMillis() > until) fail("never reached the expected state, last value: $value")
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

internal fun Image.inkRows(): Int {
    val bitmap = Bitmap.makeFromImage(this)
    val background = bitmap.getColor(width - 3, height - 3)
    return (0 until height).count { y -> (8 until width - 16 step 3).any { x -> bitmap.getColor(x, y) != background } }
}
