package com.aurora.music.playback

import android.content.ComponentName
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.AuroraApplication
import com.aurora.music.data.DspMode
import com.aurora.music.data.Preservation
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
import kotlin.math.abs
import kotlin.math.sin

/** Real service contract, including the library commands consumed by Android Auto. */
class R0PlaybackDeviceTest {
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
        val end = SystemClock.elapsedRealtime() + 12_000
        while (SystemClock.elapsedRealtime() < end) {
            if (check()) return
            SystemClock.sleep(40)
        }
        fail("Timed out: $label")
    }

    @Test fun serviceBrowseTransportAndSignalPathFollowActualPlayback() {
        val store = container.settingsStore
        val prefs = runBlocking { store.playbackPrefs.first() }
        val audio = runBlocking { store.audioPrefs.first() }
        val oldRack = runBlocking { store.processingRack.first() }
        val oldHistory = container.playHistory.snapshot()
        assumeFalse("An exclusive USB session needs physical DAC validation", prefs.bitPerfectUsb)
        assumeFalse("Keep an active Mix session untouched", container.mixController.activeProject != null)
        val browser = main {
            MediaBrowser.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        }.get(12, TimeUnit.SECONDS)
        if (main { browser.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE }) {
            main { browser.release() }
            assumeFalse("Keep remote playback untouched", true)
        }
        val oldQueue = main { (0 until browser.mediaItemCount).map { browser.getMediaItemAt(it) } }
        val oldIndex = main { browser.currentMediaItemIndex }
        val oldPosition = main { browser.currentPosition }
        val oldRepeat = main { browser.repeatMode }
        val oldWanted = main { browser.playWhenReady }
        val file = fixture()
        try {
            val root = main { browser.getLibraryRoot(null) }.get(12, TimeUnit.SECONDS)
            assertEquals(0, root.resultCode)
            assertNotNull(root.value)
            val children = main { browser.getChildren(root.value!!.mediaId, 0, 20, null) }.get(12, TimeUnit.SECONDS)
            assertEquals(0, children.resultCode)
            assertTrue("Library browsing commands must be granted", children.value!!.isNotEmpty())

            runBlocking {
                store.setCrossfade(0)
                store.setReplayGain(0)
                store.setDspMode(DspMode.CUSTOM)
                store.setProcessingRack(oldRack.copy(enabled = false)).getOrThrow()
                store.setDspConvEnabled(false)
            }
            val extras = Bundle().apply { putFloat("rgTrack", -6f) }
            val item = MediaItem.Builder().setMediaId("r0-fixture")
                .setUri(file.toURI().toString())
                .setMediaMetadata(MediaMetadata.Builder().setTitle("R0_PRIVATE_TITLE").setExtras(extras).build())
                .build()
            main {
                browser.setMediaItem(item)
                browser.repeatMode = Player.REPEAT_MODE_OFF
                browser.prepare()
                browser.play()
            }
            await("PCM source and processor path observed") {
                val path = container.signalPath.value
                path.active && path.decoder.format?.rateHz == 44100 &&
                    // The requested-but-not-yet-applied list also contains "Custom" during
                    // sink preparation. Wait for observed processing, not that request label.
                    path.reasons.any { it.startsWith("Custom Aurora DSP") && it.endsWith("processes the current samples") }
            }
            val processed = container.signalPath.value
            assertEquals(Preservation.MODIFIED, processed.preservation)
            assertFalse(processed.bitPerfect)
            assertEquals(2, processed.decoder.format?.channels)
            assertFalse("A local filename must not enter diagnostic reports", processed.toDiagnosticReport().contains(file.name))
            assertFalse(processed.toDiagnosticReport().contains("R0_PRIVATE_TITLE"))
            val report = File(context.getExternalFilesDir(null), "r0-signal-path.txt")
            report.writeText(processed.toDiagnosticReport())

            main { browser.pause() }
            await("pause") { main { !browser.isPlaying } }
            val paused = main { browser.currentPosition }
            SystemClock.sleep(250)
            assertTrue(abs(main { browser.currentPosition } - paused) < 100)
            main { browser.seekTo(3000); browser.play() }
            await("seek and resume") { main { browser.currentPosition > 3150 && browser.isPlaying } }

            runBlocking { store.setReplayGain(1) }
            await("applied ReplayGain becomes visible") {
                container.signalPath.value.reasons.any { it.contains("gain", ignoreCase = true) || it.contains("volume", ignoreCase = true) }
            }
            main { browser.stop(); browser.clearMediaItems() }
            await("idle clears old source and output") { !container.signalPath.value.active }
            assertNull(container.signalPath.value.decoder.format)
            assertNull(container.signalPath.value.outputStage.format)
        } finally {
            runBlocking {
                // Enabling a rack selects Custom mode; restore the original mode afterward.
                store.setProcessingRack(oldRack).getOrThrow()
                store.setCrossfade(prefs.crossfadeSec)
                store.setReplayGain(audio.replayGain)
                store.setDspMode(audio.dspMode)
                store.setDspConvEnabled(audio.dspConvEnabled)
                container.playHistory.restoreBackup(oldHistory)
            }
            main {
                browser.pause()
                browser.repeatMode = oldRepeat
                if (oldQueue.isNotEmpty()) {
                    browser.setMediaItems(oldQueue, oldIndex.coerceIn(oldQueue.indices), oldPosition)
                    browser.prepare()
                    browser.playWhenReady = oldWanted
                } else browser.clearMediaItems()
                browser.release()
            }
            file.delete()
        }
    }

    private fun fixture(): File {
        val file = File(context.cacheDir, "r0-${System.nanoTime()}.wav")
        val rate = 44100
        val frames = rate * 12
        file.outputStream().buffered().use { out ->
            fun le(value: Int, size: Int) { repeat(size) { out.write(value ushr (8 * it) and 255) } }
            out.write("RIFF".toByteArray()); le(36 + frames * 4, 4)
            out.write("WAVEfmt ".toByteArray()); le(16, 4); le(1, 2); le(2, 2)
            le(rate, 4); le(rate * 4, 4); le(4, 2); le(16, 2)
            out.write("data".toByteArray()); le(frames * 4, 4)
            repeat(frames) { i ->
                val sample = (sin(2 * PI * 440 * i / rate) * 300).toInt()
                le(sample, 2); le(sample, 2)
            }
        }
        return file
    }
}
