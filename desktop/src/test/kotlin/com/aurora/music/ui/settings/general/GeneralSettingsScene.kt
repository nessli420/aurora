package com.aurora.music.ui.settings.general

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
import com.aurora.music.ui.testing.EdtScene
import com.aurora.music.ui.testing.saveTo
import com.aurora.music.ui.theme.AuroraTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
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
    private val ui: EdtScene

    init {
        runBlocking { container.seed() }
        ui = EdtScene(width, height) {
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

    fun shot(suffix: String = ""): Image = settle().saveTo(shotsDir, "$name$suffix")

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
        ui.close()
        container.close()
        runBlocking { container.scope.coroutineContext.job.join() }
        root.deleteRecursively()
    }
}
