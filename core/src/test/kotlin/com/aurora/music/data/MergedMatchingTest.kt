package com.aurora.music.data

import com.aurora.music.model.Song
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

private fun unsupported(): MediaBackend = Proxy.newProxyInstance(MediaBackend::class.java.classLoader, arrayOf(MediaBackend::class.java)) { _, method, _ ->
    throw UnsupportedOperationException(method.name)
} as MediaBackend

class MergedMatchingTest {
    private class Hanging(override val session: Session) : MediaBackend by unsupported() {
        val searches = AtomicInteger()
        val listings = AtomicInteger()
        override suspend fun matchingSongs(song: Song): List<Song> {
            searches.incrementAndGet()
            awaitCancellation()
        }
        override suspend fun allSongs(): List<Song> {
            listings.incrementAndGet()
            return emptyList()
        }
    }

    private class Catalogue(override val session: Session) : MediaBackend by unsupported() {
        override suspend fun allSongs() = emptyList<Song>()
    }

    private class Checks(override val session: Session, val answer: suspend (List<String>) -> Set<String>) : MediaBackend by unsupported() {
        override suspend fun likedSongIds(ids: List<String>) = answer(ids)
    }

    private class Holds(override val session: Session, val songs: List<Song>) : MediaBackend by unsupported() {
        override suspend fun matchingSongs(song: Song) = songs
        override suspend fun search(query: String) = SearchResults(songs = songs)
        override fun playbackSourceIdentity(song: Song) = PlaybackSourceIdentity.fromSession(session, song.albumId)
    }

    private val youtube = Session("https://music.youtube.com", "Listener", "", "token", ServerType.YOUTUBE_MUSIC, userId = "account")
    private val spotify = Session("https://open.spotify.com", "Listener", "", "token", ServerType.SPOTIFY, userId = "listener")

    @Test fun copiesHoldTheSongOnEachSourceThatHasItAndMarkWhereItPlays() = runBlocking {
        val merged = MergedBackend(listOf(
            Holds(server, emptyList()),
            Holds(youtube, listOf(Song("y0", "Fever (Live)", "Buckshot", "", "", 300), Song("y1", "Fever", "Buckshot", "Fever", "", 146, albumId = "ya", artistId = "yb"))),
            Holds(spotify, listOf(Song("p1", "Fever", "Someone Else", "", "", 200))),
        ), server)
        val playing = Song(idOn(server, "s1"), "Fever", "Buckshot", "Fever", "", 144, albumId = idOn(server, "a1"),
            playbackSource = PlaybackSourceIdentity.fromSession(server, "a1"))
        val copies = merged.sourceCopies(playing)
        assertEquals(listOf("Navidrome", "YouTube Music"), copies.map { it.label })
        assertEquals(listOf(true, false), copies.map { it.playing })
        assertEquals(listOf(idOn(server, "s1"), idOn(youtube, "y1")), copies.map { it.song.id })
        assertEquals(idOn(youtube, "ya"), copies[1].song.albumId)
    }

    // finds only what a title search would: songs whose title is in the query
    private class Finds(override val session: Session, val songs: List<Song>) : MediaBackend by unsupported() {
        override suspend fun matchingSongs(song: Song) = songs.filter { recordingKey(it.title) == recordingKey(song.title) }
        override suspend fun search(query: String) = SearchResults(songs = songs.filter { query.contains(it.title, ignoreCase = true) })
        override fun playbackSourceIdentity(song: Song) = PlaybackSourceIdentity.fromSession(session, song.albumId)
    }

    @Test fun anUploadWithTheArtistInItsTitleStillFindsItsCopies() = runBlocking {
        val soundcloud = Session("https://soundcloud.com", "Listener", "", "token", ServerType.SOUNDCLOUD, userId = "sc")
        val original = Song(idOn(spotify, "p1"), "Мало тебя", "Серебро", "", "", 201, playbackSource = PlaybackSourceIdentity.fromSession(spotify, ""))
        val merged = MergedBackend(listOf(
            Finds(server, listOf(Song("s1", "Мало тебя", "Серебро", "", "", 201))),
            Finds(youtube, listOf(Song("y1", "Мало тебя", "Серебро", "", "", 200))),
            Finds(spotify, emptyList()),
            Finds(soundcloud, emptyList()),
        ), server)
        val upload = Song(idOn(soundcloud, "c1"), "Мало тебя - Серебро", "some uploader", "", "", 202,
            playbackSource = PlaybackSourceIdentity.fromSession(soundcloud, ""))

        val alone = merged.sourceCopies(upload)
        assertEquals(listOf(idOn(server, "s1"), idOn(youtube, "y1"), idOn(soundcloud, "c1")), alone.map { it.song.id })

        val swapped = merged.sourceCopies(original, playing = upload)
        assertEquals(listOf(idOn(server, "s1"), idOn(youtube, "y1"), idOn(spotify, "p1"), idOn(soundcloud, "c1")), swapped.map { it.song.id })
        assertEquals(listOf(false, false, false, true), swapped.map { it.playing })
    }
    private val server =Session("https://server.invalid", "Listener", "", "token", ServerType.SUBSONIC)
    private fun idOn(session: Session, id: String) = "${mergeNamespace(session)}$MERGE_NAMESPACE_SEP$id"

    @Test fun aSearchOutOfTimeIsSkippedForTheNextTitleWhileBrowsingStillAsksTheServer() = runBlocking {
        val hanging = Hanging(server)
        val merged = MergedBackend(listOf(hanging, Catalogue(youtube)), youtube)
        assertTrue(merged.playbackCandidates(Song("a", "First", "Artist", "", "", 100)).isEmpty())
        assertTrue(merged.playbackCandidates(Song("b", "Second", "Artist", "", "", 100)).isEmpty())
        assertEquals(1, hanging.searches.get())
        merged.allSongs()
        assertEquals(1, hanging.listings.get())
    }

    @Test fun likedChecksRunTogetherAndAFailureReachesTheCaller() = runBlocking {
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        val together = MergedBackend(listOf(
            Checks(server) { first.complete(Unit); second.await(); it.toSet() },
            Checks(youtube) { second.complete(Unit); first.await(); emptySet() },
        ), server)
        val ids = listOf(idOn(server, "s"), idOn(youtube, "y"))
        assertEquals(setOf(idOn(server, "s")), withTimeout(5_000) { together.likedSongIds(ids) })

        val failing = MergedBackend(listOf(Checks(server) { it.toSet() }, Checks(youtube) { throw IOException("refused") }), server)
        assertTrue(runCatching { failing.likedSongIds(ids) }.exceptionOrNull() is PartialLikes)
    }
}
