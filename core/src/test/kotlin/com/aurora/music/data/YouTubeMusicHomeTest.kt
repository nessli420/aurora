package com.aurora.music.data

import com.aurora.music.data.remote.*
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class YouTubeMusicHomeTest {
    private fun parsed(value: String) = JsonParser.parseString(value).asJsonObject
    private val mixed = """{"musicCarouselShelfRenderer":{"shelfId":"1","header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Fresh finds"}]}}},"contents":[
        {"musicTwoRowItemRenderer":{"title":{"simpleText":"Your mix"},"navigationEndpoint":{"browseEndpoint":{"browseId":"VLRDmix"}},"thumbnailOverlay":{"musicPlayButtonRenderer":{"playNavigationEndpoint":{"watchEndpoint":{"videoId":"abcdefghijk"}}}}}},
        {"musicTwoRowItemRenderer":{"title":{"simpleText":"A song"},"subtitle":{"simpleText":"Song • Artist Name"},"navigationEndpoint":{"watchEndpoint":{"videoId":"abcdefghijk","playlistId":"RDAMVMabcdefghijk"}}}},
        {"musicTwoRowItemRenderer":{"title":{"simpleText":"An album"},"navigationEndpoint":{"browseEndpoint":{"browseId":"MPREalbum"}}}},
        {"musicTwoRowItemRenderer":{"title":{"simpleText":"Artist"},"navigationEndpoint":{"browseEndpoint":{"browseId":"UCartist"}}}}
        ],"continuations":[{"nextContinuationData":{"continuation":"carousel-only"}}]}}"""

    @Test fun homePreservesServiceTitlesAndMixedCardOrder() {
        val page = YouTubeMusicParser.home(parsed("""{"sectionListRenderer":{"contents":[$mixed],"continuations":[{"nextContinuationData":{"continuation":"home-next"}}]}}"""))
        assertEquals("Fresh finds", page.sections.single().title)
        assertEquals(listOf("playlist:RDmix", "song:abcdefghijk", "album:MPREalbum", "artist:UCartist"), page.sections.single().items.map { it.key })
        assertEquals("home-next", page.continuation)
        assertEquals("Artist Name", (page.sections.single().items[1] as HomeFeedItem.Track).song.artist)
        assertTrue(page.random.isEmpty())
        assertTrue(page.playlists.isEmpty())
    }

    @Test fun carouselContinuationIsNotMistakenForAnotherHomePage() {
        val page = YouTubeMusicParser.home(parsed("""{"sectionListRenderer":{"contents":[$mixed]}}"""))
        assertNull(page.continuation)
    }

    @Test fun homeAndContinuationDoNotDependOnUnrelatedLibraryRequests() = runBlocking {
        val requests = mutableListOf<String>()
        val api = YouTubeMusicTransport { endpoint, body ->
            assertEquals("browse", endpoint)
            requests += if (body.has("continuation")) body.get("continuation").asString else body.get("browseId").asString
            parsed(if (body.has("continuation")) """{"continuationContents":{"sectionListContinuation":{"contents":[$mixed]}}}"""
                else """{"sectionListRenderer":{"contents":[$mixed],"continuations":[{"nextContinuationData":{"continuation":"more"}}]}}""")
        }
        val backend = YouTubeMusicBackend(Session("fixture", "Listener", "", "", ServerType.YOUTUBE_MUSIC), api)
        assertEquals("more", backend.home().continuation)
        assertEquals(1, backend.homePage("more").sections.size)
        assertEquals(listOf("FEmusic_home", "more"), requests)
    }

    @Test fun transientRequestFailureDoesNotClaimAccountExpired() = runBlocking {
        val errors = mutableListOf<String>()
        val backend = ReportingMediaBackend(YouTubeMusicBackend(
            Session("fixture", "Listener", "", "", ServerType.YOUTUBE_MUSIC),
            YouTubeMusicTransport { _, _ -> throw IOException("Network") }), errors::add)
        assertTrue(backend.allAlbums().isEmpty())
        assertFalse(errors.single().contains("reconnect", ignoreCase = true))
        try { backend.home(); fail("Home failure was disguised as an empty feed") } catch (_: IOException) { }
    }
}
