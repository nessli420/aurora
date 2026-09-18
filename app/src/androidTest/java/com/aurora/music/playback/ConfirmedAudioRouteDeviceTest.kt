package com.aurora.music.playback

import androidx.media3.common.MediaItem
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.AuroraApplication
import com.aurora.music.data.routes.ProcessingRouteKind
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ConfirmedAudioRouteDeviceTest {
    private val helper = PrecisionPlaybackDeviceTest()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as AuroraApplication).container

    @Before fun foreground() = helper.keepTargetForegroundForAudioFocus()
    @After fun cleanup() = helper.removeFixturesAndFinishActivity()

    @Test fun realServiceAudioTrackConfirmsOutputAndDiagnosticCategory() {
        helper.withProcessingFixture(0) { controller, _ ->
            val file = helper.wav("confirmed-route", 48000, 48000 * 8) { _, _ -> 0 }
            helper.main {
                controller.setMediaItem(MediaItem.fromUri(file.toURI().toString()))
                controller.prepare()
                controller.play()
            }
            helper.await("confirmed AudioTrack output", controller) {
                val route = container.settingsStore.processingRoutes.current.route
                helper.main { controller.isPlaying } && route.kind == ProcessingRouteKind.ANDROID &&
                    route.category != null && container.signalPath.value.device.evidence == "AudioTrack routed-device observation"
            }
            val observation = container.settingsStore.processingRoutes.current
            val route = observation.route
            val path = container.signalPath.value
            assertTrue(path.device.detail.startsWith(requireNotNull(route.category).label))
            assertFalse(path.device.detail.contains("Active route unknown"))
            route.key?.let { assertFalse(path.toDiagnosticReport().contains(it)) }
            helper.main { controller.seekTo(1000) }
            helper.await("same routed output after seek", controller) {
                container.settingsStore.processingRoutes.current.route == route && helper.main { controller.currentPosition > 1300 }
            }
            helper.main { controller.stop() }
            helper.await("idle clears route evidence", controller) {
                container.settingsStore.processingRoutes.current.route.kind == ProcessingRouteKind.IDLE && !container.signalPath.value.active
            }
        }
    }
}
