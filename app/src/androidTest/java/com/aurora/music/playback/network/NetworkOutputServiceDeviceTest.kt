package com.aurora.music.playback.network

import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.AuroraApplication
import com.aurora.music.data.AudioPrefs
import com.aurora.music.data.ProcessingRack
import com.aurora.music.data.ProcessingRackNode
import com.aurora.music.data.RackNodeKind
import com.aurora.music.playback.PlaybackService
import com.aurora.music.playback.PrecisionPlaybackDeviceTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.xml.sax.InputSource
import java.io.Closeable
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

@OptIn(UnstableApi::class)
class NetworkOutputServiceDeviceTest {
    @Test fun serviceProcessesPrivateAudioThroughDlnaAndRestoresQueueAfterDisconnect() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as AuroraApplication).container
        val manager = container.networkOutput
        assumeTrue("Keep an active network session untouched", manager.state.value.receiverName == null && !manager.state.value.receiverEnabled)
        val fixture = PrecisionPlaybackDeviceTest()
        fixture.keepTargetForegroundForAudioFocus()
        try {
            fixture.withProcessingFixture(crossfadeSeconds = 0) { controller, _ ->
                runBlocking {
                    container.settingsStore.setDspConvEnabled(false)
                    container.settingsStore.setReplayGain(0)
                    container.settingsStore.setProcessingRack(ProcessingRack(enabled = true, name = "Network fixture", nodes = listOf(
                        ProcessingRackNode("00000000-0000-0000-0000-000000000012", "Gain", RackNodeKind.GAIN,
                            audio = AudioPrefs(dspPreampDb = -12f)),
                    ))).getOrThrow()
                }
                val source = fixture.wav("network-private", 48_000, 12 * 48_000) { frame, channel ->
                    (sin(2 * PI * 1_000 * frame / 48_000) * if (channel == 0) 8_192 else 4_096).toInt()
                }
                val items = listOf("net-b", "net-a", "net-c").map { id -> MediaItem.Builder().setMediaId(id)
                    .setUri(source.toURI().toString()).setMimeType("audio/wav")
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(id).setArtist("Network fixture").build()).build() }
                FakeDlna().use { fake ->
                    try {
                        fixture.main { controller.setMediaItems(items, 0, 2_000); controller.prepare(); controller.repeatMode = Player.REPEAT_MODE_ALL }
                        fixture.await("local fixture ready", controller) { fixture.main { controller.playbackState == Player.STATE_READY } }
                        shuffle(fixture, controller, true, listOf("net-a", "net-b", "net-c"))
                        val physical = fixture.main { (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId } }
                        fixture.main { manager.connect(NetworkTarget.Dlna(fake.renderer)) }
                        fixture.await("DLNA receives processed audio", controller) {
                            fake.captures.size == 1 && !manager.state.value.preparing && fixture.main { controller.playbackState == Player.STATE_READY }
                        }
                        assertEquals("audio/x-wav", fake.captures.single().mime)
                        assertTrue(fake.captures.single().metadata.contains("http-get:*:audio/x-wav:*"))
                        assertFalse(fake.captures.single().url.contains(source.name))
                        measuredGain(fake.captures.single().bytes, source.readBytes())
                        assertEquals(0, fake.plays)
                        assertEquals(0, fake.volumeWrites)
                        fixture.main {
                            assertFalse(controller.playWhenReady)
                            assertTrue(controller.shuffleModeEnabled)
                            assertEquals(Player.REPEAT_MODE_ALL, controller.repeatMode)
                            assertEquals(physical, (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId })
                            controller.seekTo(3_000)
                            controller.play()
                        }
                        fixture.await("DLNA play and seek", controller) { fake.playing && fake.position() >= 3_000 }
                        fixture.await("sender audio output is stopped", controller) {
                            !context.getSystemService(AudioManager::class.java).isMusicActive
                        }
                        assertTrue(container.signalPath.value.output.startsWith("DLNA"))
                        fixture.main { controller.pause() }
                        fixture.await("DLNA pause", controller) { !fake.playing }
                        val paused = fake.position()
                        SystemClock.sleep(250)
                        assertEquals(paused, fake.position())
                        fixture.await("receiver volume is available", controller) { fixture.main {
                            controller.isCommandAvailable(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)
                        } }
                        fixture.main { controller.setDeviceVolume(29, 0) }
                        fixture.await("DLNA volume uses the requested step", controller) { fake.lastVolume == 29 }
                        for ((mode, icon) in listOf(
                            Player.REPEAT_MODE_ONE to androidx.media3.session.CommandButton.ICON_REPEAT_ONE,
                            Player.REPEAT_MODE_OFF to androidx.media3.session.CommandButton.ICON_REPEAT_OFF,
                            Player.REPEAT_MODE_ALL to androidx.media3.session.CommandButton.ICON_REPEAT_ALL,
                        )) {
                            fixture.main { controller.sendCustomCommand(SessionCommand(PlaybackService.CMD_REPEAT, Bundle.EMPTY), Bundle.EMPTY) }
                                .get(5, TimeUnit.SECONDS)
                            fixture.await("remote repeat button updates", controller) { fixture.main {
                                controller.repeatMode == mode && controller.customLayout.any {
                                    it.sessionCommand?.customAction == PlaybackService.CMD_REPEAT && it.icon == icon
                                }
                            } }
                        }
                        val firstUrl = fake.captures.first().url
                        fixture.main { controller.seekToNextMediaItem() }
                        fixture.await("DLNA next track", controller) { fake.captures.size == 2 && !manager.state.value.preparing }
                        assertFalse(fake.playing)
                        assertEquals(404, responseCode(firstUrl))
                        shuffle(fixture, controller, false)
                        fixture.main {
                            assertEquals(listOf("net-a", "net-b", "net-c"), (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId })
                            assertFalse(controller.shuffleModeEnabled)
                        }
                        val remoteIndex = fixture.main { controller.currentMediaItemIndex }
                        fixture.main { manager.disconnect() }
                        fixture.await("paused local handback", controller) {
                            manager.state.value.receiverName == null && fixture.main { controller.playbackState == Player.STATE_READY && !controller.playWhenReady }
                        }
                        fixture.main {
                            assertEquals(remoteIndex, controller.currentMediaItemIndex)
                            assertEquals(Player.REPEAT_MODE_ALL, controller.repeatMode)
                            assertEquals(listOf("net-a", "net-b", "net-c"), (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId })
                        }
                        fake.captures.forEach { assertEquals(404, responseCode(it.url)) }
                        fixture.main { manager.connect(NetworkTarget.Dlna(fake.renderer)) }
                        fixture.await("DLNA reconnect", controller) { fake.captures.size == 3 && !manager.state.value.preparing }
                        fixture.main { controller.play() }
                        fixture.await("reconnected receiver plays", controller) { fake.playing }
                        fake.failStop = true
                        fixture.main { manager.disconnect() }
                        fixture.await("unconfirmed stop keeps local playback paused", controller) {
                            manager.state.value.receiverName == null && manager.state.value.error?.contains("confirm stop") == true &&
                                fixture.main { !controller.playWhenReady }
                        }
                        assertEquals(404, responseCode(fake.captures.last().url))
                    } finally {
                        fake.failStop = false
                        fixture.main { controller.pause(); manager.disconnect() }
                        fixture.await("network fixture disconnected") { manager.state.value.receiverName == null }
                        fixture.main { manager.dismissError() }
                    }
                }
            }
        } finally { fixture.removeFixturesAndFinishActivity() }
    }

    private fun shuffle(fixture: PrecisionPlaybackDeviceTest, controller: MediaController, enabled: Boolean, order: List<String>? = null) {
        val extras = Bundle().apply { putInt("target", if (enabled) 1 else 0); order?.let { putStringArrayList("order", ArrayList(it)) } }
        val result = fixture.main { controller.sendCustomCommand(SessionCommand(PlaybackService.CMD_SHUFFLE, extras), Bundle.EMPTY) }
            .get(5, TimeUnit.SECONDS)
        assertEquals(0, result.resultCode)
        fixture.await("shuffle state $enabled", controller) { fixture.main { controller.shuffleModeEnabled == enabled } }
    }

    private fun measuredGain(bytes: ByteArray, source: ByteArray) {
        val pcm = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val original = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN)
        val sourceBytes = original.getShort(34).toInt() / 8
        val gain = 10.0.pow(-12.0 / 20.0)
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals(48_000, pcm.getInt(24))
        assertEquals(16, pcm.getShort(34).toInt())
        for (channel in 0..1) {
            var squared = 0.0
            var expectedSquared = 0.0
            for (frame in 1_000 until 11_000) {
                val sample = pcm.getShort(44 + frame * 4 + channel * 2) / 32768.0
                val offset = 44 + (frame * 2 + channel) * sourceBytes
                val sourceSample = if (sourceBytes == 2) original.getShort(offset) / 32768.0 else {
                    val packed = (source[offset].toInt() and 255) or ((source[offset + 1].toInt() and 255) shl 8) or
                        ((source[offset + 2].toInt() and 255) shl 16)
                    ((packed shl 8) shr 8) / 8388608.0
                }
                val expectedSample = floor(sourceSample * gain * 32768 + .5) / 32768.0
                assertEquals("Captured frame $frame channel $channel", expectedSample, sample, 1.0 / 32768.0)
                squared += sample * sample
                expectedSquared += expectedSample * expectedSample
            }
            assertEquals("Captured channel $channel gain", sqrt(expectedSquared / 10_000), sqrt(squared / 10_000), .000001)
        }
    }

    internal data class Capture(val url: String, val mime: String, val metadata: String, val bytes: ByteArray)

    internal class FakeDlna : Closeable {
        val captures = CopyOnWriteArrayList<Capture>()
        @Volatile var failStop = false
        @Volatile var playing = false
        @Volatile var plays = 0
        @Volatile var volumeWrites = 0
        @Volatile var lastVolume = 37
        private var base = 0L
        private var started = 0L
        private val server = BoundedHttpServer(::request).apply { start(InetAddress.getByName("127.0.0.1")) }
        val renderer = DlnaRenderer("uuid:aurora-service-test", "Test DLNA", "http://127.0.0.1:${server.port}/device.xml",
            service("AVTransport", "transport"), service("RenderingControl", "rendering"), service("ConnectionManager", "connection"))

        private fun service(type: String, path: String) = DlnaService("urn:schemas-upnp-org:service:$type:1", "http://127.0.0.1:${server.port}/$path")
        @Synchronized fun position(): Long = base + if (playing) SystemClock.elapsedRealtime() - started else 0
        @Synchronized private fun updatePlaying(value: Boolean) { base = position(); started = SystemClock.elapsedRealtime(); playing = value }
        @Synchronized private fun seek(value: Long) { base = value; started = SystemClock.elapsedRealtime() }

        private fun request(request: HttpRequest): HttpResponse {
            val action = request.headers["soapaction"].orEmpty().substringAfter('#').trimEnd('"')
            val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(InputSource(StringReader(request.body.toString(Charsets.UTF_8))))
            fun text(tag: String) = xml.getElementsByTagName(tag).item(0)?.textContent.orEmpty()
            if (action == "Stop" && failStop) return HttpResponse(500,
                body = "<error><errorCode>701</errorCode></error>".toByteArray())
            val values = when (action) {
                "GetProtocolInfo" -> "<Sink>http-get:*:audio/x-wav:*</Sink>"
                "SetAVTransportURI" -> {
                    val url = text("CurrentURI")
                    val connection = URL(url).openConnection() as HttpURLConnection
                    connection.connectTimeout = 3_000; connection.readTimeout = 5_000
                    try { captures.add(Capture(url, connection.contentType, text("CurrentURIMetaData"), connection.inputStream.use { it.readBytes() })) }
                    finally { connection.disconnect() }
                    updatePlaying(false); seek(0); ""
                }
                "Play" -> { plays++; updatePlaying(true); "" }
                "Pause" -> { updatePlaying(false); "" }
                "Stop" -> { updatePlaying(false); "" }
                "Seek" -> { seek(parseTime(text("Target"))); "" }
                "SetVolume" -> { volumeWrites++; lastVolume = text("DesiredVolume").toInt(); "" }
                "GetVolume" -> "<CurrentVolume>$lastVolume</CurrentVolume>"
                "GetTransportInfo" -> "<CurrentTransportState>${if (playing) "PLAYING" else "PAUSED_PLAYBACK"}</CurrentTransportState>"
                "GetPositionInfo" -> "<RelTime>${formatTime(position())}</RelTime><TrackDuration>00:00:12</TrackDuration>"
                else -> ""
            }
            val body = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" +
                "<u:${action}Response xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">$values</u:${action}Response></s:Body></s:Envelope>"
            return HttpResponse(200, body = body.toByteArray())
        }
        override fun close() = server.close()
        private fun parseTime(text: String): Long {
            val parts = text.split(':').map { it.toLong() }
            return (parts[0] * 3_600 + parts[1] * 60 + parts[2]) * 1_000
        }
        private fun formatTime(millis: Long): String = "00:00:${(millis / 1_000).coerceIn(0, 59).toString().padStart(2, '0')}"
    }

    private fun responseCode(url: String): Int {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 2_000; connection.readTimeout = 2_000
        return try { connection.responseCode } finally { connection.disconnect() }
    }
}
