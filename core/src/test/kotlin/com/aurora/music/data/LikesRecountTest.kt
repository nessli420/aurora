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
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext

private fun unsupported(): MediaBackend = Proxy.newProxyInstance(MediaBackend::class.java.classLoader, arrayOf(MediaBackend::class.java)) { _, method, _ ->
    throw UnsupportedOperationException(method.name)
} as MediaBackend

class LikesRecountTest {
    private class Source(name: String, private val liked: List<Song> = emptyList()) : MediaBackend by unsupported() {
        override val session = Session("https://$name.invalid", name, "salt", "token", ServerType.SUBSONIC, "user-$name")
        override val signedIn = true
        @Volatile var fetches = 0
        @Volatile var answer = CompletableDeferred(emptySet<String>())
        override suspend fun starredIds(): Set<String> {
            fetches++
            return answer.await()
        }
        override suspend fun starredSongs() = liked
        override suspend fun likedSongIds(ids: List<String>) = emptySet<String>()
    }

    private fun song(id: String) = Song(id, "Blue Hour", "The Band", "Album", "", 200)

    private val dir: File = Files.createTempDirectory("aurora-recount").toFile()
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val active = Source("active")
    private val other = Source("other", listOf(song("elsewhere")))
    @Volatile private var holding = false
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)

    // only a recount reads the active account from a pool thread here, which holds it mid-match
    private val repository = MusicRepository({
        if (holding && Thread.currentThread().name.startsWith("DefaultDispatcher")) {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        active
    }, DownloadManager(dir, { "file://$it" }, { null }), likeSourcesProvider = { listOf(other) }, mapping = EmptyCoroutineContext)
    private val likes = Likes(scope, repository) { _, _ -> }

    @After fun tearDown() {
        release.countDown()
        scope.cancel()
        executor.shutdownNow()
        dir.deleteRecursively()
    }

    private suspend fun until(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(5) }

    private fun test(block: suspend () -> Unit) = runBlocking(dispatcher) { block() }

    @Test fun aRecountCaughtByAnAccountChangeDoesNotBringItsHeartsBack() = test {
        val blue = song("own")
        likes.refresh()
        likes.check(listOf(blue))
        until { blue.id in likes.likedIds.value }

        holding = true
        likes.check(listOf(song("own-again")))
        until { entered.count == 0L }
        likes.reset()
        holding = false
        release.countDown()
        delay(200)
        assertFalse(blue.id in likes.likedIds.value)
    }

    @Test fun aRefreshDuringAFetchJoinsItAndAnAccountChangeStartsAnother() = test {
        active.answer = CompletableDeferred()
        likes.refresh()
        likes.refresh()
        until { active.fetches == 1 }
        delay(50)
        assertEquals(1, active.fetches)
        likes.reset()
        until { active.fetches == 2 }
    }
}
