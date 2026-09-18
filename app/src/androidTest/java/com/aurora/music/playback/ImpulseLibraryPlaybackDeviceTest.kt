package com.aurora.music.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.AuroraApplication
import com.aurora.music.data.*
import com.aurora.music.data.ir.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class ImpulseLibraryPlaybackDeviceTest {
    private val fixture = PrecisionPlaybackDeviceTest()
    private val container get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AuroraApplication).container
    @Before fun prepare() = fixture.keepTargetForegroundForAudioFocus()
    @After fun finish() = fixture.removeFixturesAndFinishActivity()

    @Test fun preparedStereoResponseReachesOutputAndSurvivesSeekAndBypass() {
        val store = container.settingsStore
        val original = runBlocking { store.exportPrefs() }
        val files = mutableListOf<File>()
        try {
            fixture.withProcessingFixture(0) { controller, _ ->
                var rack = ProcessingRack(enabled = true, name = "IR playback test", nodes = listOf(
                    ProcessingRackNode(UUID.randomUUID().toString(), "Convolution", RackNodeKind.CONVOLUTION)))
                runBlocking { store.setProcessingRack(rack).getOrThrow() }
                fun levels(rate: Int, left: Double, right: Double, since: Long) {
                    fixture.await("$rate Hz IR channel levels", controller) {
                        val m = container.signalPath.value.measurements
                        val before = m?.before; val after = m?.after
                        m?.playing == true && m.afterAvailable && before != null && after != null &&
                            before.sampleRate == rate && after.sampleRate == rate &&
                            before.measuredAtNanos > since && after.measuredAtNanos > since &&
                            before.leftRms > .02 && before.rightRms > .02 &&
                            abs(after.leftRms / before.leftRms - left) < .015 &&
                            abs(after.rightRms / before.rightRms - right) < .015
                    }
                    assertEquals(0L, container.signalPath.value.measurements?.after?.invalidSamples)
                    assertEquals(0L, container.signalPath.value.measurements?.after?.fullScaleSamples)
                }
                for (rate in listOf(48_000, 96_000)) {
                    val entry = runBlocking {
                        store.importImpulse(ByteArrayInputStream(impulse(rate)), "Stereo $rate.wav").getOrThrow().also { entry ->
                            files += File(entry.sourcePath)
                            store.prepareImpulse(entry.id, ImpulsePreparation(1, 2, ImpulseNormalization.PEAK_MINUS_1_DB)).getOrThrow()
                            files += File(store.impulseLibrary.first().first { it.id == entry.id }.prepared!!.path)
                            store.selectImpulse(entry.id, true).getOrThrow()
                        }
                    }
                    val wave = fixture.wav("ir-library-$rate.wav", rate, rate * 20) { frame, _ ->
                        (4000 * sin(2 * PI * 1000 * frame / rate)).toInt()
                    }
                    val since = System.nanoTime()
                    fixture.main {
                        controller.setMediaItem(MediaItem.Builder().setMediaId("ir-library-$rate").setUri(wave.toURI().toString())
                            .setMimeType(MimeTypes.AUDIO_WAV).build())
                        controller.prepare(); controller.play()
                    }
                    levels(rate, ImpulseLibraryFiles.NORMALIZED_PEAK, ImpulseLibraryFiles.NORMALIZED_PEAK / 2, since)
                    runBlocking { store.deleteImpulse(entry.id).getOrThrow() }
                    fixture.main { controller.seekTo(4_000) }
                    levels(rate, ImpulseLibraryFiles.NORMALIZED_PEAK, ImpulseLibraryFiles.NORMALIZED_PEAK / 2, System.nanoTime())
                }
                val since = System.nanoTime()
                rack = rack.copy(nodes = rack.nodes.map { it.copy(bypass = true) })
                runBlocking { store.setProcessingRack(rack).getOrThrow() }
                levels(96_000, 1.0, 1.0, since)
            }
        } finally {
            runBlocking { store.restoreBackupPrefs(original).getOrThrow() }
            files.forEach { it.delete() }
        }
    }

    private fun impulse(rate: Int): ByteArray = ByteBuffer.allocate(60).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(52); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(1); putShort(2); putInt(rate); putInt(rate * 4); putShort(4); putShort(16)
        put("data".toByteArray()); putInt(16)
        listOf(0, 0, 16384, 8192, 0, 0, 0, 0).forEach { putShort(it.toShort()) }
    }.array()
}
