package com.aurora.music.desktop.player

import com.aurora.music.data.ListenBrainzScrobbler
import com.aurora.music.data.QueueStore
import com.aurora.music.data.SavedQueue
import com.aurora.music.data.SavedTrack
import com.aurora.music.data.remote.ListenBrainzClient
import com.aurora.music.data.toSavedTrack
import com.aurora.music.desktop.DesktopContainer
import com.aurora.music.desktop.audio.DesktopPlaybackEngine
import com.aurora.music.desktop.audio.EngineEvent
import com.aurora.music.desktop.audio.EnginePhase
import com.aurora.music.desktop.audio.FakeBackend
import com.aurora.music.desktop.audio.PlaybackEngine
import com.aurora.music.desktop.audio.Tracks
import com.aurora.music.desktop.audio.TransitionReason
import com.aurora.music.desktop.platform.DesktopPaths
import com.aurora.music.desktop.platform.DesktopSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import com.aurora.music.desktop.audio.RepeatMode as EngineRepeat

class DesktopPlayerTest {
    private val root: File = Files.createTempDirectory("aurora-player").toFile()
    private val container = DesktopContainer(DesktopPaths(File(root, "Roaming"), File(root, "Local")))
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val ready = MutableStateFlow<Boolean?>(null)
    private val epoch = MutableStateFlow(0)
    @Volatile private var account = "subsonic|alice"
    private val players = mutableListOf<DesktopPlayer>()

    @After fun tearDown() {
        players.forEach { player -> on { player.close() } }
        scope.cancel()
        container.close()
        executor.shutdownNow()
        root.deleteRecursively()
    }

    private fun deps(queueStore: QueueStore = container.queueStore) = container.playerDependencies()
        .copy(scope = scope, queueStore = queueStore, sessionReady = ready, accountEpoch = epoch, accountKey = { account })

    private fun player(engine: PlaybackEngine = FakeEngine(), deps: PlayerDependencies = deps()): DesktopPlayer =
        on { DesktopPlayer(engine, deps) }.also { players += it; settle() }

    private fun <T> on(block: () -> T): T = runBlocking(dispatcher) { block() }

    private fun settle() = repeat(3) { on { } }

