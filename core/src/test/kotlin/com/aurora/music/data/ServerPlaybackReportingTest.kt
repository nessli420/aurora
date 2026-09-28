package com.aurora.music.data

import com.aurora.music.data.remote.JellyfinClient
import com.aurora.music.data.remote.SubsonicClient
import com.aurora.music.data.remote.testClientInfo
import com.aurora.music.model.Song
import com.google.gson.JsonParser
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException

class ServerPlaybackReportingTest {
    private val song = Song("track & one", "Track", "Artist", "Album", "", 180)

    private fun report(
        event: PlaybackReportEvent = PlaybackReportEvent.START,
        state: PlaybackReportState = PlaybackReportState.PLAYING,
        position: Long = 1234L,
        track: Song = song,
    ) = PlaybackReport(track, "play-occurrence", event, state, position, 180_000L, 90_000L, 1_750_000_000_123L, 1.25f)

    private fun session(server: MockWebServer, type: ServerType) =
        Session(server.url("/").toString().trimEnd('/'), "listener", "salt", "token", type, "user-id")

    private fun subsonic(server: MockWebServer) = SubsonicBackend(
        SubsonicClient(session(server, ServerType.SUBSONIC)), { 0 }, { it },
    )

    private fun jellyfin(server: MockWebServer) = JellyfinBackend(
        JellyfinClient(session(server, ServerType.JELLYFIN), testClientInfo), { 0 }, { it },
    )

    private fun ok(extra: String = "") = MockResponse().setHeader("Content-Type", "application/json")
        .setBody("""{"subsonic-response":{"status":"ok"$extra}}""")

    private fun extensions() = ok(",\"openSubsonicExtensions\":[{\"name\":\"playbackReport\",\"versions\":[1]}]")

