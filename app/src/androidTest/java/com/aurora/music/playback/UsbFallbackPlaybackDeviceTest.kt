package com.aurora.music.playback

import android.content.ComponentName
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.AuroraApplication
import com.aurora.music.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

class UsbFallbackPlaybackDeviceTest {
    @Test fun unavailableUsbHonorsFallbackPolicyThroughService() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as AuroraApplication).container
        val store = container.settingsStore
        assumeTrue(com.decent.usbaudio.UsbAudioDevice.getInstance(context).findUsbAudioDevice() == null)
        val arguments = InstrumentationRegistry.getArguments()
        val mode = UsbOutputMode.valueOf(arguments.getString("usbMode") ?: "PROCESSED")
        val fallback = UsbFallbackPolicy.valueOf(arguments.getString("usbFallback") ?: "PAUSE")
        val original = runBlocking { store.exportPrefs() }
        val helper = PrecisionPlaybackDeviceTest()
        var foreground = false
        var controller: MediaController? = null
        try {
            runBlocking {
                store.setBitPerfectUsb(true)
                store.setUsbOutputMode(mode)
                store.setUsbFallbackPolicy(fallback)
                store.setSkipSilence(false)
                store.setCrossfade(0)
                store.importPrefs(PrefsBackup(ints = mapOf("dsp_mode" to DspMode.CUSTOM),
                    booleans = mapOf("dsp_conv_enabled" to false)))
                store.setProcessingRack(ProcessingRack(enabled = true, name = "USB fixture", nodes = listOf(
                    ProcessingRackNode(UUID.randomUUID().toString(), "Gain", RackNodeKind.GAIN,
                        audio = AudioPrefs(dspPreampDb = -6f))))).getOrThrow()
            }
            helper.keepTargetForegroundForAudioFocus()
            foreground = true
            val connected = helper.main {
                MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
            }.get(12, TimeUnit.SECONDS)
            controller = connected
            val tone = helper.wav("usb-fallback", 48000, 48000 * 8) { frame, channel ->
                (sin(2 * PI * 1000 * frame / 48000) * if (channel == 0) 8192 else 4096).toInt()
            }
            helper.main {
                connected.stop(); connected.clearMediaItems()
                connected.repeatMode = Player.REPEAT_MODE_OFF
                connected.setMediaItem(MediaItem.fromUri(tone.toURI().toString()))
                connected.prepare(); connected.play()
            }
            if (fallback == UsbFallbackPolicy.PAUSE) {
                helper.await("USB failure pauses playback") { helper.main { !connected.playWhenReady } &&
                    container.signalPath.value.output == "USB unavailable" }
                assertFalse(helper.main { connected.isPlaying })
                assertNull(container.signalPath.value.outputStage.format)
                helper.await("USB failure is visible") { container.signalPath.value.note.contains("USB", ignoreCase = true) }
            } else {
                helper.await("USB fallback retains graph processing", connected) {
                    val path = container.signalPath.value
                    val before = path.measurements?.before; val after = path.measurements?.after
                    helper.main { connected.isPlaying } && path.output == "Android audio" &&
                        before != null && after != null && before.leftRms > 0.1 &&
                        abs(after.leftRms / before.leftRms - 10.0.pow(-6.0 / 20)) < 0.008
                }
                assertFalse(container.signalPath.value.bitPerfect)
                assertTrue(container.signalPath.value.reasons.any { it.contains("USB", ignoreCase = true) })
                helper.main { connected.pause(); connected.seekTo(4000); connected.play() }
                helper.await("Android fallback seek resumes", connected) { helper.main { connected.isPlaying && connected.currentPosition > 4200 } }
                helper.main { connected.seekTo(7400) }
                helper.await("Android fallback drains to EOS", connected) { helper.main { connected.playbackState == Player.STATE_ENDED } }
            }
        } finally {
            controller?.let { helper.main { it.stop(); it.clearMediaItems(); it.release() } }
            try { if (foreground) helper.removeFixturesAndFinishActivity() }
            finally { runBlocking { store.restoreBackupPrefs(original).getOrThrow() } }
        }
    }
}
