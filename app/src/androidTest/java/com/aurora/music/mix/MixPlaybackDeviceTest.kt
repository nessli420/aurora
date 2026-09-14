package com.aurora.music.mix

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import androidx.media3.common.*
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.aurora.music.AuroraApplication
import com.aurora.music.MainActivity
import com.aurora.music.model.Song
import com.aurora.music.playback.PlaybackService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

@Suppress("DEPRECATION")
class MixPlaybackDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var mix: MixPlayer? = null
    private var foregroundActivity: MainActivity? = null
    private val files = mutableListOf<File>()
    private data class QueueSnapshot(val items: List<MediaItem>, val index: Int, val position: Long,
        val repeat: Int, val parameters: PlaybackParameters, val wanted: Boolean, val state: Int)
    private var originalController: MediaController? = null
    private var originalQueue: QueueSnapshot? = null
    private var originalHistory: List<com.aurora.music.data.PlayEvent>? = null
    private var originalPrivateSession: Boolean? = null
    private var originalQueueAccount: String? = null
    private var originalSavedQueue: com.aurora.music.data.SavedQueue? = null
    private fun <T> main(block: () -> T): T {
        val task = FutureTask(Callable(block)); instrumentation.runOnMainSync(task); return task.get()
    }
    private fun await(label: String, seconds: Int = 10, check: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + seconds * 1000
        while (SystemClock.elapsedRealtime() < until) { if (check()) return; SystemClock.sleep(40) }
        val playback = main { mix?.let { "playWhenReady=${it.playWhenReady}, state=${it.playbackState}, error=${it.playerError?.message}" } }
        fail("Timed out: $label${playback?.let { " ($it)" }.orEmpty()}")
    }
    private fun wav(duration: Int = 12): File {
        val file = File(context.cacheDir, "mix-qa-${System.nanoTime()}.wav"); files.add(file)
        val sr = 44100; val count = duration * sr; val size = count * 4
        file.outputStream().buffered().use { out ->
            fun le(value: Int, n: Int) { repeat(n) { out.write(value ushr (8 * it) and 255) } }
            out.write("RIFF".toByteArray()); le(size + 36, 4); out.write("WAVEfmt ".toByteArray()); le(16, 4)
            le(1, 2); le(2, 2); le(sr, 4); le(sr * 4, 4); le(4, 2); le(16, 2)
            out.write("data".toByteArray()); le(size, 4)
            for (i in 0 until count) {
                val pulse = if (i % (sr / 2) < sr / 30) 0.15 else 0.02
                val sample = (sin(2 * PI * 440 * i / sr) * pulse * 32767).toInt()
                le(sample, 2); le(sample, 2)
            }
        }
        return file
    }
    private fun song(file: File, id: String = file.name) = Song(id, "QA tone", "Aurora QA", "", "", 12, streamUrl = file.toURI().toString())
    private fun decks(): List<ExoPlayer> = main {
        val field = MixPlayer::class.java.getDeclaredField("decks").apply { isAccessible = true }
        (field.get(mix) as List<*>).map { it!!::class.java.getDeclaredField("player").apply { isAccessible = true }.get(it) as ExoPlayer }
    }
    @Before fun keepTargetForegroundForAudioFocus() {
        val container = (context.applicationContext as AuroraApplication).container
        runBlocking {
            originalPrivateSession = container.settingsStore.privateSession.first()
            container.settingsStore.setPrivateSession(true)
            container.sessionReady.first { it != null }
            originalQueueAccount = container.currentAccountKey()
            originalSavedQueue = originalQueueAccount?.takeIf { it.isNotBlank() }?.let(container.queueStore::get)
            originalHistory = container.playHistory.snapshot()
        }
        // Android 15 requires a top app or foreground service for media audio focus.
        // Raw MixPlayer fixtures deliberately have no service. Launch after instrumentation
        // starts (it restarts the app process), and keep this activity resumed until release.
        foregroundActivity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        instrumentation.waitForIdleSync()
        await("foreground activity ready for audio focus") {
            main { foregroundActivity?.let {
                it.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && it.hasWindowFocus()
            } == true }
        }
        org.junit.Assume.assumeFalse("Keep exclusive USB sessions untouched",
            runBlocking { container.settingsStore.playbackPrefs.first().bitPerfectUsb })
        org.junit.Assume.assumeFalse("Keep active Mix sessions untouched", container.mixController.activeProject != null)
        val c = main { MediaController.Builder(context,
            SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }.get(10, TimeUnit.SECONDS)
        originalController = c
        org.junit.Assume.assumeFalse("Keep remote sessions untouched", main { c.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE })
        originalQueue = main { QueueSnapshot((0 until c.mediaItemCount).map(c::getMediaItemAt),
            c.currentMediaItemIndex, c.currentPosition, c.repeatMode, c.playbackParameters, c.playWhenReady, c.playbackState) }
        main { c.pause() }
    }

    @After fun tearDown() {
        try {
            main { try { mix?.release() } finally { mix = null } }
            val c = originalController
            val old = originalQueue
            if (c != null && old != null) {
                main { c.sendCustomCommand(androidx.media3.session.SessionCommand(PlaybackService.CMD_EXIT_MIX,
                    android.os.Bundle.EMPTY), android.os.Bundle.EMPTY) }
                await("original player available for fixture cleanup") {
                    main { c.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS) }
                }
                main {
                    c.pause(); c.repeatMode = old.repeat; c.playbackParameters = old.parameters
                    if (old.items.isNotEmpty()) {
                        c.setMediaItems(old.items, old.index.coerceIn(old.items.indices), old.position)
                        if (old.state != Player.STATE_IDLE) c.prepare() else c.stop()
                        c.playWhenReady = old.wanted
                    } else c.clearMediaItems()
                }
            }
        } finally {
            try {
                main { originalController?.release(); originalController = null; originalQueue = null }
                files.forEach { it.delete() }; files.clear()
                val activity = foregroundActivity
                main { activity?.finish(); foregroundActivity = null }
                instrumentation.waitForIdleSync()
                if (activity != null) await("test activity finished") { main { activity.isDestroyed } }
            } finally {
                try {
                    val container = (context.applicationContext as AuroraApplication).container
                    // onCleared can schedule a final queue write. Restore only after destruction;
                    // QueueStore serializes this durable replacement against those queued saves.
                    runBlocking {
                        originalQueueAccount?.takeIf { it.isNotBlank() }?.let { container.queueStore.restoreAccount(it, originalSavedQueue) }
                        originalHistory?.let { container.playHistory.restoreBackup(it) }
                    }
                    originalQueueAccount = null; originalSavedQueue = null; originalHistory = null
                } finally {
                    // Restore reporting only after test playback and its activity callbacks end.
                    originalPrivateSession?.let { runBlocking {
                        (context.applicationContext as AuroraApplication).container.settingsStore.setPrivateSession(it)
                    } }
                    originalPrivateSession = null
                }
            }
        }
    }

    @Test fun testThreeDeckPlaybackPauseSeekAndLiveControls() {
        val tone = song(wav())
        val clips = (0..2).map { MixClip(song = tone.copy(id = "tone-$it"), fadeInSec = 0.1f, fadeOutSec = 1f) }
        val project = MixProject(clips = clips)
        val bus = MixController()
        main {
            mix = MixPlayer(context, project, DefaultMediaSourceFactory(DefaultDataSource.Factory(context)), bus, { null })
            mix!!.play()
        }
        await("three decks playing") { bus.state.value.playing && !bus.state.value.buffering && bus.state.value.positionSec > 0.8f }
        val players = decks()
        main {
            assertEquals(3, players.size)
            assertTrue(players.all { it.isPlaying })
            assertTrue(players.sumOf { it.volume.toDouble() } <= 0.9)
            mix!!.pause()
        }
        SystemClock.sleep(100)
        val paused = bus.state.value.positionSec
        SystemClock.sleep(450)
        assertEquals(paused, bus.state.value.positionSec, 0.03f)
        main { assertTrue(players.none { it.isPlaying }); mix!!.seekTo(5000); mix!!.play() }
        await("seek resumes") { bus.state.value.positionSec > 5.4f }
        main { players.forEach { assertTrue("deck seek mismatch ${it.currentPosition}", abs(it.currentPosition - bus.state.value.positionSec * 1000) < 250) } }
        main { mix!!.updateProject(project.copy(clips = clips.mapIndexed { i, c -> c.copy(solo = i == 1) })) }
        SystemClock.sleep(100)
        main { assertEquals(0f, players[0].volume, 0f); assertTrue(players[1].volume > 0f); assertEquals(0f, players[2].volume, 0f) }
        main { mix!!.updateProject(project.copy(loop = true)); mix!!.seekTo(11_900) }
        await("mix loop") { bus.state.value.positionSec < 1f && bus.state.value.playing }
    }

    @Test fun testWholeCollectionUsesBoundedDecksAndSeeksToTheLastTrack() {
        val source = song(wav())
        val project = AutoMixPlanner.plan("32 tracks", List(32) { source.copy(id = "collection-$it") }, emptyMap())
        val bus = MixController()
        main { mix = MixPlayer(context, project, DefaultMediaSourceFactory(DefaultDataSource.Factory(context)), bus, { null }); mix!!.play() }
        await("collection starts") { bus.state.value.positionSec > .3f }
        assertTrue(decks().size <= 4)
        val last = project.clips.last()
        main { mix!!.seekTo(((last.startSec + 1) * 1000).toLong()) }
        await("last track after long seek") { bus.state.value.positionSec > last.startSec + 1.4f && !bus.state.value.buffering }
        assertTrue(decks().size <= 2)
        main { mix!!.pause(); mix!!.seekTo(1000); mix!!.play() }
        await("return to first tracks") { bus.state.value.positionSec in 1.3f..4f }
        assertEquals(32, bus.activeProject!!.clips.size)
        assertTrue(decks().size <= 4)
    }

    @Test fun testNeuralVocalSeparationProducesPlayableComplementaryStems() = runBlocking {
        val fixture = wav()
        TestHttp(fixture.readBytes()).use { server ->
        server.gate.set(true)
        val source = song(fixture).copy(id = "qa-separation-${System.nanoTime()}", streamUrl = "http://127.0.0.1:${server.port}/stems.wav")
        val separator = StemSeparator(context) { null }
        val started = SystemClock.elapsedRealtime()
        val stems = separator.separate("qa-stems", source) { label, progress ->
            android.util.Log.i("AuroraStemQA", "$label ${(progress * 100).toInt()}%")
        }
        val vocals = File(android.net.Uri.parse(stems.vocals).path!!)
        val backing = File(android.net.Uri.parse(stems.backing).path!!)
        files.add(vocals); files.add(backing)
        files.add(File(vocals.parentFile, vocals.name.removeSuffix("-vocals.wav") + ".ready"))
        assertTrue(vocals.length() > 44100 * 4L)
        assertEquals(vocals.length(), backing.length())
        assertEquals(stems, separator.cached("qa-stems", source))
        assertNull(separator.cached("other-account", source))
        val original = fixture.readBytes()
        val v = vocals.readBytes(); val b = backing.readBytes()
        fun sample(bytes: ByteArray, i: Int) = ((bytes[i].toInt() and 255) or (bytes[i+1].toInt() shl 8)).toShort().toInt()
        var residual = 0.0; var delta = 0.0
        for (i in 44 until v.size step 2) {
            val error = sample(original, i) - sample(v, i) - sample(b, i)
            residual += error.toDouble() * error
            delta += abs(sample(original, i) - sample(v, i))
        }
        assertTrue("stems must reconstruct the source", sqrt(residual / ((v.size - 44) / 2)) <= 1.1)
        assertTrue("model must perform separation", delta > 1000)
        val elapsed = SystemClock.elapsedRealtime() - started
        android.util.Log.i("AuroraStemQA", "12 seconds separated in $elapsed ms")
        instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stemElapsedMs", elapsed.toString()); putString("stemFrames", ((v.size - 44) / 4).toString()) })
        val bus = MixController()
        val clips = listOf(MixClip(song = source, stem = StemMode.VOCALS, stemUri = stems.vocals),
            MixClip(song = source, stem = StemMode.BACKING, stemUri = stems.backing))
        main { mix = MixPlayer(context, MixProject(clips = clips), DefaultMediaSourceFactory(DefaultDataSource.Factory(context)), bus, { null }); mix!!.play() }
        await("separated stems play") { bus.state.value.positionSec > .4f && !bus.state.value.buffering }
        }
    }

    @Test fun testSonicDiscoveryScansStreamsAndSharesMixAnalysisAcrossReopens() = runBlocking {
        val tone = wav()
        TestHttp(tone.readBytes()).use { server ->
            server.gate.set(true)
            val songs = (0..2).map { song(tone, "qa-sonic-$it").copy(streamUrl = "http://127.0.0.1:${server.port}/tone.wav") }
            val owner = "qa-sonic-${System.nanoTime()}"
            val store = com.aurora.music.data.SonicStore(context)
            store.selectAccount(owner)
            val analyzer = MixAnalyzer(context, { null }) { account, id, vector -> store.put(id, vector, account) }
            val engine = com.aurora.music.data.SonicEngine(store, { songs }, { owner }, analyzer)
            val downloads = (context.applicationContext as AuroraApplication).container.downloadManager.downloads.value.size
            try {
                engine.scan()
                await("all streamed Sonic tracks analyzed", 45) { engine.progress.value.done == 3 && !engine.progress.value.running }
                assertEquals(0, engine.progress.value.failed)
                assertEquals(3, store.count.value)
                for (song in songs) {
                    val result = analyzer.cached(owner, song)!!
                    assertEquals(com.aurora.music.data.SonicFeatures.DIMS, result.sonic.size)
                    assertArrayEquals(result.sonic.toFloatArray(), store.get(song.id)!!, .000001f)
                    assertNotNull(engine.keyInfo(song.id))
                }
                store.selectAccount("unrelated-server")
                assertNull(store.get(songs[0].id))
                val restored = com.aurora.music.data.SonicStore(context)
                restored.selectAccount(owner)
                assertEquals(3, restored.count.value)
                assertEquals(downloads, (context.applicationContext as AuroraApplication).container.downloadManager.downloads.value.size)
            } finally { engine.cancel() }
        }
    }

    @Test fun testRemoteBufferingFreezesAllTracksAndAnalysisNeedsNoDownload() {
        val tone = wav()
        TestHttp(tone.readBytes()).use { server ->
            val local = song(tone)
            val remote = local.copy(id = "qa-remote", streamUrl = "http://127.0.0.1:${server.port}/slow.wav")
            server.gate.set(false)
            val bus = MixController()
            main {
                mix = MixPlayer(context, MixProject(clips = listOf(MixClip(song = local), MixClip(song = remote))),
                    DefaultMediaSourceFactory(DefaultDataSource.Factory(context)), bus, { null })
                mix!!.play()
            }
            SystemClock.sleep(700)
            assertTrue(bus.state.value.buffering)
            assertTrue(bus.state.value.positionSec < 0.1f)
            val players = decks()
            main { assertTrue(players.none { it.isPlaying }) }
            server.gate.set(true)
            await("stream becomes ready", 15) { bus.state.value.positionSec > 0.5f && !bus.state.value.buffering }
            main { mix!!.pause() }
            val result = runBlocking { MixAnalyzer(context, { null }).analyze("qa-${System.nanoTime()}", remote) {} }
            assertTrue(result.peaks.size > 100)
            assertEquals(12f, result.seconds, 0.2f)
            assertTrue("120 BPM synthetic pulse: ${result.bpm}", abs(result.bpm - 120f) < 6)
            assertTrue(result.confidence > 0.1f)
        }
    }

    @Test fun testEightSimultaneousDecksStayBounded() {
        val tone = song(wav())
        val bus = MixController()
        main {
            mix = MixPlayer(context, MixProject(clips = (0..7).map { MixClip(song = tone.copy(id = "eight-$it"), fadeOutSec = 1f) }),
                DefaultMediaSourceFactory(DefaultDataSource.Factory(context)), bus, { null })
            mix!!.play()
        }
        await("eight decks", 15) { bus.state.value.positionSec > 0.8f }
        val players = decks()
        main {
            assertEquals(8, players.size)
            assertTrue(players.all { it.isPlaying })
            assertTrue(players.sumOf { it.volume.toDouble() } <= 0.9)
            mix!!.sleepFade(250)
        }
        await("sleep fade stops all decks") { !bus.state.value.playing }
        main { assertTrue(players.none { it.isPlaying }) }
    }

    @Test fun testBadSourceStopsAllDecksWithAnError() {
        val tone = song(wav())
        val bus = MixController()
        main {
            mix = MixPlayer(context, MixProject(clips = listOf(MixClip(song = tone), MixClip(song = tone.copy(id = "missing", streamUrl = "file:///does-not-exist.wav")))),
                DefaultMediaSourceFactory(DefaultDataSource.Factory(context)), bus, { null })
            mix!!.play()
        }
        // A denied audio-focus request also has a bus message; require a real source error.
        await("source error") { main { mix?.playerError != null } }
        assertFalse(bus.state.value.playing)
        val players = decks()
        main { assertTrue(players.none { it.isPlaying }) }
    }

    @Test fun testSavedMixRoundTripAndAccountIsolation() = runBlocking {
        val store = MixStore(context)
        val a = "qa-${System.nanoTime()}"
        val tone = song(wav())
        val project = MixProject(name = "Device QA", clips = listOf(MixClip(song = tone, cueInSec = 1.2f, cueOutSec = 9.3f,
            bassDb = -6f, speed = 1.1f, curve = FadeCurve.POWER, solo = true), MixClip(song = tone.copy(id = "two"), startSec = 6f)))
        try {
            store.save(a, project)
            val loaded = store.list(a).single()
            assertEquals(project.normalized(), loaded.copy(clips = loaded.clips.mapIndexed { i, c -> c.copy(song = project.clips[i].song) }))
            assertTrue(store.list("$a-other").isEmpty())
        } finally { store.delete(a, project.id) }
    }

    @Test fun testServiceCrossfadePausesAndKeepsQueue() {
        val app = context.applicationContext as AuroraApplication
        val old = runBlocking { app.container.settingsStore.playbackPrefs.first() }
        // Exercise the standard Android path; leave an exclusive USB session untouched.
        if (old.bitPerfectUsb) return
        val future = main { MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
        val controller = future.get(10, TimeUnit.SECONDS)
        val previous = main { (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it) } }
        val oldIndex = main { controller.currentMediaItemIndex }
        val oldPosition = main { controller.currentPosition }
        val tone = wav()
        try {
            runBlocking { app.container.settingsStore.setCrossfade(3) }
            SystemClock.sleep(150)
            val items = (0..2).map { MediaItem.Builder().setMediaId("mix-qa-$it").setUri(tone.toURI().toString()).build() }
            main { controller.setMediaItems(items); controller.prepare(); controller.play() }
            await("crossfade handoff", 16) { main { controller.currentMediaItemIndex == 1 } }
            main { assertEquals(3, controller.mediaItemCount); controller.pause() }
            val paused = main { controller.currentPosition }
            SystemClock.sleep(500)
            main { assertTrue(abs(controller.currentPosition - paused) < 100); controller.play() }
            await("fade completion") { main { controller.currentPosition > 3500 } }
            main { controller.seekTo(2, 0) }
            await("manual skip recovers") { main { controller.currentMediaItemIndex == 2 && controller.isPlaying } }
        } finally {
            main {
                controller.pause()
                if (previous.isNotEmpty()) { controller.setMediaItems(previous, oldIndex.coerceIn(previous.indices), oldPosition); controller.prepare() }
                else controller.clearMediaItems()
                controller.release()
            }
            runBlocking { app.container.settingsStore.setCrossfade(old.crossfadeSec) }
        }
    }

    @Test fun testMixSessionTransportReturnsToNormalPlayback() {
        val app = context.applicationContext as AuroraApplication
        org.junit.Assume.assumeFalse(runBlocking { app.container.settingsStore.playbackPrefs.first().bitPerfectUsb })
        val tone = song(wav())
        val project = MixProject(name = "Session QA", clips = listOf(MixClip(song = tone), MixClip(song = tone.copy(id = "session-two"))))
        val future = main { MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
        val c = future.get(10, TimeUnit.SECONDS)
        try {
            main {
                app.container.mixController.pendingProject = project
                app.container.mixController.pendingPosition = 0f
                app.container.mixController.pendingTracklist = false
                context.startService(android.content.Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_MIX))
            }
            await("session switched to mix") { main { c.currentMediaItem?.mediaId?.startsWith("aurora-mix:") == true && c.isPlaying } }
            assertEquals(project.id, app.container.mixController.activeProject?.id)
            main { assertEquals("Session QA", c.mediaMetadata.title.toString()); c.pause() }
            await("session pause reaches all decks") { !app.container.mixController.state.value.playing }
            main { c.seekTo(3000); c.play() }
            await("session seek") { app.container.mixController.state.value.positionSec > 3.2f }
            main { c.sendCustomCommand(androidx.media3.session.SessionCommand(PlaybackService.CMD_EXIT_MIX, android.os.Bundle.EMPTY), android.os.Bundle.EMPTY) }
            await("queue player restored") { main { c.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS) } }
            assertNull(app.container.mixController.activeProject)
            main {
                c.setMediaItem(MediaItem.Builder().setMediaId("mix-qa-restored").setUri(tone.streamUrl).build())
                c.prepare(); c.play()
            }
            await("ordinary song plays after mix") { main { c.currentMediaItem?.mediaId == "mix-qa-restored" && c.isPlaying } }
        } finally {
            main {
                c.sendCustomCommand(androidx.media3.session.SessionCommand(PlaybackService.CMD_EXIT_MIX, android.os.Bundle.EMPTY), android.os.Bundle.EMPTY)
                c.pause(); c.release()
            }
        }
    }

    @Test fun testCollectionMixUsesNormalPlayerTracksAndControls() {
        val app = context.applicationContext as AuroraApplication
        org.junit.Assume.assumeFalse(runBlocking { app.container.settingsStore.playbackPrefs.first().bitPerfectUsb })
        val source = song(wav())
        val songs = listOf(source.copy(id = "normal-mix-a", title = "First track"),
            source.copy(id = "normal-mix-b", title = "Second track"),
            source.copy(id = "normal-mix-a", title = "First track"))
        val project = AutoMixPlanner.plan("Normal player QA", songs, emptyMap())
        val future = main { MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
        val c = future.get(10, TimeUnit.SECONDS)
        val store = androidx.lifecycle.ViewModelStore()
        val vm = main { com.aurora.music.viewmodel.PlayerViewModel(app).also { store.put("player", it) } }
        try {
            main {
                app.container.mixController.pendingProject = project
                app.container.mixController.pendingPosition = 0f
                app.container.mixController.pendingTracklist = true
                context.startService(android.content.Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_MIX))
            }
            await("normal player receives collection mix") { vm.state.value.current.id == songs[0].id && vm.state.value.isPlaying }
            main {
                assertEquals(songs.map { it.id }, vm.state.value.queue.map { it.id })
                assertEquals(3, c.mediaItemCount)
                assertTrue(vm.state.value.isMix)
                assertEquals(12_000L, c.duration)
                assertEquals("First track", c.mediaMetadata.title.toString())
                vm.seekTo(.79f)
            }
            await("automatic transition updates normal player") { vm.state.value.currentIndex == 1 }
            assertEquals("Second track", vm.state.value.current.title)
            assertTrue(vm.state.value.positionSec < 5f)
            main { vm.togglePlay() }
            await("normal pause stops mix") { !app.container.mixController.state.value.playing }
            val paused = app.container.mixController.state.value.positionSec
            SystemClock.sleep(200)
            assertEquals(paused, app.container.mixController.state.value.positionSec, .04f)
            main { vm.next() }
            await("next preserves duplicate occurrence") { vm.state.value.currentIndex == 2 }
            main { vm.previous() }
            await("previous returns to second track") { vm.state.value.currentIndex == 1 }
            main { vm.seekTo(.5f) }
            await("source relative seek") {
                abs(vm.state.value.positionSec - 6f) < .2f &&
                    abs(app.container.mixController.state.value.positionSec - (project.clips[1].startSec + 6f)) < .1f
            }
            assertEquals(project.clips[1].startSec + 6f, app.container.mixController.state.value.positionSec, .1f)
            main { vm.jumpTo(0); c.repeatMode = Player.REPEAT_MODE_ONE; vm.seekTo(.65f) }
            await("repeat one returns to track start") { app.container.mixController.state.value.positionSec < 2f && vm.state.value.currentIndex == 0 }
            main { c.repeatMode = Player.REPEAT_MODE_OFF; vm.play(source.copy(id = "normal-after-mix")) }
            await("ordinary play exits mixed queue") { vm.state.value.current.id == "normal-after-mix" && vm.state.value.isPlaying }
            assertFalse(vm.state.value.isMix)
            assertNull(app.container.mixController.activeProject)
        } finally {
            main {
                c.sendCustomCommand(androidx.media3.session.SessionCommand(PlaybackService.CMD_EXIT_MIX, android.os.Bundle.EMPTY), android.os.Bundle.EMPTY)
                c.pause(); c.release(); store.clear()
                app.container.mixController.pendingTracklist = false
            }
        }
    }

    private class TestHttp(private val data: ByteArray) : AutoCloseable {
        val gate = AtomicBoolean(true)
        private val running = AtomicBoolean(true)
        private val socket = ServerSocket(0)
        val port get() = socket.localPort
        private val workers = Executors.newCachedThreadPool()
        init { workers.execute {
            while (running.get()) runCatching {
                val client = socket.accept()
                workers.execute { runCatching { client.use { c ->
                    val input = c.getInputStream().bufferedReader()
                    var path = input.readLine().orEmpty()
                    var offset = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Range:", true)) offset = line.substringAfter("bytes=").substringBefore('-').toIntOrNull() ?: 0
                    }
                    val length = (data.size - offset).coerceAtLeast(0)
                    val out = c.getOutputStream()
                    val headers = if (offset > 0) "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $offset-${data.size - 1}/${data.size}\r\n"
                        else "HTTP/1.1 200 OK\r\n"
                    out.write((headers + "Content-Type: audio/wav\r\nAccept-Ranges: bytes\r\nContent-Length: $length\r\nConnection: close\r\n\r\n").toByteArray())
                    out.flush()
                    while (running.get() && !gate.get() && path.contains("slow")) Thread.sleep(25)
                    if (running.get()) { out.write(data, offset, length); out.flush() }
                } } }
            }
        } }
        override fun close() { running.set(false); socket.close(); workers.shutdownNow() }
    }
}
