package com.aurora.music.data

import com.aurora.music.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Proxy

class LyricsSourceOrderTest {
    private val song = Song("s1", "Title", "Artist", "Album", "", 200)
    private fun lyrics(source: String, synced: Boolean) = Lyrics(listOf(LyricLine(0, "la")), synced, source)

    private fun backend(type: ServerType, own: Lyrics?, spotify: Lyrics?): MediaBackend =
        Proxy.newProxyInstance(MediaBackend::class.java.classLoader, arrayOf(MediaBackend::class.java)) { _, method, args ->
            when (method.name) {
                "sourceType" -> type
                "serverLyrics" -> own
                "catalogueLyrics" -> spotify.takeIf { args!![1] == ServerType.SPOTIFY }
                else -> error("Unexpected ${method.name}")
            }
        } as MediaBackend

    private fun repo(backend: MediaBackend, order: List<String>, spotifyAllowed: Boolean = true) =
        LyricsRepository({ backend }, lrclibEnabledProvider = { false }, orderProvider = { order }, spotifyAllowed = { spotifyAllowed })

    @Test fun syncedLyricsWinOverAnEarlierPlainSource() = runBlocking {
        val b = backend(ServerType.SUBSONIC, lyrics("server", false), lyrics("spotify", true))
        assertEquals("spotify", repo(b, listOf("server", "spotify", "lrclib")).lyricsFor(song)!!.source)
    }

    @Test fun theFirstSourceWinsWhenBothAreSyncedAndPlainFallsBackInOrder() = runBlocking {
        val synced = backend(ServerType.SUBSONIC, lyrics("server", true), lyrics("spotify", true))
        assertEquals("spotify", repo(synced, listOf("spotify", "server", "lrclib")).lyricsFor(song)!!.source)
        val plain = backend(ServerType.SUBSONIC, lyrics("server", false), lyrics("spotify", false))
        assertEquals("spotify", repo(plain, listOf("spotify", "server", "lrclib")).lyricsFor(song)!!.source)
    }

    @Test fun switchingSpotifyOffSkipsItEvenForSpotifysOwnSongs() = runBlocking {
        val hosted = backend(ServerType.SUBSONIC, null, lyrics("spotify", true))
        assertNull(repo(hosted, DEFAULT_LYRICS_SOURCES, spotifyAllowed = false).lyricsFor(song))
        val own = backend(ServerType.SPOTIFY, lyrics("server", true), null)
        assertNull(repo(own, DEFAULT_LYRICS_SOURCES, spotifyAllowed = false).lyricsFor(song))
        assertEquals("server", repo(own, DEFAULT_LYRICS_SOURCES).lyricsFor(song)!!.source)
    }
}
