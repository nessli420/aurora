package com.aurora.music.playback

import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.AuroraApplication
import com.aurora.music.data.AudioPrefs
import com.aurora.music.data.DspMode
import com.aurora.music.data.ProcessingPlaybackPrefs
import com.aurora.music.data.ProcessingPreset
import com.aurora.music.data.ProcessingRack
import com.aurora.music.data.ProcessingRackNode
import com.aurora.music.data.RackNodeKind
import com.aurora.music.data.routes.ProcessingRouteKind
import com.aurora.music.playback.compare.ComparisonSelection
import com.aurora.music.playback.compare.ComparisonRenderer
import com.aurora.music.viewmodel.ComparisonViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin

class ComparisonViewModelDeviceTest {
    private val fixture = PrecisionPlaybackDeviceTest()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as AuroraApplication
    private val rules get() = app.container.settingsStore.presetRuleContext
    private val a = preset(0f)
    private val b = preset(-6f)

    @Before fun setup() = fixture.keepTargetForegroundForAudioFocus()
    @After fun cleanup() = fixture.removeFixturesAndFinishActivity()

    @Test fun cancelledPreparationCannotStopTheNextSession() = withComparison { vm, controller, uri, _ ->
        startReady(vm, uri)
        fixture.main { vm.stop() }
        awaitRestored(controller)
        fixture.main {
            vm.start(uri, 0, a, b, true)
            assertTrue(vm.state.value.preparing)
            vm.stop()
            assertFalse(rules.current.frozen)
            vm.start(uri, 0, a, b, true)
            assertTrue(vm.state.value.preparing)
        }
        awaitReady(vm)
        SystemClock.sleep(400)
        assertTrue(vm.state.value.active)
        assertTrue(rules.current.frozen)
        assertNull(vm.state.value.error)
        assertNull(vm.state.value.result)
        assertNull(vm.state.value.levels)
        fixture.main { assertFalse(controller.playWhenReady); vm.stop() }
        awaitRestored(controller)
    }

    @Test fun lateFailureFromCancelledPreparationCannotOverwriteTheNextSession() {
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failed = CountDownLatch(1)
        withComparison(createViewModel = { application ->
            ComparisonViewModel(application) { app, uri, start, a, b, volume ->
                if (calls.incrementAndGet() == 2) withContext(Dispatchers.IO) {
                    entered.countDown()
                    try {
                        check(release.await(10, TimeUnit.SECONDS))
                        throw IOException("Late cancelled source failure")
                    } finally { failed.countDown() }
                }
                ComparisonRenderer.prepare(app, uri, start, a, b, volume)
            }
        }) { vm, controller, uri, _ ->
            try {
                startReady(vm, uri)
                fixture.main { vm.stop() }
                awaitRestored(controller)
                fixture.main { vm.start(uri, 0, a, b, true) }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                fixture.main { vm.stop(); vm.start(uri, 0, a, b, true) }
                awaitReady(vm)
                release.countDown()
                assertTrue(failed.await(5, TimeUnit.SECONDS))
                repeat(10) {
                    SystemClock.sleep(40)
                    assertTrue(vm.state.value.active)
                    assertNull(vm.state.value.error)
                    assertTrue(rules.current.frozen)
                }
                fixture.main { vm.stop() }
                awaitRestored(controller)
            } finally { release.countDown() }
        }
    }

    @Test fun volumeInvalidationDoesNotResumeTheOriginalPlayer() = withComparison { vm, controller, uri, _ ->
        val manager = context.getSystemService(AudioManager::class.java)
        assumeFalse("A variable media volume is required", manager.isVolumeFixed)
        val original = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maximum = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val changed = if (original < maximum) original + 1 else original - 1
        require(changed >= 0 && changed != original)
        try {
            startReady(vm, uri)
            fixture.main { manager.setStreamVolume(AudioManager.STREAM_MUSIC, changed, 0) }
            fixture.await("volume change interrupts the comparison", controller) {
                !vm.state.value.active && vm.state.value.error == "Volume changed. The comparison stopped." && !rules.current.frozen
            }
            SystemClock.sleep(400)
            fixture.main { assertFalse(controller.playWhenReady); assertFalse(controller.isPlaying) }
            val result = requireNotNull(vm.state.value.result)
            assertTrue(result.interrupted)
            assertNull(result.probability)
            assertNotNull(vm.state.value.levels)
        } finally {
            fixture.main { manager.setStreamVolume(AudioManager.STREAM_MUSIC, original, 0) }
        }
        SystemClock.sleep(300)
        fixture.main { assertFalse(controller.playWhenReady) }
    }

    @Test fun stoppingRestoresPlaybackAndReleasesTheRuleFreeze() = withComparison { vm, controller, uri, _ ->
        val before = runBlocking { app.container.settingsStore.processingSettings.first() }
        startReady(vm, uri)
        assertTrue(rules.current.frozen)
        fixture.main { assertFalse(controller.playWhenReady) }
        assertEquals(48_000, vm.state.value.rate)
        assertEquals(48_000 * 4, vm.state.value.selectionFrames)
        fixture.main { vm.select(ComparisonSelection.X); vm.guess(true); vm.stop() }
        awaitRestored(controller)
        assertEquals(before, runBlocking { app.container.settingsStore.processingSettings.first() })
        val result = requireNotNull(vm.state.value.result)
        assertTrue(result.interrupted)
        assertEquals(1, result.answered)
        assertEquals(1, result.trials.size)
        assertNull(result.probability)
        assertNotNull(vm.state.value.levels)
    }

