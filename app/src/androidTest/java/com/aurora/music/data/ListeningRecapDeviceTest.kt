package com.aurora.music.data

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.model.Song
import com.aurora.music.data.remote.DiscordGateway
import com.aurora.music.ui.screens.stats.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ListeningRecapDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val song = Song("fixture", "A very long song name with punctuation & featured artists", "Test artist", "Test album", "", 240)

    @Test fun measuredHistorySurvivesReloadAndRepeatedSongsAreSeparatePlays() {
        val dir = File(context.cacheDir, "recap-test-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getFilesDir(): File = dir }
        val history = PlayHistoryStore(isolated)
        history.recordListening(song, 35_000)
        history.recordListening(song, 25_000)
        history.endListeningSession()
        history.recordListening(song, 30_000, System.currentTimeMillis() + 1)
        history.endListeningSession()
        val snapshot = history.snapshot()
        assertEquals(2, snapshot.size)
        assertEquals(90_000L, snapshot.sumOf { it.listeningMillis })
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline && PlayHistoryStore(isolated).snapshot().sumOf { it.listeningMillis } != 90_000L) Thread.sleep(50)
        assertEquals(90_000L, PlayHistoryStore(isolated).snapshot().sumOf { it.listeningMillis })
    }

    @Test fun allPictureStylesEncodeReadablePng() {
        val today = LocalDate.now()
        val events = (1..6).map { PlayEvent("$it", "Song $it", "Artist $it", "Album", "album", "artist-$it", "", 180, System.currentTimeMillis(), it * 60_000L) }
        val recap = ListeningRecaps.build(events, RecapWindow.containing(RecapPeriod.MONTH, today))
        RecapStyle.entries.forEach { style ->
            val bitmap = renderRecap(recap, style)
            val file = File(context.getExternalFilesDir(null), "recap-${style.name.lowercase()}.png")
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val decoded = BitmapFactory.decodeFile(file.path)
            assertEquals(1080, decoded.width); assertEquals(1600, decoded.height)
            assertTrue(file.length() > 10_000)
        }
    }

    @Test fun localAndDownloadedArtworkCanBeEncodedForDiscord() = kotlinx.coroutines.runBlocking {
        val file = File(context.cacheDir, "discord-cover-fixture.png")
        val source = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.MAGENTA) }
        file.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val loader = coil.ImageLoader(context)
        try {
            listOf(file.absolutePath, android.net.Uri.fromFile(file).toString()).forEach { url ->
                val bytes = loadDiscordArtwork(context, loader, url) ?: error("Local cover was not loaded")
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                assertEquals(512, decoded.width); assertEquals(512, decoded.height)
                assertTrue(android.graphics.Color.red(decoded.getPixel(200, 200)) > 240)
            }
        } finally { loader.shutdown(); file.delete() }
    }

    @Test fun discordSettingsControlHeadlineAndAlbum() {
        val artist = discordActivity(song, true, "artist", 20f, 100_000)
        assertEquals(song.artist, artist.getString("name"))
        assertEquals(1, artist.getInt("status_display_type"))
        assertEquals(song.artist, artist.getString("state"))
        assertTrue(artist.getString("details").contains(song.album))
        assertEquals(80_000L, artist.getJSONObject("timestamps").getLong("start"))
        val title = discordActivity(song, false, "song", 0f)
        assertEquals(song.title, title.getString("name"))
        assertEquals(2, title.getInt("status_display_type"))
        assertEquals(song.title, title.getString("details"))
        assertFalse(title.getString("state").contains(song.album))
    }

    @Test fun rapidSkipsAndPauseDeliverNewestPresenceThroughGateway() {
        val messages = LinkedBlockingQueue<JSONObject>()
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("{\"op\":10,\"d\":{\"heartbeat_interval\":60000}}") }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = JSONObject(text)
                if (message.optInt("op") == 2) webSocket.send("{\"op\":0,\"t\":\"READY\",\"d\":{\"user\":{\"username\":\"test\"}}}")
                if (message.optInt("op") == 3) messages.offer(message.getJSONObject("d"))
                if (message.optInt("op") == 1) webSocket.send("{\"op\":11}")
            }
        }))
        server.start()
        val gateway = DiscordGateway({}, {}, server.url("/").toString())
        try {
            gateway.connect("local-fixture-token", JSONObject().put("name", "first"))
            assertNotNull(messages.poll(8, TimeUnit.SECONDS))
            repeat(100) { gateway.updateActivity(JSONObject().put("name", "Song $it")) }
            val final = messages.poll(8, TimeUnit.SECONDS) ?: error("Final song was lost")
            assertEquals("Song 99", final.getJSONArray("activities").getJSONObject(0).getString("name"))
            gateway.updateActivity(null)
            assertEquals(0, (messages.poll(8, TimeUnit.SECONDS) ?: error("Pause was lost")).getJSONArray("activities").length())
        } finally { gateway.disconnect(); server.shutdown() }
    }
}
