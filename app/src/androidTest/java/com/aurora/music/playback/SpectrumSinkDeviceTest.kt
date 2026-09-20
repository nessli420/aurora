package com.aurora.music.playback

import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.playback.engine.OutputRateMode
import com.aurora.music.playback.engine.OutputRatePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

@UnstableApi
class SpectrumSinkDeviceTest {
    @Test fun realResampledAudioTrackKeepsSourceSpectrumAcrossFlush() {
        val thread = HandlerThread("spectrum-sink-fixture").apply { start() }
        val task = FutureTask(Callable {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val before = PcmLevelMeter()
            val after = PcmLevelMeter()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            var track: AudioTrack? = null
            val errors = mutableListOf<Exception>()
            val base = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(true)
                .setAudioTrackProvider { configuration, attributes, session ->
                    DefaultAudioSink.AudioTrackProvider.DEFAULT.getAudioTrack(configuration, attributes, session).also { track = it }
                }.build()
            val precision = PrecisionAudioSink(base, PrecisionBlockProcessor(), after,
                { OutputRatePolicy(mode = OutputRateMode.FIXED, fixedRate = 48_000) }, { intArrayOf(48_000) })
            val sink = TappingAudioSink(precision, VisualizerController(scope), before)
            sink.setListener(object : AudioSink.Listener {
                override fun onPositionDiscontinuity() = Unit
                override fun onUnderrun(bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) = Unit
                override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) = Unit
                override fun onAudioSinkError(audioSinkError: Exception) { errors += audioSinkError }
            })
            try {
                sink.setVolume(0f)
                sink.configure(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW)
                    .setPcmEncoding(C.ENCODING_PCM_FLOAT).setSampleRate(44_100).setChannelCount(2).build(), 0, null)
                var previousCapture = 0L
                repeat(2) { pass ->
                    if (pass > 0) {
                        sink.flush()
                        assertTrue(before.spectrum.windows().isEmpty())
                        assertTrue(after.spectrum.windows().isEmpty())
                    }
                    val startedAt = System.nanoTime()
                    val startUs = 1_000_000L + pass * 8_000_000L
                    val samples = ByteBuffer.allocateDirect(22_050 * 8).order(ByteOrder.LITTLE_ENDIAN)
                    repeat(22_050) { frame ->
                        samples.putFloat((sin(2 * PI * 997 * frame / 44_100) * .25).toFloat())
                        samples.putFloat((sin(2 * PI * 1703 * frame / 44_100) * .125).toFloat())
                    }
                    samples.flip()
                    sink.play()
                    val deadline = SystemClock.elapsedRealtime() + 8_000
                    while (!sink.handleBuffer(samples, startUs, 1)) {
                        assertTrue("Real AudioTrack consumes resampled PCM", SystemClock.elapsedRealtime() < deadline)
                        SystemClock.sleep(2)
                    }
                    val observed = requireNotNull(track)
                    while (observed.playbackHeadPosition == 0) {
                        assertTrue("Hardware playback head advances", SystemClock.elapsedRealtime() < deadline)
                        SystemClock.sleep(2)
                    }
                    do {
                        sink.playToEndOfStream()
                        assertTrue("Resampled AudioTrack drains", SystemClock.elapsedRealtime() < deadline)
                        SystemClock.sleep(2)
                    } while (!sink.isEnded)
                    assertTrue("No AudioTrack errors: $errors", errors.isEmpty())
                    assertTrue(precision.precisionActive)
                    assertEquals(48_000, precision.configuredOutputSampleRate)
                    assertNull(precision.rateFallbackReason)
                    assertEquals(48_000, observed.sampleRate)
                    assertEquals(C.ENCODING_PCM_FLOAT, observed.audioFormat)
                    assertEquals(44_100, requireNotNull(before.snapshot()).sampleRate)
                    assertEquals(48_000, requireNotNull(after.snapshot()).sampleRate)
                    assertTrue(after.spectrum.windows().isNotEmpty())
                    val spectrum = requireNotNull(PcmSpectrumAnalyzer.snapshot(before.spectrum.windows(), after.spectrum.windows()))
                    assertEquals(44_100, spectrum.sampleRate)
                    assertNull("Different sample rates are not presented as an aligned comparison", spectrum.afterDb)
                    assertTrue(spectrum.beforeDb.all(Float::isFinite))
                    assertTrue(spectrum.beforeDb.any { it > -30f })
                    assertTrue(spectrum.presentationStartUs >= startUs)
                    assertTrue(spectrum.measuredAtNanos > startedAt && spectrum.measuredAtNanos > previousCapture)
                    previousCapture = spectrum.measuredAtNanos
                }
            } finally {
                sink.release()
                scope.cancel()
            }
        })
        try {
            Handler(thread.looper).post(task)
            task.get(25, TimeUnit.SECONDS)
        } finally {
            thread.quitSafely()
            thread.join(5_000)
        }
    }
}