    @Test fun sixteenAnswersFinishOnceAndRevealTheTrialLogAndLevels() = withComparison { vm, controller, uri, _ ->
        startReady(vm, uri)
        repeat(16) { index ->
            fixture.main {
                assertTrue(vm.state.value.active)
                assertEquals(index, vm.state.value.answered)
                assertNull(vm.state.value.result)
                assertNull(vm.state.value.levels)
                vm.select(ComparisonSelection.X)
                vm.guess(index % 2 == 0)
            }
        }
        awaitRestored(controller)
        val completed = vm.state.value
        assertFalse(completed.active)
        assertEquals(16, completed.answered)
        assertEquals(48_000 * 4, completed.selectionFrames)
        assertNotNull(completed.levels)
        val result = requireNotNull(completed.result)
        assertEquals(16, result.planned)
        assertEquals(16, result.answered)
        assertFalse(result.interrupted)
        assertNotNull(result.probability)
        assertEquals((1..16).toList(), result.trials.map { it.number })
        assertEquals((0..15).map { it % 2 == 0 }, result.trials.map { it.guessA })
        assertEquals(result.trials.count { it.guessA == it.xIsA }, result.correct)
        fixture.main { vm.guess(true); vm.select(ComparisonSelection.B) }
        assertEquals(completed, vm.state.value)
    }

    @Test fun clearingAnActiveViewModelClosesAudioBeforeRestoringPlayback() = withComparison { vm, controller, uri, lifecycle ->
        startReady(vm, uri)
        fixture.main { lifecycle.clear(); lifecycle.clear() }
        awaitRestored(controller)
        assertFalse(vm.state.value.active)
        assertTrue(requireNotNull(vm.state.value.result).interrupted)
        fixture.main { vm.start(uri, 0, a, b, true) }
        assertFalse(vm.state.value.active)
        assertFalse(vm.state.value.preparing)
        assertFalse(rules.current.frozen)
    }

    private fun withComparison(createViewModel: (AuroraApplication) -> ComparisonViewModel = { ComparisonViewModel(it) },
        block: (ComparisonViewModel, MediaController, Uri, ViewModelStore) -> Unit) {
        fixture.withProcessingFixture(0) { controller, _ ->
            assertFalse("No existing comparison owns the rule freeze", rules.current.frozen)
            val track = fixture.wav("comparison-original", 48_000, 48_000 * 30, ::sample)
            val selection = fixture.wav("comparison-selection", 48_000, 48_000 * 4, ::sample)
            fixture.main {
                controller.setMediaItem(MediaItem.Builder().setMediaId("comparison-original").setUri(Uri.fromFile(track)).build())
                controller.repeatMode = Player.REPEAT_MODE_ONE
                controller.prepare()
                controller.play()
            }
            fixture.await("original playback has a confirmed output", controller) {
                val route = app.container.settingsStore.processingRoutes.current.route
                fixture.main { controller.isPlaying } && route.kind == ProcessingRouteKind.ANDROID && route.androidDeviceId != null
            }
            val lifecycle = ViewModelStore()
            val vm = fixture.main { createViewModel(app).also { lifecycle.put("comparison", it) } }
            try { block(vm, controller, Uri.fromFile(selection), lifecycle) }
            finally {
                fixture.main { lifecycle.clear() }
                fixture.await("comparison cleanup releases the rule freeze", controller) { !rules.current.frozen }
            }
        }
    }

    private fun startReady(vm: ComparisonViewModel, uri: Uri) {
        fixture.main { vm.start(uri, 0, a, b, true) }
        fixture.await("comparison controller connects") {
            if (vm.state.value.error == "The player is still connecting.") fixture.main { vm.start(uri, 0, a, b, true) }
            vm.state.value.preparing || vm.state.value.active || vm.state.value.error != "The player is still connecting."
        }
        awaitReady(vm)
    }

    private fun awaitReady(vm: ComparisonViewModel) {
        fixture.await("comparison becomes ready") {
            assertNull(vm.state.value.error)
            vm.state.value.active
        }
    }

    private fun awaitRestored(controller: MediaController) = fixture.await("original playback resumes after comparison cleanup", controller) {
        val route = app.container.settingsStore.processingRoutes.current.route
        !rules.current.frozen && route.kind == ProcessingRouteKind.ANDROID && route.androidDeviceId != null &&
            fixture.main { controller.isPlaying && controller.currentMediaItem?.mediaId == "comparison-original" }
    }

    private fun preset(gain: Float): ProcessingPreset = ProcessingPreset(UUID.randomUUID().toString(), "Comparison $gain",
        createdAtMs = 0, audio = AudioPrefs(dspMode = DspMode.CUSTOM), playback = ProcessingPlaybackPrefs(),
        rack = ProcessingRack(enabled = true, nodes = listOf(ProcessingRackNode(UUID.randomUUID().toString(), "Gain",
            RackNodeKind.GAIN, audio = AudioPrefs(dspPreampDb = gain)))))

    private fun sample(frame: Int, channel: Int): Int =
        (900 * sin(2 * PI * (if (channel == 0) 613 else 947) * frame / 48_000)).toInt()
}
