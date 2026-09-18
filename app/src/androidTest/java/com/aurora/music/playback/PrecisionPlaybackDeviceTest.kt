package com.aurora.music.playback

import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.AuroraApplication
import com.aurora.music.MainActivity
import com.aurora.music.data.AudioMeasurements
import com.aurora.music.data.AudioPrefs
import com.aurora.music.data.DspMode
import com.aurora.music.data.PrefsBackup
import com.aurora.music.data.ProcessingRack
import com.aurora.music.data.ProcessingRackNode
import com.aurora.music.data.RackNodeKind
import com.aurora.music.mix.MixAudioConfig
import com.aurora.music.mix.MixClip
import com.aurora.music.mix.MixController
import com.aurora.music.mix.MixPlayer
import com.aurora.music.mix.MixProject
import com.aurora.music.model.Song
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/** Run with -e precisionOutput true to exercise the opt-in decoder-side sink through the service. */
class PrecisionPlaybackDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (context.applicationContext as AuroraApplication).container
    private var foregroundActivity: MainActivity? = null
    private val files = mutableListOf<File>()
    private val preampGain = 10.0.pow(-6.0 / 20.0)
    private val preciseOutput get() = InstrumentationRegistry.getArguments().getString("precisionOutput") == "true"
    private val rackOutput get() = InstrumentationRegistry.getArguments().getString("rackOutput") == "true"
    private var originalHighRes: Boolean? = null
    private var originalPrivateSession: Boolean? = null
    private var originalQueueAccount: String? = null
    private var originalSavedQueue: com.aurora.music.data.SavedQueue? = null
    private var originalActivityHistory: List<com.aurora.music.data.PlayEvent>? = null
    private var originalRouteRules: String? = null
    private var routeRulesCaptured = false

    @Before fun keepTargetForegroundForAudioFocus() {
        runBlocking {
            originalRouteRules = container.settingsStore.exportPrefs().strings[com.aurora.music.data.routes.ProcessingRouteCodec.PREFERENCE_KEY]
            routeRulesCaptured = true
            container.settingsStore.setRouteRulesEnabled(false).getOrThrow()
            originalPrivateSession = container.settingsStore.privateSession.first()
            container.settingsStore.setPrivateSession(true)
            container.sessionReady.first { it != null }
            originalQueueAccount = container.currentAccountKey()
            originalSavedQueue = originalQueueAccount?.takeIf { it.isNotBlank() }?.let(container.queueStore::get)
            originalActivityHistory = container.playHistory.snapshot()
            originalHighRes = container.settingsStore.playbackPrefs.first().preferHighRes
            container.settingsStore.setPreferHighRes(preciseOutput)
        }
        // Instrumentation restarts the app process. Android 15 needs this activity resumed
        // after the runner starts before the service can request media audio focus.
        foregroundActivity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        instrumentation.waitForIdleSync()
        await("foreground activity") {
            main { foregroundActivity?.let {
                it.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && it.hasWindowFocus()
            } == true }
        }
    }

    @After fun removeFixturesAndFinishActivity() {
        try {
            files.forEach { it.delete() }
            files.clear()
            val activity = foregroundActivity
            main { activity?.finish(); foregroundActivity = null }
            instrumentation.waitForIdleSync()
            if (activity != null) await("fixture activity finished") { main { activity.isDestroyed } }
        } finally {
            try {
                runBlocking {
                    originalQueueAccount?.takeIf { it.isNotBlank() }?.let { container.queueStore.restoreAccount(it, originalSavedQueue) }
                    originalActivityHistory?.let { container.playHistory.restoreBackup(it) }
                }
                originalQueueAccount = null; originalSavedQueue = null; originalActivityHistory = null
            } finally {
                try {
                    originalHighRes?.let { runBlocking { container.settingsStore.setPreferHighRes(it) } }
                    originalHighRes = null
                } finally {
                    if (routeRulesCaptured) runBlocking {
                        val store = container.settingsStore
                        val current = store.exportPrefs()
                        val key = com.aurora.music.data.routes.ProcessingRouteCodec.PREFERENCE_KEY
                        val strings = originalRouteRules?.let { current.strings + (key to it) } ?: (current.strings - key)
                        store.restoreBackupPrefs(current.copy(strings = strings)).getOrThrow()
                        routeRulesCaptured = false
                    }
                    // Keep reporting suppressed until fixture playback has been replaced and its
                    // activity/ViewModel callbacks have finished; local history rollback cannot
                    // undo a remote scrobble or now-playing submission.
                    originalPrivateSession?.let { runBlocking { container.settingsStore.setPrivateSession(it) } }
                    originalPrivateSession = null
                }
            }
        }
    }

    @Test fun liveConvolutionReplacementFormatChangeAndSeekKeepMeasuredStereoGain() {
        withProcessingFixture(crossfadeSeconds = 0) { controller, firstImpulse ->
            val replacement = impulse(left = 0.25, right = 0.5)
            val tracks = listOf(tone(44_100, 30), tone(48_000, 30))
            val items = tracks.mapIndexed { index, file -> item("r1c-format-$index", file) }
            val startedAt = System.nanoTime()
            main {
                controller.setMediaItems(items)
                controller.prepare()
                controller.play()
            }
            val initial = awaitLevels(controller, 44_100, 0.5, 0.25, startedAt)
            assertFalse(initial.overlappingPlayers)
            assertProcessingEvidence()
            assertQueue(controller, items)
            File(context.getExternalFilesDir(null), "r1c-signal-path-initial.txt")
                .writeText(container.signalPath.value.toDiagnosticReport())

            val replacedAt = System.nanoTime()
            runBlocking {
                container.settingsStore.setDspConvIr(replacement.absolutePath, "Precision fixture B")
            }
            awaitLevels(controller, 44_100, 0.25, 0.5, replacedAt)
            assertEquals(replacement.absolutePath, runBlocking {
                container.settingsStore.audioPrefs.first().dspConvIrPath
            })
            assertNotEquals(firstImpulse.absolutePath, replacement.absolutePath)
            assertQueue(controller, items)

            val formatChangedAt = System.nanoTime()
            main { controller.seekTo(1, 2_000) }
            await("48 kHz track and seek position", controller) {
                main { controller.currentMediaItemIndex == 1 && controller.currentPosition >= 2_000 }
            }
            awaitLevels(controller, 48_000, 0.25, 0.5, formatChangedAt)
            assertEquals(48_000, container.signalPath.value.decoder.format?.rateHz)
            assertQueue(controller, items)

            // A second seek exercises flush with the same format and the already-loaded IR.
            val soughtAt = System.nanoTime()
            main { controller.seekTo(1, 9_000) }
            awaitLevels(controller, 48_000, 0.25, 0.5, soughtAt)
            await("same-format seek position", controller) { main { controller.currentPosition >= 9_000 } }
            pauseAndResume(controller)
            assertQueue(controller, items)
            assertProcessingEvidence()
            File(context.getExternalFilesDir(null), "r1c-signal-path-format-change.txt")
                .writeText(container.signalPath.value.toDiagnosticReport())
        }
    }

    @Test fun precisionOutputKeepsSpeedAndFallsBackWhenSilenceSkippingIsEnabled() {
        assumeTrue("Opt-in precision run", preciseOutput)
        withProcessingFixture(crossfadeSeconds = 0) { controller, _ ->
            val items = listOf(item("r1d-96k", tone(96_000, 30)))
            main { controller.setMediaItems(items); controller.prepare(); controller.play() }
            awaitLevels(controller, 96_000, 0.5, 0.25, 0)
            assertProcessingEvidence()
            main { controller.setPlaybackSpeed(1.25f) }
            await("platform speed remains active", controller) {
                main { controller.isPlaying && abs(controller.playbackParameters.speed - 1.25f) < 0.001f }
            }
            // Controller parameters can publish before buffered AudioTrack frames adopt the
            // new rate. Measure a full steady interval against elapsed time, not that first edge.
            var measuredRate = 0.0
            await("playback clock settles at 1.25x (last measured $measuredRate)", controller) {
                val started = SystemClock.elapsedRealtime()
                val position = main { controller.currentPosition }
                SystemClock.sleep(1_000)
                measuredRate = (main { controller.currentPosition } - position).toDouble() /
                    (SystemClock.elapsedRealtime() - started)
                measuredRate in 1.10..1.40
            }
            pauseAndResume(controller)
            File(context.getExternalFilesDir(null), "r1d-signal-path-96k.txt")
                .writeText(container.signalPath.value.toDiagnosticReport())
            runBlocking { container.settingsStore.setSkipSilence(true) }
            await("silence skipping chooses compatibility output", controller) {
                val path = container.signalPath.value
                path.processing.detail.contains("Compatibility PCM16") &&
                    path.processing.detail.contains("Silence skipping enabled")
            }
            awaitLevels(controller, 96_000, 0.5, 0.25, 0)
            File(context.getExternalFilesDir(null), "r1d-signal-path-skip-fallback.txt")
                .writeText(container.signalPath.value.toDiagnosticReport())
            assertQueue(controller, items)
        }
    }

    @Test fun crossfadeHandoffRetainsCustomAndConvolutionProcessingAndQueue() {
        withProcessingFixture(crossfadeSeconds = 3) { controller, _ ->
            val file = tone(44_100, 12)
            val items = (0..2).map { item("r1c-crossfade-$it", file) }
            val startedAt = System.nanoTime()
            main {
                controller.setMediaItems(items)
                controller.prepare()
                controller.play()
            }
            awaitLevels(controller, 44_100, 0.5, 0.25, startedAt)
            // Leave enough time to prepare the incoming deck, then observe the overlap itself.
            main { controller.seekTo(0, 7_000) }
            await("processed incoming player becomes primary during crossfade", controller) {
                main { controller.currentMediaItemIndex == 1 && controller.isPlaying } &&
                    container.signalPath.value.measurements?.overlappingPlayers == true
            }
            // Measurements belong to the incoming primary player, before its fade volume.
            awaitLevels(controller, 44_100, 0.5, 0.25, sinceNanos = 0)
            assertProcessingEvidence()
            assertQueue(controller, items)
            File(context.getExternalFilesDir(null), "r1c-signal-path-crossfade.txt")
                .writeText(container.signalPath.value.toDiagnosticReport())
            pauseAndResume(controller)
            await("crossfade finishes after resume", controller) {
                main { controller.currentMediaItemIndex == 1 && controller.currentPosition > 3_500 && controller.isPlaying } &&
                    container.signalPath.value.measurements?.overlappingPlayers == false
            }
            val skippedAt = System.nanoTime()
            main { controller.seekTo(2, 1_000) }
            awaitLevels(controller, 44_100, 0.5, 0.25, skippedAt)
            assertQueue(controller, items)
            main { assertEquals(2, controller.currentMediaItemIndex) }
        }
    }

    @Test fun liveRackWetBypassAndDisableKeepQueueAndMeasuredGain() {
        assumeTrue("Explicit rack run", rackOutput)
        withProcessingFixture(crossfadeSeconds = 0) { controller, _ ->
            val items = listOf(item("r1e-rack-edits", tone(48_000, 45)))
            main { controller.setMediaItems(items); controller.prepare(); controller.play() }
            awaitLevels(controller, 48_000, 0.5, 0.25, 0)
            assertProcessingEvidence()
            val initial = fixtureRack()
            val wetGain = (1.0 + preampGain) / 2.0
            var changedAt = System.nanoTime()
            runBlocking { container.settingsStore.setProcessingRack(initial.copy(nodes = initial.nodes.map {
                if (it.kind == RackNodeKind.GAIN) it.copy(wet = 0.5f) else it
            })).getOrThrow() }
            awaitLevels(controller, 48_000, 0.5 * wetGain / preampGain, 0.25 * wetGain / preampGain, changedAt)
            changedAt = System.nanoTime()
            runBlocking { container.settingsStore.setProcessingRack(initial.copy(nodes = initial.nodes.map {
                if (it.kind == RackNodeKind.GAIN) it.copy(bypass = true) else it
            })).getOrThrow() }
            awaitLevels(controller, 48_000, 0.5 / preampGain, 0.25 / preampGain, changedAt)
            changedAt = System.nanoTime()
            runBlocking { container.settingsStore.setProcessingRack(initial.copy(enabled = false)).getOrThrow() }
            awaitLevels(controller, 48_000, 0.5, 0.25, changedAt)
            await("rack disables into standard effects", controller) {
                val detail = container.signalPath.value.processing.detail
                !detail.contains("Serial rack") && detail.contains("Custom")
            }
            assertQueue(controller, items)
            pauseAndResume(controller)
        }
    }

    @Test fun customShuffleSurvivesCrossfadeAndUnshuffleRestoresTheOriginalOrder() {
        withProcessingFixture(crossfadeSeconds = 3) { controller, _ ->
            val source = tone(44_100, 12)
            val original = (0..3).map { item("shuffle-crossfade-$it", source) }
            val shuffled = listOf(original[0], original[2], original[3], original[1])
            main { controller.setMediaItems(shuffled) }
            // This is the service's normal shuffle-from-start contract: an already reordered
            // queue plus its original IDs, not ExoPlayer's independent native random shuffle.
            setFixtureShuffle(controller, true, original.map { it.mediaId })
            await("custom shuffle enabled", controller) { main { controller.shuffleModeEnabled } }
            val orderedIds = main { (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId } }
            assertEquals(shuffled.map { it.mediaId }, orderedIds)
            main { controller.prepare(); controller.play() }
            awaitLevels(controller, 44_100, .5, .25, 0)
            main { controller.seekTo(0, 7_000) }
            await("shuffled incoming player becomes primary", controller) {
                main { controller.currentMediaItemIndex == 1 && controller.isPlaying } &&
                    container.signalPath.value.measurements?.overlappingPlayers == true
            }
            main {
                assertTrue("Crossfade preserves the service shuffle flag", controller.shuffleModeEnabled)
                assertEquals(orderedIds, (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId })
                assertEquals(shuffled[1].mediaId, controller.currentMediaItem?.mediaId)
            }
            setFixtureShuffle(controller, false)
            await("custom shuffle disabled after handoff", controller) { main { !controller.shuffleModeEnabled } }
            assertQueue(controller, original)
            main {
                assertEquals("Unshuffle keeps the current track", shuffled[1].mediaId, controller.currentMediaItem?.mediaId)
                controller.pause()
            }
        }
    }

    @Test fun bothMixDecksProcessCustomEffectsAndConvolutionThroughSeekAndResume() {
        withProcessingFixture(crossfadeSeconds = 0) { controller, impulseFile ->
            val file = tone(44_100, 12)
            val ordinaryQueue = listOf(item("r1c-after-mix", file))
            main { controller.setMediaItems(ordinaryQueue) }
            val song = Song("r1c-mix-first", "Precision fixture", "Aurora QA", "", "", 12,
                streamUrl = file.toURI().toString())
            val project = MixProject(clips = listOf(MixClip(song = song),
                MixClip(song = song.copy(id = "r1c-mix-second"))))
            val bus = MixController()
            val config = MixAudioConfig(
                params = DspParams(preampDb = -6f, limiterEnabled = false), mode = DspMode.CUSTOM,
                impulse = requireNotNull(ConvolutionProcessor.loadWav(impulseFile)), convolution = true,
                rack = if (rackOutput) fixtureRack() else null,
            )
            val mix = main {
                MixPlayer(context, project, DefaultMediaSourceFactory(DefaultDataSource.Factory(context)),
                    bus, { null }, audioConfig = config).also { it.play() }
            }
            try {
                val decks = main {
                    val field = MixPlayer::class.java.getDeclaredField("decks").apply { isAccessible = true }
                    (field.get(mix) as List<*>).map { requireNotNull(it) }.map { deck ->
                        fun field(name: String): Any = requireNotNull(deck.javaClass.getDeclaredField(name)
                            .apply { isAccessible = true }.get(deck))
                        MixDeckEvidence(field("player") as ExoPlayer, field("dsp") as AuroraDspProcessor,
                            (field("globalProcessor") as PrecisionRackAudioProcessor).engine)
                    }
                }
                assertEquals(2, decks.size)
                fun bothDecksProcess() = main {
                    assertNull("Mix renderer error", mix.playerError)
                    decks.all { it.player.isPlaying && it.clipDsp.enabled && it.clipDsp.processingActive &&
                        it.global.enabled && (if (rackOutput) it.global.rackActive else
                            it.global.processingActive && it.global.convolutionProcessingActive) }
                }
                await("both Mix decks process Custom DSP and convolution") {
                    bus.state.value.playing && !bus.state.value.buffering && bus.state.value.positionSec > 0.4f && bothDecksProcess()
                }
                main { mix.pause() }
                await("Mix pause reaches both decks") { main { decks.none { it.player.isPlaying } } && !bus.state.value.playing }
                val paused = bus.state.value.positionSec
                SystemClock.sleep(300)
                assertEquals(paused, bus.state.value.positionSec, 0.03f)
                main { mix.seekTo(4_000); mix.play() }
                await("Mix seek resumes both processed decks") { bus.state.value.positionSec > 4.3f && bothDecksProcess() }
                main {
                    decks.forEach {
                        assertNull(it.player.playerError)
                        assertTrue("Deck follows Mix seek", abs(it.player.currentPosition - bus.state.value.positionSec * 1_000) < 250)
                    }
                }
                assertNull(bus.state.value.error)
                assertQueue(controller, ordinaryQueue)
            } finally { main { mix.release() } }
            assertNull(bus.activeProject)
            val resumedAt = System.nanoTime()
            main { controller.prepare(); controller.play() }
            awaitLevels(controller, 44_100, 0.5, 0.25, resumedAt)
            assertQueue(controller, ordinaryQueue)
            assertProcessingEvidence()
        }
    }

    private data class MixDeckEvidence(val player: ExoPlayer, val clipDsp: AuroraDspProcessor,
        val global: PrecisionBlockProcessor)

    private fun fixtureRack() = ProcessingRack(enabled = true, name = "Service fixture", nodes = listOf(
        ProcessingRackNode("00000000-0000-0000-0000-000000000001", "Input gain", RackNodeKind.GAIN, audio = AudioPrefs(dspPreampDb = -6f)),
        ProcessingRackNode("00000000-0000-0000-0000-000000000002", "Stereo IR", RackNodeKind.CONVOLUTION)))

    internal fun withProcessingFixture(crossfadeSeconds: Int, block: (MediaController, File) -> Unit) {
        val store = container.settingsStore
        val original = runBlocking { store.processingSettings.first() }
        val originalAutoEq = runBlocking { store.autoEqAutoSwitch.first() }
        val originalHistory = container.playHistory.snapshot()
        assumeFalse("Exclusive USB needs its own device validation", original.playback.bitPerfectUsb)
        assumeFalse("Keep an active Mix session untouched", container.mixController.activeProject != null)
        val controller = main {
            MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        }.get(12, TimeUnit.SECONDS)
        if (main { controller.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE }) {
            main { controller.release() }
            assumeFalse("Keep remote playback untouched", true)
        }
        val shuffled = main { controller.shuffleModeEnabled }
        val queue = main { (0 until controller.mediaItemCount).map(controller::getMediaItemAt) }
        val index = main { controller.currentMediaItemIndex }
        val position = main { controller.currentPosition }
        val repeat = main { controller.repeatMode }
        val playing = main { controller.playWhenReady }
        val playbackState = main { controller.playbackState }
        val parameters = main { controller.playbackParameters }
        var unshuffledIds: List<String>? = null
        val errors = CopyOnWriteArrayList<Int>()
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { errors += error.errorCode }
        }
        try {
            main { controller.pause(); controller.addListener(listener) }
            if (shuffled) {
                // Observe the service-owned original order through its normal unshuffle command,
                // then restore both the physical shuffled queue and that original order in finally.
                setFixtureShuffle(controller, false)
                unshuffledIds = main { (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId } }
            }
            val ir = impulse(left = 0.5, right = 0.25)
            runBlocking {
                store.setAutoEqAutoSwitch(false)
                writeAudio(AudioPrefs(dspMode = DspMode.CUSTOM, dspPreampDb = -6f,
                    dspLimiterEnabled = false, dspConvEnabled = true,
                    dspConvIrPath = ir.absolutePath, dspConvIrName = "Precision fixture A"))
                store.setMono(false)
                store.setSkipSilence(false)
                store.setCrossfade(crossfadeSeconds)
                store.setProcessingRack(if (rackOutput) fixtureRack() else original.rack.copy(enabled = false)).getOrThrow()
            }
            main { controller.setPlaybackSpeed(1f); controller.repeatMode = Player.REPEAT_MODE_OFF }
            block(controller, ir)
            main { assertNull("No renderer error", controller.playerError) }
            assertTrue("Playback error codes: $errors", errors.isEmpty())
        } finally {
            try {
            main { controller.pause(); controller.removeListener(listener) }
            // Fixtures start unshuffled. Clear any fixture-owned original-order snapshot before
            // restoring the user's queue, even if an assertion failed during the shuffle test.
            setFixtureShuffle(controller, false)
            runBlocking {
                store.setProcessingRack(original.rack).getOrThrow()
                writeAudio(original.audio)
                store.setMono(original.playback.monoAudio)
                store.setSkipSilence(original.playback.skipSilence)
                store.setCrossfade(original.playback.crossfadeSec)
                store.setAutoEqAutoSwitch(originalAutoEq)
                container.playHistory.restoreBackup(originalHistory)
            }
            main {
                controller.playbackParameters = parameters
                controller.repeatMode = repeat
                if (queue.isNotEmpty()) {
                    controller.setMediaItems(queue, index.coerceIn(queue.indices), position)
                    if (playbackState != Player.STATE_IDLE) controller.prepare()
                    else controller.stop()
                } else controller.clearMediaItems()
            }
            if (shuffled) setFixtureShuffle(controller, true, requireNotNull(unshuffledIds))
            main {
                assertEquals("Fixture restores the user's physical queue", queue.map { it.mediaId },
                    (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId })
                assertEquals("Fixture restores the user's shuffle flag", shuffled, controller.shuffleModeEnabled)
                controller.playWhenReady = playing
            }
            } finally { main { controller.release() } }
        }
    }

    private fun awaitLevels(controller: MediaController, rate: Int, leftIr: Double, rightIr: Double,
        sinceNanos: Long): AudioMeasurements {
        val expectedLeft = preampGain * leftIr
        val expectedRight = preampGain * rightIr
        var accepted: AudioMeasurements? = null
        await("$rate Hz processed L/R RMS gains $expectedLeft / $expectedRight", controller) {
            val measurement = container.signalPath.value.measurements
            val before = measurement?.before
            val after = measurement?.after
            val matches = measurement != null && measurement.playing && measurement.afterAvailable && before != null && after != null &&
                before.sampleRate == rate && after.sampleRate == rate && before.channels == 2 && after.channels == 2 &&
                before.measuredAtNanos > sinceNanos && after.measuredAtNanos > sinceNanos &&
                before.leftRms > 0.1 && before.rightRms > 0.05 &&
                abs(after.leftRms / before.leftRms - expectedLeft) < 0.008 &&
                abs(after.rightRms / before.rightRms - expectedRight) < 0.008
            if (matches) accepted = measurement
            matches
        }
        return requireNotNull(accepted).also {
            val after = requireNotNull(it.after)
            assertEquals(0L, after.invalidSamples)
            assertEquals(0L, after.fullScaleSamples)
        }
    }

    private fun assertProcessingEvidence() {
        if (preciseOutput) await("observed float32 AudioTrack configuration") {
            // PCM meters and AudioTrack analytics arrive independently. A previous track's
            // asynchronous release can precede the new initialization notification.
            val observed = container.signalPath.value.outputStage.format
            observed?.encoding == "float PCM" && observed.bitDepth == 32
        }
        val detail = container.signalPath.value.processing.detail
        assertTrue(detail, detail.contains("binary64", ignoreCase = true))
        if (preciseOutput) {
            assertTrue(detail, detail.contains("float32 Android output"))
            assertEquals("float PCM", container.signalPath.value.outputStage.format?.encoding)
            assertEquals(32, container.signalPath.value.outputStage.format?.bitDepth)
        } else assertTrue(detail, detail.contains("PCM16"))
        if (rackOutput) {
            assertTrue(detail, detail.contains("Serial rack"))
            assertTrue(detail, detail.contains("GAIN"))
            assertTrue(detail, detail.contains("CONVOLUTION"))
        } else {
            assertTrue(detail, detail.contains("Custom"))
            assertTrue(detail, detail.contains("Convolution"))
        }
    }

    private fun assertQueue(controller: MediaController, expected: List<MediaItem>) = main {
        assertEquals(expected.map { it.mediaId }, (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId })
    }

    private fun setFixtureShuffle(controller: MediaController, enabled: Boolean, originalIds: List<String>? = null) {
        val extras = android.os.Bundle().apply {
            putInt("target", if (enabled) 1 else 0)
            originalIds?.let { putStringArrayList("order", ArrayList(it)) }
        }
        val result = main {
            controller.sendCustomCommand(androidx.media3.session.SessionCommand(PlaybackService.CMD_SHUFFLE, extras), android.os.Bundle.EMPTY)
        }.get(10, TimeUnit.SECONDS)
        assertEquals("Service shuffle command succeeded", 0, result.resultCode)
        await("service shuffle flag $enabled", controller) { main { controller.shuffleModeEnabled == enabled } }
    }

    private fun pauseAndResume(controller: MediaController) {
        main { controller.pause() }
        await("pause", controller) { main { !controller.isPlaying && !controller.playWhenReady } }
        val paused = main { controller.currentPosition }
        SystemClock.sleep(300)
        main { assertTrue("Pause keeps position", abs(controller.currentPosition - paused) < 100); controller.play() }
        await("resume", controller) { main { controller.isPlaying && controller.currentPosition > paused + 150 } }
    }

    private fun item(id: String, file: File): MediaItem =
        MediaItem.Builder().setMediaId(id).setUri(file.toURI().toString()).build()

    private fun tone(rate: Int, seconds: Int): File = wav("tone-$rate", rate, seconds * rate) { frame, channel ->
        (sin(2 * PI * 1_000 * frame / rate) * if (channel == 0) 8_192 else 4_096).toInt()
    }

    private fun impulse(left: Double, right: Double): File = wav("impulse", if (preciseOutput) 96_000 else 48_000, 16) { frame, channel ->
        // A 48 kHz delta downsampled to 44.1 kHz has no adjacent nonzero taps under
        // the existing linear IR resampler; both stream rates have the same known gain.
        if (frame == 0) ((if (channel == 0) left else right) * 32_768).toInt() else 0
    }

    internal fun wav(name: String, rate: Int, frames: Int, sample: (Int, Int) -> Int): File {
        val file = File(context.cacheDir, "r1c-$name-${System.nanoTime()}.wav")
        files += file
        file.outputStream().buffered().use { output ->
            val bytes = if (preciseOutput) 3 else 2
            val frameBytes = bytes * 2
            fun le(value: Int, bytes: Int) { repeat(bytes) { output.write(value ushr (8 * it) and 255) } }
            output.write("RIFF".toByteArray()); le(36 + frames * frameBytes, 4)
            output.write("WAVEfmt ".toByteArray()); le(16, 4); le(1, 2); le(2, 2)
            le(rate, 4); le(rate * frameBytes, 4); le(frameBytes, 2); le(bytes * 8, 2)
            output.write("data".toByteArray()); le(frames * frameBytes, 4)
            repeat(frames) { frame ->
                le(sample(frame, 0) * if (bytes == 3) 256 else 1, bytes)
                le(sample(frame, 1) * if (bytes == 3) 256 else 1, bytes)
            }
        }
        return file
    }

    internal fun <T> main(block: () -> T): T {
        val task = FutureTask(Callable(block))
        instrumentation.runOnMainSync(task)
        return task.get()
    }

    internal fun await(label: String, controller: MediaController? = null, check: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 12_000
        while (SystemClock.elapsedRealtime() < until) {
            if (check()) return
            controller?.let { main { assertNull("Renderer error while waiting for $label", it.playerError) } }
            SystemClock.sleep(40)
        }
        val status = controller?.let { main {
            "index=${it.currentMediaItemIndex}, position=${it.currentPosition}, state=${it.playbackState}, playing=${it.isPlaying}"
        } }.orEmpty()
        fail("Timed out: $label ($status). ${container.signalPath.value.toDiagnosticReport()}")
    }

    private suspend fun writeAudio(p: AudioPrefs) = container.settingsStore.importPrefs(PrefsBackup(
        strings = mapOf("eq_bands" to p.eqBands.joinToString(","), "dsp_graphic" to p.dspGraphicBands.joinToString(","),
            "dsp_parametric" to p.dspParametric.joinToString(";") { "${it.freqHz}:${it.gainDb}:${it.q}:${it.type}" },
            "dsp_conv_path" to p.dspConvIrPath, "dsp_conv_name" to p.dspConvIrName),
        ints = mapOf("eq_preset" to p.eqPreset, "bass_boost" to p.bassBoost, "virtualizer" to p.virtualizer,
            "loudness_gain" to p.loudnessGain, "replay_gain" to p.replayGain, "dsp_mode" to p.dspMode,
            "dsp_graphic_layout" to p.dspGraphicLayout),
        booleans = mapOf("eq_enabled" to p.eqEnabled, "dsp_limiter" to p.dspLimiterEnabled,
            "dsp_comp" to p.dspCompEnabled, "dsp_conv_enabled" to p.dspConvEnabled),
        floats = mapOf("dsp_preamp" to p.dspPreampDb, "dsp_balance" to p.dspBalance, "dsp_width" to p.dspWidth,
            "dsp_crossfeed" to p.dspCrossfeed, "dsp_ceiling" to p.dspLimiterCeilingDb,
            "dsp_comp_thresh" to p.dspCompThreshDb, "dsp_comp_ratio" to p.dspCompRatio,
            "dsp_conv_makeup" to p.dspConvMakeupDb, "dsp_saturation" to p.dspSaturation,
            "dsp_delay_l" to p.dspDelayLeftMs, "dsp_delay_r" to p.dspDelayRightMs,
            "dsp_trim_l" to p.dspTrimLeftDb, "dsp_trim_r" to p.dspTrimRightDb),
    ))
}
