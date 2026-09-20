package com.aurora.music.playback.dsd

import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.test.platform.app.InstrumentationRegistry
import com.decent.usbaudio.UsbAudioDevice
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class DsdUsbHardwareDeviceTest {
    @Test(timeout = 120000) fun idleDsdStreamsConfirmClocksDrainAndRestart() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("dsdHardware") == "true")
        val wire = DsdWireFormat.valueOf(args.getString("dsdWire") ?: "DOP")
        val rate = args.getString("dsdRate")?.toInt() ?: 2_822_400
        val seconds = (args.getString("dsdSeconds")?.toInt() ?: 3).coerceIn(1, 60)
        val listening = args.getString("dsdListening") == "true"
        val listeningCycle = if (listening) java.io.File(requireNotNull(args.getString("dsdToneFile"))).let { file ->
            require(rate == 2_822_400 && file.length() == rate / 8L * 2 * 6)
            file.readBytes()
        } else null
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val usb = UsbAudioDevice.getInstance(context)
        val device = requireNotNull(usb.findUsbAudioDevice())
        if (!usb.hasPermission(device)) {
            val permission = java.util.concurrent.CountDownLatch(1)
            instrumentation.runOnMainSync { usb.requestPermission(device) { permission.countDown() } }
            assertTrue(permission.await(65, java.util.concurrent.TimeUnit.SECONDS))
        }
        assertTrue("USB permission required", usb.hasPermission(device))
        val source = Format.Builder().setSampleMimeType(RawDsdAudioRenderer.MIME).setSampleRate(rate).setChannelCount(2)
            .setMetadata(Metadata(DsdSourceInfo("DSF", rate, 2, rate.toLong() * seconds))).build()
        repeat(2) { pass ->
            val transport = NativeDsdUsbTransport(context, wire)
            try {
                val caps = transport.capabilities(source)
                transport.start(source.buildUpon().setSampleRate(caps.rates.single()).build())
                val packer = DsdUsbPacker(2, wire, caps.containerBits / 8)
                var left = rate / 8L * (if (pass == 0) seconds else 1)
                var offset = 0L
                val start = SystemClock.elapsedRealtime()
                while (left > 0) {
                    val count = minOf(4096L, left).toInt()
                    val data = ByteArray(count * 2) { i ->
                        if (listeningCycle != null && pass == 0)
                            listeningCycle[((offset * 2 + i) % listeningCycle.size).toInt()] else 0x69
                    }
                    transport.write(packer.pack(data), 0)
                    offset += count
                    left -= count
                }
                transport.write(packer.finish(), 0)
                transport.finish()
                val status = transport.status()
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$wire DSD${rate / 44100} pass=$pass elapsed=${SystemClock.elapsedRealtime() - start}: $status\n") })
                assertNull(status.error)
                assertEquals(0L, status.pendingFrames)
                assertEquals(0L, status.packetErrors)
                assertEquals(0L, status.timeouts)
                assertEquals(caps.rates.single(), status.clockRate)
                assertEquals(rate.toLong() * (if (pass == 0) seconds else 1) / (wire.sourceBytes * 8), status.completedFrames)
            } finally { transport.close() }
        }
    }
}
