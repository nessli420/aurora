package com.aurora.music.playback

import android.content.ComponentName
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.AuroraApplication
import com.aurora.music.MainActivity
import com.aurora.music.data.SignalPath
import com.aurora.music.data.UsbOutputMode
import com.aurora.music.playback.engine.OutputRateMode
import com.decent.usbaudio.UsbAudioDevice
import com.decent.usbaudio.UsbAudioDescriptors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class UsbHardwarePlaybackDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (context.applicationContext as AuroraApplication).container
    private val files = mutableListOf<File>()
    @Volatile private var expectedFixtureId: String? = null
    @Volatile private var fixtureViolation: String? = null
    private val eventTrace = CopyOnWriteArrayList<String>()

    @Test fun inspectAttachedDacFormatsAndClocks() {
        val usb = UsbAudioDevice.getInstance(context)
        val device = requireNotNull(usb.findUsbAudioDevice()) { "A physical USB DAC is required" }
        assertTrue("Approve Aurora's USB permission first", usb.hasPermission(device))
        val manager = context.getSystemService(UsbManager::class.java)
        val connection = requireNotNull(manager.openDevice(device))
        try {
            val raw = connection.rawDescriptors
            report("descriptors", raw.joinToString("") { "%02x".format(it.toInt() and 255) })
            val parsed = UsbAudioDescriptors.parse(raw)
            report("formats", "malformed=${parsed.malformed}; ${parsed.formats.joinToString("\n") { "$it; unsupported=${it.unsupportedReason}" }}")
            parsed.formats.distinctBy { it.controlInterfaceId to it.clockSourceId }.forEach { format ->
                fun request(label: String) {
                    val index = format.clockSourceId shl 8 or format.controlInterfaceId
                    val count = ByteArray(2)
                    val result = connection.controlTransfer(0xa1, 2, 0x0100, index, count, count.size, 500)
                    report("clock-$label", "interface=${format.controlInterfaceId}; clock=${format.clockSourceId}; countResult=$result; countBytes=${count.toList()}")
                    val ranges = (count[0].toInt() and 255) or ((count[1].toInt() and 255) shl 8)
                    if (result == 2 && ranges in 1..64) {
                        val buffer = ByteArray(2 + ranges * 12)
                        val fullResult = connection.controlTransfer(0xa1, 2, 0x0100, index, buffer, buffer.size, 500)
                        report("ranges-$label", "result=$fullResult; bytes=${buffer.toList()}; parsed=${runCatching { UsbAudioDescriptors.parseClockRanges(buffer) }}")
                    }
                }
                request("before-claim")
                val control = (0 until device.interfaceCount).map(device::getInterface)
                    .firstOrNull { it.id == format.controlInterfaceId && it.alternateSetting == 0 }
                val claimed = control?.let { connection.claimInterface(it, true) } == true
                report("control-claim", "interface=${format.controlInterfaceId}; claimed=$claimed")
                try { request("after-claim") }
                finally { if (claimed) connection.releaseInterface(requireNotNull(control)) }
            }
        } finally { connection.close() }
        try {
            val opened = usb.openDevice(device)
            report("open", "device=${opened?.deviceName}; failure=${usb.lastFailure}")
            requireNotNull(opened) { usb.lastFailure ?: "USB device did not open" }.formats.forEach { format ->
                report("clock", "interface=${format.interfaceId}; alt=${format.alternateSetting}; rates=${usb.getClockRates(format)}")
            }
        } finally { usb.closeDevice() }
    }

    @Test fun physicalUsbPlaysQuietPcmAcrossRatesPauseSeekAndEnd() {
        val mode = UsbOutputMode.valueOf(InstrumentationRegistry.getArguments().getString("usbMode") ?: "PROCESSED")
        val preferences = runBlocking { container.settingsStore.playbackPrefs.first() }
        assertTrue("Enable USB DAC output before running hardware tests", preferences.bitPerfectUsb)
        assertEquals("Select the requested USB mode and restart Aurora", mode, preferences.usbOutputMode)
        if (mode == UsbOutputMode.PROCESSED) assertEquals("Select Follow source for the rate-change test",
            OutputRateMode.FOLLOW_SOURCE, preferences.outputRatePolicy.mode)
        assertNull("Finish the active Mix before hardware testing", container.mixController.activeProject)
        val usb = UsbAudioDevice.getInstance(context)
        val device = requireNotNull(usb.findUsbAudioDevice()) { "A physical USB DAC is required" }
        assertTrue("Approve Aurora's USB permission before running hardware tests", usb.hasPermission(device))
        report("device", "${device.productName}: ${device.vendorId}:${device.productId}; mode=$mode")

        runBlocking { container.sessionReady.first { it != null } }
        val account = container.currentAccountKey()
        val savedQueue = account.takeIf { it.isNotBlank() }?.let(container.queueStore::get)
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        var controller: MediaController? = null
        try {
            await("foreground activity") { main {
                activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && activity.hasWindowFocus()
            } }
            val connected = main {
                MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
            }.get(12, TimeUnit.SECONDS)
            controller = connected
            assertEquals(DeviceInfo.PLAYBACK_TYPE_LOCAL, main { connected.deviceInfo.playbackType })
            val savedIds = savedQueue?.tracks.orEmpty().filter { !it.id.isNullOrEmpty() && !it.streamUrl.isNullOrEmpty() }.map { it.id }
            if (savedIds.isNotEmpty()) await("complete saved queue restored", connected) { main {
                (0 until connected.mediaItemCount).map { connected.getMediaItemAt(it).mediaId } == savedIds
            } }
            assertFalse("Pause existing playback before the hardware test", main { connected.playWhenReady })
            withRestoredQueue(connected) {
                val arguments = InstrumentationRegistry.getArguments()
                val holdSeconds = arguments.getString("holdSeconds")?.toInt() ?: 0
                require(holdSeconds in 0..8) { "holdSeconds must be between 0 and 8" }
                val externalFixture = arguments.getString("fixturePath")
                val formats = if (externalFixture == null) listOf(44_100 to 16, 48_000 to 24, 96_000 to 24)
                    else listOf((arguments.getString("fixtureRate")?.toInt() ?: 44_100) to
                        (arguments.getString("fixtureBits")?.toInt() ?: 16))
                for ((rate, bits) in formats) {
                    val file = if (externalFixture == null) quietWav(rate, bits) else {
                        val source = File(externalFixture)
                        require(source.isFile && source.canRead()) { "The external fixture is not readable" }
                        File(context.cacheDir, "usb-hardware-${System.nanoTime()}.${source.extension}").also {
                            files += it; source.copyTo(it)
                        }
                    }
                    val item = MediaItem.Builder().setMediaId("aurora-mix:usb-hardware-$rate-$bits")
                        .setUri(file.toURI().toString())
                        .setMediaMetadata(MediaMetadata.Builder().setTitle("USB hardware test").build()).build()
                    main {
                        expectedFixtureId = null
                        connected.stop(); connected.clearMediaItems()
                        connected.repeatMode = Player.REPEAT_MODE_OFF
                        connected.setPlaybackSpeed(1f)
                        if (mode == UsbOutputMode.PROCESSED) connected.volume = 0.1f
                        connected.setMediaItem(item)
                        expectedFixtureId = item.mediaId
                        connected.prepare(); connected.play()
                    }
                    await("$rate Hz/$bits-bit USB playback", connected) {
                        val path = container.signalPath.value
                        main { connected.isPlaying && connected.currentPosition > 500 } &&
                            path.output == outputName(mode) && path.outputStage.format?.rateHz == rate &&
                            path.usbDiagnostics?.completedFrames?.let { it > rate / 2 } == true
                    }
                    assertTransport(rate, mode)
                    if (mode == UsbOutputMode.DIRECT && file.extension.equals("flac", ignoreCase = true)) {
                        assertEquals(container.signalPath.value.toDiagnosticReport(), "Native libFLAC", container.signalPath.value.decoder.detail)
                    }
                    val initial = requireNotNull(container.signalPath.value.usbDiagnostics).completedFrames
                    await("$rate Hz USB completions advance", connected) {
                        requireNotNull(container.signalPath.value.usbDiagnostics).completedFrames > initial + rate
                    }
                    assertTransport(rate, mode)
                    report("$rate-$bits-start", container.signalPath.value.toDiagnosticReport())
                    val holdUntil = SystemClock.elapsedRealtime() + holdSeconds * 1_000L
                    while (SystemClock.elapsedRealtime() < holdUntil) {
                        fixtureViolation?.let { fail("$it\n${eventTrace.joinToString("\n")}") }
                        assertTransport(rate, mode)
                        main {
                            assertNull("Renderer error during sustained USB playback", connected.playerError)
                            assertTrue("USB remains playing during the hold", connected.isPlaying)
                        }
                        SystemClock.sleep(100)
                    }
                    if (holdSeconds > 0) report("$rate-$bits-hold", container.signalPath.value.toDiagnosticReport())

                    main { connected.pause() }
                    await("USB pause", connected) { main { !connected.isPlaying && !connected.playWhenReady } }
                    SystemClock.sleep(500)
                    val paused = main { connected.currentPosition }
                    SystemClock.sleep(350)
                    assertTrue("Paused position keeps still", abs(main { connected.currentPosition } - paused) < 150)
                    main { connected.play() }
                    await("USB resume", connected) { main { connected.isPlaying && connected.currentPosition > paused + 350 } }
                    awaitUsbProgress("USB resume completions", connected, rate, mode)

                    main { connected.seekTo(5_000) }
                    await("USB forward seek", connected) { main {
                        connected.isPlaying && connected.currentPosition in 5_300..7_500
                    } }
                    awaitUsbProgress("USB forward seek completions", connected, rate, mode)
                    main { connected.seekTo(1_000) }
                    await("USB backward seek", connected) { main {
                        connected.isPlaying && connected.currentPosition in 1_300..3_500
                    } }
                    val seekPath = awaitUsbProgress("USB backward seek completions", connected, rate, mode)
                    report("$rate-$bits-seek", seekPath.toDiagnosticReport())

                    val endingPosition = main { (connected.duration - 2_000).coerceAtLeast(0) }
                    main { connected.seekTo(endingPosition) }
                    var lastActiveReport = awaitUsbProgress("USB final seek completions", connected, rate, mode).toDiagnosticReport()
                    await("USB end of stream", connected) {
                        val path = container.signalPath.value
                        if (path.usbDiagnostics != null) {
                            assertTransport(rate, mode, path, requireCompleted = false)
                            lastActiveReport = path.toDiagnosticReport()
                        }
                        main { connected.playbackState == Player.STATE_ENDED }
                    }
                    assertNull("USB finishes without renderer error", main { connected.playerError })
                    report("$rate-$bits-end", "STATE_ENDED; final active snapshot:\n$lastActiveReport")
                }
            }
        } finally {
            try { controller?.let { main { it.pause(); it.release() } } }
            finally {
                try {
                    main { activity.finish() }
                    instrumentation.waitForIdleSync()
                    await("test activity finished") { main { activity.isDestroyed } }
                } finally {
                    files.forEach { it.delete() }
                    if (account.isNotBlank()) runBlocking { container.queueStore.restoreAccount(account, savedQueue) }
                }
            }
        }
    }

    private fun awaitUsbProgress(label: String, controller: MediaController, rate: Int, mode: UsbOutputMode): SignalPath {
        var observed = container.signalPath.value
        var previousFrames = observed.usbDiagnostics?.completedFrames ?: 0L
        var confirmed: SignalPath? = null
        await(label, controller) {
            val path = container.signalPath.value
            if (path === observed) false else {
                observed = path
                assertTransport(rate, mode, path, requireCompleted = false)
                val frames = requireNotNull(path.usbDiagnostics).completedFrames
                if (frames < previousFrames) previousFrames = frames
                val advancing = frames >= previousFrames + rate / 10
                if (advancing) {
                    assertTransport(rate, mode, path)
                    confirmed = path
                }
                advancing
            }
        }
        return requireNotNull(confirmed)
    }

    private fun assertTransport(rate: Int, mode: UsbOutputMode, path: SignalPath = container.signalPath.value,
        requireCompleted: Boolean = true) {
        val diagnostic = path.toDiagnosticReport()
        assertEquals(diagnostic, outputName(mode), path.output)
        assertEquals(diagnostic, rate, path.outputStage.format?.rateHz)
        assertEquals(diagnostic, "integer PCM", path.outputStage.format?.encoding)
        assertTrue(diagnostic, path.outputStage.evidence.contains("clock request accepted"))
        assertTrue(diagnostic, path.device.evidence.contains("Live native USB stream"))
        assertFalse(diagnostic, path.processing.detail.contains("restart the app"))
        val transport = requireNotNull(path.usbDiagnostics) { diagnostic }
        assertEquals(diagnostic, 0L, transport.packetErrors)
        assertEquals(diagnostic, 0L, transport.timeouts)
        assertTrue(diagnostic, transport.completedFrames >= if (requireCompleted) 1 else 0)
        assertTrue(diagnostic, transport.pendingFrames >= 0)
        assertTrue(diagnostic, transport.pendingFrames < rate * 5L)
        if (mode == UsbOutputMode.PROCESSED) {
            assertTrue(diagnostic, path.processing.detail.contains("binary64"))
            path.measurements?.after?.let {
                assertEquals(diagnostic, 0L, it.invalidSamples)
                assertEquals(diagnostic, 0L, it.fullScaleSamples)
            }
        }
    }

    internal fun withRestoredQueue(controller: MediaController, block: () -> Unit) {
        val queue = main { (0 until controller.mediaItemCount).map(controller::getMediaItemAt) }
        val index = main { controller.currentMediaItemIndex }
        val position = main { controller.currentPosition }
        val repeat = main { controller.repeatMode }
        val parameters = main { controller.playbackParameters }
        val volume = main { controller.volume }
        val shuffled = main { controller.shuffleModeEnabled }
        var originalOrder: List<String>? = null
        fixtureViolation = null
        eventTrace.clear()
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                val id = player.currentMediaItem?.mediaId
                val entry = "state=${player.playbackState}; id=$id; items=${player.mediaItemCount}; position=${player.currentPosition}; playing=${player.isPlaying}; repeat=${player.repeatMode}"
                eventTrace += entry
                while (eventTrace.size > 40) eventTrace.removeAt(0)
                report("player-event", entry)
                val expected = expectedFixtureId
                if (expected != null && ((id != null && id != expected) || player.mediaItemCount > 1)) {
                    if (fixtureViolation == null) fixtureViolation = "Fixture queue changed: expected=$expected; $entry"
                    player.pause()
                }
            }
        }
        try {
            if (shuffled) {
                shuffle(controller, false)
                originalOrder = main { (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId } }
            }
            main { controller.addListener(listener) }
            block()
        } finally {
            main {
                expectedFixtureId = null
                controller.removeListener(listener)
                controller.pause(); controller.stop(); controller.clearMediaItems()
            }
            shuffle(controller, false)
            await("fixture playback stopped", controller) { main {
                controller.playbackState == Player.STATE_IDLE && controller.mediaItemCount == 0 && !controller.playWhenReady
            } }
            main {
                controller.repeatMode = repeat
                controller.playbackParameters = parameters
                controller.volume = volume
                if (queue.isNotEmpty()) controller.setMediaItems(queue, index.coerceIn(queue.indices), position)
            }
            if (shuffled) shuffle(controller, true, requireNotNull(originalOrder))
            assertEquals("Original physical queue restored", queue.map { it.mediaId }, main {
                (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId }
            })
            await("restored library queue remains paused", controller) { main { !controller.playWhenReady } }
            SystemClock.sleep(300)
            assertFalse("Restored library queue remains paused", main { controller.playWhenReady })
        }
    }

    private fun shuffle(controller: MediaController, enabled: Boolean, order: List<String>? = null) {
        val extras = Bundle().apply {
            putInt("target", if (enabled) 1 else 0)
            order?.let { putStringArrayList("order", ArrayList(it)) }
        }
        val result = main {
            controller.sendCustomCommand(SessionCommand(PlaybackService.CMD_SHUFFLE, extras), Bundle.EMPTY)
        }.get(10, TimeUnit.SECONDS)
        assertEquals(0, result.resultCode)
        await("shuffle state restored") { main { controller.shuffleModeEnabled == enabled } }
    }

    internal fun quietWav(rate: Int, bits: Int): File {
        val file = File(context.cacheDir, "usb-hardware-$rate-$bits-${System.nanoTime()}.wav")
        files += file
        val frames = rate * 12
        val sampleBytes = bits / 8
        val frameBytes = sampleBytes * 2
        val amplitude = ((1L shl (bits - 1)) * 0.003).toInt()
        file.outputStream().buffered().use { output ->
            fun le(value: Int, size: Int) { repeat(size) { output.write(value ushr (8 * it) and 255) } }
            output.write("RIFF".toByteArray()); le(36 + frames * frameBytes, 4)
            output.write("WAVEfmt ".toByteArray()); le(16, 4); le(1, 2); le(2, 2)
            le(rate, 4); le(rate * frameBytes, 4); le(frameBytes, 2); le(bits, 2)
            output.write("data".toByteArray()); le(frames * frameBytes, 4)
            repeat(frames) { frame ->
                val value = (sin(2 * PI * 440 * frame / rate) * amplitude).toInt()
                le(value, sampleBytes); le(value / 2, sampleBytes)
            }
        }
        return file
    }

    private fun outputName(mode: UsbOutputMode) = if (mode == UsbOutputMode.PROCESSED)
        "Processed USB transport" else "Direct USB transport"

    private fun report(label: String, value: String) {
        android.util.Log.i("AuroraUsbHardware", "$label: $value")
        instrumentation.sendStatus(2, Bundle().apply { putString("usbHardware", "$label: $value") })
    }

    private fun <T> main(block: () -> T): T {
        val task = FutureTask(Callable(block))
        instrumentation.runOnMainSync(task)
        return task.get()
    }

    private fun await(label: String, controller: MediaController? = null, check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (expectedFixtureId != null) fixtureViolation?.let { fail("$it\n${eventTrace.joinToString("\n")}") }
            if (check()) return
            controller?.let { main { assertNull("Renderer error during $label", it.playerError) } }
            SystemClock.sleep(50)
        }
        fail("Timed out: $label\n${eventTrace.joinToString("\n")}\n${container.signalPath.value.toDiagnosticReport()}")
    }
}
