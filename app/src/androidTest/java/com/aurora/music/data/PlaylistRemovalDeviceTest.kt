package com.aurora.music.data

import com.aurora.music.data.remote.JellyfinClient
import com.aurora.music.data.remote.SubsonicClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class PlaylistRemovalDeviceTest {
    @Test fun subsonicRemovesMatchingIndicesWithoutRewritingOtherEntries() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"subsonic-response":{"status":"ok","playlist":{"id":"p","entry":[{"id":"target"},{"id":"keep"},{"id":"target"}]}}}"""))
            server.enqueue(MockResponse().setBody("""{"subsonic-response":{"status":"ok"}}"""))
            val session = Session(server.url("/").toString(), "listener", "salt", "token", ServerType.SUBSONIC)
            val backend = SubsonicBackend(SubsonicClient(session), { 0 }, { it })
            assertTrue(backend.removeFromPlaylist("p", listOf("target")))
            assertTrue(server.takeRequest(2, TimeUnit.SECONDS)!!.path!!.contains("getPlaylist"))
            val mutation = server.takeRequest(2, TimeUnit.SECONDS)!!.requestUrl!!
            assertEquals(listOf("0", "2"), mutation.queryParameterValues("songIndexToRemove"))
            assertNull(mutation.queryParameter("songIdToAdd"))
        }
    }

    @Test fun jellyfinUsesOccurrenceIdsAcrossPages() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"Items":[{"Id":"target","PlaylistItemId":"entry-a"},{"Id":"keep","PlaylistItemId":"entry-b"}],"TotalRecordCount":3}"""))
            server.enqueue(MockResponse().setBody("""{"Items":[{"Id":"target","PlaylistItemId":"entry-c"}],"TotalRecordCount":3}"""))
            server.enqueue(MockResponse().setResponseCode(204))
            val session = Session(server.url("/").toString(), "listener", "", "token", ServerType.JELLYFIN, "user")
            val backend = JellyfinBackend(JellyfinClient(session), { 0 }, { it })
            assertTrue(backend.removeFromPlaylist("p", listOf("target")))
            assertEquals("0", server.takeRequest(2, TimeUnit.SECONDS)!!.requestUrl!!.queryParameter("startIndex"))
            assertEquals("2", server.takeRequest(2, TimeUnit.SECONDS)!!.requestUrl!!.queryParameter("startIndex"))
            val mutation = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("DELETE", mutation.method)
            assertEquals("entry-a,entry-c", mutation.requestUrl!!.queryParameter("entryIds"))
        }
    }

    @Test fun missingJellyfinOccurrenceIdCannotReportSuccess() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"Items":[{"Id":"target"}],"TotalRecordCount":1}"""))
            val session = Session(server.url("/").toString(), "listener", "", "token", ServerType.JELLYFIN, "user")
            val backend = JellyfinBackend(JellyfinClient(session), { 0 }, { it })
            assertFalse(backend.removeFromPlaylist("p", listOf("target")))
            assertEquals(1, server.requestCount)
        }
    }
}
