package com.aurora.music.data

import com.aurora.music.data.remote.SubsonicClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubsonicPlaylistCreationTest {
    private fun backend(server: MockWebServer): SubsonicBackend = SubsonicBackend(
        SubsonicClient(Session(server.url("/").toString().trimEnd('/'), "fixture", "salt", "token")),
        { 0 }, { it },
    )

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Test fun rejectedCreationCannotReturnAnExistingSameNamePlaylist() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json("""{"subsonic-response":{"status":"failed","error":{"code":50,"message":"Not authorized"}}}"""))
            server.enqueue(json("""{"subsonic-response":{"status":"ok","playlists":{"playlist":[{"id":"existing","name":"Music"}]}}}"""))
            server.start()
            assertNull(backend(server).createPlaylistWithId("Music"))
            assertEquals(1, server.requestCount)
            assertEquals("/rest/createPlaylist.view", server.takeRequest().requestUrl!!.encodedPath)
        }
    }

    @Test fun successfulOlderServerCreationCanStillLookUpThePlaylistId() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json("""{"subsonic-response":{"status":"ok"}}"""))
            server.enqueue(json("""{"subsonic-response":{"status":"ok","playlists":{"playlist":[{"id":"created","name":"Music"}]}}}"""))
            server.start()
            assertEquals("created", backend(server).createPlaylistWithId("Music"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun successfulCreationWithAnIdDoesNotNeedAFallbackLookup() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json("""{"subsonic-response":{"status":"ok","playlist":{"id":"created","name":"Music"}}}"""))
            server.start()
            assertEquals("created", backend(server).createPlaylistWithId("Music"))
            assertEquals(1, server.requestCount)
        }
    }
}
