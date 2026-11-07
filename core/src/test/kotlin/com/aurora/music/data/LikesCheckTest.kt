package com.aurora.music.data

import com.aurora.music.model.Song
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.coroutines.EmptyCoroutineContext

private fun unsupported(): MediaBackend = Proxy.newProxyInstance(MediaBackend::class.java.classLoader, arrayOf(MediaBackend::class.java)) { _, method, _ ->
    throw UnsupportedOperationException(method.name)
} as MediaBackend

class LikesCheckTest {
    private class Source : MediaBackend by unsupported() {
        override val session = Session("https://one.invalid", "one", "salt", "token", ServerType.SUBSONIC, "user-one")
        override val signedIn = true
        @Volatile var asked = 0
        @Volatile var gate: CompletableDeferred<Unit>? = null
        override suspend fun starredIds() = emptySet<String>()
        override suspend fun starredSongs() = emptyList<Song>()
        override suspend fun likedSongIds(ids: List<String>): Set<String> {
            asked++
            gate?.await()
            return ids.filterTo(HashSet()) { it == "b" }
        }
    }

    private fun song(id: String) = Song(id, "Title $id", "Artist", "Album", "", 200)

    private val dir: File = Files.createTempDirectory("aurora-likes-check").toFile()
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val source = Source()
    private val likes = Likes(scope, MusicRepository({ source }, DownloadManager(dir, { "file://$it" }, { null }), mapping = EmptyCoroutineContext)) { _, _ -> }

    @After fun tearDown() {
        scope.cancel()
        executor.shutdownNow()
        dir.deleteRecursively()
    }

    private suspend fun until(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(5) }

    @Test fun aListIsAskedAboutOnceUntilTheAccountChanges() = runBlocking(dispatcher) {
        val songs = listOf(song("a"), song("b"))
        likes.checkList(songs)
        until { "b" in likes.likedIds.value }
        likes.checkList(songs)
        likes.checkList(songs.toList())
        delay(50)
        assertEquals(1, source.asked)
        likes.reset()
        likes.checkList(songs)
        until { source.asked == 2 }
    }

    @Test fun anAnswerFromBeforeAnAccountChangeIsDropped() = runBlocking(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        source.gate = gate
        likes.checkList(listOf(song("a"), song("b")))
        until { source.asked == 1 }
        likes.reset()
        gate.complete(Unit)
        delay(50)
        assertFalse("b" in likes.likedIds.value)
    }

    @Test fun theWindowCoversItsBucketWithAMarginEachWay() {
        val songs = (0 until 230).map { song("s$it") }
        assertEquals((0 until 150).map { "s$it" }, likeWindow(songs, 0).map { it.id })
        assertEquals((50 until 230).map { "s$it" }, likeWindow(songs, 2).map { it.id })
        assertTrue(likeWindow(songs, 9).isEmpty())
    }
}
