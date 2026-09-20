package com.aurora.music.playback

import android.os.Build
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.AuroraApplication
import com.aurora.music.data.*
import com.aurora.music.playback.engine.OutputRatePolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin

class StudioRackPlaybackDeviceTest {
    private val helper = PrecisionPlaybackDeviceTest()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as AuroraApplication).container
    private var originalPolicy: OutputRatePolicy? = null
    @Before fun foreground() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("precisionOutput") == "true" && arguments.getString("rackOutput") == "true")
        runBlocking {
            originalPolicy = container.settingsStore.outputRatePolicy.first()
            container.settingsStore.setOutputRatePolicy(OutputRatePolicy()).getOrThrow()
        }
        helper.keepTargetForegroundForAudioFocus()
    }
    @After fun cleanup() {
        try { originalPolicy?.let { runBlocking { container.settingsStore.setOutputRatePolicy(it).getOrThrow() } } }
        finally { helper.removeFixturesAndFinishActivity() }
    }

    @Test fun studioFamiliesKeepPlayingThroughLiveEditsBypassSeekAndQueueChanges() {
        val results = JSONArray()
        try {
            for (rate in listOf(48000, 96000)) helper.withProcessingFixture(0) { controller, _ ->
                val track = helper.wav("r6-studio-$rate", rate, rate * 25) { frame, channel ->
                    (sin(2 * PI * (if (channel == 0) 997 else 5503) * frame / rate) * 6500).toInt()
                }
                var rack = fixture()
                runBlocking { container.settingsStore.setProcessingRack(rack).getOrThrow() }
                helper.main {
                    controller.setMediaItems(List(2) { index -> MediaItem.Builder().setMediaId("r6-studio-$rate-$index").setUri(track.toURI().toString()).build() })
                    controller.prepare(); controller.play()
                }
                helper.await("studio stages produce fresh $rate Hz output", controller) {
                    val path = container.signalPath.value
                    helper.main { controller.isPlaying && controller.currentPosition > 1000 } &&
                        path.processing.detail.contains("float32 Android output") &&
                        listOf("DYNAMICS", "TONE", "SPACE", "MODULATION").all(path.processing.detail::contains) &&
                        path.measurements?.after?.let { it.sampleRate == rate && it.leftRms > 1e-8 && System.nanoTime() - it.measuredAtNanos < 2_000_000_000 } == true &&
                        path.nodeMeters.size == rack.nodes.size && path.audioTrackUnderruns != null
                }
                val initialUnderruns = checkNotNull(container.signalPath.value.audioTrackUnderruns)
                val started = SystemClock.elapsedRealtime()
                var lastAdvance = started; var previousPosition = helper.main { controller.currentPosition }
                var edited = false; var bypassed = false; var restored = false; var sought = false; var queued = false
                var graceUntil = 0L; var longestPause = 0L; var oldestMeter = 0L
                while (SystemClock.elapsedRealtime() - started < 16000) {
                    SystemClock.sleep(200)
                    val now = SystemClock.elapsedRealtime(); val elapsed = now - started
                    helper.main { assertNull(controller.playerError) }
                    val position = helper.main { controller.currentPosition }
                    if (position != previousPosition) lastAdvance = now
                    previousPosition = position; longestPause = maxOf(longestPause, now - lastAdvance)
                    assertTrue("Playback clock continues", now - lastAdvance < 4000)
                    val path = container.signalPath.value
                    assertTrue(path.nodeMeters.all { it.peak.isFinite() && it.changeDb.isFinite() })
                    if (now > graceUntil) {
                        assertTrue(helper.main { controller.isPlaying })
                        val meter = checkNotNull(path.measurements?.after)
                        assertEquals(0L, meter.invalidSamples); assertEquals(0L, meter.fullScaleSamples)
                        val age = (System.nanoTime() - meter.measuredAtNanos) / 1_000_000
                        oldestMeter = maxOf(oldestMeter, age); assertTrue("Fresh processed samples", age < 3500)
                        assertEquals(rate, meter.sampleRate)
                    }
                    if (!edited && elapsed > 2500) {
                        rack = rack.copy(nodes = rack.nodes.map { node -> when (node.kind) {
                            RackNodeKind.DYNAMICS -> node.copy(dynamics = RackDynamicsEffect(mode = RackDynamicsMode.DEESSER))
                            RackNodeKind.TONE -> node.copy(tone = RackTone(mode = RackToneMode.TUBE, amount = .2))
                            RackNodeKind.SPACE -> node.copy(space = RackSpace(mode = RackSpaceMode.REVERB, timeMs = 10.0, decaySeconds = .3))
                            RackNodeKind.MODULATION -> node.copy(modulation = RackModulation(mode = RackModulationMode.PHASER, depth = .4))
                            else -> node
                        } })
                        runBlocking { container.settingsStore.setProcessingRack(rack).getOrThrow() }; edited = true
                    }
                    if (!bypassed && elapsed > 5500) {
                        runBlocking { container.settingsStore.setProcessingRack(rack.copy(nodes = rack.nodes.map { it.copy(bypass = true) })).getOrThrow() }
                        helper.await("studio bypass is applied", controller) { container.signalPath.value.processing.detail.contains("DYNAMICS (bypassed)") }
                        bypassed = true
                    }
                    if (!restored && elapsed > 7000) {
                        runBlocking { container.settingsStore.setProcessingRack(rack).getOrThrow() }; restored = true
                    }
                    if (!sought && elapsed > 9500) { graceUntil = now + 3000; helper.main { controller.seekTo(1500) }; sought = true }
                    if (!queued && elapsed > 12500) { graceUntil = now + 3000; helper.main { controller.seekToNextMediaItem() }; queued = true }
                }
                helper.await("next item keeps studio processing", controller) {
                    helper.main { controller.currentMediaItemIndex == 1 && controller.isPlaying && controller.currentPosition > 1000 } &&
                        container.signalPath.value.measurements?.after?.let { it.sampleRate == rate && it.invalidSamples == 0L && it.leftRms > 1e-8 } == true
                }
                assertTrue(edited && bypassed && restored && sought && queued)
                val final = container.signalPath.value
                results.put(JSONObject().put("rate", rate).put("maxClockPauseMs", longestPause).put("maxMeterAgeMs", oldestMeter)
                    .put("underrunDelta", checkNotNull(final.audioTrackUnderruns) - initialUnderruns)
                    .put("edited", edited).put("bypassed", bypassed).put("restored", restored).put("seek", sought).put("queue", queued)
                    .put("processing", final.processing.detail))
            }
        } finally {
            File(context.getExternalFilesDir(null), "r6-studio-playback.json").writeText(JSONObject()
                .put("device", Build.MODEL).put("secondsPerRate", 16).put("results", results).toString(2))
        }
    }
    private fun fixture(): ProcessingRack {
        fun node(kind: RackNodeKind) = ProcessingRackNode(UUID.nameUUIDFromBytes("r6-${kind.name}".toByteArray()).toString(), kind.name, kind)
        return ProcessingRack(enabled = true, name = "R6 studio fixture", nodes = listOf(
            node(RackNodeKind.GAIN).copy(audio = AudioPrefs(dspPreampDb = -9f)),
            node(RackNodeKind.DYNAMICS).copy(dynamics = RackDynamicsEffect(dynamics = RackDynamics(thresholdDb = -55.0))),
            node(RackNodeKind.TONE).copy(tone = RackTone(amount = .2)),
            node(RackNodeKind.SPACE).copy(wet = .15f, space = RackSpace(timeMs = 30.0, feedback = .15)),
            node(RackNodeKind.MODULATION).copy(modulation = RackModulation(depth = .3)),
            node(RackNodeKind.LIMITER).copy(audio = AudioPrefs(dspLimiterCeilingDb = -2f))))
    }
}
