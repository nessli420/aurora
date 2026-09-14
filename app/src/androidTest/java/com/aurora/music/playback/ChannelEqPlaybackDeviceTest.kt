package com.aurora.music.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.AuroraApplication
import com.aurora.music.data.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/** Real service/AudioTrack validation; fixture helper restores transport, processing and history. */
class ChannelEqPlaybackDeviceTest {
    private val fixture = PrecisionPlaybackDeviceTest()
    private val container get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AuroraApplication).container
    @Before fun prepare() = fixture.keepTargetForegroundForAudioFocus()
    @After fun finish() = fixture.removeFixturesAndFinishActivity()

    @Test fun independentEqChannelsWetAndRoutingEditsReachTheActualOutput() {
        fixture.withProcessingFixture(0) { controller, _ ->
            fun node(channel: RackEqChannel, gain: Float) = ProcessingRackNode(UUID.randomUUID().toString(), channel.name,
                RackNodeKind.EQ, audio = AudioPrefs(dspParametric = listOf(ParamBand(1000f, gain, 1f))), eqChannel = channel)
            var rack = ProcessingRack(enabled = true, name = "R2b channel playback", nodes = listOf(node(RackEqChannel.LEFT, -6f), node(RackEqChannel.RIGHT, -12f)))
            runBlocking { container.settingsStore.setProcessingRack(rack).getOrThrow() }
            fun levels(rate: Int, left: Double, right: Double, since: Long) {
                fixture.await("$rate Hz channel gains $left / $right", controller) {
                    val m = container.signalPath.value.measurements
                    val before = m?.before; val after = m?.after
                    m?.playing == true && m.afterAvailable && before != null && after != null && before.sampleRate == rate && after.sampleRate == rate &&
                        before.measuredAtNanos > since && after.measuredAtNanos > since && before.leftRms > .02 && before.rightRms > .02 &&
                        abs(after.leftRms / before.leftRms - left) < .012 && abs(after.rightRms / before.rightRms - right) < .012
                }
                assertEquals(0L, container.signalPath.value.measurements?.after?.invalidSamples)
                assertEquals(0L, container.signalPath.value.measurements?.after?.fullScaleSamples)
            }
            for (rate in listOf(48_000, 96_000)) {
                val wave = fixture.wav("r2b-channel-$rate.wav", rate, rate * 25) { frame, _ -> (4000 * sin(2 * PI * 1000 * frame / rate)).toInt() }
                val since = System.nanoTime()
                fixture.main { controller.setMediaItem(MediaItem.Builder().setMediaId("r2b-channel-$rate").setUri(wave.toURI().toString())
                    .setMimeType(MimeTypes.AUDIO_WAV).build()); controller.prepare(); controller.play() }
                levels(rate, 10.0.pow(-6.0 / 20), 10.0.pow(-12.0 / 20), since)
            }
            var since = System.nanoTime()
            rack = rack.copy(nodes = listOf(rack.nodes[0].copy(wet = .5f), rack.nodes[1].copy(bypass = true)))
            runBlocking { container.settingsStore.setProcessingRack(rack).getOrThrow() }
            levels(96_000, .5 + .5 * 10.0.pow(-6.0 / 20), 1.0, since)
            since = System.nanoTime()
            rack = rack.copy(nodes = listOf(rack.nodes[0].copy(eqChannel = RackEqChannel.RIGHT, wet = 1f), rack.nodes[1]))
            runBlocking { container.settingsStore.setProcessingRack(rack).getOrThrow() }
            levels(96_000, 1.0, 10.0.pow(-6.0 / 20), since)
            fixture.main { controller.seekTo(5_000) }
            levels(96_000, 1.0, 10.0.pow(-6.0 / 20), System.nanoTime())
        }
    }
}
