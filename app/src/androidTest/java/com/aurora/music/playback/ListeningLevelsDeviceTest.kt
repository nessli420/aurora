package com.aurora.music.playback

import android.media.AudioManager
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.AuroraApplication
import com.aurora.music.data.PcmLevels
import com.aurora.music.data.listening.*
import com.aurora.music.data.routes.*
import java.io.File
import java.lang.reflect.Proxy
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

@UnstableApi
class ListeningLevelsDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as AuroraApplication).container

    @Test fun sinkGainIncludesTheActualRendererMultiplier() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            var delegated = Float.NaN
            var observed = Float.NaN
            val delegate = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, args ->
                if (method.name == "setVolume") delegated = args!![0] as Float
                null
            } as AudioSink
            val sink = TappingAudioSink(delegate, VisualizerController(scope), onVolume = { observed = it })
            sink.setVolume(0.2f * 0.5f)
            assertEquals(0.1f, delegated, 0f)
            assertEquals(delegated, observed, 0f)
            sink.setVolume(0f)
            assertEquals(0f, observed, 0f)
        } finally { scope.cancel() }
    }

    @Test fun playbackPublishesVolumeRangeAndStopsWithoutInventingCalibration() {
        val helper = PrecisionPlaybackDeviceTest()
        helper.keepTargetForegroundForAudioFocus()
        try {
            helper.withProcessingFixture(0) { controller, _ ->
                val file = helper.wav("listening-levels", 48_000, 48_000 * 6) { frame, _ ->
                    (sin(frame * 2.0 * PI * 440.0 / 48_000) * 0.05 * 32_767).toInt()
                }
                val since = System.nanoTime()
                helper.main {
                    controller.setMediaItem(MediaItem.fromUri(file.toURI().toString()))
                    controller.prepare()
                    controller.play()
                }
                val audio = context.getSystemService(AudioManager::class.java)
                helper.await("listening output observations", controller) {
                    val measurements = container.signalPath.value.measurements
                    measurements?.after != null && measurements.before?.let {
                        it.measuredAtNanos > since && it.leftPeak in 0.049..0.051 && it.rightPeak in 0.049..0.051
                    } == true &&
                        container.listeningLevels.volumeStep.value == audio.getStreamVolume(AudioManager.STREAM_MUSIC) &&
                        container.listeningLevels.volumeMaximum.value == audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                }
                helper.main { controller.stop(); controller.clearMediaItems() }
                helper.await("listening estimate cleared on stop", controller) {
                    !container.signalPath.value.active && container.listeningLevels.estimate.value is ListeningEstimate.Unavailable
                }
            }
        } finally { helper.removeFixturesAndFinishActivity() }
    }

    @Test fun localCalibrationRequiresFreshConfirmationAfterReconnectAndRestart() = runBlocking {
        val file = File(context.noBackupFilesDir, "listening-test-${System.nanoTime()}.json")
        try {
            val routes = ProcessingRouteMonitor()
            val route = ProcessingRoute(ProcessingRouteKind.ANDROID, "android:22:" + "a".repeat(64), "Fixture DAC", category = OutputDeviceCategory.USB)
            routes.publish(route)
            val store = ListeningLevelStore(file, routes)
            val now = System.nanoTime()
            fun input() = ListeningObservation(routes.current, true,
                PcmLevels(48000, 2, 4800, 4800, 1.0, 1.0, 1.0 / sqrt(2.0), 1.0 / sqrt(2.0), 0, 0, 100000, now),
                0.5, 10, false, true, nowNanos = now, volumeMaximum = 15)
            store.observe(input())
            val profile = ListeningProfile("device-fixture", "Fixture headphones", route.key, route.label, 100.0,
                SensitivityUnit.DB_PER_VOLT, 32.0, 1.0, "Low", 0.0, 0.0, listOf(VolumeCalibration(10, -10.0)), "Test fixture", 3.0, 15)
            store.saveProfile(profile, routes.current).getOrThrow()
            store.confirm(profile.id, routes.current).getOrThrow()
            assertEquals(83.9794000867, (store.estimate.value as ListeningEstimate.Available).maximumDb!!, 1e-8)
            routes.publish(ProcessingRoute())
            routes.publish(route)
            store.observe(input())
            assertTrue(store.estimate.value is ListeningEstimate.Unavailable)
            store.confirm(profile.id, routes.current).getOrThrow()
            val restarted = ListeningLevelStore(file, routes)
            restarted.observe(input())
            assertTrue(restarted.estimate.value is ListeningEstimate.Unavailable)
            assertTrue(restarted.state.value.history.isEmpty())
        } finally { file.delete() }
    }
}
