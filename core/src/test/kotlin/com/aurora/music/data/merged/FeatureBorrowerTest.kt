package com.aurora.music.data.merged

import com.aurora.music.data.MediaBackend
import com.aurora.music.data.PlaybackSourceIdentity
import com.aurora.music.data.SearchResults
import com.aurora.music.data.ServerType
import com.aurora.music.data.Session
import com.aurora.music.data.SpotifyWebBackend
import com.aurora.music.data.mergeNamespace
import com.aurora.music.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

class FeatureBorrowerTest {
    private val server = Session("https://server.invalid", "Listener", "", "token", ServerType.SUBSONIC)
    private val spotify = Session(SpotifyWebBackend.SERVER, "Listener", SpotifyWebBackend.WEB_PLAYER, "reference", ServerType.SPOTIFY, userId = "listener")
    private val soundcloud = Session("https://soundcloud.com", "listener", "", "reference", ServerType.SOUNDCLOUD, userId = "42")

    private val spotifySong = Song("4uLU6hMCjMI75M1A2tKUQC", "Never Gonna", "Rick", "Whenever", "", 213)
    private val serverSong = spotifySong.copy(id = "server")

    private class Calls { val searches = mutableListOf<String>(); val canvases = mutableListOf<String>() }

    private fun source(session: Session, songs: List<Song> = emptyList(), calls: Calls = Calls(), signedIn: Boolean = true): MediaBackend =
        Proxy.newProxyInstance(MediaBackend::class.java.classLoader, arrayOf(MediaBackend::class.java)) { _, method, args ->
            when (method.name) {
                "getSession" -> session
                "getSignedIn" -> signedIn
                "playbackSourceIdentity" -> PlaybackSourceIdentity.fromSession(session, (args!![0] as Song).albumId)
                "search" -> { calls.searches += args!![0] as String; SearchResults(songs = songs) }
                "supportsCanvas" -> session.type == ServerType.SPOTIFY
                "canvasUrl" -> { calls.canvases += (args!![0] as Song).id; "https://canvaz.scdn.co/loop.mp4" }
                "supportsComments" -> session.type == ServerType.SOUNDCLOUD
                else -> error("Unexpected ${method.name}")
            }
        } as MediaBackend

    private fun borrowerOf(vararg sources: MediaBackend) =
        FeatureBorrower(SourceRouter(sources.toList(), sources.last().session, nested = false, downUntil = ConcurrentHashMap()))

    @Test fun borrowsOnlyForAnotherSourcesOrdinarySongs() {
        val borrower = borrowerOf(source(server), source(spotify))
        assertTrue(borrower.borrows(1, 0, serverSong))
        assertFalse(borrower.borrows(1, 1, serverSong))
        assertFalse(borrower.borrows(-1, 0, serverSong))
        assertFalse(borrower.borrows(1, 0, serverSong.copy(id = "radio:x")))
        assertFalse(borrower.borrows(1, 0, serverSong.copy(id = "podcast:x")))
    }

    @Test fun aFoundTwinIsRememberedAndOwnCanvasWinsFirst() = runBlocking {
        val calls = Calls()
        val router = SourceRouter(listOf(source(server), source(spotify, listOf(spotifySong), calls)), spotify, nested = false, downUntil = ConcurrentHashMap())
        val borrower = FeatureBorrower(router)
        val hosted = router.wrap(serverSong, 0)
        assertEquals("https://canvaz.scdn.co/loop.mp4", borrower.canvasUrl(hosted))
        assertEquals("https://canvaz.scdn.co/loop.mp4", borrower.canvasUrl(hosted))
        assertEquals(listOf("Never Gonna Rick"), calls.searches)

        borrower.canvasUrl(router.wrap(spotifySong, 1))
        assertEquals(listOf(spotifySong.id, spotifySong.id, spotifySong.id), calls.canvases)
        assertEquals(1, calls.searches.size)
    }

    @Test fun borrowedCommentsNeedASignedInSoundcloud() {
        val hosted = serverSong.copy(id = "${mergeNamespace(server)}\u0001server")
        assertFalse(borrowerOf(source(server), source(soundcloud, signedIn = false)).canPostComments(hosted))
        assertTrue(borrowerOf(source(server), source(soundcloud, signedIn = true)).canPostComments(hosted))
    }
}
