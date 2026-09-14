package com.aurora.music.playback

import android.content.ComponentName
import android.os.SystemClock
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.AuroraApplication
import com.aurora.music.data.DspMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

/** A saved preset must reach the live service without replacing or starting the queue. */
class ProcessingPresetPlaybackDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (context.applicationContext as AuroraApplication).container
    private val lifecycleFixture = PrecisionPlaybackDeviceTest()

    @Before fun foregroundAndPreserveUserState() = lifecycleFixture.keepTargetForegroundForAudioFocus()
    @After fun restoreUserStateAndFinishActivity() = lifecycleFixture.removeFixturesAndFinishActivity()
    private fun <T> main(block: () -> T): T {
        val task = FutureTask(Callable(block))
        instrumentation.runOnMainSync(task)
        return task.get()
    }
    private fun await(label: String, check: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < until) {
            if (check()) return
            SystemClock.sleep(40)
        }
        fail("Timed out: $label")
    }

    @Test fun applyingSavedProcessingReachesThePlayingAndPausedService() {
        val store = container.settingsStore
        val original = runBlocking { store.processingSettings.first() }
        val originalHistory = container.playHistory.snapshot()
        assumeFalse(original.playback.bitPerfectUsb)
        assumeFalse(container.mixController.activeProject != null)
        val controller = main {
            MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        }.get(10, TimeUnit.SECONDS)
        if (main { controller.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE }) {
            main { controller.release() }
            assumeFalse("Keep remote sessions untouched", true)
        }
        val queue = main { (0 until controller.mediaItemCount).map(controller::getMediaItemAt) }
        val index = main { controller.currentMediaItemIndex }
        val position = main { controller.currentPosition }
        val repeat = main { controller.repeatMode }
        val playing = main { controller.playWhenReady }
        var presetId: String? = null
        var presetAsset: File? = null
        val file = fixture()
        try {
            main { controller.pause() }
            runBlocking {
                store.setDspMode(DspMode.CUSTOM)
                store.setProcessingRack(original.rack.copy(enabled = false)).getOrThrow()
                store.setMono(true)
                store.setDspPreamp(-5f)
                store.setDspConvEnabled(false)
                val saved = store.saveProcessingPreset("QA playback ${System.nanoTime()}").getOrThrow()
                presetId = saved.id
                if (saved.audio.dspConvIrPath != original.audio.dspConvIrPath) {
                    presetAsset = File(saved.audio.dspConvIrPath).takeIf {
                        it.parentFile == File(context.filesDir, "processing-presets")
                    }
                }
            }
            main {
                controller.setMediaItem(MediaItem.fromUri(file.toURI().toString()))
                controller.repeatMode = Player.REPEAT_MODE_OFF
                controller.prepare()
                controller.play()
            }
            await("fixture playing") { main { controller.isPlaying } }
            runBlocking { store.setDspMode(DspMode.OFF); store.setMono(false) }
            await("EQ bypass observed") { !container.signalPath.value.processing.detail.contains("Active: Custom") &&
                container.signalPath.value.processing.detail.contains("No active Aurora") }
            runBlocking { store.applyProcessingPreset(requireNotNull(presetId)).getOrThrow() }
            await("saved Custom mono reaches service") {
                val detail = container.signalPath.value.processing.detail
                detail.contains("Active:") && detail.contains("Mono downmix (inside Custom DSP")
            }
            main { assertTrue(controller.isPlaying); assertEquals(1, controller.mediaItemCount); controller.pause() }
            runBlocking { store.applyProcessingPreset(requireNotNull(presetId)).getOrThrow() }
            SystemClock.sleep(300)
            main { assertFalse("Applying a preset must not start paused music", controller.playWhenReady) }
        } finally {
            runBlocking {
                presetId?.let { store.deleteProcessingPreset(it).getOrThrow() }
                // Enabling a rack selects Custom mode; restore the original mode afterward.
                store.setProcessingRack(original.rack).getOrThrow()
                store.setDspMode(original.audio.dspMode)
                store.setMono(original.playback.monoAudio)
                store.setDspPreamp(original.audio.dspPreampDb)
                store.setDspConvEnabled(original.audio.dspConvEnabled)
                store.setDspConvIr(original.audio.dspConvIrPath, original.audio.dspConvIrName)
                container.playHistory.restoreBackup(originalHistory)
            }
            main {
                controller.pause()
                controller.repeatMode = repeat
                if (queue.isNotEmpty()) {
                    controller.setMediaItems(queue, index.coerceIn(queue.indices), position)
                    controller.prepare()
                    if (playing) controller.play()
                } else controller.clearMediaItems()
                controller.release()
            }
            file.delete()
            presetAsset?.delete()
        }
    }

    private fun fixture(): File = File(context.cacheDir, "preset-playback-${System.nanoTime()}.wav").also { file ->
        val rate = 44_100
        val frames = rate * 15
        file.outputStream().buffered().use { out ->
            fun le(value: Int, size: Int) { repeat(size) { out.write(value ushr (8 * it) and 255) } }
            out.write("RIFF".toByteArray()); le(36 + frames * 4, 4)
            out.write("WAVEfmt ".toByteArray()); le(16, 4); le(1, 2); le(2, 2)
            le(rate, 4); le(rate * 4, 4); le(4, 2); le(16, 2)
            out.write("data".toByteArray()); le(frames * 4, 4)
            repeat(frames) { i ->
                val sample = (sin(2 * PI * 440 * i / rate) * 200).toInt()
                le(sample, 2); le(sample, 2)
            }
        }
    }
}
