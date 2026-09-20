package com.aurora.music.playback

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.AuroraApplication
import com.aurora.music.data.UsbDsdMode
import com.aurora.music.data.UsbFallbackPolicy
import com.aurora.music.data.UsbOutputMode
import com.aurora.music.playback.dsd.DsdFixtures
import com.decent.usbaudio.UsbAudioDevice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@UnstableApi
class RawDsdHardwarePlaybackDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (context.applicationContext as AuroraApplication).container
    private val helper = PrecisionPlaybackDeviceTest()

    @Test fun mixedQueuePausesSeeksChangesRatesAndEndsOnPhysicalDac() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("dsdHardware") == "true")
        val mode = UsbDsdMode.valueOf(args.getString("dsdMode") ?: "DOP")
        require(mode != UsbDsdMode.PCM)
        val highRate = args.getString("dsdHighRate")?.toInt() ?: 5644800
        require(highRate == 5644800 || mode == UsbDsdMode.NATIVE && highRate == 11289600)
        val store = container.settingsStore
        val original = runBlocking { store.exportPrefs() }
        val files = mutableListOf<File>()
        var controller: MediaController? = null
        var foreground = false
        try {
            val usb = UsbAudioDevice.getInstance(context)
            val device = requireNotNull(usb.findUsbAudioDevice())
            if (!usb.hasPermission(device)) {
                val permission = java.util.concurrent.CountDownLatch(1)
                helper.main { usb.requestPermission(device) { permission.countDown() } }
                assertTrue(permission.await(65, TimeUnit.SECONDS))
            }
            assertTrue("USB permission required", usb.hasPermission(device))
            assertNull("Finish the active Mix first", container.mixController.activeProject)
            runBlocking {
                store.setBitPerfectUsb(true)
                store.setUsbOutputMode(UsbOutputMode.DIRECT)
                store.setUsbDsdMode(mode)
                store.setUsbFallbackPolicy(UsbFallbackPolicy.PAUSE)
                store.setCrossfade(0)
            }
            runBlocking { container.sessionReady.first { it != null } }
            val savedIds = container.currentAccountKey().takeIf(String::isNotBlank)
                ?.let(container.queueStore::get)?.tracks.orEmpty()
                .filter { !it.id.isNullOrEmpty() && !it.streamUrl.isNullOrEmpty() }.map { it.id }
            foreground = true
            helper.keepTargetForegroundForAudioFocus()
            val connected = helper.main {
                MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
            }.get(12, TimeUnit.SECONDS)
            controller = connected
            helper.main { connected.pause() }
            if (savedIds.isNotEmpty()) helper.await("saved queue restored", connected) { helper.main {
                (0 until connected.mediaItemCount).map { connected.getMediaItemAt(it).mediaId } == savedIds
            } }
            val hardware = UsbHardwarePlaybackDeviceTest()
            hardware.withRestoredQueue(connected) {
                fun dsd(extension: String, rate: Int, seconds: Int = 10): File {
                    val channels = Array(2) { ByteArray(rate / 8 * seconds) { 0x69 } }
                    val bytes = if (extension == "dsf") DsdFixtures.dsf(channels, rate) else DsdFixtures.dff(channels, rate)
                    return File(context.cacheDir, "raw-hardware-${System.nanoTime()}.$extension").also {
                        files += it; it.writeBytes(bytes)
                    }
                }
                val tracks = listOf(hardware.quietWav(44100, 16).also { files += it },
                    dsd("dsf", 2822400), dsd("dff", highRate),
                    hardware.quietWav(96000, 24).also { files += it }, dsd("dsf", 2822400))
                val rates = listOf(44100, 2822400, highRate, 96000, 2822400)
                val items = tracks.mapIndexed { i, file -> MediaItem.Builder()
                    .setMediaId("aurora-mix:raw-hardware-$i").setUri(file.toURI().toString()).build() }
                helper.main {
                    connected.stop(); connected.clearMediaItems()
                    connected.repeatMode = Player.REPEAT_MODE_OFF
                    connected.setPlaybackSpeed(1f)
                    connected.setMediaItems(items); connected.prepare(); connected.play()
                }
                for (index in tracks.indices) {
                    val raw = tracks[index].extension != "wav"
                    val output = if (!raw) "Direct USB transport" else if (mode == UsbDsdMode.DOP) "DoP · USB" else "Native DSD · USB"
                    val rate = if (!raw) rates[index] else rates[index] / if (mode == UsbDsdMode.DOP) 16 else 32
                    fun progressing(label: String) {
                        helper.await(label, connected) {
                            val path = container.signalPath.value
                            helper.main { connected.currentMediaItemIndex == index && connected.isPlaying && connected.currentPosition > 300 } &&
                                path.output == output && path.outputStage.format?.rateHz == rate &&
                                (path.usbDiagnostics?.completedFrames ?: 0) > rate / 10
                        }
                        val before = container.signalPath.value.usbDiagnostics!!.completedFrames
                        helper.await("$label USB completions", connected) {
                            val path = container.signalPath.value
                            path.output == output && (path.usbDiagnostics?.completedFrames ?: 0) > before + rate / 4
                        }
                        val path = container.signalPath.value
                        assertEquals(path.toDiagnosticReport(), 0L, path.usbDiagnostics!!.packetErrors)
                        assertEquals(path.toDiagnosticReport(), 0L, path.usbDiagnostics!!.timeouts)
                        instrumentation.sendStatus(2, Bundle().apply { putString("lifecycle", "$label\n${path.toDiagnosticReport()}") })
                    }
                    progressing("$mode track $index start")
                    helper.main { connected.pause() }
                    SystemClock.sleep(500)
                    val paused = helper.main { connected.currentPosition }
                    SystemClock.sleep(350)
                    assertTrue("Paused position stays still", abs(helper.main { connected.currentPosition } - paused) < 100)
                    helper.main { connected.play() }
                    progressing("$mode track $index resume")
                    helper.main { connected.seekTo(5000) }
                    helper.await("forward seek", connected) { helper.main { connected.currentPosition in 5300..8000 } }
                    progressing("$mode track $index forward")
                    helper.main { connected.seekTo(1000) }
                    helper.await("backward seek", connected) { helper.main { connected.currentPosition in 1300..4000 } }
                    progressing("$mode track $index backward")
                    helper.main { connected.seekTo(connected.duration - 1200) }
                    helper.await("track $index EOS", connected) { helper.main {
                        if (index == tracks.lastIndex) connected.playbackState == Player.STATE_ENDED
                        else connected.currentMediaItemIndex == index + 1
                    } }
                }
                assertNull(helper.main { connected.playerError })
                val unsupported = dsd("dsf", 22579200, 1)
                helper.main {
                    connected.stop()
                    connected.setMediaItem(MediaItem.fromUri(unsupported.toURI().toString()))
                    connected.prepare(); connected.play()
                }
                helper.await("unsupported raw rate rejected") { helper.main { connected.playerError != null } }
                helper.await("unsupported raw rate reported") {
                    val path = container.signalPath.value
                    !path.active && path.reasons.any { it.contains("exceeds the DAC's raw output range") }
                }
                helper.main {
                    connected.stop(); connected.setMediaItem(items[1]); connected.prepare(); connected.play()
                }
                helper.await("supported DSD reopens after rejected rate", connected) {
                    val path = container.signalPath.value
                    helper.main { connected.isPlaying && connected.currentPosition > 500 } && path.active &&
                        path.decoder.detail == "Raw DSD" && (path.usbDiagnostics?.completedFrames ?: 0) > 1000
                }
                instrumentation.sendStatus(2, Bundle().apply { putString("lifecycle", "$mode unsupported rate rejected; supported DSD recovered") })
                if (args.getString("dsdReconnect") == "true") {
                    helper.main { connected.repeatMode = Player.REPEAT_MODE_ONE }
                    instrumentation.sendStatus(2, Bundle().apply { putString("lifecycle", "READY_FOR_DAC_RECONNECT") })
                    fun awaitDevice(label: String, predicate: () -> Boolean) {
                        val deadline = SystemClock.elapsedRealtime() + 180_000
                        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
                        assertTrue(label, predicate())
                    }
                    awaitDevice("DAC disconnected") { usb.findUsbAudioDevice() == null }
                    helper.await("raw stream stops after disconnect") {
                        helper.main { connected.playerError != null && !connected.isPlaying }
                    }
                    assertFalse("No Android fallback for raw DSD", container.signalPath.value.active)
                    instrumentation.sendStatus(2, Bundle().apply { putString("lifecycle", "DAC_DISCONNECT_CONFIRMED") })
                    awaitDevice("DAC reconnected") { usb.findUsbAudioDevice() != null }
                    val reconnected = requireNotNull(usb.findUsbAudioDevice())
                    if (!usb.hasPermission(reconnected)) {
                        val permission = java.util.concurrent.CountDownLatch(1)
                        helper.main { usb.requestPermission(reconnected) { permission.countDown() } }
                        assertTrue(permission.await(65, TimeUnit.SECONDS))
                    }
                    helper.main { connected.prepare(); connected.play() }
                    helper.await("DSD resumes after DAC reconnect", connected) {
                        val path = container.signalPath.value
                        helper.main { connected.isPlaying && connected.currentPosition > 300 } && path.active &&
                            path.decoder.detail == "Raw DSD" && (path.usbDiagnostics?.completedFrames ?: 0) > 1000
                    }
                    val status = requireNotNull(container.signalPath.value.usbDiagnostics)
                    assertEquals(0L, status.packetErrors)
                    assertEquals(0L, status.timeouts)
                    instrumentation.sendStatus(2, Bundle().apply { putString("lifecycle", "$mode DAC reconnect recovered") })
                }
            }
        } finally {
            try { controller?.let { helper.main { it.pause(); it.release() } } }
            finally {
                try { if (foreground) helper.removeFixturesAndFinishActivity() }
                finally {
                    context.stopService(Intent(context, PlaybackService::class.java))
                    runBlocking { store.restoreBackupPrefs(original).getOrThrow() }
                    files.forEach(File::delete)
                }
            }
        }
    }
}
