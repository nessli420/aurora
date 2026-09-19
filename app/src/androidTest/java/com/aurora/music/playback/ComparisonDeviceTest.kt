package com.aurora.music.playback

import android.net.Uri
import android.media.AudioManager
import android.media.AudioDeviceInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.data.*
import com.aurora.music.playback.compare.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.sin

class ComparisonDeviceTest {
    private val fixture = PrecisionPlaybackDeviceTest()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun setup() = fixture.keepTargetForegroundForAudioFocus()
    @After fun cleanup() = fixture.removeFixturesAndFinishActivity()

    private fun preset(gain: Float): ProcessingPreset {
        val rack = ProcessingRack(enabled = true, nodes = listOf(ProcessingRackNode(UUID.randomUUID().toString(),
            "Gain", RackNodeKind.GAIN, audio = AudioPrefs(dspPreampDb = gain))))
        return ProcessingPreset(UUID.randomUUID().toString(), "Compare $gain", createdAtMs = 0,
            audio = AudioPrefs(dspMode = DspMode.CUSTOM), playback = ProcessingPlaybackPrefs(), rack = rack)
    }

    @Test fun decoderAndBothProductionGraphsUseTheSameSourceThenMatchLevels() = runBlocking {
        val rate = 48000
        val wave = fixture.wav("comparison-source.wav", rate, rate * 4) { frame, channel ->
            (3000 * sin(2 * PI * (if (channel == 0) 613 else 947) * frame / rate)).toInt()
        }
        val source = ComparisonRenderer.decode(context, Uri.fromFile(wave), 1)
        assertEquals(rate, source.rate)
        assertEquals(rate * 4 * 2, source.samples.size)
        assertEquals(rate, source.warmupFrames)
        val a = ComparisonRenderer.render(source, preset(0f))
        val b = ComparisonRenderer.render(source, preset(-12f))
        val match = ComparisonMatching.match(rate, a.first, b.first, a.second, b.second, source.warmupFrames)
        assertEquals(rate * 3, match.frames)
        assertArrayEquals(match.a, match.b, 1e-7f)
        assertEquals(0.0, match.levels.residualDb, 1e-5)
    }

    @Test fun highRateSelectionsStayBoundedAndRetainUsefulMatchedAudio() = runBlocking {
        val rate = 192000
        val wave = fixture.wav("comparison-high-rate.wav", rate, rate * 8) { frame, channel ->
            (2000 * sin(2 * PI * (if (channel == 0) 613 else 947) * frame / rate)).toInt()
        }
        val source = ComparisonRenderer.decode(context, Uri.fromFile(wave), 3)
        assertEquals(rate, source.rate)
        assertTrue(source.samples.size <= 2_000_000)
        assertTrue(source.samples.size / 2 - source.warmupFrames >= rate)
        val first = ComparisonRenderer.render(source, preset(0f))
        val second = ComparisonRenderer.render(source, preset(-6f))
        val matched = ComparisonMatching.match(rate, first.first, second.first, first.second, second.second, source.warmupFrames)
        assertEquals(source.samples.size / 2 - source.warmupFrames, matched.frames)
        assertArrayEquals(matched.a, matched.b, 1e-7f)
    }

    @Test fun comparisonSwitchesUseOneAdvancingAudioTrack() {
        fixture.withProcessingFixture(0) { controller, _ ->
            fixture.main { controller.pause() }
            val rate = 48000
            val samples = DoubleArray(rate * 4) { i -> .02 * sin(2 * PI * 440 * (i / 2) / rate) }
            val audio = ComparisonMatching.match(rate, samples, samples)
            val failure = AtomicReference<String?>()
            val output = ComparisonPlayback(context, audio) { failure.set(it) }
            try {
                fixture.main { output.start() }
                runBlocking { output.awaitReady() }
                assertNotNull(output.confirmedDeviceId)
                fixture.await("comparison AudioTrack starts", controller) { failure.get() != null || output.playedFrames > rate / 4 }
                assertNull(failure.get())
                val before = output.playedFrames
                repeat(8) { output.select(it % 2 == 0); Thread.sleep(40) }
                assertTrue(output.playedFrames > before)
                assertNull(failure.get())
            } finally { closeAndWait(output) }
        }
    }

    @Test fun comparisonConfirmsRequestedRouteBeforeReadiness() {
        fixture.withProcessingFixture(0) { controller, _ ->
            fixture.main { controller.pause() }
            val device = context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .first { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            val samples = DoubleArray(48000 * 4) { i -> .01 * sin(2 * PI * 440 * (i / 2) / 48000) }
            val failure = AtomicReference<String?>()
            val output = ComparisonPlayback(context, ComparisonMatching.match(48000, samples, samples), device.id) { failure.set(it) }
            try {
                fixture.main { output.start() }
                runBlocking { output.awaitReady() }
                assertEquals(device.id, output.confirmedDeviceId)
                assertNull(failure.get())
            } finally { closeAndWait(output) }
        }
    }

    @Test fun missingExpectedRouteCannotBecomeReadyOnTheDefaultOutput() {
        fixture.withProcessingFixture(0) { controller, _ ->
            fixture.main { controller.pause() }
            val samples = DoubleArray(48000 * 4) { .01 }
            val output = ComparisonPlayback(context, ComparisonMatching.match(48000, samples, samples), Int.MAX_VALUE) { }
            try {
                fixture.main { output.start() }
                assertTrue(runCatching { runBlocking { output.awaitReady() } }.isFailure)
                assertNull(output.confirmedDeviceId)
                assertNotNull(output.invalidationReason)
            } finally { closeAndWait(output) }
        }
    }

    private fun closeAndWait(output: ComparisonPlayback) = runBlocking {
        val closed = CompletableDeferred<Unit>()
        output.whenClosed { closed.complete(Unit) }
        output.close()
        withTimeout(5000) { closed.await() }
    }
}
