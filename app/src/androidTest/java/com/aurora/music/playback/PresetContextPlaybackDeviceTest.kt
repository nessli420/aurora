package com.aurora.music.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.AuroraApplication
import com.aurora.music.data.PlaybackCollectionIdentity
import com.aurora.music.data.PlaybackSourceIdentity
import com.aurora.music.data.Session
import com.aurora.music.data.rules.RuleSource
import com.aurora.music.model.Song
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PresetContextPlaybackDeviceTest {
    private val helper = PrecisionPlaybackDeviceTest()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store get() = (context.applicationContext as AuroraApplication).container.settingsStore
    @Before fun foreground() = helper.keepTargetForegroundForAudioFocus()
    @After fun cleanup() = helper.removeFixturesAndFinishActivity()

    @Test fun realPlaybackPublishesObservedRateCodecAndQueueOrigin() {
        helper.withProcessingFixture(0) { controller, _ ->
            val file = helper.wav("rule-context", 48000, 48000 * 8) { _, _ -> 0 }
            val source = PlaybackSourceIdentity.fromSession(Session("fixture", "listener", "", ""), "album", RuleSource.LOCAL_FILE)
            val playlist = PlaybackCollectionIdentity(PlaybackSourceIdentity.scoped(source.providerId!!, "playlist", "evening"), "Evening")
            val song = Song("context-fixture", "Context", "Fixture", "Test album", "", 8, streamUrl = file.toURI().toString(),
                genre = "Ambient", suffix = "wav", sampleRateHz = 192000, playbackSource = source, playbackCollection = playlist)
            val item = MediaItem.Builder().setMediaId(song.id).setUri(song.streamUrl).setMediaMetadata(MediaMetadata.Builder()
                .setAlbumTitle(song.album).setExtras(PresetContextPublisher.extras(song)).build()).build()
            helper.main { controller.setMediaItem(item); controller.prepare(); controller.play() }
            helper.await("observed rule playback context", controller) {
                val playback = store.presetRuleContext.current.playback
                playback.active && playback.mediaId == song.id && playback.sampleRateHz == 48000 && playback.codec == "pcm"
            }
            val actual = store.presetRuleContext.current.playback
            assertEquals(source.providerId, actual.providerId)
            assertEquals(source.albumId, actual.albumId)
            assertEquals(playlist.id, actual.playlistId)
            assertEquals(setOf("Ambient"), actual.genres)
            assertEquals("wav", actual.container)
            helper.main { controller.stop() }
            helper.await("idle clears rule metadata", controller) {
                val stopped = store.presetRuleContext.current.playback
                !stopped.active && stopped.playlistId == null && stopped.providerId == null
            }
        }
    }
}
