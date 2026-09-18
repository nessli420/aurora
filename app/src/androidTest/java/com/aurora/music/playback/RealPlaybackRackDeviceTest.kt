package com.aurora.music.playback

import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.AuroraApplication
import com.aurora.music.MainActivity
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
import kotlin.math.pow
import kotlin.math.sin

/** Two minutes of real service/AudioTrack playback; no physical-route inference from preferences. */
class RealPlaybackRackDeviceTest {
    private val helper = PrecisionPlaybackDeviceTest()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as AuroraApplication).container
    @Before fun foreground() = helper.keepTargetForegroundForAudioFocus()
    @After fun cleanup() = helper.removeFixturesAndFinishActivity()

    @Test fun denseRackKeepsPlayingThroughBackgroundEditsAndSeekAt48And96k() = runDensePlayback(false)

    @Test fun maximumRackKeepsPlayingThroughBackgroundEditsAndSeekAt48And96k() = runDensePlayback(true)

    private fun runDensePlayback(maximum: Boolean) {
        val results = JSONArray()
        val report = JSONObject().put("device", Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
            .put("bands", if (maximum) 256 else 64).put("eqSections", if (maximum) 512 else 74).put("irFrames", 4096).put("secondsPerRate", 60).put("results", results)
            .put("limits", "Actual primary-player AudioTrack underrun notifications and app PCM taps. No DAC/Bluetooth, acoustic, battery-drain or long-term thermal guarantee.")
        try {
            for (rate in intArrayOf(48_000, 96_000)) helper.withProcessingFixture(0) { controller, _ ->
                val impulse = helper.wav("sustained-ir-$rate", rate, 4096) { frame, channel ->
                    if (frame == 0) if (channel == 0) 16384 else 14746
                    else if (frame % (if (channel == 0) 73 else 89) == 0) (180 * exp(-frame / 1200.0)).toInt() else 0
                }
                val tone = helper.wav("sustained-tone-$rate", rate, rate * 85) { frame, channel ->
                    (sin(2 * PI * (if (channel == 0) 997 else 3001) * frame / rate) * 6000).toInt()
                }
                val rack = if (maximum) maximumRack() else denseRack()
                val graphDescription = rack.nodes.joinToString(" \u2192 ") { it.kind.name }
                runBlocking {
                    container.settingsStore.setDspConvIr(impulse.absolutePath, "Dense rack fixture")
                    container.settingsStore.setProcessingRack(rack).getOrThrow()
                }
                helper.main {
                    controller.setMediaItem(MediaItem.Builder().setMediaId("r2a-dense-$rate").setUri(tone.toURI().toString()).build())
                    controller.prepare(); controller.play()
                }
                helper.await("dense rack has fresh stereo output", controller) {
                    val path = container.signalPath.value
                    helper.main { controller.isPlaying && controller.currentPosition > 2_000 } &&
                        path.processing.detail.contains("Serial rack") && path.measurements?.after?.sampleRate == rate &&
                        path.processing.detail.contains(graphDescription) &&
                        !path.processing.detail.contains("unavailable", ignoreCase = true) &&
                        path.measurements?.after?.let { it.leftRms > 0.00001 &&
                            (System.nanoTime() - it.measuredAtNanos) < 1_500_000_000L } == true && path.audioTrackUnderruns != null
                }
                if (maximum) helper.await("automatic headroom and aligned spectrum", controller) {
                    val path = container.signalPath.value
                    path.processing.detail.contains("Auto headroom") && path.measurements?.spectrum?.let { spectrum ->
                        spectrum.sampleRate == rate && spectrum.beforeDb.all { it.isFinite() } && spectrum.afterDb.all { it.isFinite() }
                    } == true
                }
                val initialUnderruns = requireNotNull(container.signalPath.value.audioTrackUnderruns)
                val power = context.getSystemService(PowerManager::class.java)
                val thermal = JSONArray()
                val started = SystemClock.elapsedRealtime()
                var lastPosition = helper.main { controller.currentPosition }
                var backgrounded = false; var foregrounded = false; var edited = false; var sought = false
                var editObserved = false
                var maxClockStallMs = 0L
                var lastAdvanceAt = started
                var previousTick = started
                var maxMeterAgeMs = 0L
                var seekGraceUntil = 0L
                var seekMeterGaps = 0
                while (SystemClock.elapsedRealtime() - started < 60_000) {
                    SystemClock.sleep(250)
                    val now = SystemClock.elapsedRealtime()
                    val elapsed = now - started
                    helper.main {
                        assertNull("No renderer error", controller.playerError)
                        assertTrue("Playback continues outside bounded seek buffering", controller.isPlaying || now < seekGraceUntil)
                    }
                    val currentPosition = helper.main { controller.currentPosition }
                    if (currentPosition > lastPosition) lastAdvanceAt = now
                    else maxClockStallMs = maxOf(maxClockStallMs, now - lastAdvanceAt)
                    assertTrue("Playback clock stalled", now - lastAdvanceAt < 2_500)
                    lastPosition = currentPosition
                    val after = container.signalPath.value.measurements?.after
                    // A seek flush deliberately clears the tap's 100 ms window. Missing data
                    // is allowed only during the same bounded seek grace as rebuffering.
                    if (after == null && now < seekGraceUntil) { seekMeterGaps++; continue }
                    requireNotNull(after) { "After-processing meter missing outside seek grace" }
                    if (edited && container.signalPath.value.processing.detail.contains("EQ (70% wet)")) editObserved = true
                    assertEquals(0L, after.invalidSamples)
                    assertEquals(0L, after.fullScaleSamples)
                    maxMeterAgeMs = maxOf(maxMeterAgeMs, (System.nanoTime() - after.measuredAtNanos) / 1_000_000)
                    assertTrue("After-processing meter remains fresh", (System.nanoTime() - after.measuredAtNanos) < 2_500_000_000L)
                    if (Build.VERSION.SDK_INT >= 29 && elapsed / 10_000 != (previousTick - started) / 10_000)
                        thermal.put(JSONObject().put("elapsedMs", elapsed).put("status", power.currentThermalStatus))
                    previousTick = now
                    if (!backgrounded && elapsed > 10_000) {
                        context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        backgrounded = true
                    }
                    if (!edited && elapsed > 22_000) {
                        runBlocking { container.settingsStore.setProcessingRack(rack.copy(nodes = rack.nodes.map {
                            if (it.kind == RackNodeKind.EQ) it.copy(wet = 0.7f) else it
                        })).getOrThrow() }
                        edited = true
                    }
                    if (!foregrounded && elapsed > 32_000) {
                        context.startActivity(Intent(context, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                        foregrounded = true
                    }
                    if (!sought && elapsed > 43_000) {
                        helper.main { controller.seekTo(50_000) }; sought = true
                        lastPosition = 0; lastAdvanceAt = now
                        seekGraceUntil = now + 2_000
                    }
                }
                val finalPath = container.signalPath.value
                val underruns = requireNotNull(finalPath.audioTrackUnderruns) - initialUnderruns
                val result = JSONObject().put("sampleRate", rate).put("elapsedMs", SystemClock.elapsedRealtime() - started)
                    .put("initialUnderruns", initialUnderruns).put("newUnderruns", underruns)
                    .put("maxObservedClockStallMs", maxClockStallMs).put("maxMeterAgeMs", maxMeterAgeMs)
                    .put("transientSeekMeterGaps", seekMeterGaps)
                    .put("output", finalPath.outputStage.format?.describe()).put("thermalStatusSamples", thermal)
                    .put("backgroundAndResume", backgrounded && foregrounded).put("liveWetEdit", editObserved).put("seek", sought)
                results.put(result)
                File(context.getExternalFilesDir(null), "${if (maximum) "r2d-max" else "r2a"}-phone-path-$rate.txt").writeText(finalPath.toDiagnosticReport())
                assertEquals("No new reported AudioTrack underruns after warmup", 0L, underruns)
                assertTrue("Live wet edit reaches the active graph", editObserved)
                helper.main { controller.pause() }
            }
            report.put("passed", true)
        } catch (failure: Throwable) {
            report.put("passed", false).put("failure", failure.javaClass.simpleName + ": " + failure.message)
            throw failure
        } finally {
            File(context.getExternalFilesDir(null), "${if (maximum) "r2d-max" else "r2a"}-real-playback.json").writeText(report.toString(2))
        }
    }

    private fun maximumRack(): ProcessingRack {
        val base = denseRack()
        val eq = base.nodes.first { it.kind == RackNodeKind.EQ }
        val stages = List(4) { stage -> eq.copy(id = UUID.randomUUID().toString(), name = "EQ ${stage + 1}",
            audio = AudioPrefs(dspGraphicBands = emptyList(), dspParametric = List(64) { i ->
                ParamBand((30 * (18_000.0 / 30).pow(i / 63.0)).toFloat(),
                    if ((i + stage) % 2 == 0) .05f else -.05f, .70710677f, FilterType.TILT.code)
            })) }
        return ProcessingRackCodec.validate(base.copy(name = "512-section fixture", autoHeadroom = true,
            nodes = base.nodes.flatMap { if (it.kind == RackNodeKind.EQ) stages else listOf(it) }))
    }

    private fun denseRack(): ProcessingRack {
        fun node(name: String, kind: RackNodeKind, audio: AudioPrefs = AudioPrefs()) =
            ProcessingRackNode(UUID.randomUUID().toString(), name, kind, audio = audio)
        return ProcessingRack(enabled = true, name = "Dense playback fixture", nodes = listOf(
            node("Input", RackNodeKind.GAIN, AudioPrefs(dspPreampDb = -12f)),
            node("64 bands", RackNodeKind.EQ, AudioPrefs(dspParametric = List(64) { i ->
                ParamBand((30 * (18_000.0 / 30).pow(i / 63.0)).toFloat(), if (i % 2 == 0) 0.25f else -0.25f, 0.8f)
            })), node("Texture", RackNodeKind.SATURATION, AudioPrefs(dspSaturation = 0.02f)),
            node("Stereo", RackNodeKind.STEREO, AudioPrefs(dspWidth = 0.95f)),
            node("Crossfeed", RackNodeKind.CROSSFEED, AudioPrefs(dspCrossfeed = 0.03f)),
            node("Dynamics", RackNodeKind.COMPRESSOR, AudioPrefs(dspCompThreshDb = -6f, dspCompRatio = 2f)),
            node("Delay", RackNodeKind.DELAY, AudioPrefs(dspDelayRightMs = 1f)),
            node("IR", RackNodeKind.CONVOLUTION), node("Output", RackNodeKind.LIMITER)))
    }
}
