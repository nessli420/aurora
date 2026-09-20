package com.aurora.music.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.AuroraApplication
import com.aurora.music.data.YouTubeMusicCredentials
import com.aurora.music.data.remote.YouTubeMusicParser
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class YouTubeMusicPlaybackDeviceTest {
    private val helper = PrecisionPlaybackDeviceTest()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as AuroraApplication).container
    @Before fun foreground() = helper.keepTargetForegroundForAudioFocus()
    @After fun cleanup() = helper.removeFixturesAndFinishActivity()

    @Test fun googleSessionIsEncryptedSurvivesReopenAndCanBeForgotten() {
        val store = YouTubeMusicCredentials(context)
        val account = com.aurora.music.data.remote.YouTubeMusicWebSession(
            "SAPISID=aurora-fixture-secret", "fixture-visitor", "fixture-channel", "1").encode()
        val reference = store.save(account)
        try {
            assertEquals(account, YouTubeMusicCredentials(context).read(reference))
            val disk = File(context.noBackupFilesDir, "youtube-music/$reference").readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(disk.contains(account))
            store.remove(reference)
            assertEquals("", store.read(reference))
        } finally { store.remove(reference) }
    }

    @Test fun exactYouTubeTrackDecodesThroughCustomDspAndConvolution() {
        // Public performance; no Google account or changes to the user's library are required.
        val videoId = "Hu7hscHkfPw"
        val sentinel = YouTubeMusicParser.sentinel(videoId)
        val resolved = container.youtubeResolver.resolveSentinel(Uri.parse(sentinel))
        assertNotNull("YouTube did not provide a playable stream for the exact video ID", resolved)
        helper.withProcessingFixture(0) { controller, _ ->
            val since = System.nanoTime()
            helper.main {
                controller.setMediaItem(MediaItem.Builder().setMediaId("youtube-device-fixture")
                    .setUri(sentinel).setMediaMetadata(MediaMetadata.Builder().setTitle("YouTube playback check").build()).build())
                controller.prepare()
                controller.play()
            }
            helper.await("YouTube decoded PCM reaches custom DSP and convolution", controller) {
                val path = container.signalPath.value
                val measurements = path.measurements
                measurements?.playing == true && measurements.afterAvailable &&
                    (measurements.before?.measuredAtNanos ?: 0L) > since &&
                    (measurements.after?.measuredAtNanos ?: 0L) > since &&
                    (measurements.before?.leftRms ?: 0.0) > 0.001 &&
                    (measurements.after?.leftRms ?: 0.0) > 0.0001 &&
                    (measurements.after?.leftRms ?: 0.0) / (measurements.before?.leftRms ?: 1.0) in 0.1..0.45 &&
                    path.processing.detail.contains("Custom") && path.processing.detail.contains("Convolution")
            }
            File(context.getExternalFilesDir(null), "youtube-music-signal-path.txt")
                .writeText(container.signalPath.value.toDiagnosticReport())
            val seekAt = System.nanoTime()
            helper.main { controller.pause(); controller.seekTo(30_000); controller.play() }
            helper.await("YouTube seek resumes processed playback", controller) {
                val after = container.signalPath.value.measurements?.after
                helper.main { controller.isPlaying && controller.currentPosition > 30_250 } &&
                    after != null && after.measuredAtNanos > seekAt && after.leftRms > 0.0001 && after.invalidSamples == 0L
            }
        }
    }
}
