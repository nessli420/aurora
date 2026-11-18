package com.aurora.music.desktop.player

import com.aurora.music.data.DetailData
import com.aurora.music.data.MediaBackend
import com.aurora.music.data.MusicRepository
import com.aurora.music.data.PlaybackReportTarget
import com.aurora.music.data.PlaybackSourceIdentity
import com.aurora.music.data.Session
import com.aurora.music.desktop.audio.PlaybackEngine
import com.aurora.music.desktop.closeAndJoin
import com.aurora.music.desktop.DesktopContainer
import com.aurora.music.desktop.platform.DesktopPaths
import com.aurora.music.foryou.GeneratedMix
import com.aurora.music.foryou.MIX_KIND
import com.aurora.music.foryou.MixKind
import com.aurora.music.foryou.queueMix
import com.aurora.music.model.DetailInfo
import com.aurora.music.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.coroutines.EmptyCoroutineContext

private fun pagedBase(): MediaBackend = Proxy.newProxyInstance(MediaBackend::class.java.classLoader, arrayOf(MediaBackend::class.java)) { _, method, _ ->
    throw UnsupportedOperationException(method.name)
} as MediaBackend

class DesktopPlayerQueueFillTest {
    private class PagedBackend : MediaBackend by pagedBase() {
        override val session = Session("http://127.0.0.1:9", "alice", "salt", "token")
        override fun playbackSourceIdentity(song: Song): PlaybackSourceIdentity? = null
        override fun playbackReportTarget(song: Song): PlaybackReportTarget? = null
        @Volatile var pages = 0
        override suspend fun detailPage(kind: String, id: String, offset: Int): List<Song> =
            List((TOTAL - offset).coerceIn(0, PAGE)) { song("s${offset + it}") }.also { pages++ }
    }

    private val root: File = Files.createTempDirectory("aurora-fill").toFile()
    private val container = DesktopContainer(DesktopPaths(File(root, "Roaming"), File(root, "Local")))
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val fake = FakeEngine()
    private val appended = CopyOnWriteArrayList<Int>()
    private val engine = object : PlaybackEngine by fake {
        override fun append(songs: List<Song>) {
            appended += songs.size
            fake.append(songs)
        }
    }
    private val backend = PagedBackend()
    private val evening = GeneratedMix("time-evening", MixKind.TIME, 2)
    private val turned = MutableStateFlow(false)
    private val player = on {
        DesktopPlayer(engine, container.playerDependencies().copy(
            scope = scope, sessionReady = MutableStateFlow(null),
            repository = MusicRepository(backendProvider = { backend }, downloadManager = container.downloadManager, mapping = EmptyCoroutineContext,
                mixes = { DetailData(DetailInfo("Evening", "", "", 0, false, 4, "Made for you"), listOf("s1", "e1", "mine", "e2").map { song(it) }) }),
            followingMix = { playing -> playing.combine(turned) { id, on -> evening.takeIf { on && id == "time-afternoon@2" } }.filterNotNull() },
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

    @Test fun pagesLandInBatchesButAQueueAboutToRunOutIsToppedUpAtOnce() {
        on { player.playCollection("album", "a1", List(100) { song("s$it") }, 95, TOTAL) }
        val deadline = System.currentTimeMillis() + 10_000
        while (on { fake.state.value.entries.size } < TOTAL) {
            check(System.currentTimeMillis() < deadline) { "Timed out" }
            Thread.sleep(20)
        }
        assertEquals(listOf(PAGE, 500, 450), appended.toList())
        assertEquals(List(TOTAL) { "s$it" }, on { fake.state.value.entries.map { it.song.id } })
    }

    @Test fun aQueueEditStopsTheFillButKeepsWhatItHadFetched() {
        on { player.playCollection("album", "a1", List(100) { song("s$it") }, 0, TOTAL) }
        val deadline = System.currentTimeMillis() + 10_000
        // the edit lands while the first page still waits in the batch
        while (!on { if (backend.pages == 1) { player.addToQueue(song("x")); true } else false }) {
            check(System.currentTimeMillis() < deadline) { "Timed out" }
            Thread.sleep(5)
        }
        while (on { backend.pages } < 2) {
            check(System.currentTimeMillis() < deadline) { "Timed out" }
            Thread.sleep(20)
        }
        Thread.sleep(50)
        assertEquals(List(100 + PAGE) { "s$it" } + "x", on { fake.state.value.entries.map { it.song.id } })
    }

    @Test fun aMixThatMovedOnTakesOverBehindThePlayingSongAndWhatWasQueuedByHand() {
        on { player.playCollection(MIX_KIND, "time-afternoon@2", List(4) { song("s$it") }, 1, 4) }
        on { player.addToQueue(song("mine")) }
        Thread.sleep(100)
        assertEquals("the queue stays while its edition stands", listOf("s0", "s1", "s2", "s3", "mine"), on { fake.state.value.entries.map { it.song.id } })
        on { turned.value = true }
        val deadline = System.currentTimeMillis() + 10_000
        while (on { fake.state.value.entries.lastOrNull()?.song?.id } != "e2") {
            check(System.currentTimeMillis() < deadline) { "Timed out" }
            Thread.sleep(20)
        }
        assertEquals(listOf("s0", "s1", "mine", "e1", "e2"), on { fake.state.value.entries.map { it.song.id } })
        assertEquals(1, on { fake.state.value.index })
        assertEquals("the queue is on the new edition now", "time-evening@2", on { queueMix(fake.state.value.entries.map { it.song }, 1) })
    }

    private companion object {
        const val PAGE = 250
        const val TOTAL = 1_300
    }
}
