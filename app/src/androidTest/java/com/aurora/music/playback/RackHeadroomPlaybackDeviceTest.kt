package com.aurora.music.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.AuroraApplication
import com.aurora.music.data.AudioPrefs
import com.aurora.music.data.ParamBand
import com.aurora.music.data.ProcessingRack
import com.aurora.music.data.ProcessingRackNode
import com.aurora.music.data.RackNodeKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

class RackHeadroomPlaybackDeviceTest {
    private val fixture = PrecisionPlaybackDeviceTest()
    private val container get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AuroraApplication).container

    @Before fun prepare() = fixture.keepTargetForegroundForAudioFocus()
    @After fun finish() = fixture.removeFixturesAndFinishActivity()

    @Test fun automaticHeadroomAttenuationToggleAndBypassReachActualOutputAt48And96k() {
        fixture.withProcessingFixture(0) { controller, _ ->
            val rates = listOf(48_000, 96_000)
            val items = rates.map { rate ->
                val wave = fixture.wav("headroom-$rate.wav", rate, rate * 45) { frame, _ ->
                    (2000 * sin(2 * PI * 1000 * frame / rate)).toInt()
                }
                MediaItem.Builder().setMediaId("headroom-$rate").setUri(wave.toURI().toString())
                    .setMimeType(MimeTypes.AUDIO_WAV).build()
            }
            val equalizer = ProcessingRackNode(UUID.randomUUID().toString(), "Headroom EQ", RackNodeKind.EQ,
                audio = AudioPrefs(dspGraphicBands = emptyList(), dspParametric = listOf(ParamBand(1000f, 6f, 1f))))
            val base = ProcessingRack(enabled = true, name = "Headroom playback fixture", nodes = listOf(equalizer), autoHeadroom = true)

            fun queueUnchanged(index: Int) = fixture.main {
                assertEquals(items.map { it.mediaId }, (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId })
                assertEquals(index, controller.currentMediaItemIndex)
                assertNull(controller.playerError)
            }

            fun awaitGain(rate: Int, expected: Double, since: Long, description: (String) -> Boolean) {
                fixture.await("$rate Hz headroom gain $expected", controller) {
                    val path = container.signalPath.value
                    val measurements = path.measurements
                    val before = measurements?.before
                    val after = measurements?.after
                    measurements?.playing == true && measurements.afterAvailable && description(path.processing.detail) &&
                        before != null && after != null && before.sampleRate == rate && after.sampleRate == rate &&
                        before.measuredAtNanos > since && after.measuredAtNanos > since &&
                        before.leftRms > .02 && before.rightRms > .02 &&
                        abs(after.leftRms / before.leftRms - expected) < .012 &&
                        abs(after.rightRms / before.rightRms - expected) < .012
                }
                val after = requireNotNull(container.signalPath.value.measurements?.after)
                assertEquals(0L, after.invalidSamples)
                assertEquals(0L, after.fullScaleSamples)
                assertEquals(rate, container.signalPath.value.decoder.format?.rateHz)
            }

            fixture.main { controller.setMediaItems(items) }
            rates.forEachIndexed { index, rate ->
                var since = System.nanoTime()
                runBlocking { container.settingsStore.setProcessingRack(base).getOrThrow() }
                fixture.main { controller.seekTo(index, 0); controller.prepare(); controller.play() }
                awaitGain(rate, 10.0.pow(-1.0 / 20), since) { it.contains("Auto headroom -7.0 dB") }
                queueUnchanged(index)

                since = System.nanoTime()
                runBlocking { container.settingsStore.setProcessingRack(base.copy(autoHeadroom = false)).getOrThrow() }
                awaitGain(rate, 10.0.pow(6.0 / 20), since) { !it.contains("Auto headroom") && it.contains("Serial rack") }
                queueUnchanged(index)

                since = System.nanoTime()
                runBlocking { container.settingsStore.setProcessingRack(base.copy(nodes = listOf(equalizer.copy(bypass = true)))).getOrThrow() }
                awaitGain(rate, 1.0, since) { it.contains("Auto headroom 0.0 dB") && it.contains("EQ (bypassed)") }
                queueUnchanged(index)

                since = System.nanoTime()
                fixture.main { controller.seekTo(index, 5000) }
                awaitGain(rate, 1.0, since) { it.contains("Auto headroom 0.0 dB") }
                fixture.await("headroom seek position", controller) { fixture.main { controller.currentPosition >= 5000 } }
                queueUnchanged(index)
            }
            fixture.main { controller.pause() }
        }
    }
}
