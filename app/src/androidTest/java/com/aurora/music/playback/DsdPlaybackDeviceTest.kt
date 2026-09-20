package com.aurora.music.playback

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.AuroraApplication
import com.aurora.music.data.AudioPrefs
import com.aurora.music.data.ProcessingRack
import com.aurora.music.data.ProcessingRackNode
import com.aurora.music.data.RackNodeKind
import com.aurora.music.playback.dsd.DsdFixtures
import com.aurora.music.playback.dsd.DsdSourceInfo
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.pow

@UnstableApi
class DsdPlaybackDeviceTest {
    private val helper = PrecisionPlaybackDeviceTest()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as AuroraApplication).container
    private val files = ArrayList<File>()
    @Before fun foreground() = helper.keepTargetForegroundForAudioFocus()
    @After fun cleanup() {
        try { helper.removeFixturesAndFinishActivity() } finally { files.forEach(File::delete) }
    }

    @Test fun dsfAndDffPlayThroughRackWithMetadataSeekPauseQueueAndEos() {
        playRates(2_822_400, 5_644_800, 6)
    }

    @Test fun dsd256And512PlayThroughRackWithMetadataSeekPauseQueueAndEos() {
        playRates(11_289_600, 22_579_200, 4)
    }

    private fun playRates(dsfRate: Int, dffRate: Int, seconds: Int) {
        val dsf = fixture("dsf", DsdFixtures.dsf(DsdFixtures.tone(dsfRate, seconds), dsfRate, title = "DSF playback fixture"))
        val dff = fixture("dff", DsdFixtures.dff(DsdFixtures.tone(dffRate, seconds), dffRate, title = "DFF playback fixture"))
        helper.withProcessingFixture(0) { controller, _ ->
            val rack = ProcessingRack(enabled = true, name = "DSD fixture", nodes = listOf(ProcessingRackNode(
                UUID.randomUUID().toString(), "Gain", RackNodeKind.GAIN, audio = AudioPrefs(dspPreampDb = -6f))))
            runBlocking { container.settingsStore.setProcessingRack(rack).getOrThrow() }
            val queue = listOf(dsf, dff).mapIndexed { index, file -> MediaItem.Builder()
                .setMediaId("dsd-fixture-$index").setUri(file.toURI().toString()).build() }
            helper.main { controller.setMediaItems(queue); controller.prepare(); controller.play() }
            for ((index, expected) in listOf("DSF" to dsfRate, "DFF" to dffRate).withIndex()) {
                val since = System.nanoTime()
                helper.await("${expected.first} source plays through the graph", controller) {
                    val measurement = container.signalPath.value.measurements
                    val before = measurement?.before; val after = measurement?.after
                    helper.main { controller.currentMediaItemIndex == index && controller.isPlaying } &&
                        before != null && after != null && measurement.afterAvailable &&
                        before.sampleRate == 176400 && before.channels == 2 &&
                        before.measuredAtNanos > since && after.measuredAtNanos > since &&
                        before.leftRms > 0.1 && before.rightRms > 0.05 &&
                        abs(after.leftRms / before.leftRms - 10.0.pow(-6.0 / 20)) < 0.008 &&
                        abs(after.rightRms / before.rightRms - 10.0.pow(-6.0 / 20)) < 0.008
                }
                helper.await("${expected.first} source metadata", controller) {
                    helper.main {
                        val info = controller.currentTracks.groups.flatMap { group ->
                            (0 until group.length).mapNotNull { DsdSourceInfo.from(group.getTrackFormat(it)) }
                        }.firstOrNull()
                        info?.container == expected.first && info.bitRate == expected.second && info.channels == 2 &&
                            info.sampleCount == expected.second * seconds.toLong() &&
                            controller.mediaMetadata.title?.toString() == "${expected.first} playback fixture"
                    }
                }
                helper.await("DSD conversion appears in Signal Path", controller) {
                    val path = container.signalPath.value
                    path.codec == "${expected.first} / DSD" && path.source.format?.rateHz == expected.second &&
                        path.source.format?.bitDepth == 1 && path.decoder.format?.rateHz == 176400 &&
                        path.preservation == com.aurora.music.data.Preservation.MODIFIED &&
                        path.reasons.any { it.contains("DSD converted") }
                }
                helper.main {
                    assertEquals(seconds * 1000L, controller.duration)
                    assertEquals(2, controller.mediaItemCount)
                    assertNull(controller.playerError)
                    controller.pause()
                }
                helper.await("DSD pauses", controller) { helper.main { !controller.isPlaying } }
                val paused = helper.main { controller.currentPosition }
                SystemClock.sleep(180)
                helper.main {
                    assertTrue(abs(controller.currentPosition - paused) < 80)
                    controller.seekTo(seconds * 250L)
                    controller.play()
                }
                helper.await("DSD resumes after seek", controller) {
                    helper.main { controller.currentMediaItemIndex == index && controller.isPlaying && controller.currentPosition in (seconds * 250L + 150)..(seconds * 1000L - 500) }
                }
                val output = requireNotNull(container.signalPath.value.measurements?.after)
                assertEquals(0L, output.invalidSamples); assertEquals(0L, output.fullScaleSamples)
                helper.main { controller.seekTo(seconds * 1000L - 700) }
                if (index == 0) helper.await("DSF EOS advances to DFF", controller) {
                    helper.main { controller.currentMediaItemIndex == 1 && controller.isPlaying }
                } else helper.await("DFF reaches EOS", controller) {
                    helper.main { controller.playbackState == Player.STATE_ENDED && !controller.isPlaying }
                }
            }
        }
    }

    private fun fixture(extension: String, bytes: ByteArray) = File(context.cacheDir, "dsd-${UUID.randomUUID()}.$extension")
        .also { it.writeBytes(bytes); files += it }
}
