package com.aurora.music.data

import com.aurora.music.data.remote.*
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class SoundCloudBackendTest {
    private val session = Session(SoundCloudClient.WEB, "listener", "", "opaque-reference", ServerType.SOUNDCLOUD, "42")
    private fun track(id: Int, title: String = "Track $id") =
        """{"id":$id,"title":"$title","duration":30000,"full_duration":322606,"artwork_url":"https://i1.sndcdn.com/artworks-a-large.jpg","user":{"id":7,"username":"uploader","avatar_url":"https://i1.sndcdn.com/avatars-b-large.jpg"},"publisher_metadata":{"artist":"Real Artist","album_title":"Record","explicit":true}}"""
    private fun stub(id: Int) = """{"id":$id,"kind":"track","policy":"ALLOW"}"""

    @Test fun mapsTracksWithPublisherArtistFullLengthAndLargeArt() {
        val song = SoundCloudBackend.song(JsonParser.parseString(track(5)).asJsonObject)!!
        assertEquals("Real Artist", song.artist)
        assertEquals("Record", song.album)
        assertEquals(322, song.durationSec)
        assertEquals("https://i1.sndcdn.com/artworks-a-t500x500.jpg", song.artworkUrl)
        assertEquals("aurora-sc://track/5", song.streamUrl)
        assertEquals("7", song.artistId)
        assertTrue(song.explicit)
        assertNull(SoundCloudBackend.song(JsonParser.parseString(stub(6)).asJsonObject))
    }

    @Test fun playlistsHydrateIdStubsInOrder() = runBlocking {
        val asked = mutableListOf<String>()
        val backend = SoundCloudBackend(session, SoundCloudTransport { path ->
            asked += path
            JsonParser.parseString(when {
                path.startsWith("/playlists/9") -> """{"id":9,"title":"Mix","is_album":false,"user":{"id":42,"username":"listener"},"tracks":[${track(1)},${stub(2)},${track(3)},${stub(4)}]}"""
                path.startsWith("/tracks?ids=") -> "[${track(4)},${track(2)}]"
                else -> error(path)
            })
        })
        val detail = backend.detail("playlist", "9")!!
        assertEquals(listOf("1", "2", "3", "4"), detail.tracks.map { it.id })
        assertTrue(detail.info.editable)
        assertEquals(listOf("/playlists/9", "/tracks?ids=2,4"), asked)
    }

    @Test fun libraryFollowsEveryPageAndSplitsAlbumsFromPlaylists() = runBlocking {
        val backend = SoundCloudBackend(session, SoundCloudTransport { path ->
            JsonParser.parseString(when (path) {
                "/me/library/all?limit=200" -> """{"collection":[{"type":"playlist","playlist":{"id":1,"title":"Mine","track_count":3,"user":{"username":"listener"}}}],"next_href":"https://api-v2.soundcloud.com/me/library/all?offset=1"}"""
                "https://api-v2.soundcloud.com/me/library/all?offset=1" -> """{"collection":[{"type":"album-like","playlist":{"id":2,"title":"LP","is_album":true,"track_count":9,"release_date":"2001-03-12T00:00:00Z","user":{"username":"band"}}},{"type":"system-playlist-like","system_playlist":{"urn":"soundcloud:system-playlists:weekly:42","title":"Weekly","tracks":[{"id":1}]}}],"next_href":null}"""
                else -> error(path)
            })
        })
        assertEquals(listOf("1", "soundcloud:system-playlists:weekly:42"), backend.allPlaylists().map { it.id })
        assertEquals(listOf(2001), backend.allAlbums().map { it.year })
    }

    @Test fun coverlessPlaylistsBorrowTheirFirstTrackArtOnce() = runBlocking {
        val asked = mutableListOf<String>()
        val backend = SoundCloudBackend(session, SoundCloudTransport { path ->
            asked += path
            JsonParser.parseString(when (path) {
                "/me/library/all?limit=200" -> """{"collection":[{"type":"playlist","playlist":{"id":1,"title":"Bare","artwork_url":null}},{"type":"playlist","playlist":{"id":2,"title":"Drawn","artwork_url":"https://i1.sndcdn.com/artworks-own-large.jpg"}}]}"""
                "/playlists/1?representation=mini" -> """{"id":1,"tracks":[${stub(8)},${track(5)}]}"""
                else -> error(path)
            })
        })
        val first = backend.allPlaylists().associate { it.id to it.coverUrl }
        assertEquals("https://i1.sndcdn.com/artworks-a-t500x500.jpg", first["1"])
        assertEquals("https://i1.sndcdn.com/artworks-own-t500x500.jpg", first["2"])
        backend.allPlaylists()
        assertEquals(1, asked.count { it.startsWith("/playlists/") })
    }

    private class Recorder(private val reads: (String) -> String) : SoundCloudTransport {
        val writes: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        override suspend fun get(path: String) = JsonParser.parseString(reads(path))
        override suspend fun send(method: String, path: String, body: com.google.gson.JsonObject?, form: Map<String, String>?): com.google.gson.JsonElement {
            writes += "$method $path ${body ?: ""}".trim()
            val tracks = body?.getAsJsonObject("playlist")?.getAsJsonArray("tracks")
            return JsonParser.parseString(if (method == "PUT" && tracks != null) """{"track_count":${tracks.size()}}""" else """{"id":77}""")
        }
    }

    @Test fun streamsSkipPreviewsAndDrmAndPreferHighQuality() {
        fun coding(preset: String, protocol: String, quality: String = "sq", snipped: Boolean = false) =
            """{"url":"https://api-v2.soundcloud.com/media/$preset/$protocol","preset":"$preset","snipped":$snipped,"quality":"$quality","format":{"protocol":"$protocol"}}"""
        fun pick(vararg codings: String) = SoundCloudBackend.transcoding(JsonParser.parseString("""{"media":{"transcodings":[${codings.joinToString(",")}]}}""").asJsonObject)?.string("preset")
        assertEquals("aac_160k", pick(coding("aac_96k", "hls"), coding("abr_sq", "hls"), coding("aac_160k", "hls")))
        assertEquals("aac_96k", pick(coding("aac_160k", "ctr-encrypted-hls"), coding("aac_96k", "hls", "hq")))
        assertNull(pick(coding("mp3_1_0", "hls", snipped = true), coding("aac_160k", "cbc-encrypted-hls")))
    }

    @Test fun playbackResolvesTheSignedLinkWithTheTrackAuthorization() = runBlocking {
        val asked = mutableListOf<String>()
        val backend = SoundCloudBackend(session, SoundCloudTransport { path ->
            asked += path
            JsonParser.parseString(when {
                path == "/tracks/5" -> """{"track_authorization":"a+b","media":{"transcodings":[{"url":"https://api-v2.soundcloud.com/media/soundcloud:tracks:5/x/stream/hls","preset":"aac_160k","format":{"protocol":"hls"}}]}}"""
                else -> """{"url":"https://playback.media-streaming.soundcloud.cloud/x/playlist.m3u8?expires=1"}"""
            })
        })
        assertEquals("https://playback.media-streaming.soundcloud.cloud/x/playlist.m3u8?expires=1", backend.playbackUrl("5"))
        assertEquals("https://api-v2.soundcloud.com/media/soundcloud:tracks:5/x/stream/hls?track_authorization=a%2Bb", asked[1])
    }

    @Test fun likesFollowsAndHistoryUseTheWebPlayersRoutes() = runBlocking {
        val api = Recorder { error(it) }
        val backend = SoundCloudBackend(session, api)
        backend.setStarred("5", true, "song")
        backend.setStarred("9", false, "playlist")
        backend.setStarred("7", true, "artist")
        backend.setStarred("soundcloud:system-playlists:weekly:42", true, "playlist")
        backend.scrobble("5")
        kotlinx.coroutines.withTimeout(5_000) { while (api.writes.size < 5) kotlinx.coroutines.delay(10) }
        assertEquals(setOf("PUT /users/42/track_likes/5", "DELETE /users/42/playlist_likes/9", "POST /me/followings/7",
            "PUT /users/42/system_playlist_likes/soundcloud:system-playlists:weekly:42",
            """POST /me/play-history {"track_urn":"soundcloud:tracks:5"}"""), api.writes.toSet())
    }

    @Test fun repostsReadEveryIdPageAndUseTheWebPlayersRoutes() = runBlocking {
        val api = Recorder { path -> when (path) {
            "/me/track_reposts/ids?limit=200" -> """{"collection":[3,4],"next_href":"https://api-v2.soundcloud.com/me/track_reposts/ids?offset=2"}"""
            "https://api-v2.soundcloud.com/me/track_reposts/ids?offset=2" -> """{"collection":[1234567890123],"next_href":null}"""
            else -> error(path)
        } }
        val backend = SoundCloudBackend(session, api)
        assertTrue(backend.isReposted("4"))
        assertTrue(backend.isReposted("1234567890123"))
        assertFalse(backend.isReposted("6"))
        assertFalse(backend.supportsReposts("soundcloud:system-playlists:weekly:42"))
        assertTrue(backend.setReposted("4", false))
        assertTrue(backend.setReposted("6", true))
        assertEquals(listOf("DELETE /me/track_reposts/4", "PUT /me/track_reposts/6"), api.writes)
    }

    @Test fun playlistEditsSendTheWholeTrackListBack() = runBlocking {
        val api = Recorder { """{"id":9,"track_count":3,"tracks":[${track(1)},${stub(2)},${stub(1)}]}""" }
        val backend = SoundCloudBackend(session, api)
        assertTrue(backend.addToPlaylist("9", listOf("4")))
        assertTrue(backend.removeFromPlaylist("9", listOf("1")))
        assertFalse(backend.removeFromPlaylist("9", listOf("8")))
        assertTrue(backend.updatePlaylist("9", "New", null))
        assertEquals(listOf("""PUT /playlists/9 {"playlist":{"tracks":[1,2,1,4]}}""", """PUT /playlists/9 {"playlist":{"tracks":[2]}}""",
            """PUT /playlists/9 {"playlist":{"title":"New","tracks":[1,2,1]}}"""), api.writes)
        assertEquals("77", backend.createPlaylistWithId("Mix", listOf("5")))
        assertEquals("""POST /playlists {"playlist":{"title":"Mix","sharing":"private","tracks":[5]}}""", api.writes.last())
    }

    @Test fun editsRefuseAPlaylistThatCameBackIncomplete() = runBlocking {
        val backend = SoundCloudBackend(session, Recorder { """{"id":9,"track_count":4,"tracks":[${stub(1)}]}""" })
        assertThrows(java.io.IOException::class.java) { runBlocking { backend.addToPlaylist("9", listOf("4")) } }
        Unit
    }

    @Test fun commentsFollowEveryPageInTrackOrderAndPostAtAPosition() = runBlocking {
        fun comment(id: Int, at: Int) = """{"id":$id,"body":" nice $id ","timestamp":$at,"user":{"username":"fan$id","avatar_url":"https://i1.sndcdn.com/avatars-x-large.jpg"}}"""
        val api = object : SoundCloudTransport {
            val writes = mutableListOf<String>()
            override suspend fun get(path: String) = JsonParser.parseString(when (path) {
                "/tracks/5/comments?threaded=0&filter_replies=0&limit=200" -> """{"collection":[${comment(1, 90000)},{"id":9,"body":""}],"next_href":"https://api-v2.soundcloud.com/tracks/5/comments?offset=2"}"""
                "https://api-v2.soundcloud.com/tracks/5/comments?offset=2" -> """{"collection":[${comment(2, 1500)}],"next_href":null}"""
                else -> error(path)
            })
            override suspend fun send(method: String, path: String, body: com.google.gson.JsonObject?, form: Map<String, String>?): com.google.gson.JsonElement {
                writes += "$method $path $form"
                return JsonParser.parseString(comment(3, 42000))
            }
        }
        val backend = SoundCloudBackend(session, api)
        val song = SoundCloudBackend.song(JsonParser.parseString(track(5)).asJsonObject)!!
        val comments = backend.comments(song)!!
        assertEquals(listOf("2", "1"), comments.map { it.id })
        assertEquals("nice 2", comments.first().body)
        assertEquals("https://i1.sndcdn.com/avatars-x-t500x500.jpg", comments.first().avatarUrl)
        assertEquals(42000L, backend.postComment(song, "wow", 42000)!!.timestampMs)
        assertEquals(listOf("POST /tracks/5/comments {body=wow, timestamp=42000}"), api.writes)
        assertNull(backend.comments(song.copy(id = "soundcloud:system-playlists:x")))
    }

    @Test fun homeShelvesDropRepeatedItems() = runBlocking {
        val user = """{"kind":"user","id":7,"username":"uploader"}"""
        val backend = SoundCloudBackend(session, SoundCloudTransport { path ->
            if (!path.startsWith("/mixed-selections")) error(path)
            JsonParser.parseString("""{"collection":[{"id":"artists","title":"Artists","items":{"collection":[$user,$user]}}]}""")
        })
        assertEquals(listOf("artist:7"), backend.home().sections.single().items.map { it.key })
    }

    @Test fun nextLinksStayOnTheApiHost() {
        assertEquals("api-v2.soundcloud.com", SoundCloudClient.url("/me").host)
        assertThrows(IllegalArgumentException::class.java) { SoundCloudClient.url("https://evil.example/me") }
        assertThrows(IllegalArgumentException::class.java) { SoundCloudClient.url("/me/../oauth") }
    }

    @Test fun sessionReadsTheTokenExpiryAndRotatesCookies() {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":1792103236}""".toByteArray())
        val web = SoundCloudWebSession("sc_anonymous_id=x; oauth_token=h.$payload.s; oauth_refresh_token=old", "UA")
        assertEquals(1792103236L, web.expiresAt)
        assertTrue(web.canRefresh)
        val next = web.refreshed(listOf("oauth_token=fresh; Path=/; Secure", "oauth_refresh_token=new; HttpOnly", "sc_anonymous_id=; Max-Age=0"))
        assertEquals("fresh", next.token)
        assertEquals("oauth_token=fresh; oauth_refresh_token=new", next.cookie)
        assertEquals(next.cookie, SoundCloudWebSession.decode(next.encode()).cookie)
        assertEquals("https://soundcloud.com/n/api/refresh-token", web.refreshUrl)
    }

    @Test fun mobileLoginsKeepTheirSiteThroughRefreshAndStorage() {
        val mobile = SoundCloudWebSession("oauth_token=a.b.c", "UA", SoundCloudWebSession.MOBILE)
        assertEquals("https://m.soundcloud.com/-/refresh-token", mobile.refreshUrl)
        assertEquals(SoundCloudWebSession.MOBILE, SoundCloudWebSession.decode(mobile.refreshed(listOf("oauth_token=d.e.f")).encode()).origin)
        assertThrows(java.io.IOException::class.java) { SoundCloudWebSession.decode(SoundCloudWebSession("oauth_token=x", "", "https://evil.example").encode()) }
    }

    @Test fun likesCountHereStraightAwayWhileSoundCloudRefusesThem() = runBlocking {
        val backend = SoundCloudBackend(session, object : SoundCloudTransport {
            override suspend fun get(path: String) = JsonParser.parseString(when (path) {
                "/users/42/track_likes?limit=200" -> """{"collection":[{"track":${track(5)}}]}"""
                "/tracks?ids=9" -> "[${track(9)}]"
                else -> error(path)
            })
            override suspend fun send(method: String, path: String, body: com.google.gson.JsonObject?, form: Map<String, String>?): com.google.gson.JsonElement =
                throw SoundCloudCheckException()
        })
        assertTrue(backend.setStarred("9", true, "song"))
        assertEquals(listOf("9", "5"), backend.allSongs().map { it.id })
        backend.setStarred("5", false, "song")
        assertEquals(listOf("9"), backend.allSongs().map { it.id })
        backend.setStarred("9", false, "song")
        assertEquals(emptyList<String>(), backend.allSongs().map { it.id })
        backend.setStarred("5", true, "song")
        assertEquals(listOf("5"), backend.allSongs().map { it.id })
    }

    @Test fun theLikeCountIsTheListNotSoundCloudsDriftingCounter() = runBlocking {
        val backend = SoundCloudBackend(session, SoundCloudTransport { path ->
            when (path) {
                "/me" -> JsonParser.parseString("""{"id":42,"likes_count":14}""")
                "/users/42/track_likes?limit=200" -> JsonParser.parseString("""{"collection":[{"track":${track(1)}},{"track":${track(2)}},{"track":${track(3)}}]}""")
                else -> error(path)
            }
        })
        assertEquals(3, backend.starredCount())
        assertEquals(3, backend.starredSongs().size)
    }

    @Test fun pendingLikesSurviveARestartAndSettleOnceSoundCloudAgrees() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("aurora-sc-likes-").toFile()
        try {
            SoundCloudPendingLikes(dir).set("42", "track:9", PendingLike(true))
            val backend = SoundCloudBackend(session, SoundCloudTransport { path ->
                if (path != "/users/42/track_likes?limit=200") error(path)
                JsonParser.parseString("""{"collection":[{"track":${track(9)}}]}""")
            }, pending = SoundCloudPendingLikes(dir))
            assertEquals(listOf("9"), backend.allSongs().map { it.id })
            assertTrue(SoundCloudPendingLikes(dir).of("42").isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun onlyVerifyingCanOpenTheBotCheckAndOnlyOnce() {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":4102444800}""".toByteArray())
        val http = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val text = if (chain.request().url.host == "soundcloud.com") """<script src="https://a-v2.sndcdn.com/assets/app.js">"""
                else """client_id:"abcdefghijklmnopqrstuvwxyz012345""""
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(text.toResponseBody()).build()
        }.build()
        val prompts = mutableListOf<Boolean>()
        val client = SoundCloudClient({ SoundCloudWebSession("oauth_token=h.$payload.s") }, http = http, browser = SoundCloudBrowser { _, _, _, _, _, _, prompt ->
            prompts += prompt
            403 to """{"url":"https://geo.captcha-delivery.com/captcha/?cid=x"}"""
        })
        assertThrows(SoundCloudCheckException::class.java) { runBlocking { client.send("POST", "/tracks/9/comments") } }
        assertThrows(SoundCloudCheckException::class.java) { runBlocking { client.sendWithCheck("PUT", "/users/42/track_likes/9") } }
        assertEquals(listOf(false, true), prompts)
    }

    @Test fun followsWaitTooAndVerifyingSendsWhatWaited() = runBlocking {
        val sent = java.util.Collections.synchronizedList(mutableListOf<String>())
        val backend = SoundCloudBackend(session, object : SoundCloudTransport {
            override suspend fun get(path: String) = JsonParser.parseString(when (path) {
                "/users/42/followings?limit=200" -> """{"collection":[]}"""
                "/users/7" -> """{"id":7,"username":"uploader"}"""
                else -> error(path)
            })
            override suspend fun send(method: String, path: String, body: com.google.gson.JsonObject?, form: Map<String, String>?): com.google.gson.JsonElement =
                throw SoundCloudCheckException()
            override suspend fun sendWithCheck(method: String, path: String): com.google.gson.JsonElement {
                sent += "$method $path"
                return JsonParser.parseString("{}")
            }
        })
        backend.setStarred("7", true, "artist")
        assertEquals(listOf("uploader"), backend.allArtists().map { it.name })
        assertTrue(backend.verify())
        assertEquals(listOf("POST /me/followings/7"), sent)
    }
}
