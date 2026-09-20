package com.aurora.music.data

import com.aurora.music.data.remote.*
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class YouTubeMusicBackendTest {
    private val session = Session("https://music.youtube.com", "Listener", "", "opaque-reference", ServerType.YOUTUBE_MUSIC, "@listener")
    private fun parsed(value: String) = JsonParser.parseString(value).asJsonObject
    private fun track(id: String = "abcdefghijk") = """{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"$id"},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Track"}]}}}],"fixedColumns":[{"musicResponsiveListItemFixedColumnRenderer":{"text":{"simpleText":"3:24"}}}]}}"""

    @Test fun publicSearchFixtureKeepsAlbumsSeparateFromPlayButtonsAndParsesNestedSongs() {
        val root = javaClass.getResourceAsStream("/youtube-music/search.json")!!.bufferedReader().use { parsed(it.readText()) }
        val result = YouTubeMusicParser.results(root)
        assertTrue(result.songs.isNotEmpty())
        assertTrue(result.albums.isNotEmpty())
        assertTrue(result.playlists.isNotEmpty())
        assertTrue(result.songs.all { it.streamUrl == "aurora-yt://video/${it.id}" && it.durationSec > 0 })
        assertTrue(result.songs.none { it.id.startsWith("MPRE") })
    }

    @Test fun playlistContinuationPreservesRepeatedTracksAndIgnoresSuggestions() = runBlocking {
        val requests = mutableListOf<JsonObject>()
        val api = YouTubeMusicTransport { _, body ->
            requests += body
            if (body.has("continuation")) parsed("""{"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[${track()}]}}]}""")
            else parsed("""{"header":{"musicResponsiveHeaderRenderer":{"title":{"simpleText":"My playlist"}}},"contents":{"musicPlaylistShelfRenderer":{"contents":[${track()}],"continuations":[{"nextContinuationData":{"continuation":"page-2"}}]}},"suggestions":[${track("zyxwvutsrqp")}] }""")
        }
        val result = YouTubeMusicBackend(session, api).detail("playlist", "PLtest")!!
        assertEquals(listOf("abcdefghijk", "abcdefghijk"), result.tracks.map { it.id })
        assertEquals("My playlist", result.info.title)
        assertEquals(2, result.info.songCount)
        assertEquals("VLPLtest", requests[0].get("browseId").asString)
        assertEquals("page-2", requests[1].get("continuation").asString)
    }

    @Test fun failedContinuationCannotReturnAPartialCollection() = runBlocking {
        val backend = YouTubeMusicBackend(session, YouTubeMusicTransport { _, body ->
            if (body.has("continuation")) throw IOException("Network failed")
            parsed("""{"musicPlaylistShelfRenderer":{"contents":[${track()}],"continuations":[{"nextContinuationData":{"continuation":"page-2"}}]}}""")
        })
        try { backend.collectionTracks("playlist", "PLtest"); fail("Partial collection was accepted") } catch (_: IOException) { }
    }

    @Test fun nextResponseUsesExactTrackAndNeverSearchesForASubstitute() = runBlocking {
        val backend = YouTubeMusicBackend(session, YouTubeMusicTransport { endpoint, body ->
            assertEquals("next", endpoint)
            assertEquals("abcdefghijk", body.get("videoId").asString)
            parsed("""{"playlistPanelVideoRenderer":{"videoId":"abcdefghijk","title":{"simpleText":"Exact version"},"lengthText":{"simpleText":"4:15"}}}""")
        })
        assertEquals("aurora-yt://video/abcdefghijk", backend.songFor("abcdefghijk")!!.streamUrl)
        assertEquals(255, backend.songFor("abcdefghijk")!!.durationSec)
    }

    @Test fun playlistWritesUseExpectedActionsAndPrivateCreation() = runBlocking {
        val requests = mutableListOf<Pair<String, JsonObject>>()
        val backend = YouTubeMusicBackend(session, YouTubeMusicTransport { endpoint, body ->
            requests += endpoint to body
            parsed(if (endpoint == "playlist/create") """{"playlistId":"PLnew"}""" else """{"status":"STATUS_SUCCEEDED"}""")
        })
        assertEquals("PLnew", backend.createPlaylistWithId("My music"))
        assertTrue(backend.addToPlaylist("PLnew", listOf("abcdefghijk", "abcdefghijk")))
        assertTrue(backend.setStarred("abcdefghijk", true, "song"))
        assertEquals("PRIVATE", requests[0].second.get("privacyStatus").asString)
        assertEquals(2, requests[1].second.getAsJsonArray("actions").size())
        assertEquals("like/like", requests[2].first)
        assertEquals("abcdefghijk", requests[2].second.getAsJsonObject("target").get("videoId").asString)
    }

    @Test fun expiredAccountReportsFailureWithoutCrashingScreenOrPretendingToSave() = runBlocking {
        val errors = mutableListOf<String>()
        val backend = ReportingMediaBackend(YouTubeMusicBackend(session, YouTubeMusicTransport { _, _ -> throw IOException("Expired") }), errors::add)
        assertEquals(HomeData(), backend.home())
        assertFalse(backend.setStarred("abcdefghijk", true, "song"))
        assertEquals(2, errors.size)
        assertTrue(errors.all { it.contains("reconnect") })
    }

    @Test fun cancellationIsNotConvertedIntoEmptyLibrary() = runBlocking {
        val backend = ReportingMediaBackend(YouTubeMusicBackend(session, YouTubeMusicTransport { _, _ -> throw CancellationException() })) { fail("Cancellation reported as failure") }
        try { backend.allSongs(); fail("Cancellation swallowed") } catch (_: CancellationException) { }
    }

    @Test fun standaloneSourcesCannotEnterMergedLibrary() {
        assertFalse(ServerType.YOUTUBE_MUSIC.supportsMergedLibrary)
        assertFalse(ServerType.SPOTIFY.supportsMergedLibrary)
        assertTrue(ServerType.LOCAL.supportsMergedLibrary)
        assertTrue(ServerType.SUBSONIC.supportsMergedLibrary)
        assertTrue(ServerType.JELLYFIN.supportsMergedLibrary)
    }

    @Test fun accountIdentitySurvivesDisplayNameAndTokenChanges() {
        assertEquals(session.accountKey(), session.copy(username = "New name", token = "new-reference").accountKey())
        assertNotEquals(session.accountKey(), session.copy(userId = "@another-listener").accountKey())
    }

    private fun webSession(cookie: String = "SAPISID=fixture-secret; SID=fixture-id") =
        YouTubeMusicWebSession(cookie, "fixture-visitor", "fixture-channel", "2", "1.20260920.01.00", "fixture-browser")

    @Test fun browserRequestsSignCookiesAndPreserveSelectedProfile() = runBlocking {
        val auth = webSession()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("music.youtube.com", request.url.host)
            assertEquals(auth.cookie, request.header("Cookie"))
            assertEquals("2", request.header("X-Goog-AuthUser"))
            assertEquals("fixture-visitor", request.header("X-Goog-Visitor-Id"))
            val authorization = request.header("Authorization")!!
            val timestamp = authorization.substringAfter(' ').substringBefore('_').toLong()
            assertEquals(auth.authorization(timestamp), authorization)
            val buffer = okio.Buffer()
            request.body!!.writeTo(buffer)
            val context = parsed(buffer.readUtf8()).getAsJsonObject("context")
            assertEquals("fixture-channel", context.getAsJsonObject("user").get("onBehalfOfUser").asString)
            assertEquals("fixture-visitor", context.getAsJsonObject("client").get("visitorData").asString)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("Fixture").body("{}".toResponseBody()).build()
        }.build()
        assertEquals(JsonObject(), YouTubeMusicClient({ auth }, http).request("browse", JsonObject()))
    }

    @Test fun expiredBrowserSessionDoesNotLoopOrPretendToReturnAnEmptyLibrary() = runBlocking {
        var calls = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(403).message("Forbidden").body("{}".toResponseBody()).build()
        }.build()
        try { YouTubeMusicClient({ webSession() }, http).request("browse", JsonObject()); fail("Expected authorization failure") }
        catch (_: IOException) { }
        assertEquals(1, calls)
    }

    @Test fun browserCredentialsCannotFollowRedirects() = runBlocking {
        var calls = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(302).message("Moved").header("Location", "https://example.com/")
                .body("".toResponseBody()).build()
        }.build()
        try { YouTubeMusicClient({ webSession() }, http).request("browse", JsonObject()); fail("Redirect was accepted") }
        catch (_: IOException) { }
        assertEquals(1, calls)
    }

    @Test fun browserSessionRoundTripAndSecureCookieFallback() {
        val auth = webSession("__Secure-3PAPISID=fixture-secret")
        val restored = YouTubeMusicWebSession.decode(auth.encode())
        assertEquals(auth.cookie, restored.cookie)
        assertEquals("fixture-channel", restored.dataSyncId)
        assertEquals(webSession().authorization(12345), restored.authorization(12345))
        assertEquals("SAPISIDHASH 12345_" + java.security.MessageDigest.getInstance("SHA-1")
            .digest("12345 fixture-secret https://music.youtube.com".toByteArray())
            .joinToString("") { "%02x".format(it) }, restored.authorization(12345))
    }

    @Test fun browserSessionExtractionRequiresExactSecureOrigin() {
        assertTrue(YouTubeMusicWebSession.isMusicPage("https://music.youtube.com/"))
        listOf("http://music.youtube.com", "https://music.youtube.com.evil.test", "https://accounts.google.com",
            "https://music.youtube.com:444", "https://x@music.youtube.com", "about:blank").forEach {
            assertFalse(it, YouTubeMusicWebSession.isMusicPage(it))
        }
        listOf("old-oauth@example.test", "{}", "null").forEach {
            try { YouTubeMusicWebSession.decode(it); fail("Invalid session accepted") } catch (_: IOException) { }
        }
    }

    @Test fun authenticatedProfileWithoutPublicHandleCanConnect() = runBlocking {
        val api = YouTubeMusicTransport { _, _ ->
            parsed("""{"activeAccountHeaderRenderer":{"accountName":{"simpleText":"Listener"}}}""")
        }
        assertEquals("profile-123", YouTubeMusicBackend.account(api, "profile-123").second)
        val signedOut = YouTubeMusicTransport { _, _ -> JsonObject() }
        try { YouTubeMusicBackend.account(signedOut, "profile-123"); fail("Anonymous account accepted") }
        catch (_: IOException) { }
    }
}
