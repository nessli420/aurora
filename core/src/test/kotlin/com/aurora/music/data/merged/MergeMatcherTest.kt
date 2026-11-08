package com.aurora.music.data.merged

import com.aurora.music.data.CatalogueMatchPrefs
import com.aurora.music.data.HomeData
import com.aurora.music.data.MediaBackend
import com.aurora.music.data.ServerType
import com.aurora.music.data.Session
import com.aurora.music.data.mergeNamespace
import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

class MergeMatcherTest {
    private val server = Session("https://server.invalid", "Listener", "", "token", ServerType.SUBSONIC)
    private val other = Session("https://other.invalid", "Listener", "", "token", ServerType.JELLYFIN)
    private val yt = Session("https://music.youtube.com", "Listener", "", "token", ServerType.YOUTUBE_MUSIC, userId = "account")

    private fun source(session: Session): MediaBackend =
        Proxy.newProxyInstance(MediaBackend::class.java.classLoader, arrayOf(MediaBackend::class.java)) { _, method, _ ->
            when (method.name) {
                "getSession" -> session
                else -> error("Unexpected ${method.name}")
            }
        } as MediaBackend

    private val sessions = listOf(server, other, yt)
    private val matcher = MergeMatcher(SourceRouter(sessions.map(::source), server, nested = false, downUntil = ConcurrentHashMap()),
        { emptyList() }, { emptyList() }, { CatalogueMatchPrefs() })

    @Test fun dedupKeepsTheBestCopyPerRecordingInFirstSeenOrder() {
        val mp3 = Song("a-mp3", "Song", "A", "", "", 200, suffix = "mp3", bitrateKbps = 320)
        val b = Song("b", "Other", "B", "", "", 100)
        val flac = Song("a-flac", "Song", "A", "", "", 201, suffix = "flac", bitDepth = 24, sampleRateHz = 96_000)
        // beyond the duration tolerance, so another recording
        val live = Song("a-live", "Song", "A", "", "", 260)
        assertEquals(listOf("a-flac", "a-live", "b"), matcher.dedupSongs(listOf(mp3, b, flac, live)).map { it.id })
    }

    @Test fun dedupAlbumsKeepsTheFirstAndArtistsDropBlankNames() {
        val albums = listOf(Album("a1", "Title", "Artist", "", 2020, 10), Album("a2", "title", "ARTIST", "", 2021, 12))
        assertEquals(listOf("a1"), matcher.dedupAlbums(albums).map { it.id })
        val artists = listOf(Artist("blank", "", "", 0), Artist("b1", "Beyoncé", "", 0), Artist("b2", "beyonce", "", 0))
        assertEquals(listOf("b1"), matcher.dedupArtists(artists).map { it.id })
    }

    @Test fun mergeHomesWrapsPerSourceSkipsMissingHomesAndNeverDedupsPlaylists() {
        fun home(tag: String) = HomeData(newReleases = listOf(Album("al-$tag", "Same", "Band", "", 2020, 10)),
            playlists = listOf(Playlist("p-$tag", "Mix", "", "", 1)))
        val merged = matcher.mergeHomes(listOf(home("0"), null, home("2")))
        assertEquals(listOf("${mergeNamespace(server)}\u0001al-0"), merged.newReleases.map { it.id })
        assertEquals(listOf("${mergeNamespace(server)}\u0001p-0", "${mergeNamespace(yt)}\u0001p-2"), merged.playlists.map { it.id })
    }
}
