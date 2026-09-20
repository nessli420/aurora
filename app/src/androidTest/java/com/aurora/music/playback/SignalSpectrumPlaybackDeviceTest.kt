package com.aurora.music.playback

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.AuroraApplication
import com.aurora.music.data.AudioSpectrum
import com.aurora.music.playback.engine.OutputRateMode
import com.aurora.music.playback.engine.OutputRatePolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

class SignalSpectrumPlaybackDeviceTest {
    private val helper = PrecisionPlaybackDeviceTest()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (context.applicationContext as AuroraApplication).container
    private var originalPolicy: OutputRatePolicy? = null

    @Before fun foreground() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Requires precision output", arguments.getString("precisionOutput") == "true")
        assumeTrue("Requires processing rack", arguments.getString("rackOutput") == "true")
        runBlocking {
            originalPolicy = container.settingsStore.outputRatePolicy.first()
            container.settingsStore.setOutputRatePolicy(OutputRatePolicy()).getOrThrow()
        }
        helper.keepTargetForegroundForAudioFocus()
    }

    @After fun cleanup() {
        try {
            originalPolicy?.let { policy ->
                runBlocking { container.settingsStore.setOutputRatePolicy(policy).getOrThrow() }
            }
        } finally {
            helper.removeFixturesAndFinishActivity()
        }
    }

    @Test fun alignedSpectrumSurvivesPauseResumeSeekAndSampleRateChange() {
        withSpectrumFixture { controller ->
            val tracks = listOf(tone(44_100), tone(48_000))
            val startedAt = System.nanoTime()
            helper.main {
                controller.setMediaItems(tracks.mapIndexed { index, file -> item("spectrum-precision-$index", file) })
                controller.prepare()
                controller.play()
            }
            awaitSpectrum(controller, 44_100, startedAt, aligned = true)

            helper.main { controller.pause() }
            helper.await("paused spectrum measurements", controller) {
                !helper.main { controller.isPlaying || controller.playWhenReady } &&
                    container.signalPath.value.measurements?.playing == false
            }
            val pausedPosition = helper.main { controller.currentPosition }
            SystemClock.sleep(3_300)
            val paused = requireNotNull(container.signalPath.value.measurements?.spectrum)
            assertEquals(44_100, paused.sampleRate)
            assertNotNull("Paused spectrum keeps the aligned output", paused.afterDb)
            assertTrue("Paused spectrum retains actual sample time", System.nanoTime() - paused.measuredAtNanos > 2_000_000_000L)
            assertEquals(pausedPosition, helper.main { controller.currentPosition })

            val resumedAt = System.nanoTime()
            helper.main { controller.play() }
            val resumed = awaitSpectrum(controller, 44_100, resumedAt, aligned = true)
            assertTrue(resumed.measuredAtNanos > paused.measuredAtNanos)

            val soughtAt = System.nanoTime()
            helper.main { controller.seekTo(9_000) }
            val sought = awaitSpectrum(controller, 44_100, soughtAt, aligned = true, minimumTimeUs = 9_000_000)
            assertTrue(sought.presentationStartUs > resumed.presentationStartUs)

            val changedAt = System.nanoTime()
            helper.main { controller.seekTo(1, 2_000) }
            awaitSpectrum(controller, 48_000, changedAt, aligned = true, minimumTimeUs = 2_000_000)
            helper.main { assertEquals(1, controller.currentMediaItemIndex) }
            assertEquals(48_000, container.signalPath.value.decoder.format?.rateHz)
        }
    }

    @Test fun paddedMp3KeepsSourceSpectrumInFollowingCompatibilityStream() {
        val mp3 = File(context.cacheDir, "spectrum-gapless-${System.nanoTime()}.mp3")
        try {
            instrumentation.context.assets.open("network/gapless.mp3").use { source ->
                mp3.outputStream().use { output -> source.copyTo(output) }
            }
            withSpectrumFixture { controller ->
                val wav = tone(44_100)
                val startedAt = System.nanoTime()
                helper.main {
                    controller.setMediaItems(listOf(item("spectrum-gapless", mp3), item("spectrum-after-gapless", wav)))
                    controller.prepare()
                    controller.play()
                }
                helper.await("WAV retains gapless compatibility after MP3", controller) {
                    val path = container.signalPath.value
                    helper.main { controller.currentMediaItemIndex == 1 && controller.isPlaying } &&
                        path.processing.detail.contains("Compatibility PCM16") &&
                        path.reasons.any { it.contains("retained after a padded stream") }
                }
                val initial = awaitSpectrum(controller, 44_100, startedAt, aligned = false)
                assertEquals(16, container.signalPath.value.outputStage.format?.bitDepth)

                val soughtAt = System.nanoTime()
                helper.main { controller.seekTo(8_000) }
                val sought = awaitSpectrum(controller, 44_100, soughtAt, aligned = false, minimumTimeUs = 8_000_000)
                assertTrue(sought.presentationStartUs > initial.presentationStartUs)

                helper.main { controller.pause() }
                helper.await("compatibility pause", controller) { helper.main { !controller.isPlaying } }
                val resumedAt = System.nanoTime()
                helper.main { controller.play() }
                awaitSpectrum(controller, 44_100, resumedAt, aligned = false, minimumTimeUs = 8_000_000)
                assertTrue(container.signalPath.value.processing.detail.contains("Compatibility PCM16"))
            }
        } finally {
            mp3.delete()
        }
    }

    @Test fun fixedOutputPolicyKeepsSpectrumForTheObservedRateDecision() {
        withSpectrumFixture { controller ->
            val tracks = listOf(tone(48_000), tone(44_100))
            val startedAt = System.nanoTime()
            helper.main {
                controller.setMediaItems(tracks.mapIndexed { index, file -> item("spectrum-resampled-$index", file) })
                controller.prepare()
                controller.play()
            }
            awaitSpectrum(controller, 48_000, startedAt, aligned = true)
            helper.await("observed Android audio route", controller) {
                container.signalPath.value.device.evidence == "AudioTrack routed-device observation"
            }
            runBlocking {
                container.settingsStore.setOutputRatePolicy(OutputRatePolicy(mode = OutputRateMode.FIXED, fixedRate = 48_000)).getOrThrow()
            }
            val changedAt = System.nanoTime()
            helper.main { controller.seekTo(1, 2_000) }
            helper.await("fixed-rate decision reaches the active output", controller) {
                val path = container.signalPath.value
                helper.main { controller.currentMediaItemIndex == 1 && controller.isPlaying } &&
                    path.decoder.format?.rateHz == 44_100 &&
                    (path.outputStage.format?.rateHz == 48_000 && path.resampling.detail.contains("44100 → 48000 Hz") ||
                        path.outputStage.format?.rateHz == 44_100 &&
                        path.resampling.detail in listOf("Output rates are unknown; following source.", "48000 Hz is unavailable; following source."))
            }
            val outputRate = requireNotNull(container.signalPath.value.outputStage.format?.rateHz)
            val aligned = outputRate == 44_100
            val first = awaitSpectrum(controller, 44_100, changedAt, aligned, minimumTimeUs = 2_000_000)
            awaitSpectrum(controller, 44_100, first.measuredAtNanos + 500_000_000L, aligned, minimumTimeUs = 2_000_000)
            assertEquals(outputRate, container.signalPath.value.measurements?.after?.sampleRate)
        }
    }

    private fun withSpectrumFixture(block: (MediaController) -> Unit) {
        helper.withProcessingFixture(0) { controller, _ ->
            try {
                block(controller)
            } finally {
                originalPolicy?.let { policy ->
                    runBlocking { container.settingsStore.setOutputRatePolicy(policy).getOrThrow() }
                }
            }
        }
    }

    private fun awaitSpectrum(controller: MediaController, rate: Int, sinceNanos: Long, aligned: Boolean,
        minimumTimeUs: Long = 0): AudioSpectrum {
        var accepted: AudioSpectrum? = null
        helper.await("fresh $rate Hz ${if (aligned) "aligned" else "source"} spectrum", controller) {
            val measurements = container.signalPath.value.measurements
            val spectrum = measurements?.spectrum
            val valid = measurements?.playing == true && spectrum != null && spectrum.sampleRate == rate &&
                spectrum.measuredAtNanos > sinceNanos && System.nanoTime() - spectrum.measuredAtNanos < 2_000_000_000L &&
                spectrum.presentationStartUs >= minimumTimeUs && spectrum.beforeDb.size == PcmSpectrumTap.SIZE / 2 + 1 &&
                spectrum.beforeDb.all { it.isFinite() } && spectrum.beforeDb.any { it > -60f } &&
                (if (aligned) spectrum.afterDb?.let { it.size == spectrum.beforeDb.size && it.all(Float::isFinite) } == true
                else spectrum.afterDb == null)
            if (valid) accepted = spectrum
            valid
        }
        return requireNotNull(accepted)
    }

    private fun item(id: String, file: File): MediaItem =
        MediaItem.Builder().setMediaId(id).setUri(file.toURI().toString()).build()

    private fun tone(rate: Int): File = helper.wav("spectrum-$rate", rate, rate * 30) { frame, channel ->
        (sin(2 * PI * (if (channel == 0) 997 else 1703) * frame / rate) * 8_192).toInt()
    }
}
