package com.aurora.music.playback.dsd

import android.os.SystemClock
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.playback.usb.UsbPcmCapabilities
import com.aurora.music.playback.usb.UsbPcmStatus
import com.aurora.music.playback.usb.UsbPcmTransport
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

class RawDsdRendererDeviceTest {
    @Test fun media3RawRendererPlaysDsfThenDffWithoutAnyPcmDecoder() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = Array(2) { channel -> ByteArray(60000) { (it * 7 + channel * 13).toByte() } }
        val dsf = File.createTempFile("raw-dsd-", ".dsf", context.cacheDir).apply { writeBytes(DsdFixtures.dsf(source)) }
        val dff = File.createTempFile("raw-dsd-", ".dff", context.cacheDir).apply { writeBytes(DsdFixtures.dff(source)) }
        val transports = CopyOnWriteArrayList<Transport>()
        val error = AtomicReference<PlaybackException?>()
        lateinit var player: ExoPlayer
        var created = false
        try {
            instrumentation.runOnMainSync {
                val factory = RenderersFactory { handler, _, audio, _, _ ->
                    val sink = RawDsdAudioSink(DefaultAudioSink.Builder(context).build(), DsdWireFormat.DOP,
                        { Transport().also(transports::add) })
                    arrayOf<Renderer>(RawDsdAudioRenderer(handler, audio, sink))
                }
                player = ExoPlayer.Builder(context, factory).setMediaSourceFactory(DefaultMediaSourceFactory(
                    DefaultDataSource.Factory(context), DsdExtractorsFactory(rawOutput = true))).build()
                created = true
                player.addListener(object : Player.Listener { override fun onPlayerError(failure: PlaybackException) { error.set(failure) } })
                player.setMediaItems(listOf(dsf, dff).map { MediaItem.fromUri(it.toURI().toString()) })
                player.prepare(); player.play()
            }
            val deadline = SystemClock.elapsedRealtime() + 10000
            var ended = false
            while (!ended && error.get() == null && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync { ended = player.playbackState == Player.STATE_ENDED }
                SystemClock.sleep(5)
            }
            assertNull("Raw renderer failure", error.get())
            assertTrue("Raw queue did not finish", ended)
            assertEquals(2, transports.size)
            val raw = ByteArray(120000) { source[it % 2][it / 2] }
            val packer = DsdUsbPacker(2, DsdWireFormat.DOP, 4)
            val expected = ByteArrayOutputStream()
            var offset = 0
            while (offset < raw.size) {
                val end = minOf(raw.size, offset + 8192)
                expected.write(packer.pack(raw.copyOfRange(offset, end))); offset = end
            }
            expected.write(packer.finish())
            transports.forEach { assertArrayEquals(expected.toByteArray(), it.bytes()) }
        } finally {
            instrumentation.runOnMainSync { if (created) player.release() }
            dsf.delete(); dff.delete()
        }
    }

    private class Transport : UsbPcmTransport {
        private val output = ByteArrayOutputStream()
        @Volatile private var completed = 0L
        override fun capabilities(source: Format) = UsbPcmCapabilities(intArrayOf(source.sampleRate / 16), 32, 32)
        override fun start(format: Format) = Unit
        @Synchronized override fun write(bytes: ByteArray, encoding: Int) { output.write(bytes); completed += bytes.size / 8 }
        override fun finish() = Unit
        override fun status() = UsbPcmStatus(completedFrames = completed)
        override fun interrupt() = Unit
        override fun close() = Unit
        @Synchronized fun bytes() = output.toByteArray()
    }
}
