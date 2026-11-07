package com.aurora.music.data

import com.aurora.music.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class MergedSpotifyTest {
    private val spotify = Session(SpotifyWebBackend.SERVER, "Listener", SpotifyWebBackend.WEB_PLAYER, "reference", ServerType.SPOTIFY, userId = "listener")
    private val yt = Session("https://music.youtube.com", "Listener", "", "token", ServerType.YOUTUBE_MUSIC, userId = "account")
    private val server = Session("https://server.invalid", "Listener", "", "token", ServerType.SUBSONIC)
    private val local = Session("On this device", "Local Library", "", "local", ServerType.LOCAL)

    private val spotifySong = Song("4uLU6hMCjMI75M1A2tKUQC", "Never Gonna", "Rick", "Whenever", "", 213,
        streamUrl = "aurora-yt://4uLU6hMCjMI75M1A2tKUQC?q=x&dur=213", albumId = "spotify-album")
    private val ytSong = Song("youtube-id", "Still love you", "GLM-RST", "Still", "", 146, streamUrl = "aurora-yt://video/youtube-id")
    private val serverSong = spotifySong.copy(id = "server", streamUrl = "https://server.invalid/stream", suffix = "flac", bitDepth = 24, sampleRateHz = 96000)
    private val localSong = Song("file", "Local only", "Someone", "Disk", "", 180, streamUrl = "file:///local.flac")

    private class Calls { var homes = 0; val searches = mutableListOf<String>(); val canvases = mutableListOf<String>() }

    private fun source(session: Session, songs: List<Song>, calls: Calls = Calls()): MediaBackend =
        Proxy.newProxyInstance(MediaBackend::class.java.classLoader, arrayOf(MediaBackend::class.java)) { _, method, args ->
            when (method.name) {
                "getSession" -> session
                "home" -> { calls.homes++; HomeData(sections = listOf(HomeFeedSection("shelf", "For you", items = songs.map { HomeFeedItem.Track(it) }))) }
                "homePage" -> HomeData()
                "search" -> { calls.searches += args!![0] as String; SearchResults(songs = songs) }
                "matchingSongs" -> songs
                "songFor" -> songs.firstOrNull { it.id == args!![0] }
                "allSongs", "librarySongs", "starredSongs", "collectionTracks" -> songs
                "songsPage" -> songs
                "starredCount" -> songs.size
                "allAlbums", "allArtists", "allPlaylists" -> emptyList<Any>()
                "playbackSourceIdentity" -> (args!![0] as Song).playbackSource ?: PlaybackSourceIdentity.fromSession(session, (args[0] as Song).albumId)
                "supportsCanvas" -> session.type == ServerType.SPOTIFY
                "canvasUrl" -> { calls.canvases += (args!![0] as Song).id; "https://canvaz.scdn.co/loop.mp4".takeIf { session.type == ServerType.SPOTIFY } }
                "serverLyrics" -> Lyrics(listOf(LyricLine(0, "la")), true, "spotify").takeIf { session.type == ServerType.SPOTIFY }
                "streamUrl" -> songs.firstOrNull { it.id == args!![0] }?.streamUrl.orEmpty()
                else -> error("Unexpected ${method.name}")
            }
        } as MediaBackend

    @Test fun spotifyJoinsTheMergeAsACatalogue() {
        assertTrue(ServerType.SPOTIFY.supportsMergedLibrary)
        assertTrue(ServerType.SPOTIFY.isCatalogue && ServerType.YOUTUBE_MUSIC.isCatalogue)
        assertFalse(ServerType.SUBSONIC.isCatalogue)
        // the stable account id keys a spotify account, not its display name
        assertEquals(spotify.accountKey(), spotify.copy(username = "Renamed").accountKey())
    }

    @Test fun everyCatalogueGetsItsOwnFeedSourceAndView() {
        val all = MergedBackend(listOf(source(local, emptyList()), source(yt, emptyList()), source(spotify, emptyList())), spotify)
        assertEquals(listOf("library", "discovery", "spotify"), all.homeFeeds.map { it.id })
        assertEquals(listOf("library", "discovery", "spotify"), all.searchSources.map { it.id })
        assertEquals(listOf("library", "youtube", "spotify"), all.libraryViews.map { it.id })

        val spotifyOnly = MergedBackend(listOf(source(local, emptyList()), source(spotify, emptyList())), spotify)
        assertEquals(listOf("Library", "Spotify"), spotifyOnly.homeFeeds.map { it.label })
        assertEquals(listOf("library", "spotify"), spotifyOnly.libraryViews.map { it.id })

        val serversOnly = MergedBackend(listOf(source(local, emptyList()), source(server, emptyList())), server)
        assertTrue(serversOnly.homeFeeds.isEmpty() && serversOnly.libraryViews.isEmpty() && serversOnly.searchSources.isEmpty())
    }

    @Test fun spotifyHomeAndSearchStayOnSpotifyAndRouteBack() = runBlocking {
        val localCalls = Calls(); val ytCalls = Calls(); val spotifyCalls = Calls()
        val merged = MergedBackend(listOf(source(local, listOf(localSong), localCalls), source(yt, listOf(ytSong), ytCalls),
            source(spotify, listOf(spotifySong), spotifyCalls)), spotify)

        val home = merged.home("spotify")
        assertEquals(0, localCalls.homes); assertEquals(0, ytCalls.homes); assertEquals(1, spotifyCalls.homes)
        val track = (home.sections.single().items.single() as HomeFeedItem.Track).song
        assertEquals(spotifySong.streamUrl, merged.songFor(track.id)!!.streamUrl)

        merged.home("library")
        assertEquals(1, localCalls.homes); assertEquals(0, ytCalls.homes); assertEquals(1, spotifyCalls.homes)

        assertEquals(spotifySong.id, stripMergeNamespace(merged.search("never", "spotify").songs.single().id))
        assertEquals(listOf("never"), spotifyCalls.searches); assertTrue(ytCalls.searches.isEmpty())

        merged.search("local", "library")
        assertEquals(listOf("local"), localCalls.searches); assertEquals(1, spotifyCalls.searches.size); assertTrue(ytCalls.searches.isEmpty())
    }

    @Test fun libraryViewsSplitTheSourcesButKeepRoutableIds() = runBlocking {
        val merged = MergedBackend(listOf(source(local, listOf(localSong)), source(server, listOf(serverSong.copy(title = "Hosted"))),
            source(yt, listOf(ytSong)), source(spotify, listOf(spotifySong))), spotify)

        val library = merged.libraryView("library").allSongs().map { stripMergeNamespace(it.id) }.toSet()
        assertEquals(setOf(localSong.id, serverSong.id), library)
        assertEquals(listOf(ytSong.id), merged.libraryView("youtube").allSongs().map { stripMergeNamespace(it.id) })

        val fromView = merged.libraryView("spotify").allSongs().single()
        assertEquals(spotifySong.id, stripMergeNamespace(fromView.id))
        assertEquals(spotifySong.streamUrl, merged.songFor(fromView.id)!!.streamUrl)
        assertEquals(1, merged.libraryView("spotify").starredCount())
        assertTrue(merged.libraryView("spotify").libraryViews.isEmpty())
    }

    @Test fun likedSongsFollowTheViewAndAllCombinesEverySource() = runBlocking {
        val merged = MergedBackend(listOf(source(local, listOf(localSong)), source(yt, listOf(ytSong)), source(spotify, listOf(spotifySong))), spotify)
        fun ids(data: DetailData?) = data!!.tracks.map { stripMergeNamespace(it.id) }.toSet()

        assertEquals(setOf(spotifySong.id), ids(merged.detail("liked", "spotify")))
        assertEquals(setOf(localSong.id), ids(merged.detail("liked", "library")))
        val everything = setOf(localSong.id, ytSong.id, spotifySong.id)
        assertEquals(everything, ids(merged.detail("liked", ALL_LIKED_ID)))
        // the old id keeps meaning every source so other entry points don't change
        assertEquals(everything, ids(merged.detail("liked", "liked")))
        assertEquals(setOf(ytSong.id), merged.collectionTracks("liked", "youtube").map { stripMergeNamespace(it.id) }.toSet())
    }

    @Test fun anUnreachableServerIsAskedOnceThenSkipped() = runBlocking {
        var attempts = 0
        val offline = Proxy.newProxyInstance(MediaBackend::class.java.classLoader, arrayOf(MediaBackend::class.java)) { _, method, _ ->
            when (method.name) {
                "getSession" -> server
                "allSongs" -> { attempts++; throw java.io.IOException("no route") }
                else -> error("Unexpected ${method.name}")
            }
        } as MediaBackend
        val merged = MergedBackend(listOf(source(local, listOf(localSong)), offline), server)
        assertEquals(listOf(localSong.id), merged.allSongs().map { stripMergeNamespace(it.id) })
        assertEquals(listOf(localSong.id), merged.allSongs().map { stripMergeNamespace(it.id) })
        assertEquals(1, attempts)
    }

    @Test fun spotifySongsPlayFromAServerCopyAndCanvasStaysOnSpotify() = runBlocking {
        val spotifyCalls = Calls()
        val merged = MergedBackend(listOf(source(server, listOf(serverSong)), source(spotify, listOf(spotifySong), spotifyCalls)), spotify)
        val track = merged.search("never", "spotify").songs.single()
        assertEquals(serverSong.streamUrl, merged.playbackCandidates(track).first().streamUrl)

        assertTrue(merged.supportsCanvas(track))
        assertEquals("https://canvaz.scdn.co/loop.mp4", merged.canvasUrl(track))
        assertEquals(listOf(spotifySong.id), spotifyCalls.canvases)
        // a server copy borrows the canvas of its spotify twin, found by title, artist and length
        val hosted = merged.search("never", "library").songs.single()
        assertTrue(merged.supportsCanvas(hosted))
        assertEquals("https://canvaz.scdn.co/loop.mp4", merged.canvasUrl(hosted))
        assertEquals(listOf(spotifySong.id, spotifySong.id), spotifyCalls.canvases)
        assertEquals("Never Gonna Rick", spotifyCalls.searches.last())
    }

    @Test fun aSongWithoutASpotifyTwinHasNoCanvas() = runBlocking {
        val other = serverSong.copy(id = "other", title = "Something else", artist = "Nobody")
        val merged = MergedBackend(listOf(source(server, listOf(other)), source(spotify, listOf(spotifySong))), spotify)
        val hosted = merged.search("x", "library").songs.single()
        assertNull(merged.canvasUrl(hosted))
        // without a spotify account nothing offers a canvas
        val alone = MergedBackend(listOf(source(server, listOf(other))), server)
        assertFalse(alone.supportsCanvas(alone.search("x", "library").songs.single()))
    }

    @Test fun aSpotifySearchThatFoundNothingIsAskedAgain() = runBlocking {
        val found = mutableListOf<Song>()
        val spotifyCalls = Calls()
        val merged = MergedBackend(listOf(source(server, listOf(serverSong)), source(spotify, found, spotifyCalls)), spotify)
        val hosted = merged.search("never", "library").songs.single()
        assertNull(merged.canvasUrl(hosted))
        found += spotifySong
        assertEquals("https://canvaz.scdn.co/loop.mp4", merged.canvasUrl(hosted))
        assertEquals(2, spotifyCalls.searches.size)
    }

    @Test fun aServerSongBorrowsItsSpotifyTwinsLyrics() = runBlocking {
        val merged = MergedBackend(listOf(source(server, listOf(serverSong)), source(spotify, listOf(spotifySong))), spotify)
        val hosted = merged.search("never", "library").songs.single()
        assertEquals("spotify", merged.catalogueLyrics(hosted)!!.source)
        // spotify's own songs already get theirs as their source's lyrics
        assertNull(merged.catalogueLyrics(merged.search("never", "spotify").songs.single()))
    }
}
