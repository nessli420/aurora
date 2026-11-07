package com.aurora.music.data

import com.aurora.music.data.remote.*
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class CataloguePerformanceTest {
    private fun parsed(value: String) = JsonParser.parseString(value).asJsonObject
    private fun reply(chain: Interceptor.Chain, code: Int, text: String) = Response.Builder().request(chain.request())
        .protocol(Protocol.HTTP_1_1).code(code).message("OK").body(text.toResponseBody()).build()

    @Test fun viewsOfKnownSizeAskForSmallerArtAndOthersKeepFullSize() {
        val art = "https://lh3.googleusercontent.com/abc=w1200-h1200-l90-rj"
        assertEquals("https://lh3.googleusercontent.com/abc=w240-h240-l90-rj", YouTubeMusicParser.sized(art, 156, 156))
        assertEquals("https://lh3.googleusercontent.com/abc=w544-h544-l90-rj", YouTubeMusicParser.sized("https://lh3.googleusercontent.com/abc=w60-h60-l90-rj", 480, 480))
        assertEquals(art, YouTubeMusicParser.sized(art, 1080, 1080))
        assertEquals(art, YouTubeMusicParser.sized(art, 0, 0))
        assertEquals(art, YouTubeMusicParser.sized(art, 156, 0))
        assertEquals("https://yt3.ggpht.com/def=s240-c-k-c0x00ffffff-no-rj", YouTubeMusicParser.sized("https://yt3.ggpht.com/def=s1200-c-k-c0x00ffffff-no-rj", 100, 100))
        val video = "https://i.ytimg.com/vi/abcdefghijk/maxresdefault.jpg"
        assertEquals("https://i.ytimg.com/vi/abcdefghijk/mqdefault.jpg", YouTubeMusicParser.sized(video, 156, 156))
        assertEquals(video, YouTubeMusicParser.sized(video, 480, 480))
        assertEquals("https://example.com/a=w1200-h1200", YouTubeMusicParser.sized("https://example.com/a=w1200-h1200", 156, 156))
    }

    @Test fun aMissingMqdefaultFallsBackToHqdefault() {
        val seen = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor(YouTubeArtworkFallback).addInterceptor { chain ->
            val url = chain.request().url.toString().also { seen += it }
            reply(chain, if (url.endsWith("mqdefault.jpg")) 404 else 200, "img")
        }.build()
        client.newCall(Request.Builder().url("https://i.ytimg.com/vi/jNQXAC9IVRw/mqdefault.jpg").build()).execute().use {
            assertEquals(200, it.code)
        }
        assertEquals(listOf("https://i.ytimg.com/vi/jNQXAC9IVRw/mqdefault.jpg", "https://i.ytimg.com/vi/jNQXAC9IVRw/hqdefault.jpg"), seen)
    }

    @Test fun aSavedSoundCloudClientIdSparesTheScrapeUntilItIsRefused() = runBlocking {
        val fresh = "abcdefghijklmnopqrstuvwxyz012345"
        val hosts = mutableListOf<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            hosts += url.host
            when {
                url.host == "soundcloud.com" -> reply(chain, 200, """<script src="https://a-v2.sndcdn.com/assets/app.js">""")
                url.host == "a-v2.sndcdn.com" -> reply(chain, 200, """client_id:"$fresh"""")
                url.queryParameter("client_id") == fresh -> reply(chain, 200, "{}")
                else -> reply(chain, 401, "")
            }
        }.build()
        var saved = ""
        fun client() = SoundCloudClient(null, http = http, savedClientId = { saved }, onClientId = { saved = it })

        client().get("/me")
        assertEquals(listOf("soundcloud.com", "a-v2.sndcdn.com", "api-v2.soundcloud.com"), hosts)
        assertEquals(fresh, saved)

        hosts.clear()
        client().get("/me")
        assertEquals(listOf("api-v2.soundcloud.com"), hosts)

        saved = "stale"
        hosts.clear()
        client().get("/me")
        assertEquals(listOf("api-v2.soundcloud.com", "soundcloud.com", "a-v2.sndcdn.com", "api-v2.soundcloud.com"), hosts)
        assertEquals(fresh, saved)
    }

    @Test fun soundCloudLikesAreMappedOncePerCacheEntryWhilePendingLikesStayLive() = runBlocking {
        var mapped = 0
        val backend = SoundCloudBackend(Session(SoundCloudClient.WEB, "listener", "", "opaque-reference", ServerType.SOUNDCLOUD, "42"), object : SoundCloudTransport {
            override suspend fun get(path: String) = JsonParser.parseString("""{"collection":[{"track":{"id":5,"title":"Five"}},{"track":{"id":6,"title":"Six"}}]}""")
            override suspend fun send(method: String, path: String, body: JsonObject?, form: Map<String, String>?): JsonElement =
                if (path.startsWith("/users/")) throw SoundCloudCheckException() else JsonParser.parseString("{}")
        }, localize = { mapped++; it })

        assertEquals(listOf("6"), backend.songsPage(1, 1).map { it.id })
        assertEquals(listOf("5"), backend.songsPage(0, 1).map { it.id })
        assertEquals(2, mapped)

        backend.setStarred("5", false, "song")
        assertEquals(listOf("6"), backend.allSongs().map { it.id })
        assertEquals(2, mapped)

        backend.deletePlaylist("1")
        backend.allSongs()
        assertEquals(4, mapped)
    }

    @Test fun oneSlowYouTubeListDoesNotHoldUpAnotherAndTheSameListLoadsOnce() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val asked = mutableListOf<String>()
        val session = Session("https://music.youtube.com", "Listener", "", "opaque-reference", ServerType.YOUTUBE_MUSIC, "@listener")
        val backend = YouTubeMusicBackend(session, YouTubeMusicTransport { _, body ->
            val id = body.string("browseId")
            asked += id
            if (id == "FEmusic_liked_albums") gate.await()
            JsonObject()
        })
        val albums = List(2) { async(start = CoroutineStart.UNDISPATCHED) { backend.allAlbums() } }
        withTimeout(5_000) { backend.allSongs() }
        gate.complete(Unit)
        albums.awaitAll()
        assertEquals(listOf("FEmusic_liked_albums", "FEmusic_liked_videos"), asked)
    }

    @Test fun spotifyRowsDoNotWaitForALateCountAndKnownCountsAreNotAskedAgain() = runBlocking {
        val asked = mutableListOf<String>()
        var hang = true
        val session = Session(SpotifyWebBackend.SERVER, "Listener", SpotifyWebBackend.WEB_PLAYER, "reference", ServerType.SPOTIFY, "listener")
        val backend = SpotifyWebBackend(session, object : SpotifyGraphql {
            override suspend fun query(operation: String, variables: JsonObject): JsonObject {
                if (operation == "libraryV3") return parsed("""{"me":{"libraryV3":{"totalCount":3,"items":[
                    {"item":{"_uri":"spotify:playlist:old","data":{"name":"Old","revisionId":"r1"}}},
                    {"item":{"_uri":"spotify:playlist:slow","data":{"name":"Slow","revisionId":"r1"}}},
                    {"item":{"_uri":"spotify:playlist:bare","data":{"name":"Bare"}}}]}}}""")
                val id = variables.string("uri").substringAfterLast(':')
                asked += id
                if (id == "slow" && hang) awaitCancellation()
                return parsed("""{"playlistV2":{"content":{"totalCount":7}}}""")
            }
            override suspend fun spclient(path: String, body: JsonObject?): JsonObject = error("unused")
        }) { it }

        assertEquals(listOf(7, -1, 7), backend.allPlaylists().map { it.songCount })

        hang = false
        asked.clear()
        assertEquals(listOf(7, 7, 7), backend.allPlaylists().map { it.songCount })
        assertEquals(listOf("slow"), asked)
    }
}