    @Test fun nativeSubsonicReportsTimelineWithoutDoubleCountingTheListen() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(extensions())
            repeat(7) { server.enqueue(ok()) }
            val backend = subsonic(server)
            backend.reportPlayback(report())
            backend.reportPlayback(report(PlaybackReportEvent.PROGRESS, PlaybackReportState.PAUSED, 4000L))
            backend.reportPlayback(report(PlaybackReportEvent.PROGRESS, PlaybackReportState.BUFFERING, 4000L))
            backend.reportPlayback(report(PlaybackReportEvent.PROGRESS, position = 50_000L))
            backend.reportPlayback(report(PlaybackReportEvent.SCROBBLE, position = 90_000L))
            backend.reportPlayback(report(PlaybackReportEvent.STOP, PlaybackReportState.STOPPED, 100_000L))
            assertEquals("/rest/getOpenSubsonicExtensions.view", server.takeRequest().requestUrl!!.encodedPath)
            listOf("starting", "playing", "paused", "paused", "playing").forEach { state ->
                val url = server.takeRequest().requestUrl!!
                assertEquals("/rest/reportPlayback.view", url.encodedPath)
                assertEquals(song.id, url.queryParameter("mediaId"))
                assertEquals("song", url.queryParameter("mediaType"))
                assertEquals(state, url.queryParameter("state"))
                assertEquals("true", url.queryParameter("ignoreScrobble"))
                assertEquals("1.25", url.queryParameter("playbackRate"))
                assertEquals("Aurora", url.queryParameter("c"))
                assertEquals("token", url.queryParameter("t"))
            }
            val submitted = server.takeRequest().requestUrl!!
            assertEquals("/rest/scrobble.view", submitted.encodedPath)
            assertEquals("true", submitted.queryParameter("submission"))
            assertEquals(song.id, submitted.queryParameter("id"))
            assertEquals("1750000000123", submitted.queryParameter("time"))
            val stopped = server.takeRequest().requestUrl!!
            assertEquals("stopped", stopped.queryParameter("state"))
            assertEquals("100000", stopped.queryParameter("positionMs"))
            assertEquals(8, server.requestCount)
        }
    }

    @Test fun oldSubsonicServersGetNowPlayingAndOneTimestampedSubmission() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            repeat(3) { server.enqueue(ok()) }
            val backend = subsonic(server)
            backend.reportPlayback(report())
            backend.reportPlayback(report(PlaybackReportEvent.PROGRESS, PlaybackReportState.PAUSED))
            backend.reportPlayback(report(PlaybackReportEvent.PROGRESS))
            backend.reportPlayback(report(PlaybackReportEvent.STOP, PlaybackReportState.STOPPED))
            backend.reportPlayback(report(PlaybackReportEvent.SCROBBLE))
            server.takeRequest()
            listOf("false", "false", "true").forEach { expected ->
                val url = server.takeRequest().requestUrl!!
                assertEquals("/rest/scrobble.view", url.encodedPath)
                assertEquals(expected, url.queryParameter("submission"))
            }
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun missingOrUnknownExtensionVersionsUseLegacyProtocol() = runBlocking {
        listOf("", ",\"openSubsonicExtensions\":[{\"name\":\"playbackReport\",\"versions\":[2]}]").forEach { extra ->
            MockWebServer().use { server ->
                server.enqueue(ok(extra))
                server.enqueue(ok())
                subsonic(server).reportPlayback(report())
                server.takeRequest()
                assertEquals("false", server.takeRequest().requestUrl!!.queryParameter("submission"))
            }
        }
    }

    @Test fun advertisedButUnavailableNativeEndpointFallsBackOnlyOnce() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(extensions())
            server.enqueue(MockResponse().setResponseCode(404))
            repeat(2) { server.enqueue(ok()) }
            val backend = subsonic(server)
            backend.reportPlayback(report())
            backend.reportPlayback(report(PlaybackReportEvent.PROGRESS))
            assertEquals(4, server.requestCount)
            server.takeRequest()
            assertEquals("/rest/reportPlayback.view", server.takeRequest().requestUrl!!.encodedPath)
            repeat(2) { assertEquals("false", server.takeRequest().requestUrl!!.queryParameter("submission")) }
        }
    }

    @Test fun subsonicRejectsAuthenticationAndScrobbleErrors() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok().setBody("""{"subsonic-response":{"status":"failed","error":{"code":40,"message":"Unauthorized"}}}"""))
            val error = runCatching { subsonic(server).reportPlayback(report()) }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertEquals(1, server.requestCount)
        }
        MockWebServer().use { server ->
            server.enqueue(ok().setBody("""{"subsonic-response":{"status":"failed","error":{"code":70,"message":"Missing track"}}}"""))
            assertTrue(runCatching { subsonic(server).reportPlayback(report(PlaybackReportEvent.SCROBBLE)) }.isFailure)
            assertEquals("true", server.takeRequest().requestUrl!!.queryParameter("submission"))
        }
    }

    @Test fun jellyfinUsesSessionLifecycleAndNeverAlsoMarksTheTrackPlayed() = runBlocking {
        MockWebServer().use { server ->
            repeat(5) { server.enqueue(MockResponse().setResponseCode(204)) }
            val backend = jellyfin(server)
            backend.reportPlayback(report())
            backend.reportPlayback(report(PlaybackReportEvent.PROGRESS, PlaybackReportState.PAUSED, 4500L))
            backend.reportPlayback(report(PlaybackReportEvent.PROGRESS, PlaybackReportState.BUFFERING, 4500L))
            backend.reportPlayback(report(PlaybackReportEvent.PROGRESS, position = 80_000L))
            backend.reportPlayback(report(PlaybackReportEvent.SCROBBLE))
            backend.reportPlayback(report(PlaybackReportEvent.STOP, PlaybackReportState.STOPPED, 92_345L))
            val requests = List(5) { server.takeRequest() }
            assertEquals(listOf("/Sessions/Playing", "/Sessions/Playing/Progress", "/Sessions/Playing/Progress",
                "/Sessions/Playing/Progress", "/Sessions/Playing/Stopped"), requests.map { it.path })
            requests.forEach {
                assertEquals("POST", it.method)
                assertTrue(it.getHeader("X-Emby-Authorization")!!.contains("Client=\"Aurora\""))
                assertTrue(it.getHeader("X-Emby-Authorization")!!.contains("Version=\"${testClientInfo.version}\""))
            }
            val bodies = requests.map { JsonParser.parseString(it.body.readUtf8()).asJsonObject }
            assertEquals(listOf(12_340_000L, 45_000_000L, 45_000_000L, 800_000_000L, 923_450_000L),
                bodies.map { it["PositionTicks"].asLong })
            bodies.forEach {
                assertEquals(song.id, it["ItemId"].asString)
                assertEquals("play-occurrence", it["PlaySessionId"].asString)
            }
            assertEquals(listOf(false, true, true, false), bodies.take(4).map { it["IsPaused"].asBoolean })
            assertTrue(bodies.first()["CanSeek"].asBoolean)
            assertEquals("DirectPlay", bodies.first()["PlayMethod"].asString)
            assertEquals(5, server.requestCount)
        }
    }

    @Test fun jellyfinUsesExistingStreamIdentifiersAndSafeTicks() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setResponseCode(204)) }
            val backend = jellyfin(server)
            val streaming = song.copy(streamUrl = server.url("/Audio/track/universal?PlaySessionId=server-play&MediaSourceId=source").toString())
            backend.reportPlayback(report(position = -1L, track = streaming))
            backend.reportPlayback(report(PlaybackReportEvent.STOP, PlaybackReportState.STOPPED, Long.MAX_VALUE, streaming))
            val start = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
            assertEquals("server-play", start["PlaySessionId"].asString)
            assertEquals("source", start["MediaSourceId"].asString)
            assertEquals("Transcode", start["PlayMethod"].asString)
            assertEquals(0L, start["PositionTicks"].asLong)
            val stop = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
            assertTrue(stop["PositionTicks"].asLong > 0L)
        }
    }

    @Test fun jellyfinKeepsPlaybackFailuresVisible() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            val error = runCatching { jellyfin(server).reportPlayback(report()) }.exceptionOrNull()
            assertTrue(error is HttpException)
            assertEquals(401, (error as HttpException).code())
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun jellyfinDeviceIdentityIsStablePerCredentialAndMatchesBothStreamModes() {
        MockWebServer().use { server ->
            val saved = session(server, ServerType.JELLYFIN)
            val first = JellyfinClient(saved, testClientInfo)
            assertEquals(first.deviceId, JellyfinClient(saved, testClientInfo).deviceId)
            assertNotEquals(first.deviceId, JellyfinClient(saved.copy(token = "other-device-token"), testClientInfo).deviceId)
            assertFalse(first.deviceId.contains(saved.token))
            assertEquals("saved-device", JellyfinClient(saved.copy(clientToken = "saved-device"), testClientInfo).deviceId)
            assertEquals(first.deviceId, first.streamUrl("track", 0, true).toHttpUrl().queryParameter("DeviceId"))
            assertEquals(first.deviceId, first.streamUrl("track", 128, false).toHttpUrl().queryParameter("DeviceId"))
        }
    }

    @Test fun jellyfinNewLoginStoresTheDeviceIdentityUsedDuringAuthentication() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"AccessToken":"new-token","User":{"Id":"user-id","Name":"Listener"}}"""))
            val saved = JellyfinClient.authenticate(server.url("/").toString(), "Listener", "password", testClientInfo)
            assertTrue(saved.clientToken.startsWith("aurora-"))
            assertTrue(server.takeRequest().getHeader("X-Emby-Authorization")!!.contains("DeviceId=\"${saved.clientToken}\""))
            server.enqueue(MockResponse().setResponseCode(204))
            val client = JellyfinClient(saved, testClientInfo)
            JellyfinBackend(client, { 0 }, { it }).reportPlayback(report())
            assertTrue(server.takeRequest().getHeader("X-Emby-Authorization")!!.contains("DeviceId=\"${saved.clientToken}\""))
        }
    }

    @Test fun cancellationStopsProviderReportingWithoutFallbackRequests() = runBlocking {
        listOf(ServerType.SUBSONIC, ServerType.JELLYFIN).forEach { type ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                val backend: MediaBackend = if (type == ServerType.SUBSONIC) subsonic(server) else jellyfin(server)
                val error = runCatching { withTimeout(500L) { backend.reportPlayback(report()) } }.exceptionOrNull()
                assertTrue(error is TimeoutCancellationException)
                assertEquals(1, server.requestCount)
            }
        }
    }
}
