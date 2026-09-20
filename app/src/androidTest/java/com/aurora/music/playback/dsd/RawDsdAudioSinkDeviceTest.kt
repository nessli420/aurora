package com.aurora.music.playback.dsd

import android.os.SystemClock
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioSink
import com.aurora.music.playback.usb.UsbPcmCapabilities
import com.aurora.music.playback.usb.UsbPcmStatus
import com.aurora.music.playback.usb.UsbPcmTransport
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.nio.ByteBuffer

class RawDsdAudioSinkDeviceTest {
    @Test fun pauseResumeBackpressureAndEosPreserveEverySourceBit() {
        for (wire in DsdWireFormat.entries) {
            val transport = MemoryTransport(wire)
            val sink = RawDsdAudioSink(delegate(), wire, { transport })
            try {
                sink.configure(format(), 0, null)
                sink.setVolume(0f); sink.setSkipSilenceEnabled(true); sink.setPlaybackParameters(PlaybackParameters(2f))
                val raw = ByteArray(150002) { (it * 37).toByte() }
                val input = ByteBuffer.wrap(raw)
                repeat(24) { sink.handleBuffer(input, 9000000, 1) }
                assertTrue(input.hasRemaining())
                assertEquals(0, transport.bytes().size)
                assertEquals(PlaybackParameters.DEFAULT, sink.playbackParameters)
                assertFalse(sink.skipSilenceEnabled)
                sink.play()
                await { sink.handleBuffer(input, 9000000, 1) }
                await { sink.playToEndOfStream(); sink.isEnded }
                val reference = DsdUsbPacker(2, wire, 4)
                val packed = ByteArrayOutputStream()
                raw.asList().chunked(8192).forEach { packed.write(reference.pack(it.toByteArray())) }
                packed.write(reference.finish())
                assertArrayEquals(packed.toByteArray(), transport.bytes())
                assertEquals(9000000L + raw.size / 2 * 8L * 1_000_000 / 2_822_400, sink.getCurrentPositionUs(true))
            } finally { sink.reset() }
            assertTrue(transport.closed)
        }
    }

    @Test fun seekDiscardsPausedDataAndRestartsMarkersAndClock() {
        val first = MemoryTransport(DsdWireFormat.DOP)
        val second = MemoryTransport(DsdWireFormat.DOP)
        val transports = ArrayDeque(listOf(first, second))
        val sink = RawDsdAudioSink(delegate(), DsdWireFormat.DOP, { transports.removeFirst() })
        try {
            sink.configure(format(), 0, null)
            sink.handleBuffer(ByteBuffer.wrap(ByteArray(501 * 2) { 9 }), 1000000, 1)
            sink.flush()
            assertTrue(first.closed)
            assertEquals(0, first.bytes().size)
            assertEquals(AudioSink.CURRENT_POSITION_NOT_SET, sink.getCurrentPositionUs(false))
            sink.play()
            val input = ByteBuffer.wrap(ByteArray(39 * 2) { 0x59 })
            await { sink.handleBuffer(input, 2000000, 1) }
            await { sink.playToEndOfStream(); sink.isEnded }
            assertEquals(5, second.bytes()[3].toInt())
            assertEquals(2000000L + 39 * 8 * 1_000_000L / 2_822_400, sink.getCurrentPositionUs(true))
        } finally { sink.reset() }
    }

    @Test fun unsupportedHardwareFailsWithoutForwardingDsdToAndroid() {
        val sink = RawDsdAudioSink(delegate(), DsdWireFormat.DOP, { MemoryTransport(DsdWireFormat.DOP, true) })
        try {
            sink.configure(format(), 0, null)
            assertThrows(AudioSink.ConfigurationException::class.java) { sink.handleBuffer(ByteBuffer.wrap(ByteArray(4)), 0, 1) }
            assertFalse(sink.telemetry.active)
            assertNotNull(sink.telemetry.failure)
        } finally { sink.reset() }
    }

    private fun format() = Format.Builder().setSampleMimeType(RawDsdAudioRenderer.MIME).setSampleRate(2_822_400).setChannelCount(2)
        .setMetadata(Metadata(DsdSourceInfo("DSF", 2_822_400, 2, 2_822_400))).build()
    private fun delegate(): AudioSink = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, _ ->
        if (method.name == "configure" || method.name == "handleBuffer") error("Raw DSD reached Android output")
        when (method.returnType) { java.lang.Boolean.TYPE -> false; java.lang.Integer.TYPE -> 0; java.lang.Long.TYPE -> 0L; else -> null }
    } as AudioSink
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (!condition()) { check(SystemClock.elapsedRealtime() < deadline) { "DSD output timed out" }; SystemClock.sleep(2) }
    }
    private class MemoryTransport(val wire: DsdWireFormat, val fail: Boolean = false) : UsbPcmTransport {
        private val data = ByteArrayOutputStream()
        @Volatile var closed = false
        @Volatile var completed = 0L
        override fun capabilities(source: Format): UsbPcmCapabilities {
            check(!fail) { "Unsupported DAC" }
            return UsbPcmCapabilities(intArrayOf(2_822_400 / (wire.sourceBytes * 8)), 32, 32)
        }
        override fun start(format: Format) = Unit
        @Synchronized override fun write(bytes: ByteArray, encoding: Int) { data.write(bytes); completed += bytes.size / 8 }
        override fun finish() = Unit
        override fun status() = UsbPcmStatus(completedFrames = completed)
        override fun interrupt() = Unit
        override fun close() { closed = true }
        @Synchronized fun bytes() = data.toByteArray()
    }
}
