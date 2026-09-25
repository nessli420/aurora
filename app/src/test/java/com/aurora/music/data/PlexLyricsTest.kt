package com.aurora.music.data

import com.aurora.music.data.remote.PlexClient
import com.aurora.music.data.remote.PlexException
import com.aurora.music.model.LyricLine
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class PlexLyricsTest {
    private fun client(server: MockWebServer) = PlexClient(
        Session(server.url("/").toString().trimEnd('/'), "Plex", "", "lyrics-token", ServerType.PLEX, "machine"),
        OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(),
    )

    private fun metadata(vararg streams: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"MediaContainer":{"Metadata":[{"ratingKey":"1","type":"track","Media":[{"Part":[{"Stream":[${streams.joinToString(",")}]}]}]}]}}""")

    private fun stream(key: String, format: String = "lrc", type: Int = 4) =
        """{"streamType":$type,"key":"$key","format":"$format"}"""

    @Test fun prefersTimedLyricsAndUsesAuthenticatedUnmodifiedStreamKey() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(metadata(stream("/library/streams/plain", "txt"), stream("/library/streams/synced")))
            server.enqueue(MockResponse().setBody("\uFEFF[ar:Artist]\n[00:08.20]Second line\n[00:02.50]First line"))
            val lyrics = requireNotNull(plexLyrics(client(server), "1"))
            assertTrue(lyrics.synced)
            assertEquals("Plex", lyrics.source)
            assertEquals(listOf(LyricLine(2, "First line"), LyricLine(8, "Second line")), lyrics.lines)
            assertEquals("/library/metadata/1", server.takeRequest().path)
            val request = server.takeRequest()
            assertEquals("/library/streams/synced", request.path)
            assertEquals("lyrics-token", request.getHeader("X-Plex-Token"))
        }
    }

    @Test fun returnsPlainLyricsWithLineBreaks() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(metadata(stream("/library/streams/2", "txt")))
            server.enqueue(MockResponse().setBody("First line\r\n\r\nSecond line\r\n"))
            val lyrics = requireNotNull(plexLyrics(client(server), "1"))
            assertFalse(lyrics.synced)
            assertEquals(listOf(LyricLine(0, "First line"), LyricLine(0, ""), LyricLine(0, "Second line")), lyrics.lines)
        }
    }

    @Test fun triesAnotherStreamWhenTimedLyricsAreMissing() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(metadata(stream("/library/streams/missing"), stream("/library/streams/plain", "txt")))
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody("Available lyrics"))
            val lyrics = requireNotNull(plexLyrics(client(server), "1"))
            assertFalse(lyrics.synced)
            assertEquals("Available lyrics", lyrics.lines.single().text)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun neverSendsCredentialsToForeignLyricsHost() = runBlocking {
        MockWebServer().use { origin -> MockWebServer().use { foreign ->
            origin.enqueue(metadata(stream(foreign.url("/lyrics").toString()), stream("/library/streams/safe", "txt")))
            origin.enqueue(MockResponse().setBody("Local lyrics"))
            val lyrics = requireNotNull(plexLyrics(client(origin), "1"))
            assertEquals("Local lyrics", lyrics.lines.single().text)
            assertEquals(0, foreign.requestCount)
            assertEquals(2, origin.requestCount)
        } }
    }

    @Test fun skipsUnsupportedAndEmptyTimedStreams() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(metadata(
                stream("/library/streams/xml", "xml"),
                stream("/library/streams/audio", "txt", 2),
                stream("/library/streams/empty"),
                stream("/library/streams/plain", "txt"),
            ))
            server.enqueue(MockResponse().setBody("[ar:Artist]\n[ti:Song]"))
            server.enqueue(MockResponse().setBody("Useful lyrics"))
            val lyrics = requireNotNull(plexLyrics(client(server), "1"))
            assertEquals("Useful lyrics", lyrics.lines.single().text)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun preservesAuthenticationErrors() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(metadata(stream("/library/streams/1"), stream("/library/streams/2", "txt")))
            server.enqueue(MockResponse().setResponseCode(401))
            val error = runCatching { plexLyrics(client(server), "1") }.exceptionOrNull()
            assertTrue(error is PlexException)
            assertEquals(401, (error as PlexException).statusCode)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun cancellationStopsLyricsRetrievalWithoutTryingAnotherStream() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(metadata(stream("/library/streams/slow"), stream("/library/streams/other", "txt")))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val started = System.nanoTime()
            val error = runCatching { withTimeout(500) { plexLyrics(client(server), "1") } }.exceptionOrNull()
            assertTrue(error is TimeoutCancellationException)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2000)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun missingLyricsReturnNull() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(metadata())
            assertNull(plexLyrics(client(server), "1"))
            assertEquals(1, server.requestCount)
        }
    }
}
