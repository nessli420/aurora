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

    private val youtube = Session("https://music.youtube.com", "Listener", "", "token", ServerType.YOUTUBE_MUSIC, userId = "account")
    private val server = Session("https://server.invalid", "Listener", "", "token", ServerType.SUBSONIC)
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
