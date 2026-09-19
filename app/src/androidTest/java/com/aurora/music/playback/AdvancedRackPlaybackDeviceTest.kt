package com.aurora.music.playback

import android.os.Build
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.AuroraApplication
import com.aurora.music.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

class AdvancedRackPlaybackDeviceTest {
    private val helper = PrecisionPlaybackDeviceTest()
    private val preciseOutput get() = InstrumentationRegistry.getArguments().getString("precisionOutput") == "true"
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as AuroraApplication).container
    @Before fun foreground() = helper.keepTargetForegroundForAudioFocus()
    @After fun cleanup() = helper.removeFixturesAndFinishActivity()

    @Test fun branchedAdvancedRackSurvivesLiveLatencyRoutingAndSeekAtBothRates() {
        val results = JSONArray()
        try {
            for (rate in listOf(48000, 96000)) helper.withProcessingFixture(0) { controller, _ ->
                val impulse = helper.wav("r3-graph-ir-$rate", rate, 4096) { frame, channel ->
                    if (frame == 0) 16384 else if (frame % (if (channel == 0) 79 else 97) == 0) (140 * exp(-frame / 1000.0)).toInt() else 0
                }
                val track = helper.wav("r3-graph-signal-$rate", rate, rate * 45) { frame, channel ->
                    (sin(2 * PI * (if (channel == 0) 613 else 1703) * frame / rate) * 5000).toInt()
                }
                var rack = fixture()
                runBlocking {
                    container.settingsStore.setDspConvIr(impulse.absolutePath, "R3 graph fixture")
                    container.settingsStore.setProcessingRack(rack).getOrThrow()
                }
                helper.main {
                    controller.setMediaItem(MediaItem.Builder().setMediaId("r3-graph-$rate").setUri(track.toURI().toString()).build())
                    controller.prepare(); controller.play()
                }
                helper.await("advanced graph produces fresh output", controller) {
                    val path = container.signalPath.value
                    helper.main { controller.isPlaying && controller.currentPosition > 1500 } &&
                        processingOutputMatches() &&
                        path.processing.detail.contains("Routed graph") && !path.processing.detail.contains("unavailable", true) &&
                        path.measurements?.after?.let { it.sampleRate == rate && it.leftRms > 1e-8 && System.nanoTime() - it.measuredAtNanos < 2_000_000_000 } == true &&
                        path.nodeMeters.size >= rack.nodes.size && path.audioTrackUnderruns != null
                }
                val initialUnderruns = checkNotNull(container.signalPath.value.audioTrackUnderruns)
                val start = SystemClock.elapsedRealtime()
                var lastAdvance = start
                var previousPosition = helper.main { controller.currentPosition }
                var maxPause = 0L; var maxMeterAge = 0L; var latencyEdited = false; var routingEdited = false; var sought = false
                var seekGrace = 0L
                while (SystemClock.elapsedRealtime() - start < 30000) {
                    SystemClock.sleep(250)
                    val now = SystemClock.elapsedRealtime(); val elapsed = now - start
                    helper.main { assertNull(controller.playerError); assertTrue(controller.isPlaying || now < seekGrace) }
                    val position = helper.main { controller.currentPosition }
                    if (position > previousPosition || position < previousPosition) lastAdvance = now
                    else maxPause = maxOf(maxPause, now - lastAdvance)
                    previousPosition = position
                    assertTrue("Playback clock stays active", now - lastAdvance < 5000)
                    val path = container.signalPath.value
                    if (now >= seekGrace) assertTrue("Requested processing output remains active: ${path.processing.detail}", processingOutputMatches())
                    val meter = path.measurements?.after
                    if (meter != null) {
                        assertEquals(0L, meter.invalidSamples); assertEquals(0L, meter.fullScaleSamples)
                        val age = (System.nanoTime() - meter.measuredAtNanos) / 1_000_000
                        maxMeterAge = maxOf(maxMeterAge, age)
                        assertTrue("Fresh processing output", age < 3500 || now < seekGrace)
                    } else assertTrue("Meter gap only during seek", now < seekGrace)
                    assertTrue(path.nodeMeters.all { it.peak.isFinite() && it.changeDb.isFinite() && it.bandChangesDb.all(Double::isFinite) })
                    if (!latencyEdited && elapsed > 7000) {
                        rack = rack.copy(nodes = rack.nodes.map { if (it.kind == RackNodeKind.ALIGNMENT_DELAY) it.copy(utility = RackUtility(delayMs = 10.0)) else it })
                        runBlocking { container.settingsStore.setProcessingRack(rack).getOrThrow() }; latencyEdited = true
                    }
                    if (!routingEdited && elapsed > 15000) {
                        rack = rack.copy(nodes = rack.nodes.map { node -> if (node.name == "Parallel sum") node.copy(inputs = node.inputs!!.mapIndexed { index, edge ->
                            edge.copy(channel = if (index == 0) RackChannel.MID else RackChannel.SIDE)
                        }) else node })
                        runBlocking { container.settingsStore.setProcessingRack(rack).getOrThrow() }; routingEdited = true
                    }
                    if (!sought && elapsed > 23000) {
                        seekGrace = now + 3500; helper.main { controller.seekTo(5000) }; sought = true
                    }
                }
                helper.await("final graph remains audible after seek", controller) {
                    helper.main { controller.isPlaying && controller.currentPosition > 8000 } &&
                        processingOutputMatches() &&
                        container.signalPath.value.measurements?.after?.leftRms?.let { it > 1e-8 } == true
                }
                val final = container.signalPath.value
                results.put(JSONObject().put("rate", rate).put("maxClockPauseMs", maxPause).put("maxMeterAgeMs", maxMeterAge)
                    .put("underruns", checkNotNull(final.audioTrackUnderruns) - initialUnderruns)
                    .put("latencyEdited", latencyEdited).put("routingEdited", routingEdited).put("seekPassed", sought)
                    .put("path", final.processing.detail).put("output", final.outputStage.toString()))
            }
        } finally {
            File(context.getExternalFilesDir(null), "r3-advanced-playback.json").writeText(JSONObject()
                .put("device", Build.MODEL).put("sdk", Build.VERSION.SDK_INT).put("secondsPerRate", 30)
                .put("eqBands", 64).put("convolutionNodes", 2).put("results", results).toString(2))
        }
    }

    private fun processingOutputMatches(): Boolean {
        val path = container.signalPath.value
        return path.processing.detail.contains("binary64", true) && (!preciseOutput ||
            path.processing.detail.contains("float32 Android output") &&
            path.outputStage.format?.let { it.encoding == "float PCM" && it.bitDepth == 32 } == true)
    }

    private fun fixture(): ProcessingRack {
        fun node(name: String, kind: RackNodeKind, audio: AudioPrefs = AudioPrefs()) =
            ProcessingRackNode(UUID.nameUUIDFromBytes(name.toByteArray()).toString(), name, kind, audio = audio)
        val gain = node("Input", RackNodeKind.GAIN, AudioPrefs(dspPreampDb = -12f))
        val eq = node("64-band EQ", RackNodeKind.EQ, AudioPrefs(dspParametric = List(64) { index ->
            ParamBand((35 * Math.pow(16000.0 / 35, index / 63.0)).toFloat(), if (index % 2 == 0) 1f else -1f, 1f)
        }))
        val first = node("IR one", RackNodeKind.CONVOLUTION).copy(inputs = listOf(RackInput(eq.id, gainDb = -6.020599913279624)))
        val second = node("IR two", RackNodeKind.CONVOLUTION).copy(inputs = listOf(RackInput(eq.id, gainDb = -6.020599913279624)), wet = .5f)
        val sum = node("Parallel sum", RackNodeKind.UTILITY).copy(inputs = listOf(RackInput(first.id), RackInput(second.id)))
        return ProcessingRack(enabled = true, name = "R3 stress", autoHeadroom = true, nodes = listOf(gain, eq, first, second, sum,
            node("Oversampled saturation", RackNodeKind.SATURATION, AudioPrefs(dspSaturation = .08f)).copy(oversampling = 2),
            node("Dynamic EQ", RackNodeKind.DYNAMIC_EQ).copy(dynamic = RackDynamicEq(dynamics = RackDynamics(thresholdDb = -36.0))),
            node("Multiband", RackNodeKind.MULTIBAND).copy(multiband = RackMultiband(bands = List(3) { RackDynamics(thresholdDb = -36.0, ratio = 1.3) })),
            node("Loudness", RackNodeKind.LOUDNESS).copy(loudness = RackLoudness()),
            node("Alignment", RackNodeKind.ALIGNMENT_DELAY).copy(utility = RackUtility(delayMs = 1.0)),
            node("Limiter", RackNodeKind.LIMITER, AudioPrefs(dspLimiterCeilingDb = -2f))))
    }
}
