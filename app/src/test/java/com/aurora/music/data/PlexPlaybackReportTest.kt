package com.aurora.music.data

import com.aurora.music.data.remote.PlexClient
import com.aurora.music.data.remote.PlexException
import com.aurora.music.data.remote.appClientInfo
import com.aurora.music.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class PlexPlaybackReportTest {
    private val token = "fixture-token+/=&?#"
    private val song = Song("101", "Track", "Artist", "Album", "", 180)

    private class Fixture : AutoCloseable {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        @Volatile var response: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(204) }
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    return response(request)
                }
            }
            start()
        }
        override fun close() = server.close()
    }

    private fun backend(fixture: Fixture, path: String = "/") = PlexBackend(
        PlexClient(Session(fixture.server.url(path).toString().trimEnd('/'), "Listener", "", token,
            ServerType.PLEX, "machine", clientToken = "fixture-device"), appClientInfo, OkHttpClient()),
        { 0 }, { it },
    )

    private fun report(
        event: PlaybackReportEvent = PlaybackReportEvent.PROGRESS,
        state: PlaybackReportState = PlaybackReportState.PLAYING,
        position: Long = 40_123,
        duration: Long = 180_456,
        listened: Long = 30_000,
    ) = PlaybackReport(song, "play-occurrence-1", event, state, position, duration, listened, 1_750_000_000_000)

    @Test fun reportsNativeLifecycleWithStableDeviceAndOccurrenceHeaders() = runBlocking {
        Fixture().use { fixture ->
            val backend = backend(fixture)
            val states = listOf(PlaybackReportState.PLAYING, PlaybackReportState.PAUSED,
                PlaybackReportState.BUFFERING, PlaybackReportState.PLAYING, PlaybackReportState.STOPPED)
            states.forEachIndexed { index, state ->
                backend.reportPlayback(report(event = when (index) {
                    0 -> PlaybackReportEvent.START
                    states.lastIndex -> PlaybackReportEvent.STOP
                    else -> PlaybackReportEvent.PROGRESS
                }, state = state))
            }
            assertEquals(5, fixture.requests.size)
            fixture.requests.zip(listOf("playing", "paused", "buffering", "playing", "stopped")).forEach { (request, state) ->
                val url = request.requestUrl!!
                assertEquals("POST", request.method)
                assertEquals("/:/timeline", url.encodedPath)
                assertEquals("/library/metadata/101", url.queryParameter("key"))
                assertEquals("101", url.queryParameter("ratingKey"))
                assertEquals(state, url.queryParameter("state"))
                assertEquals("40123", url.queryParameter("time"))
                assertEquals("180456", url.queryParameter("duration"))
                assertEquals("30000", url.queryParameter("playbackTime"))
                assertEquals("fixture-device", request.getHeader("X-Plex-Client-Identifier"))
                assertEquals("play-occurrence-1", request.getHeader("X-Plex-Session-Identifier"))
                assertEquals("Aurora", request.getHeader("X-Plex-Product"))
                assertEquals("Aurora Android", request.getHeader("X-Plex-Device-Name"))
                assertEquals(token, request.getHeader("X-Plex-Token"))
                assertNull(url.queryParameter("X-Plex-Token"))
                assertNull(url.queryParameter("playQueueItemID"))
                assertNull(url.queryParameter("offline"))
            }
        }
    }

    @Test fun listenedThresholdDoesNotDuplicateNativeHistoryWithMarkPlayedRequests() = runBlocking {
        Fixture().use { fixture ->
            backend(fixture).reportPlayback(report(event = PlaybackReportEvent.SCROBBLE))
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test fun clampsOffsetsAndElapsedTimeWithoutInventingCompletion() = runBlocking {
        Fixture().use { fixture ->
            val backend = backend(fixture)
            backend.reportPlayback(report(position = -50, listened = -10))
            backend.reportPlayback(report(position = 200_000, duration = 100_000, listened = 240_000))
            backend.reportPlayback(report(event = PlaybackReportEvent.STOP,
                state = PlaybackReportState.STOPPED, position = 12_500))
            assertEquals("0", fixture.requests[0].requestUrl!!.queryParameter("time"))
            assertEquals("0", fixture.requests[0].requestUrl!!.queryParameter("playbackTime"))
            assertEquals("100000", fixture.requests[1].requestUrl!!.queryParameter("time"))
            assertEquals("240000", fixture.requests[1].requestUrl!!.queryParameter("playbackTime"))
            assertEquals("12500", fixture.requests[2].requestUrl!!.queryParameter("time"))
        }
    }

    @Test fun fallsBackToSongDurationWhenPlayerDurationIsUnavailable() = runBlocking {
        Fixture().use { fixture ->
            backend(fixture).reportPlayback(report(duration = -1, position = 190_000))
            assertEquals("180000", fixture.requests.single().requestUrl!!.queryParameter("duration"))
            assertEquals("180000", fixture.requests.single().requestUrl!!.queryParameter("time"))
        }
    }

    @Test fun omitsUnknownDurationWithoutDiscardingKnownPosition() = runBlocking {
        Fixture().use { fixture ->
            backend(fixture).reportPlayback(report(duration = 0).copy(song = song.copy(durationSec = 0)))
            assertNull(fixture.requests.single().requestUrl!!.queryParameter("duration"))
            assertEquals("40123", fixture.requests.single().requestUrl!!.queryParameter("time"))
        }
    }

    @Test fun repeatedTrackOccurrencesGetDistinctSessionsOnTheSameClient() = runBlocking {
        Fixture().use { fixture ->
            val backend = backend(fixture)
            backend.reportPlayback(report(event = PlaybackReportEvent.START))
            backend.reportPlayback(report(event = PlaybackReportEvent.START).copy(sessionId = "play-occurrence-2"))
            assertEquals(listOf("play-occurrence-1", "play-occurrence-2"),
                fixture.requests.map { it.getHeader("X-Plex-Session-Identifier") })
            assertEquals(listOf("fixture-device", "fixture-device"),
                fixture.requests.map { it.getHeader("X-Plex-Client-Identifier") })
        }
    }

    @Test fun reverseProxyPrefixIsPreservedAndEmptySuccessNeedsNoJson() = runBlocking {
        Fixture().use { fixture ->
            fixture.response = { MockResponse().setResponseCode(200).setBody("") }
            backend(fixture, "/plex/").reportPlayback(report())
            assertEquals("/plex/:/timeline", fixture.requests.single().requestUrl!!.encodedPath)
            assertEquals("/library/metadata/101", fixture.requests.single().requestUrl!!.queryParameter("key"))
        }
    }

    @Test fun nativeTimelineErrorsRemainVisibleAndNeverFallBackToMarkPlayed() = runBlocking {
        Fixture().use { fixture ->
            val backend = backend(fixture)
            for (status in listOf(401, 403, 500)) {
                fixture.response = { MockResponse().setResponseCode(status).setBody("secret $token") }
                val error = runCatching { backend.reportPlayback(report()) }.exceptionOrNull()
                assertTrue(error is PlexException)
                assertEquals(status, (error as PlexException).statusCode)
                assertFalse(error.message.orEmpty().contains(token))
            }
            assertEquals(3, fixture.requests.size)
            assertTrue(fixture.requests.all { it.requestUrl!!.encodedPath == "/:/timeline" })
        }
    }

    @Test fun refusesUnsafeTrackIdentifiersBeforeSendingAnything() = runBlocking {
        Fixture().use { fixture ->
            val error = runCatching {
                backend(fixture).reportPlayback(report().copy(song = song.copy(id = "../101?token=other")))
            }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test fun cancellationStopsAStalledTimelineWithoutReturningSuccess() = runBlocking {
        Fixture().use { fixture ->
            val requested = CompletableDeferred<Unit>()
            fixture.response = {
                requested.complete(Unit)
                MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
            }
            coroutineScope {
                var completed = false
                val pending = async { backend(fixture).reportPlayback(report()); completed = true }
                try {
                    withTimeout(5_000) { requested.await() }
                    pending.cancel()
                    withTimeout(2_000) { pending.join() }
                    assertFalse(completed)
                    assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
                } finally {
                    pending.cancel()
                }
            }
        }
    }
}
