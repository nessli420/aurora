package com.aurora.music.data

import com.aurora.music.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class MergedAppleTest {
    private val apple = Session("https://music.apple.com", "Listener", "", "reference", ServerType.APPLE_MUSIC, userId = "listener")
    private val spotify = Session(SpotifyWebBackend.SERVER, "Listener", SpotifyWebBackend.WEB_PLAYER, "reference", ServerType.SPOTIFY, userId = "listener")
    private val server = Session("https://server.invalid", "Listener", "", "token", ServerType.SUBSONIC)
    private val local = Session("On this device", "Local Library", "", "local", ServerType.LOCAL)

    private val appleSong = Song("1623924754", "Loving Machine", "TV Girl", "Who Really Cares", "", 227,
        streamUrl = "aurora-yt://am1623924754?q=x&dur=227")
    private val serverSong = appleSong.copy(id = "server", streamUrl = "https://server.invalid/stream", suffix = "flac")

    private fun source(session: Session, songs: List<Song>, searches: MutableList<String> = mutableListOf()): MediaBackend =
        Proxy.newProxyInstance(MediaBackend::class.java.classLoader, arrayOf(MediaBackend::class.java)) { _, method, args ->
            when (method.name) {
                "getSession" -> session
                "home" -> HomeData(sections = listOf(HomeFeedSection("shelf", session.typeLabel, items = songs.map { HomeFeedItem.Track(it) })))
                "search" -> { searches += args!![0] as String; SearchResults(songs = songs) }
                "allAlbums", "allArtists", "allPlaylists" -> emptyList<Any>()
                "playbackSourceIdentity" -> (args!![0] as Song).playbackSource ?: PlaybackSourceIdentity.fromSession(session, (args[0] as Song).albumId)
                "serverLyrics" -> Lyrics(listOf(LyricLine(0, "la")), true, session.typeLabel).takeIf { session.type.isCatalogue }
                else -> error("Unexpected ${method.name}")
            }
        } as MediaBackend

    @Test fun appleMusicJoinsTheMergeWithItsOwnFeedSearchAndView() = runBlocking {
        assertTrue(ServerType.APPLE_MUSIC.supportsMergedLibrary && ServerType.APPLE_MUSIC.isCatalogue)
        val searches = mutableListOf<String>()
        val merged = MergedBackend(listOf(source(local, emptyList()), source(apple, listOf(appleSong), searches)), apple)
        assertEquals(listOf("library", "apple"), merged.homeFeeds.map { it.id })
        assertEquals("Apple Music", merged.searchSources.last().label)
        assertEquals(listOf("library", "apple"), merged.libraryViews.map { it.id })
        assertEquals("Apple Music", merged.home("apple").sections.single().title)
        val found = merged.search("loving machine", "apple").songs.single()
        assertEquals(listOf("loving machine"), searches)
        assertEquals("Loving Machine", found.title)
        assertTrue(found.id != appleSong.id && found.id.endsWith(appleSong.id))
        assertTrue(merged.libraryView("apple") !== merged)
    }

    @Test fun aServerSongBorrowsTheLyricsOfEitherCatalogue() = runBlocking {
        val merged = MergedBackend(listOf(source(server, listOf(serverSong)), source(spotify, listOf(appleSong)), source(apple, listOf(appleSong))), apple)
        val hosted = merged.search("loving machine", "library").songs.single()
        assertEquals("Apple Music", merged.catalogueLyrics(hosted, ServerType.APPLE_MUSIC)!!.source)
        assertEquals("Spotify", merged.catalogueLyrics(hosted)!!.source)
        // apple's own songs already get theirs as their source's lyrics
        assertNull(merged.catalogueLyrics(merged.search("loving machine", "apple").songs.single(), ServerType.APPLE_MUSIC))
    }

    @Test fun appleLyricsFollowTheOrderAndTheirMergeSwitch() = runBlocking {
        val merged = MergedBackend(listOf(source(server, listOf(serverSong)), source(apple, listOf(appleSong))), apple)
        val hosted = merged.search("loving machine", "library").songs.single()
        fun lyrics(allowed: Boolean) = LyricsRepository(backendProvider = { merged }, lrclibEnabledProvider = { false },
            orderProvider = { listOf("server", "apple", "lrclib") }, appleAllowed = { allowed })
        assertEquals("Apple Music", lyrics(true).lyricsFor(hosted)!!.source)
        assertNull(lyrics(false).lyricsFor(hosted))
        assertTrue("apple" in DEFAULT_LYRICS_SOURCES)
    }
}
