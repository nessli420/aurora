package com.aurora.music.desktop.player

import com.aurora.music.data.MediaBackend
import com.aurora.music.data.MusicRepository
import com.aurora.music.data.Session
import com.aurora.music.desktop.DesktopContainer
import com.aurora.music.desktop.closeAndJoin
import com.aurora.music.desktop.platform.DesktopPaths
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.Executors

private fun unsupported(): MediaBackend = Proxy.newProxyInstance(MediaBackend::class.java.classLoader, arrayOf(MediaBackend::class.java)) { _, method, _ ->
    throw UnsupportedOperationException(method.name)
} as MediaBackend

class DesktopPlayerLikesTest {
    private class LikesBackend : MediaBackend by unsupported() {
        override val session = Session("http://127.0.0.1:9", "alice", "salt", "token")
        @Volatile var starred = CompletableDeferred<Set<String>>()
        @Volatile var stored = CompletableDeferred<Unit>()
        override suspend fun starredIds(): Set<String> = starred.await()
        override suspend fun setStarred(id: String, starred: Boolean, kind: String): Boolean {
            stored.await()
            return true
        }
    }

    private val root: File = Files.createTempDirectory("aurora-likes").toFile()
    private val container = DesktopContainer(DesktopPaths(File(root, "Roaming"), File(root, "Local")))
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val backend = LikesBackend()
    private val player = on {
        DesktopPlayer(FakeEngine(), container.playerDependencies().copy(
            scope = scope, sessionReady = MutableStateFlow(null),
            repository = MusicRepository(backendProvider = { backend }, downloadManager = container.downloadManager),
        ))
    }

    @After fun tearDown() {
        on { player.close() }
        scope.cancel()
        container.closeAndJoin()
        executor.shutdownNow()
        root.deleteRecursively()
    }

    private fun <T> on(block: () -> T): T = runBlocking(dispatcher) { block() }

    private fun settle() = repeat(3) { on { } }

    private fun liked(): Set<String> = on { player.state.value.likedIds }

    @Test fun aLikeToggledDuringARefreshIsNotRevertedByTheStaleResult() {
        backend.stored.complete(Unit)
        on { player.refreshLikes() }
        on { player.toggleLike("s1", "song") }
        settle()
        assertEquals(setOf("s1"), liked())
        backend.starred.complete(emptySet())
        settle()
        assertEquals(setOf("s1"), liked())

        backend.starred = CompletableDeferred(setOf("s1", "s2"))
        on { player.refreshLikes() }
        settle()
        assertEquals(setOf("s1", "s2"), liked())
    }

    @Test fun likesStillBeingSavedSurviveARefreshThatMissesThem() {
        backend.starred.complete(setOf("s3"))
        on { player.refreshLikes() }
        settle()
        assertEquals(setOf("s3"), liked())

        on { player.toggleLike("s1", "song") }
        on { player.toggleLike("s3", "song") }
        backend.starred = CompletableDeferred(setOf("s2", "s3"))
        on { player.refreshLikes() }
        settle()
        assertEquals(setOf("s1", "s2"), liked())

        backend.stored.complete(Unit)
        settle()
        backend.starred = CompletableDeferred(setOf("s1", "s2"))
        on { player.refreshLikes() }
        settle()
        assertEquals(setOf("s1", "s2"), liked())
    }
}
