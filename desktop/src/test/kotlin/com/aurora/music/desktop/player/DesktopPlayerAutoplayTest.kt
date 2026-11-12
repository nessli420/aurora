package com.aurora.music.desktop.player

import com.aurora.music.data.MediaBackend
import com.aurora.music.data.MusicRepository
import com.aurora.music.data.PlaybackReportTarget
import com.aurora.music.data.PlaybackSourceIdentity
import com.aurora.music.data.Session
import com.aurora.music.desktop.DesktopContainer
import com.aurora.music.desktop.closeAndJoin
import com.aurora.music.desktop.platform.DesktopPaths
import com.aurora.music.model.Song
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
import kotlin.coroutines.EmptyCoroutineContext

private fun radioBase(): MediaBackend = Proxy.newProxyInstance(MediaBackend::class.java.classLoader, arrayOf(MediaBackend::class.java)) { _, method, _ ->
    throw UnsupportedOperationException(method.name)
} as MediaBackend

class DesktopPlayerAutoplayTest {
    private class RadioBackend : MediaBackend by radioBase() {
        override val session = Session("http://127.0.0.1:9", "alice", "salt", "token")
        override fun playbackSourceIdentity(song: Song): PlaybackSourceIdentity? = null
        override fun playbackReportTarget(song: Song): PlaybackReportTarget? = null
        @Volatile var asked = 0
        // a different artist each, so the per-artist cap doesn't trim the batch
        override suspend fun radio(seedId: String): List<Song> = List(12) {
            Song("r$seedId-$it", "Radio $it", "Artist $it", "", "", 200, streamUrl = "https://music.example/r$it.flac")
        }.also { asked++ }
    }

    private val root: File = Files.createTempDirectory("aurora-autoplay").toFile()
    private val container = DesktopContainer(DesktopPaths(File(root, "Roaming"), File(root, "Local")))
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val engine = FakeEngine()
    private val backend = RadioBackend()
    private val player = on {
        DesktopPlayer(engine, container.playerDependencies().copy(
            scope = scope, sessionReady = MutableStateFlow(null),
            repository = MusicRepository(backendProvider = { backend }, downloadManager = container.downloadManager, mapping = EmptyCoroutineContext),
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

    private fun await(what: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!on { check() }) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for $what" }
            Thread.sleep(20)
        }
    }

    // a plain read, so it also works inside await's check, which already runs on the player's thread
    private fun ids() = engine.state.value.entries.map { it.song.id }

    @Test fun theLastSongStartingQueuesMoreAheadOfTimeAndYourOwnPlayFirst() {
        on { player.playAll(listOf(song("s0"), song("s1"), song("s2")), 0) }
        Thread.sleep(300)
        // songs are still to come, so autoplay waits
        assertEquals(0, backend.asked)

        on { player.jumpTo(2) }
        await("autoplay's picks") { engine.state.value.entries.size == 3 + com.aurora.music.data.Autoplay.BATCH }
        assertEquals(listOf("s0", "s1", "s2"), ids().take(3))
        await("the heading index") { player.state.value.autoplayFrom == 3 }

        on { player.addToQueue(song("mine")) }
        await("your song before the picks") { ids().getOrNull(3) == "mine" }
        await("the heading after it") { player.state.value.autoplayFrom == 4 }

        runBlocking { container.settingsStore.setAutoplayRadio(false) }
        await("the picks to leave") { engine.state.value.entries.size == 4 }
        assertEquals(listOf("s0", "s1", "s2", "mine"), ids())
        await("the heading to go") { player.state.value.autoplayFrom == -1 }
    }

    @Test fun aSecondShuffleTapMixesPicksInAndAThirdTakesThemOut() {
        val mine = (0..6).map { "s$it" }
        on { player.playAll(mine.map { song(it) }, 0) }
        on { player.toggleShuffle() }
        await("shuffle") { player.state.value.shuffle }
        assertEquals(false, player.state.value.smartShuffle)

        on { player.toggleShuffle() }
        val picks = com.aurora.music.data.Autoplay.smartCount(6)
        await("the picks mixed in") { engine.state.value.entries.size == 7 + picks }
        await("the marks") { player.state.value.smartShuffle && player.state.value.smartPicks.size == picks }
        val queue = ids()
        // one after every three of yours, after the playing song
        assertEquals(queue.indices.filter { queue[it] !in mine }, listOf(4, 8))

        on { player.toggleShuffle() }
        await("shuffle off and the picks gone") { !engine.state.value.shuffle && engine.state.value.entries.size == 7 }
        assertEquals(mine, ids())
        await("the marks gone") { !player.state.value.smartShuffle && player.state.value.smartPicks.isEmpty() }
    }

    @Test fun shuffleLeavesAutoplaysPicksAfterYourSongs() {
        on { player.playAll(listOf(song("s0"), song("s1"), song("s2")), 0) }
        on { player.jumpTo(2) }
        await("autoplay's picks") { engine.state.value.entries.size == 3 + com.aurora.music.data.Autoplay.BATCH }
        on { player.jumpTo(0) }
        await("on the first song") { engine.state.value.index == 0 }
        val picks = ids().drop(3)

        on { player.toggleShuffle() }
        // the playing song, your other two in some order, then the picks as they came
        await("the picks back after yours") { engine.state.value.shuffle && ids().drop(3) == picks }
        assertEquals(setOf("s1", "s2"), ids().subList(1, 3).toSet())
        await("the heading after yours") { player.state.value.autoplayFrom == 3 }
    }

    @Test fun repeatKeepsAutoplayOutUntilItIsTurnedOff() {
        on {
            player.playAll(listOf(song("s0"), song("s1"), song("s2")), 0)
            player.cycleRepeat() // all
            player.jumpTo(2)
        }
        Thread.sleep(500)
        assertEquals(0, backend.asked)
        assertEquals(3, ids().size)

        on { player.cycleRepeat(); player.cycleRepeat() } // one, then off
        await("autoplay once repeat is off") { engine.state.value.entries.size > 3 }
    }
}
