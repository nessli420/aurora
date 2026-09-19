package com.aurora.music.playback

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.decent.usbaudio.UsbAudioDevice
import com.decent.usbaudio.UsbAudioStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbHardwareTransportDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test(timeout = 45_000) fun silentPcmSurvivesNativeFlushAndDrainAcrossFormats() {
        val usb = UsbAudioDevice.getInstance(instrumentation.targetContext)
        val device = requireNotNull(usb.findUsbAudioDevice()) { "A physical USB DAC is required" }
        assertTrue("Approve Aurora's USB permission first", usb.hasPermission(device))
        try {
            val info = requireNotNull(usb.openDevice(device)) { usb.lastFailure ?: "USB device did not open" }
            for ((rate, bits) in listOf(44_100 to 16, 48_000 to 24, 96_000 to 24)) {
                val format = requireNotNull(usb.selectFormat(rate, 2, bits)) { "Unsupported USB format: $rate/$bits" }
                val clock = usb.configureFormat(format, rate)
                assertTrue(clock.failure ?: "USB clock was not verified", clock.verified)
                val stream = UsbAudioStream(info.fd, format.interfaceId, format.endpointOut, format.endpointFeedback,
                    rate, 2, format.containerBits, format.maxPacketSize, format.validBits,
                    alternateSetting = format.alternateSetting)
                try {
                    assertTrue("USB transport was not created", stream.isReady)
                    assertTrue("USB transport did not start: ${stream.telemetry}", stream.start())
                    writeSilence(stream, rate, bits, rate / 2)
                    assertTrue("USB completions must advance", stream.telemetry.completedFrames > 0)
                    assertHealthy(stream, "$rate/$bits before seek")
                    val feedbackBefore = stream.telemetry.feedbackPackets

                    stream.flush()
                    assertTrue("USB seek must restart transport: ${stream.telemetry}", stream.isAlive)
                    assertEquals(0L, stream.telemetry.acceptedFrames)
                    assertEquals(0L, stream.telemetry.completedFrames)
                    if (format.endpointFeedback > 0) assertTrue("USB seek needs fresh feedback",
                        stream.telemetry.feedbackPackets > feedbackBefore)
                    writeSilence(stream, rate, bits, rate / 2)
                    assertTrue("USB seek output must drain: ${stream.telemetry}", stream.finish())
                    assertEquals((rate / 2).toLong(), stream.telemetry.completedFrames)
                    assertEquals(0L, stream.telemetry.pendingFrames)
                    assertHealthy(stream, "$rate/$bits after seek")

                    stream.flush()
                    assertTrue("USB restart after EOS failed: ${stream.telemetry}", stream.start())
                    writeSilence(stream, rate, bits, rate / 4)
                    assertTrue("USB replay must drain: ${stream.telemetry}", stream.finish())
                    assertEquals((rate / 4).toLong(), stream.telemetry.completedFrames)
                    assertHealthy(stream, "$rate/$bits after replay")
                } finally {
                    stream.stop()
                    stream.drainUrbs()
                    stream.release()
                }
            }
        } finally {
            usb.setAltSetting(0)
            usb.closeDevice()
        }
    }

    private fun writeSilence(stream: UsbAudioStream, rate: Int, bits: Int, frames: Int) {
        val encoding = if (bits == 16) 2 else 0x15
        var remaining = frames
        while (remaining > 0) {
            val count = minOf(remaining, rate / 100)
            stream.writeRaw(ByteArray(count * 2 * bits / 8), encoding)
            assertTrue("USB write stopped: ${stream.telemetry}", stream.isAlive)
            remaining -= count
        }
    }

    private fun assertHealthy(stream: UsbAudioStream, label: String) {
        val status = stream.telemetry
        instrumentation.sendStatus(2, Bundle().apply { putString("usbHardwareTransport", "$label: $status") })
        assertNull("$label: $status", status.lastError)
        assertEquals("$label packet errors", 0L, status.packetErrors)
        assertEquals("$label submit errors", 0L, status.submitErrors)
        assertEquals("$label timeouts", 0L, status.timeouts)
        assertTrue("$label needs valid clock feedback", status.feedbackPackets > 0)
    }
}
