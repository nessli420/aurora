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
import androidx.media3.common.C
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first

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

    @Test fun liveSleepStreamUsesLiveTimelineAndNativeDsp() {
        val liveId = runBlocking {
            val session = container.settingsStore.session.first()
            if (session?.type == com.aurora.music.data.ServerType.YOUTUBE_MUSIC) {
                val auth = com.aurora.music.data.remote.YouTubeMusicWebSession.decode(container.youtubeMusicCredentials.read(session.token))
                val backend = com.aurora.music.data.YouTubeMusicBackend(session, com.aurora.music.data.remote.YouTubeMusicClient({ auth }))
                var page = backend.home()
                var found: String? = null
                repeat(4) {
                    if (found == null) {
                        found = page.sections.flatMap { it.items }.filterIsInstance<com.aurora.music.data.HomeFeedItem.Track>()
                            .firstOrNull { it.song.title.contains("24/7 deep sleep", ignoreCase = true) }?.song?.id
                        if (found == null) page.continuation?.let { page = backend.homePage(it) }
                    }
                }
                found
            } else null
        } ?: "OmnaqLn0aPs"
        val stream = container.youtubeResolver.resolvePlayback(liveId)
        assertNotNull("Live extraction failed for $liveId", stream)
        assertEquals("application/x-mpegURL", stream!!.mimeType)
        helper.withProcessingFixture(0) { controller, _ ->
            val since = System.nanoTime()
            helper.main {
                controller.setMediaItem(MediaItem.Builder().setMediaId(liveId).setUri(YouTubeMusicParser.sentinel(liveId))
                    .setMediaMetadata(MediaMetadata.Builder().setTitle("24/7 deep sleep stream check").build()).build())
                controller.prepare(); controller.play()
            }
            helper.await("live manifest produces processed audio", controller) {
                val m = container.signalPath.value.measurements
                helper.main { controller.isPlaying && controller.isCurrentMediaItemLive } &&
                    m?.afterAvailable == true && (m.after?.measuredAtNanos ?: 0) > since &&
                    (m.after?.leftRms ?: 0.0) > 0.000001
            }
            assertTrue(container.signalPath.value.processing.detail.contains("Custom"))
            assertTrue(container.signalPath.value.processing.detail.contains("Convolution"))
            File(context.getExternalFilesDir(null), "youtube-live-check.txt").writeText("videoId=$liveId\n" + container.signalPath.value.toDiagnosticReport())
        }
    }

    @Test fun videoToggleKeepsVodPositionAndProcessedAudio() {
        helper.withProcessingFixture(0) { controller, _ ->
            helper.main {
                controller.setMediaItem(MediaItem.Builder().setMediaId("youtube-video-fixture").setUri(YouTubeMusicParser.sentinel("Hu7hscHkfPw")).build())
                controller.prepare(); controller.play()
            }
            helper.await("VOD audio and video tracks", controller) {
                helper.main { controller.isPlaying && controller.duration > 30_000 && controller.currentTracks.isTypeSupported(C.TRACK_TYPE_VIDEO) }
            }
            helper.main {
                assertFalse(controller.isCurrentMediaItemLive)
                assertFalse(controller.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO))
                controller.seekTo(30_000)
            }
            val texture = helper.main {
                val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
                TextureView(activity).also { view ->
                    activity.addContentView(view, FrameLayout.LayoutParams(960, 540))
                    controller.setVideoTextureView(view)
                    controller.trackSelectionParameters = controller.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false).build()
                }
            }
            try {
                val since = System.nanoTime()
                helper.await("decoded video frame with processed audio", controller) {
                    val m = container.signalPath.value.measurements
                    helper.main {
                        val bitmap = texture.bitmap
                        val visible = bitmap?.let { b -> (1..8).any { x -> (1..4).any { y -> b.getPixel(b.width * x / 9, b.height * y / 5) and 0xFFFFFF != 0 } } } == true
                        bitmap?.recycle()
                        controller.isPlaying && controller.currentPosition >= 30_000 && controller.videoSize.width > 0 && visible
                    } && m?.afterAvailable == true && (m.after?.measuredAtNanos ?: 0) > since && (m.after?.leftRms ?: 0.0) > 0.0001
                }
                helper.main {
                    texture.bitmap?.let { bitmap ->
                        File(context.getExternalFilesDir(null), "youtube-video-check.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                    controller.clearVideoTextureView(texture)
                    controller.trackSelectionParameters = controller.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
                }
                val position = helper.main { controller.currentPosition }
                helper.await("audio continues after video is disabled", controller) {
                    helper.main { controller.isPlaying && controller.currentPosition > position + 500 && !controller.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO) }
                }
                assertTrue(container.signalPath.value.processing.detail.contains("Custom"))
                assertTrue(container.signalPath.value.processing.detail.contains("Convolution"))
            } finally {
                helper.main {
                    controller.clearVideoTextureView(texture)
                    controller.trackSelectionParameters = controller.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
                    (texture.parent as? ViewGroup)?.removeView(texture)
                }
            }
        }
    }
}
