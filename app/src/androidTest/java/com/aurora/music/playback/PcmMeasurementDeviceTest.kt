package com.aurora.music.playback

import android.content.ComponentName
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.AuroraApplication
import com.aurora.music.data.AudioPrefs
import com.aurora.music.data.DspMode
import com.aurora.music.data.PrefsBackup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

class PcmMeasurementDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (context.applicationContext as AuroraApplication).container
    private val lifecycleFixture = PrecisionPlaybackDeviceTest()

    @Before fun foregroundAndPreserveUserState() = lifecycleFixture.keepTargetForegroundForAudioFocus()
    @After fun restoreUserStateAndFinishActivity() = lifecycleFixture.removeFixturesAndFinishActivity()

    @Test fun sinkRetriesCountOnlyConsumedFramesAndLeavePcmUntouched() {
        var calls = 0
        val delegate = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, arguments ->
            when (method.name) {
                "handleBuffer" -> {
                    val input = requireNotNull(arguments)[0] as ByteBuffer
                    when (calls++) {
                        0, 2 -> false // Sink applies backpressure without consuming any bytes.
                        1 -> { input.position(input.position() + 400); false }
                        else -> { input.position(input.limit()); true }
                    }
                }
                "hashCode" -> 1
                "equals" -> false
                "toString" -> "Backpressure test sink"
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    else -> null
                }
            }
        } as AudioSink
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val meter = PcmLevelMeter()
            var configured: Format? = null
            val sink = TappingAudioSink(delegate, VisualizerController(scope), meter) { configured = it }
            val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setPcmEncoding(C.ENCODING_PCM_16BIT).setChannelCount(2).setSampleRate(1_000).build()
            sink.configure(format, 0, null)
            assertEquals(format, configured)
            val buffer = ByteBuffer.allocateDirect(800).order(ByteOrder.LITTLE_ENDIAN)
            repeat(200) { buffer.putShort(8_192); buffer.putShort(-4_096) }
            buffer.flip()
            val original = ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
            buffer.order(ByteOrder.BIG_ENDIAN)
            assertFalse(sink.handleBuffer(buffer, 500_000, 1))
            assertNull(meter.snapshot())
            assertEquals(0, buffer.position())
            assertFalse(sink.handleBuffer(buffer, 500_000, 1))
            assertEquals(100L, requireNotNull(meter.snapshot()).framesSinceReset)
            assertEquals(600_000L, requireNotNull(meter.snapshot()).presentationEndUs)
            assertEquals(400, buffer.position())
            assertFalse(sink.handleBuffer(buffer, 500_000, 1))
            assertEquals(100L, requireNotNull(meter.snapshot()).framesSinceReset)
            assertTrue(sink.handleBuffer(buffer, 500_000, 1))
            val final = requireNotNull(meter.snapshot())
            assertEquals(200L, final.framesSinceReset)
            assertEquals(700_000L, final.presentationEndUs)
            assertEquals(0.25, final.leftRms, 0.0)
            assertEquals(0.125, final.rightRms, 0.0)
            assertEquals(ByteOrder.BIG_ENDIAN, buffer.order())
            val after = ByteArray(buffer.limit()).also { buffer.duplicate().apply { position(0) }.get(it) }
            assertArrayEquals(original, after)
            sink.flush()
            assertNull(meter.snapshot())
            sink.reset()
            assertNull(configured)
        } finally {
            scope.cancel()
        }
    }

    @Test fun liveSignalPathMeasuresCustomPreampAttenuation() {
        val store = container.settingsStore
        val original = runBlocking { store.processingSettings.first() }
        val originalAutoEq = runBlocking { store.autoEqAutoSwitch.first() }
        assumeFalse(original.playback.bitPerfectUsb)
        assumeFalse(container.mixController.activeProject != null)
        val controller = main {
            MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        }.get(10, TimeUnit.SECONDS)
        if (main { controller.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE }) {
            main { controller.release() }
            assumeFalse("Keep remote playback untouched", true)
        }
        val queue = main { (0 until controller.mediaItemCount).map(controller::getMediaItemAt) }
        val index = main { controller.currentMediaItemIndex }
        val position = main { controller.currentPosition }
        val repeat = main { controller.repeatMode }
        val playing = main { controller.playWhenReady }
        val playbackParameters = main { controller.playbackParameters }
        val file = fixture()
        try {
            main { controller.pause() }
            runBlocking {
                store.setAutoEqAutoSwitch(false)
                writeAudio(AudioPrefs(dspMode = DspMode.CUSTOM, dspLimiterEnabled = false))
                store.setProcessingRack(original.rack.copy(enabled = false)).getOrThrow()
                store.setMono(false)
                store.setSkipSilence(false)
                store.setCrossfade(0)
            }
            val startedAt = System.nanoTime()
            main {
                controller.setPlaybackSpeed(1f)
                controller.setMediaItem(MediaItem.fromUri(file.toURI().toString()))
                controller.repeatMode = Player.REPEAT_MODE_OFF
                controller.prepare()
                controller.play()
            }
            await("flat before/after PCM measurements") {
                val measurement = container.signalPath.value.measurements
                val before = measurement?.before
                val after = measurement?.after
                measurement != null && measurement.playing && measurement.afterAvailable &&
                    before != null && after != null && before.measuredAtNanos > startedAt && after.measuredAtNanos > startedAt &&
                    before.leftRms > 0.015 && abs(after.leftRms / before.leftRms - 1.0) < 0.04
            }
            val flat = requireNotNull(container.signalPath.value.measurements)
            val flatBefore = requireNotNull(flat.before)
            assertEquals(44_100, flatBefore.sampleRate)
            assertEquals(2, flatBefore.channels)
            assertFalse(flat.overlappingPlayers)
            val changedAt = System.nanoTime()
            runBlocking { store.setDspPreamp(-12f) }
            val expectedGain = 10.0.pow(-12.0 / 20)
            await("measured -12 dB custom preamp") {
                val measurement = container.signalPath.value.measurements
                val before = measurement?.before
                val after = measurement?.after
                measurement != null && before != null && after != null && measurement.playing && measurement.afterAvailable &&
                    before.measuredAtNanos > changedAt && after.measuredAtNanos > changedAt &&
                    before.leftRms > 0.015 && abs(after.leftRms / before.leftRms - expectedGain) < 0.012 &&
                    abs(after.rightRms / before.rightRms - expectedGain) < 0.015
            }
            val attenuated = requireNotNull(container.signalPath.value.measurements)
            val attenuatedAfter = requireNotNull(attenuated.after)
            assertEquals(flatBefore.leftRms, requireNotNull(attenuated.before).leftRms, 0.001)
            assertEquals(0L, attenuatedAfter.invalidSamples)
            assertEquals(0L, attenuatedAfter.fullScaleSamples)
            main { assertTrue(controller.isPlaying); assertEquals(1, controller.mediaItemCount) }
        } finally {
            main { controller.pause() }
            runBlocking {
                // Enabling a rack selects Custom mode; restore the original audio mode afterward.
                store.setProcessingRack(original.rack).getOrThrow()
                writeAudio(original.audio)
                store.setMono(original.playback.monoAudio)
                store.setSkipSilence(original.playback.skipSilence)
                store.setCrossfade(original.playback.crossfadeSec)
                store.setAutoEqAutoSwitch(originalAutoEq)
            }
            main {
                controller.playbackParameters = playbackParameters
                controller.repeatMode = repeat
                if (queue.isNotEmpty()) {
                    controller.setMediaItems(queue, index.coerceIn(queue.indices), position)
                    controller.prepare()
                    if (playing) controller.play()
                } else controller.clearMediaItems()
                controller.release()
            }
            file.delete()
        }
    }

    private fun <T> main(block: () -> T): T {
        val task = FutureTask(Callable(block))
        instrumentation.runOnMainSync(task)
        return task.get()
    }

    private fun await(label: String, check: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 12_000
        while (SystemClock.elapsedRealtime() < until) {
            if (check()) return
            SystemClock.sleep(40)
        }
        fail("Timed out: $label")
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

    private fun fixture(): File = File(context.cacheDir, "pcm-measurement-${System.nanoTime()}.wav").also { file ->
        val rate = 44_100
        val frames = rate * 30
        file.outputStream().buffered().use { output ->
            fun le(value: Int, bytes: Int) { repeat(bytes) { output.write(value ushr (8 * it) and 255) } }
            output.write("RIFF".toByteArray()); le(36 + frames * 4, 4)
            output.write("WAVEfmt ".toByteArray()); le(16, 4); le(1, 2); le(2, 2)
            le(rate, 4); le(rate * 4, 4); le(4, 2); le(16, 2)
            output.write("data".toByteArray()); le(frames * 4, 4)
            repeat(frames) { i ->
                val wave = sin(2 * PI * 440 * i / rate)
                le((wave * 1_024).toInt(), 2); le((wave * 512).toInt(), 2)
            }
        }
    }
}
