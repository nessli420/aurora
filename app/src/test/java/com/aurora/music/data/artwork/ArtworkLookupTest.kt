package com.aurora.music.data.artwork

import com.aurora.music.data.ArtistSeparators
import com.aurora.music.model.Album
import com.aurora.music.model.Song
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ArtworkLookupTest {
    private val song = Song("track", "Track", "Artist", "Album", "", 180)
    private val id = "12345678-1234-1234-1234-123456789abc"
    private fun group(title: String, artist: String, mbid: String = id) = """{"id":"$mbid","title":"$title","artist-credit":[{"name":"$artist"}]}"""
    private fun client(calls: MutableList<HttpUrl>, response: (HttpUrl) -> String) = CoverArtClient(
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            calls += request.url
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body(response(request.url).toResponseBody()).build()
        }.build(), beforeRequest = {},
    )

    @Test fun existingCoversStayUnchangedAndMissingAlbumCoversShareOneRequest() {
        for (url in listOf("https://server/cover?id=one", "file:///covers/one.jpg", "content://other.provider/cover"))
            assertEquals(url, ArtworkUrls.song(song.copy(artworkUrl = url)).artworkUrl)
        val first = ArtworkUrls.song(song).artworkUrl
        assertTrue(ArtworkUrls.isArtwork(first))
        assertEquals(first, ArtworkUrls.song(song.copy(title = "Second", durationSec = 230)).artworkUrl)
        assertEquals(first, ArtworkUrls.album(Album("album", "Album", "Artist", "", 2020, 2)).artworkUrl)
        assertEquals("Album", ArtworkUrls.decode(first)!!.album)
    }

    @Test fun localArtworkIsTriedFirstAndUnknownMetadataDoesNotInventAnAlbum() {
        val original = "content://media/external/audio/albumart/23"
        assertEquals(original, ArtworkUrls.decode(ArtworkUrls.song(song.copy(artworkUrl = original)).artworkUrl)!!.original)
        assertEquals("", ArtworkUrls.song(song.copy(artist = "Unknown artist")).artworkUrl)
        val request = ArtworkUrls.decode(ArtworkUrls.song(song.copy(album = "Unknown album")).artworkUrl)!!
        assertEquals("", request.album)
        assertEquals("Track", request.title)
        assertEquals(180, request.duration)
        assertNull(ArtworkUrls.decode("content://com.aurora.music.artwork/v1/garbage"))
    }

    @Test fun matchingRejectsWrongArtistsAndVersionsRatherThanTakingFirstSearchResult() = runBlocking {
        val calls = mutableListOf<HttpUrl>()
        val client = client(calls) { """{"release-groups":[${group("Album", "Unrelated")},${group("Album (Live)", "Artist")},${group("ALBUM", "aRtIsT") }]}""" }
        assertEquals(listOf("https://coverartarchive.org/release-group/$id/front-500"), client.candidates(ArtworkRequest("Artist", "Album"), ArtistSeparators()))
        assertEquals(1, calls.size)
        assertEquals("release-group:\"Album\" AND artist:\"Artist\"", calls.single().queryParameter("query"))
    }

    @Test fun collaborationRetriesPrimaryArtistAndDoesNotMixDifferentAlbums() = runBlocking {
        val calls = mutableListOf<HttpUrl>()
        val client = client(calls) { url ->
            if (url.queryParameter("query")!!.contains("artist:\"First\"")) """{"release-groups":[${group("Album", "First")}]}"""
            else """{"release-groups":[]}"""
        }
        assertEquals(1, client.candidates(ArtworkRequest("First, Second", "Album"), ArtistSeparators()).size)
        assertEquals(2, calls.size)
        assertTrue(calls[0].queryParameter("query")!!.contains("First, Second"))
        assertTrue(calls[1].queryParameter("query")!!.contains("artist:\"First\""))
    }

    @Test fun unknownAlbumUsesMatchingRecordingAndDuration() = runBlocking {
        val calls = mutableListOf<HttpUrl>()
        val client = client(calls) { """{"recordings":[{"title":"Track","length":400000,"artist-credit":[{"name":"Artist"}],"releases":[{"id":"00000000-1234-1234-1234-123456789abc"}]},{"title":"Track","length":180000,"artist-credit":[{"name":"Artist"}],"releases":[{"id":"$id"}]}]}""" }
        assertEquals(listOf("https://coverartarchive.org/release/$id/front-500"), client.candidates(ArtworkRequest("Artist", "", "Track", duration = 180), ArtistSeparators()))
        assertEquals("/ws/2/recording", calls.single().encodedPath)
    }

    @Test fun unicodeMetadataRemainsDistinct() = runBlocking {
        val calls = mutableListOf<HttpUrl>()
        val client = client(calls) { """{"release-groups":[${group("Ночь", "Другой")},${group("День", "Исполнитель")},${group("НОЧЬ", "ИСПОЛНИТЕЛЬ")}]}""" }
        assertEquals(1, client.candidates(ArtworkRequest("Исполнитель", "Ночь"), ArtistSeparators()).size)
        assertNotEquals(artworkKey("Ночь"), artworkKey("День"))
    }
}
