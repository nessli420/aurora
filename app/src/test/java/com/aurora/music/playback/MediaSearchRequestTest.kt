package com.aurora.music.playback

import com.aurora.music.data.SearchResults
import com.aurora.music.model.Album
import com.aurora.music.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MediaSearchRequestTest {
    @Test fun missingOrMalformedQueryDoesNotResumePlayback() {
        assertNull(MediaSearchRequest.parse(null))
        assertNull(MediaSearchRequest.parse("", "vnd.android.cursor.item/audio"))
        assertNull(MediaSearchRequest.parse("", "unsupported/focus"))
        assertNull(MediaSearchRequest.parse("x".repeat(1_001)))
    }

    @Test fun explicitEmptyAnyQueryResumesCurrentQueue() {
        assertEquals(MediaSearchRequest.Kind.RESUME, MediaSearchRequest.parse("  ", "vnd.android.cursor.item/*")?.kind)
        assertEquals(MediaSearchRequest.Kind.RESUME, MediaSearchRequest.parse("")?.kind)
    }

    @Test fun structuredTitleAndArtistOverrideUnstructuredQuery() {
        assertEquals(MediaSearchRequest(MediaSearchRequest.Kind.SONG, "One", "Artist", "Album"),
            MediaSearchRequest.parse("play one by artist", "vnd.android.cursor.item/audio", " One ", " Artist ", " Album "))
    }

    @Test fun missingStructuredFieldsUseTheSuppliedQuery() {
        assertEquals(MediaSearchRequest(MediaSearchRequest.Kind.TEXT, "Album"),
            MediaSearchRequest.parse("Album", "vnd.android.cursor.item/album"))
    }

    @Test fun specificSongDoesNotPlayAnotherArtistsMatch() = runBlocking {
        val request = MediaSearchRequest(MediaSearchRequest.Kind.SONG, "One", "Wanted")
        assertTrue(resolve(request, SearchResults(songs = listOf(song("wrong", "One", "Other")))).isEmpty())
        assertEquals(listOf("right"), resolve(request, SearchResults(songs = listOf(
            song("wrong", "One", "Other"), song("right", "ONE", "wanted"), song("later", "One", "Wanted"),
        ))).map { it.id })
    }

    @Test fun albumRequestResolvesTracksInAlbumOrder() = runBlocking {
        val expected = listOf(song("second", "Second"), song("first", "First"))
        val request = MediaSearchRequest(MediaSearchRequest.Kind.ALBUM, "Album", "Artist")
        val actual = request.resolve(
            search = { SearchResults(albums = listOf(Album("album-id", "Album", "Artist", "", 2020, 2))) },
            collectionTracks = { kind, id ->
                assertEquals("album", kind)
                assertEquals("album-id", id)
                expected
            }, playlists = { emptyList() },
        )
        assertEquals(expected, actual)
    }

    @Test fun noMatchAndUnplayableItemsLeaveNoReplacement() = runBlocking {
        val request = MediaSearchRequest(MediaSearchRequest.Kind.TEXT, "unknown")
        assertTrue(resolve(request, SearchResults()).isEmpty())
        assertTrue(resolve(request, SearchResults(songs = listOf(song("bad", "bad").copy(streamUrl = "")))).isEmpty())
    }

    @Test fun anyMusicDoesNotRunSearchOrFetchAnotherQueue() = runBlocking {
        val request = MediaSearchRequest(MediaSearchRequest.Kind.RESUME, "")
        assertTrue(request.resolve({ error("unexpected search") }, { _, _ -> error("unexpected collection") },
            { error("unexpected playlists") }).isEmpty())
    }

    private suspend fun resolve(request: MediaSearchRequest, results: SearchResults) =
        request.resolve({ results }, { _, _ -> emptyList() }, { emptyList() })

    private fun song(id: String, title: String, artist: String = "Artist") =
        Song(id, title, artist, "Album", "", 20, streamUrl = "file:///test.wav")
}
