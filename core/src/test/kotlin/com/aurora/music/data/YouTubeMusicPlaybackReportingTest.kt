package com.aurora.music.data

import com.aurora.music.data.remote.YouTubeMusicClient
import com.aurora.music.data.remote.YouTubeMusicTransport
import com.aurora.music.data.remote.YouTubeMusicWebSession
import com.aurora.music.data.remote.string
import com.aurora.music.model.Song
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class YouTubeMusicPlaybackReportingTest {
    private val song = Song("abcdefghijk", "Song", "Artist", "Album", "", 200)
    private val session = Session("https://music.youtube.com", "Listener", "", "", ServerType.YOUTUBE_MUSIC, "channel")
    private fun report(
        event: PlaybackReportEvent = PlaybackReportEvent.START,
        position: Long = 0,
        listened: Long = position,
        state: PlaybackReportState = PlaybackReportState.PLAYING,
        id: String = "listen-1",
        rate: Float = 1f,
    ) = PlaybackReport(song, id, event, state, position, 200_000, listened, 1_000, rate)

    private class Fixture : YouTubeMusicTransport {
        var playerCalls = 0
        var playerFailure: Exception? = null
        var trackingFailure: Exception? = null
        var response = """{"playbackTracking":{"videostatsPlaybackUrl":{"baseUrl":"https://s.youtube.com/api/stats/playback?ei=opaque&of=identity"},"videostatsWatchtimeUrl":{"baseUrl":"https://s.youtube.com/api/stats/watchtime?ei=opaque&of=identity"}}}"""
        val pings = mutableListOf<Pair<String, Map<String, String>>>()
        override suspend fun request(endpoint: String, body: JsonObject): JsonObject {
            assertEquals("player", endpoint)
            assertEquals("abcdefghijk", body.string("videoId"))
            playerCalls++
            playerFailure?.let { throw it }
            return JsonParser.parseString(response).asJsonObject
        }

        override suspend fun trackPlayback(url: String, parameters: Map<String, String>) {
            trackingFailure?.let { throw it }
            pings += url to parameters
        }
    }

    @Test fun listeningStartsOneAuthenticatedHistoryEntryAndReportsRealIntervals() = runBlocking {
        val api = Fixture()
        val backend = YouTubeMusicBackend(session, api)
        backend.reportPlayback(report())
        backend.reportPlayback(report(PlaybackReportEvent.PROGRESS, 10_000))
        backend.reportPlayback(report(PlaybackReportEvent.SCROBBLE, 10_000))
        assertEquals(1, api.playerCalls)
        assertEquals(2, api.pings.size)
        assertTrue(api.pings.first().first.startsWith("https://music.youtube.com/api/stats/playback?"))
        assertEquals("0.000", api.pings[1].second["st"])
        assertEquals("10.000", api.pings[1].second["et"])
        assertEquals(1, api.pings.map { it.second["cpn"] }.distinct().size)
        assertTrue(api.pings[0].second["cpn"]!!.matches(Regex("[A-Za-z0-9_-]{16}")))
    }

    @Test fun pausedTimeAndSeeksDoNotBecomeWatchtime() = runBlocking {
        val api = Fixture()
        val backend = YouTubeMusicBackend(session, api)
        backend.reportPlayback(report())
        backend.reportPlayback(report(PlaybackReportEvent.PROGRESS, 10_000, state = PlaybackReportState.PAUSED))
        backend.reportPlayback(report(PlaybackReportEvent.PROGRESS, 10_000, 10_000, PlaybackReportState.PAUSED))
        backend.reportPlayback(report(PlaybackReportEvent.PROGRESS, 10_000, 10_000))
        backend.reportPlayback(report(PlaybackReportEvent.PROGRESS, 150_000, 10_000))
        backend.reportPlayback(report(PlaybackReportEvent.PROGRESS, 160_000, 20_000))
        val heard = api.pings.filter { it.second["st"] != it.second["et"] && "st" in it.second }
        assertEquals(listOf("0.000", "150.000"), heard.map { it.second["st"] })
        assertEquals(listOf("10.000", "160.000"), heard.map { it.second["et"] })
    }

    @Test fun backwardSeekAndBufferingOnlyReportAudioThatAdvanced() = runBlocking {
        val api = Fixture()
        val backend = YouTubeMusicBackend(session, api)
        backend.reportPlayback(report(position = 100_000, listened = 0))
        backend.reportPlayback(report(PlaybackReportEvent.PROGRESS, 20_000, 0))
        backend.reportPlayback(report(PlaybackReportEvent.PROGRESS, 30_000, 10_000, PlaybackReportState.BUFFERING))
        backend.reportPlayback(report(PlaybackReportEvent.PROGRESS, 30_000, 10_000))
        assertEquals("20.000", api.pings[1].second["st"])
        assertEquals("30.000", api.pings[1].second["et"])
        assertEquals("30.000", api.pings[2].second["st"])
        assertEquals("30.000", api.pings[2].second["et"])
    }

    @Test fun fasterPlaybackRecordsMediaRangeWithItsActualRate() = runBlocking {
        val api = Fixture()
        val backend = YouTubeMusicBackend(session, api)
        backend.reportPlayback(report(rate = 2f))
        backend.reportPlayback(report(PlaybackReportEvent.PROGRESS, 20_000, 10_000, rate = 2f))
        assertEquals("0.000", api.pings[1].second["st"])
        assertEquals("20.000", api.pings[1].second["et"])
        assertEquals("2.0", api.pings[1].second["rate"])
    }

    @Test fun repeatedRecordingGetsNewSessionAndStopOnlyFlushesRemainingRange() = runBlocking {
        val api = Fixture()
        val backend = YouTubeMusicBackend(session, api)
        backend.reportPlayback(report())
        backend.reportPlayback(report(PlaybackReportEvent.PROGRESS, 10_000))
        backend.reportPlayback(report(PlaybackReportEvent.STOP, 12_000, state = PlaybackReportState.STOPPED))
        backend.reportPlayback(report(id = "listen-2"))
        assertEquals("10.000", api.pings[2].second["st"])
        assertEquals("12.000", api.pings[2].second["et"])
        assertEquals("1", api.pings[2].second["final"])
        assertNotEquals(api.pings[0].second["cpn"], api.pings[3].second["cpn"])
    }

    @Test fun duplicateStartsAndOrphanPausedOrStoppedReportsDoNotCreateViews() = runBlocking {
        val api = Fixture()
        val backend = YouTubeMusicBackend(session, api)
        backend.reportPlayback(report(PlaybackReportEvent.PROGRESS, state = PlaybackReportState.PAUSED))
        backend.reportPlayback(report(PlaybackReportEvent.STOP, state = PlaybackReportState.STOPPED))
        backend.reportPlayback(report(PlaybackReportEvent.SCROBBLE, position = 100_000))
        assertEquals(0, api.playerCalls)
        backend.reportPlayback(report())
        backend.reportPlayback(report())
        assertEquals(1, api.playerCalls)
        assertEquals(1, api.pings.size)
    }

    @Test fun failedStartRetriesWithSameNonceWithoutInventingAlreadyHeardRange() = runBlocking {
        val api = Fixture().apply { trackingFailure = IOException("network") }
        val backend = YouTubeMusicBackend(session, api)
        try { backend.reportPlayback(report()); fail("Failure swallowed") } catch (_: IOException) {}
        api.trackingFailure = null
        backend.reportPlayback(report(PlaybackReportEvent.PROGRESS, 10_000))
        assertEquals(1, api.playerCalls)
        assertEquals(2, api.pings.size)
        assertEquals("0.000", api.pings[1].second["st"])
        assertEquals("10.000", api.pings[1].second["et"])
    }

    @Test fun missingTrackingDoesNotPretendThatHistoryWasSaved() = runBlocking {
        val api = Fixture().apply { response = "{}" }
        try { YouTubeMusicBackend(session, api).reportPlayback(report()); fail("Missing history accepted") }
        catch (_: IOException) {}
        assertTrue(api.pings.isEmpty())
    }

    @Test fun cancellationPropagatesThroughPlayerAndTracking() = runBlocking {
        val api = Fixture().apply { playerFailure = CancellationException() }
        val backend = YouTubeMusicBackend(session, api)
        try { backend.reportPlayback(report()); fail("Cancellation swallowed") } catch (_: CancellationException) {}
        api.playerFailure = null
        api.trackingFailure = CancellationException()
        try { backend.reportPlayback(report()); fail("Cancellation swallowed") } catch (_: CancellationException) {}
    }

    private fun credentials() = YouTubeMusicWebSession("SAPISID=fixture; SID=fixture", "visitor", "channel", "2", "1.20260922.09.00", "fixture-agent", "delegated-channel")
    private fun response(request: Request, code: Int = 204) = Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(code).message("fixture").body("".toResponseBody()).build()

    @Test fun trackingCookiesAndSelectedProfileStayOnFixedMusicOrigin() = runBlocking {
        val auth = credentials()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("music.youtube.com", request.url.host)
            assertEquals("GET", request.method)
            assertEquals(auth.cookie, request.header("Cookie"))
            assertEquals(auth.pageId, request.header("X-Goog-PageId"))
            assertEquals("2", request.header("X-Goog-AuthUser"))
            assertEquals("opaque", request.url.queryParameter("ei"))
            assertEquals("10.000", request.url.queryParameter("cmt"))
            val timestamp = request.header("Authorization")!!.substringAfter(' ').substringBefore('_').toLong()
            assertEquals(auth.authorization(timestamp), request.header("Authorization"))
            response(request)
        }.build()
        YouTubeMusicClient({ auth }, http).trackPlayback("https://s.youtube.com/api/stats/playback?ei=opaque&cmt=0", mapOf("cmt" to "10.000"))
    }

    @Test fun foreignTrackingUrlsNeverReceiveAccountCredentials() = runBlocking {
        var credentialsRead = false
        var requests = 0
        val client = YouTubeMusicClient({ credentialsRead = true; credentials() }, OkHttpClient.Builder()
            .addInterceptor { requests++; response(it.request()) }.build())
        val urls = listOf(
            "https://example.com/api/stats/playback", "http://music.youtube.com/api/stats/playback",
            "https://music.youtube.com:444/api/stats/playback", "https://music.youtube.com@evil.test/api/stats/playback",
            "https://username@music.youtube.com/api/stats/playback", "https://music.youtube.com/youtubei/v1/player",
            "https://music.youtube.com/api/stats/playback#fragment", "https://music.youtube.com.evil.test/api/stats/playback",
        )
        for (url in urls) {
            try { client.trackPlayback(url, emptyMap()); fail("Unsafe tracking address accepted") } catch (_: IllegalArgumentException) {}
        }
        assertFalse(credentialsRead)
        assertEquals(0, requests)
    }

    @Test fun unauthorizedTrackingIsReportedAsExpiredAccount() = runBlocking {
        val client = YouTubeMusicClient({ credentials() }, OkHttpClient.Builder()
            .addInterceptor { response(it.request(), 401) }.build())
        try { client.trackPlayback("https://music.youtube.com/api/stats/playback", emptyMap()); fail("Expired session accepted") }
        catch (_: MediaAccountExpiredException) {}
    }

    @Test fun trackingRedirectsAreRejected() = runBlocking {
        var requests = 0
        val client = YouTubeMusicClient({ credentials() }, OkHttpClient.Builder().addInterceptor {
            requests++
            response(it.request(), 302).newBuilder().header("Location", "https://example.com").build()
        }.build())
        try { client.trackPlayback("https://music.youtube.com/api/stats/playback", emptyMap()); fail("Redirect accepted") }
        catch (_: IOException) {}
        assertEquals(1, requests)
    }

    @Test fun delegatedProfileSurvivesStorageAndRegularProfileDoesNotUseDataSyncAsPageId() = runBlocking {
        assertEquals("delegated-channel", YouTubeMusicWebSession.decode(credentials().encode()).pageId)
        val regular = YouTubeMusicWebSession("SAPISID=fixture", "visitor", "ordinary-user", pageId = "")
        assertEquals("", YouTubeMusicWebSession.decode(regular.encode()).pageId)
        val client = YouTubeMusicClient({ regular }, OkHttpClient.Builder().addInterceptor {
            assertNull(it.request().header("X-Goog-PageId"))
            val body = okio.Buffer().also { buffer -> it.request().body!!.writeTo(buffer) }.readUtf8()
            assertFalse(JsonParser.parseString(body).asJsonObject.getAsJsonObject("context").getAsJsonObject("user").has("onBehalfOfUser"))
            response(it.request(), 200).newBuilder().body("{}".toResponseBody()).build()
        }.build())
        assertEquals(JsonObject(), client.request("player", JsonObject()))
    }
}
