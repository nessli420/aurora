package com.aurora.music.data

import com.aurora.music.data.remote.JellyfinClient
import com.aurora.music.data.remote.PlexClient
import com.aurora.music.data.remote.SubsonicClient
import com.aurora.music.data.remote.testClientInfo
import com.aurora.music.model.Album
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class ServerLibraryRequestsTest {
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun MockWebServer.answer(respond: (RecordedRequest) -> MockResponse): List<RecordedRequest> {
        val seen = CopyOnWriteArrayList<RecordedRequest>()
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                return respond(request)
            }
        }
        return seen
    }

    private fun session(server: MockWebServer, type: ServerType) =
        Session(server.url("/").toString().trimEnd('/'), "listener", "salt", "token", type, "user-id")

    private fun subsonic(server: MockWebServer) = SubsonicBackend(SubsonicClient(session(server, ServerType.SUBSONIC)), { 0 }, { it })
    private fun jellyfin(server: MockWebServer) = JellyfinBackend(JellyfinClient(session(server, ServerType.JELLYFIN), testClientInfo), { 0 }, { it })

    private fun subsonicAlbums(from: Int, count: Int) =
        (from until from + count).joinToString(",") { """{"id":"a$it","name":"Album $it"}""" }

    private fun ok(extra: String) = json("""{"subsonic-response":{"status":"ok",$extra}}""")

    @Test fun subsonicAlbumsPagePastFiveHundred() = runBlocking {
        MockWebServer().use { server ->
            val seen = server.answer { request ->
                val offset = request.requestUrl!!.queryParameter("offset")!!.toInt()
                ok(""""albumList2":{"album":[${subsonicAlbums(offset, if (offset == 0) 500 else 3)}]}""")
            }
            val albums = subsonic(server).allAlbums()
            assertEquals(503, albums.map { it.id }.toSet().size)
            assertEquals(listOf("0", "500"), seen.map { it.requestUrl!!.queryParameter("offset") })
        }
    }

    @Test fun jellyfinAlbumsPagePastFiveHundredAndSkipTheTotal() = runBlocking {
        MockWebServer().use { server ->
            val seen = server.answer { request ->
                val start = request.requestUrl!!.queryParameter("StartIndex")!!.toInt()
                val items = (start until start + if (start == 0) 500 else 2).joinToString(",") { """{"Id":"a$it","Name":"Album $it"}""" }
                json("""{"Items":[$items]}""")
            }
            assertEquals(502, jellyfin(server).allAlbums().size)
            assertEquals(2, seen.size)
            assertTrue(seen.all { it.requestUrl!!.queryParameter("EnableTotalRecordCount") == "false" })
        }
    }

    @Test fun jellyfinCountsLikesWithoutFetchingThem() = runBlocking {
        MockWebServer().use { server ->
            val seen = server.answer { json("""{"Items":[],"TotalRecordCount":7}""") }
            assertEquals(7, jellyfin(server).starredCount())
            assertEquals("0", seen.single().requestUrl!!.queryParameter("Limit"))
            assertNull(seen.single().requestUrl!!.queryParameter("EnableTotalRecordCount"))
        }
    }

    private fun album(id: String) = Album(id, "Album $id", "Artist", "", 2020, 1)

    @Test fun aServerThatIgnoresOffsetsEndsTheWalk() = runBlocking {
        var calls = 0
        val albums = pagedAlbums { _, size -> calls++; List(size) { album("a$it") } }
        assertEquals(500, albums.size)
        assertEquals(2, calls)
    }

    @Test fun aSlowLaterPageKeepsWhatAlreadyArrived() = runBlocking {
        val albums = withTimeout(5_000) {
            pagedAlbums(budgetMs = 50) { offset, size ->
                if (offset > 0) delay(60_000)
                List(size) { album("a$it") }
            }
        }
        assertEquals(500, albums.size)
    }

    @Test fun subsonicHomeSurvivesOneFailureAndCapsLikes() = runBlocking {
        MockWebServer().use { server ->
            server.answer { request ->
                when (request.requestUrl!!.encodedPath) {
                    "/rest/getAlbumList2.view" -> ok(""""albumList2":{"album":[${subsonicAlbums(0, 2)}]}""")
                    "/rest/getStarred2.view" -> ok(""""starred2":{"song":[${(0 until 60).joinToString(",") { """{"id":"s$it","title":"Song $it"}""" }}]}""")
                    "/rest/getArtists.view" -> MockResponse().setResponseCode(500)
                    else -> ok(""""playlists":{"playlist":[]}""")
                }
            }
            val home = subsonic(server).home()
            assertEquals(2, home.newReleases.size)
            assertEquals(50, home.starred.size)
            assertTrue(home.artists.isEmpty())
        }
    }

    @Test fun subsonicLaunchReadsShareOneStarredCall() = runBlocking {
        MockWebServer().use { server ->
            val fail = AtomicBoolean(true)
            val seen = server.answer { request ->
                when (request.requestUrl!!.encodedPath) {
                    "/rest/getStarred2.view" ->
                        if (fail.getAndSet(false)) MockResponse().setResponseCode(500)
                        else ok(""""starred2":{"song":[${(0 until 60).joinToString(",") { """{"id":"s$it","title":"Song $it"}""" }}]}""")
                    "/rest/unstar.view" -> ok(""""x":1""")
                    else -> ok(""""playlists":{"playlist":[]}""")
                }
            }
            val backend = subsonic(server)
            fun starredCalls() = seen.count { it.requestUrl!!.encodedPath == "/rest/getStarred2.view" }
            assertTrue(backend.starredSongs().isEmpty())
            val home = async { backend.home() }
            val songs = async { backend.starredSongs() }
            val ids = async { backend.starredIds() }
            assertEquals(60, backend.starredCount())
            assertEquals(50, home.await().starred.size)
            assertEquals(60, songs.await().size)
            assertEquals(60, ids.await().size)
            assertEquals(2, starredCalls())
            assertTrue(backend.setStarred("s1", false, "song"))
            assertEquals(60, backend.starredSongs().size)
            assertEquals(3, starredCalls())
        }
    }

    @Test fun plexAsksForSectionsOnceAndKeepsNoFailure() = runBlocking {
        MockWebServer().use { server ->
            val failing = AtomicBoolean(true)
            server.answer {
                if (failing.get()) MockResponse().setResponseCode(503)
                else json("""{"MediaContainer":{"Directory":[{"key":"2","type":"artist","title":"Music"}]}}""")
            }
            val client = PlexClient(session(server, ServerType.PLEX), testClientInfo, OkHttpClient())
            assertNotNull(runCatching { client.musicSections() }.exceptionOrNull())
            failing.set(false)
            assertEquals(1, client.musicSections().size)
            assertEquals(1, client.musicSections().size)
            assertEquals(2, server.requestCount)
        }
    }
}
