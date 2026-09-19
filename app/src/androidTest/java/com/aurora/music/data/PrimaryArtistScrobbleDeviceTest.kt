package com.aurora.music.data

import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.AuroraApplication
import com.aurora.music.data.remote.LastfmClient
import com.aurora.music.data.remote.ListenBrainzClient
import com.aurora.music.model.Song
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class PrimaryArtistScrobbleDeviceTest {
    @Test fun bothServicesSubmitOnlyTheFirstConfiguredArtistForNowPlayingAndScrobbles() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = (context.applicationContext as AuroraApplication).container.settingsStore
        val original = store.exportPrefs()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val requests = LinkedBlockingQueue<Map<String, String>>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = requireNotNull(request.body)
            val payload = if (body is FormBody) {
                (0 until body.size).associate { body.name(it) to body.value(it) }
            } else {
                val buffer = Buffer()
                body.writeTo(buffer)
                val json = JsonParser.parseString(buffer.readUtf8()).asJsonObject
                val listens = json.getAsJsonArray("payload")
                assertEquals(1, listens.size())
                val listen = listens.single().asJsonObject
                val track = listen.getAsJsonObject("track_metadata")
                mapOf("method" to json["listen_type"].asString, "artist" to track["artist_name"].asString,
                    "track" to track["track_name"].asString, "album" to track["release_name"].asString,
                    "timestamp" to (listen["listened_at"]?.asString ?: ""))
            }
            requests.add(payload)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{}".toResponseBody()).build()
        }.build()
        try {
            store.setLastfmKeys("test-key", "test-secret")
            store.saveLastfm("test-session", "Test", "")
            store.setLastfmEnabled(true)
            store.saveListenBrainz("test-token", "Test")
            store.setListenBrainzEnabled(true)
            val lastfm = LastfmScrobbler(store, scope) { key, secret -> LastfmClient(key, secret, http) }
            val listenBrainz = ListenBrainzScrobbler(store, scope, ListenBrainzClient(http))
            withTimeout(5_000) { while (!lastfm.configured || !lastfm.isConnected || !listenBrainz.isConnected) delay(10) }
            val defaults = ArtistSeparators()
            val keepBand = ArtistSeparators(defaults.rules!!.map {
                if (it.text in listOf(",", "&")) it.copy(match = SeparatorMatch.OFF) else it
            })
            val custom = ArtistSeparators(defaults.rules + ArtistSeparator("vs.", SeparatorMatch.WORD))
            listOf(
                Triple("Cynthoni, Sewerslvt", defaults, "Cynthoni"),
                Triple("Björk feat. Guest", defaults, "Björk"),
                Triple("First / Second & Third", defaults, "First"),
                Triple("AC/DC", defaults, "AC/DC"),
                Triple("R&B", defaults, "R&B"),
                Triple("Earth, Wind & Fire", keepBand, "Earth, Wind & Fire"),
                Triple("First vs. Second", custom, "First"),
            ).forEach { (credit, rules, primary) ->
                store.setArtistSeparators(rules)
                val song = Song("test", "Test track", credit, "Test album", "", 120)
                lastfm.nowPlaying(song)
                lastfm.scrobble(song, 1_700_000_000_000L)
                listenBrainz.nowPlaying(song)
                listenBrainz.scrobble(song, 1_700_000_000_000L)
                val sent = List(4) { requireNotNull(requests.poll(10, TimeUnit.SECONDS)) { "Missing scrobble request for $credit" } }
                assertEquals(setOf("track.updateNowPlaying", "track.scrobble", "playing_now", "single"), sent.map { it["method"] }.toSet())
                sent.forEach {
                    assertEquals(primary, it["artist"])
                    assertEquals(song.title, it["track"])
                    assertEquals(song.album, it["album"])
                    if (it["method"] in setOf("track.scrobble", "single")) assertEquals("1700000000", it["timestamp"])
                }
                assertEquals(credit, song.artist)
                assertTrue(requests.isEmpty())
            }
        } finally {
            scope.cancel()
            store.restoreBackupPrefs(original).getOrThrow()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }
}