    private fun waitFor(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!on(condition)) {
            check(System.currentTimeMillis() < deadline) { "Timed out" }
            Thread.sleep(10)
        }
    }

    private fun FakeEngine.ids() = state.value.entries.map { it.song.id }

    private fun DesktopPlayer.queueIds() = on { state.value.queue.map { it.id } }

    @Test fun shufflePlayRemembersTheOriginalOrderForRestore() {
        val engine = FakeEngine()
        val player = player(engine)
        val songs = songs(8)
        on { player.shufflePlay(songs) }
        settle()
        val shuffled = engine.state.value
        assertTrue(shuffled.shuffle)
        assertEquals(songs.map { it.id }.sorted(), engine.ids().sorted())
        assertEquals(songs.map { it.id }, shuffled.shuffleRestoreIds)
        assertEquals(engine.ids(), player.queueIds())
        val current = shuffled.current!!.song.id
        on { player.toggleShuffle() }
        settle()
        assertFalse(engine.state.value.shuffle)
        assertEquals(songs.map { it.id }, engine.ids())
        assertEquals(current, engine.state.value.current!!.song.id)
        assertEquals(songs.map { it.id }, player.queueIds())
        assertFalse(on { player.state.value.shuffle })
    }

    @Test fun togglingShuffleMovesTheCurrentTrackFirstAndRestoresAroundIt() {
        val engine = FakeEngine()
        val player = player(engine)
        val songs = songs(6)
        on { player.playAll(songs, 3) }
        on { player.toggleShuffle() }
        settle()
        assertEquals("s3", engine.ids().first())
        assertEquals(0, on { player.state.value.currentIndex })
        on { player.toggleShuffle() }
        settle()
        assertEquals(songs.map { it.id }, engine.ids())
        assertEquals(3, engine.state.value.index)
        assertEquals("s3", on { player.state.value.current.id })
    }

    @Test fun playAllTurnsShuffleOffWithoutReorderingTheNewQueue() {
        val engine = FakeEngine()
        val player = player(engine)
        on { player.shufflePlay(songs(5)) }
        val fresh = List(4) { song("n$it") }
        on { player.playAll(fresh, 2) }
        settle()
        assertFalse(engine.state.value.shuffle)
        assertEquals(fresh.map { it.id }, engine.ids())
        assertEquals("n2", on { player.state.value.current.id })
    }

    @Test fun previousRestartsPastFourSecondsAndOtherwiseStepsBack() {
        val engine = FakeEngine()
        val player = player(engine)
        on { player.playAll(songs(4), 2) }
        on { engine.at(4_500) }
        on { player.previous() }
        assertEquals(2, engine.state.value.index)
        assertEquals(0L, engine.state.value.positionMs)
        on { engine.at(3_000) }
        on { player.previous() }
        assertEquals(1, engine.state.value.index)
        on { player.jumpTo(0) }
        on { engine.at(1_000) }
        on { player.previous() }
        assertEquals(0, engine.state.value.index)
    }

    @Test fun clearQueueKeepsHistoryAndTheCurrentTrack() {
        val engine = FakeEngine()
        val player = player(engine)
        on { player.playAll(songs(6), 2) }
        settle()
        on { player.clearQueue() }
        settle()
        assertEquals(listOf("s0", "s1", "s2"), engine.ids())
        assertEquals(2, engine.state.value.index)
        assertEquals(listOf("s0", "s1", "s2"), player.queueIds())
    }

    @Test fun queueEditsFollowPlayOrder() {
        val engine = FakeEngine()
        val player = player(engine)
        on { player.addToQueue(song("first")) }
        settle()
        assertEquals(listOf("first"), engine.ids())
        assertTrue(engine.state.value.playWhenReady)
        on { player.addToQueue(song("last")) }
        on { player.playNext(song("next")) }
        on { player.moveQueueItem(2, 0) }
        on { player.removeFromQueue(2) }
        settle()
        assertEquals(listOf("last", "first"), engine.ids())
        assertEquals(listOf("last", "first"), player.queueIds())
        assertEquals("first", on { player.state.value.current.id })
    }

    @Test fun queuePersistsAndRestoresWithTheShuffleOrder() {
        ready.value = true
        val engine = FakeEngine()
        val first = player(engine)
        val songs = songs(7)
        on { first.playAll(songs, 2) }
        on { first.toggleShuffle() }
        on { first.cycleRepeat() }
        on { engine.at(42_000) }
        on { first.togglePlay() }
        settle()
        val physical = engine.ids()
        assertEquals("s2", physical.first())
        container.queueStore.flushNow()
        assertFalse("#shuffle-order" in File(root, "Local/queue_state.json").readText())

        val store = QueueStore(File(root, "Local"))
        assertEquals(songs.map { it.id }, store.get("subsonic|alice")?.shuffleOrder)
        val restoredEngine = FakeEngine(kotlin.random.Random(99))
        val second = player(restoredEngine, deps(store))
        waitFor { restoredEngine.state.value.entries.isNotEmpty() }
        settle()
        val restored = restoredEngine.state.value
        assertEquals(physical, restoredEngine.ids())
        assertEquals(0, restored.index)
        assertEquals(42_000L, restored.positionMs)
        assertFalse(restored.playWhenReady)
        assertEquals(EngineRepeat.ALL, restored.repeat)
        assertTrue(restored.shuffle)
        assertEquals(songs.map { it.id }, restored.shuffleRestoreIds)
        val ui = on { second.state.value }
        assertEquals(physical, ui.queue.map { it.id })
        assertEquals(RepeatMode.ALL, ui.repeat)
        assertTrue(ui.shuffle)
        assertFalse(ui.isPlaying)
        assertEquals(42f, ui.positionSec)
        on { second.toggleShuffle() }
        settle()
        assertEquals(songs.map { it.id }, restoredEngine.ids())
        assertEquals("s2", restoredEngine.state.value.current!!.song.id)
        assertNull(store.get("subsonic|alice")?.shuffleOrder)
    }

    @Test fun legacyShuffleOrderEntriesMigrateIntoTheSavedQueue() {
        val songs = songs(5)
        val physical = listOf(3, 0, 4, 1, 2).map { songs[it].id }
        val store = container.queueStore
        store.save("subsonic|alice", SavedQueue(physical.map { song(it).toSavedTrack() }, currentIndex = 1, positionSec = 9, shuffle = true))
        store.save("subsonic|alice#shuffle-order", SavedQueue(songs.map { SavedTrack(id = it.id) }))
        store.save("subsonic|bob#shuffle-order", SavedQueue(listOf(SavedTrack(id = "x"))))
        ready.value = true
        val engine = FakeEngine()
        player(engine)
        waitFor { engine.state.value.entries.isNotEmpty() }
        settle()
        assertEquals(physical, engine.ids())
        assertEquals(1, engine.state.value.index)
        assertTrue(engine.state.value.shuffle)
        assertEquals(songs.map { it.id }, engine.state.value.shuffleRestoreIds)
        assertNull(store.get("subsonic|alice#shuffle-order"))
        assertEquals(songs.map { it.id }, store.get("subsonic|alice")?.shuffleOrder)
        store.flushNow()
        val reloaded = QueueStore(File(root, "Local"))
        assertNull(reloaded.get("subsonic|alice#shuffle-order"))
        assertEquals(songs.map { it.id }, reloaded.get("subsonic|alice")?.shuffleOrder)
        account = "subsonic|bob"
        epoch.value = 1
        waitFor { store.get("subsonic|bob#shuffle-order") == null }
        assertNull(store.get("subsonic|bob"))
    }

    @Test fun accountSwitchSavesTheOutgoingQueueAndRestoresTheIncomingOne() {
        ready.value = true
        val engine = FakeEngine()
        val player = player(engine)
        val alice = songs(4)
        val bob = List(3) { song("b$it") }
        on { player.playAll(alice, 1) }
        settle()
        container.queueStore.save("subsonic|bob", SavedQueue(bob.map { it.toSavedTrack() }, currentIndex = 2, positionSec = 12, repeat = 2))
        account = "subsonic|bob"
        epoch.value = 1
        waitFor { engine.ids() == bob.map { it.id } }
        settle()
        assertEquals(alice.map { it.id }, container.queueStore.get("subsonic|alice")?.tracks?.map { it.id })
        assertEquals(1, container.queueStore.get("subsonic|alice")?.currentIndex)
        assertEquals(bob.map { it.id }, container.queueStore.get("subsonic|bob")?.tracks?.map { it.id })
        val restored = engine.state.value
        assertEquals(2, restored.index)
        assertEquals(12_000L, restored.positionMs)
        assertFalse(restored.playWhenReady)
        assertEquals(EngineRepeat.ONE, restored.repeat)
        val calls = engine.calls.toList()
        assertTrue(calls.lastIndexOf("clear") in calls.indexOf("stop") until calls.lastIndexOf("setQueue"))
        val ui = on { player.state.value }
        assertEquals(bob.map { it.id }, ui.queue.map { it.id })
        assertEquals("b2", ui.current.id)
        assertFalse(ui.isPlaying)
    }

    @Test fun scrobblesOncePerTrackAfterThirtySecondsAndHonoursPrivateSession() {
        val bodies = CopyOnWriteArrayList<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            bodies += Buffer().also { chain.request().body?.writeTo(it) }.readUtf8()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        runBlocking { container.settingsStore.saveListenBrainz("token", "alice") }
        val listenBrainz = ListenBrainzScrobbler(container.settingsStore, scope, ListenBrainzClient(http))
        waitFor { listenBrainz.isConnected }
        val engine = FakeEngine()
        val player = player(engine, deps().copy(listenBrainz = listenBrainz))
        fun count(type: String, title: String) = bodies.count { "\"$type\"" in it && title in it }

        on { player.playAll(listOf(song("a", "Alpha"), song("b", "Beta"))) }
        on { engine.at(1_000) }
        waitFor { count("playing_now", "Alpha") == 1 }
        on { engine.at(29_000) }
        on { engine.at(31_000) }
        waitFor { count("single", "Alpha") == 1 }
        on { engine.at(0) }
        on { engine.at(45_000) }
        on { player.next() }
        on { engine.at(2_000) }
        waitFor { count("playing_now", "Beta") == 1 }
        runBlocking {
            container.settingsStore.setPrivateSession(true)
            container.settingsStore.privateSession.first { it }
        }
        Thread.sleep(200)
        settle()
        on { engine.at(40_000) }
        on { player.jumpTo(0) }
        on { engine.at(35_000) }
        Thread.sleep(500)
        assertEquals(1, count("single", "Alpha"))
        assertEquals(1, count("playing_now", "Alpha"))
        assertEquals(0, count("single", "Beta"))
    }

    @Test fun endOfTrackSleepPausesOnTheNextAutomaticTransition() {
        val engine = FakeEngine()
        val player = player(engine)
        on { player.playAll(songs(3)) }
        on { player.setSleepEndOfTrack() }
        settle()
        assertTrue(on { player.state.value.sleepEndOfTrack })
        val entries = engine.state.value.entries
        on { engine.events.tryEmit(EngineEvent.Transition(entries[0], entries[1], TransitionReason.SEEK, 5_000)) }
        settle()
        assertTrue(engine.state.value.playWhenReady)
        on { engine.events.tryEmit(EngineEvent.Transition(entries[1], entries[2], TransitionReason.AUTO, 200_000)) }
        settle()
        assertFalse(engine.state.value.playWhenReady)
        assertFalse(on { player.state.value.sleepEndOfTrack })
        on { player.setSleepTimer(30) }
        on { player.setSleepTimer(0) }
        assertEquals(listOf("sleepFade(0)", "sleepFade(0)", "sleepFade(0)"), engine.calls.filter { it.startsWith("sleepFade") })
    }

    @Test fun outputAndVolumeGoToTheEngineAndPersist() {
        val engine = FakeEngine()
        val player = player(engine)
        waitFor { "setOutput(null, false)" in engine.calls }
        on { player.setPreferredDevice("dac") }
        on { player.setExclusiveOutput(true) }
        waitFor { "setOutput(dac, true)" in engine.calls }
        assertEquals("dac", on { player.preferredOutput.value })
        assertTrue(on { player.exclusiveOutput.value })
        on { player.setVolume(0.3f) }
        assertEquals(0.3f, on { player.volume.value })
        assertEquals(0.027f, engine.level, 1e-6f)
        waitFor { runBlocking { container.desktopSettings.volume.first() } == 0.3f }
        assertEquals(400, engine.config.bufferMs)
    }

    @Test fun unmuteRestoresTheLastAudibleVolumeAfterARestart() {
        val engine = FakeEngine()
        val player = player(engine)
        waitFor { "setOutput(null, false)" in engine.calls }
        on { player.setVolume(0f) }
        on { player.toggleMute() }
        assertEquals(DesktopSettings.DEFAULT_UNMUTE_VOLUME, on { player.volume.value })
        assertEquals(0.125f, engine.level, 1e-6f)
        on { player.setVolume(0.2f) }
        on { player.toggleMute() }
        assertEquals(0f, engine.level)
        waitFor { runBlocking { container.desktopSettings.volume.first() == 0f && container.desktopSettings.unmuteVolume.first() == 0.2f } }
        val restarted = FakeEngine()
        val again = player(restarted)
        waitFor { again.volume.value == 0f }
        on { again.toggleMute() }
        assertEquals(0.008f, restarted.level, 1e-6f)
        assertEquals(0.2f, on { again.volume.value })
    }

    @Test fun engineFailuresBecomeMessages() {
        val engine = FakeEngine()
        val player = player(engine)
        val messages = CopyOnWriteArrayList<String>()
        val job = scope.launch { player.messages.collect { messages += it } }
        settle()
        on { engine.events.tryEmit(EngineEvent.OutputFallback("Exclusive mode is unavailable")) }
        waitFor { messages.isNotEmpty() }
        job.cancel()
        assertEquals(listOf("Exclusive mode is unavailable"), messages)
    }

    @Test fun realEngineCarriesTheUiThroughTransitionsAndAnAccountSwitch() = Tracks().use { tracks ->
        fun track(id: String) = tracks.song(tracks.wav(id, 48_000, 16, 24_000) { frame, _ -> (frame % 200) * 40 - 4_000 })
        ready.value = true
        val engine = DesktopPlaybackEngine(FakeBackend(speed = 4.0))
        val player = player(engine)
        on { player.playAll(listOf(track("a"), track("b"))) }
        waitFor { player.state.value.current.id == "b" }
        waitFor { engine.state.value.phase == EnginePhase.ENDED }
        settle()
        val ended = on { player.state.value }
        assertEquals(listOf("a", "b"), ended.queue.map { it.id })
        assertEquals(1, ended.currentIndex)
        assertFalse(ended.isPlaying)
        assertEquals(1, container.queueStore.get("subsonic|alice")?.currentIndex)

        val bob = listOf(track("c"), track("d"), track("e"))
        container.queueStore.save("subsonic|bob", SavedQueue(bob.map { it.toSavedTrack() }, currentIndex = 1, shuffle = true))
        account = "subsonic|bob"
        epoch.value = 1
        waitFor { engine.state.value.entries.map { it.song.id } == listOf("c", "d", "e") && engine.state.value.phase == EnginePhase.READY }
        settle()
        val switched = on { player.state.value }
        assertEquals(listOf("c", "d", "e"), switched.queue.map { it.id })
        assertEquals("d", switched.current.id)
        assertTrue(switched.shuffle)
        assertFalse(switched.isPlaying)
        assertEquals(listOf("a", "b"), container.queueStore.get("subsonic|alice")?.tracks?.map { it.id })
        assertEquals(listOf("c", "d", "e"), container.queueStore.get("subsonic|bob")?.tracks?.map { it.id })
        on { player.jumpTo(2) }
        waitFor { engine.state.value.phase == EnginePhase.ENDED }
        settle()
        assertEquals("e", on { player.state.value.current.id })
    }
}
