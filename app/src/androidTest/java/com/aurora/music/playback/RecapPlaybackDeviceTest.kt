package com.aurora.music.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.AuroraApplication
import com.aurora.music.data.listeningMillis
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RecapPlaybackDeviceTest {
    @Test fun listeningTimeExcludesPausedSeekedAndPrivateTime() {
        val helper = PrecisionPlaybackDeviceTest()
        val container = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AuroraApplication).container
        val store = container.settingsStore
        val discord = runBlocking { store.discord.first() }
        val lastfm = runBlocking { store.lastfm.first() }
        val brainz = runBlocking { store.listenBrainz.first() }
        val playback = runBlocking { store.playbackPrefs.first() }
        helper.keepTargetForegroundForAudioFocus()
        try {
            runBlocking { store.setDiscordEnabled(false); store.setLastfmEnabled(false); store.setListenBrainzEnabled(false); store.setScrobble(false) }
            helper.withProcessingFixture(0) { controller, _ ->
                val id = "recap-time-fixture-${System.nanoTime()}"
                val item = MediaItem.Builder().setMediaId(id).setUri(android.net.Uri.fromFile(helper.tone(48_000, 60)))
                    .setMediaMetadata(MediaMetadata.Builder().setTitle("Recap timing fixture").setArtist("Test").build()).build()
                helper.main { controller.setMediaItem(item); controller.prepare(); controller.play() }
                helper.await("fixture is playing", controller) { helper.main { controller.isPlaying } }
                runBlocking { store.setPrivateSession(false) }
                Thread.sleep(8500)
                helper.main { controller.seekTo(40_000) }
                Thread.sleep(2500)
                helper.main { controller.pause() }
                Thread.sleep(1500)
                fun measured() = container.playHistory.snapshot().filter { it.songId == id }.sumOf { it.listeningMillis }
                val heard = measured()
                assertTrue("Expected actual time, got $heard ms", heard in 7000L..13_000L)
                Thread.sleep(2200)
                assertEquals(heard, measured())
                runBlocking { store.setPrivateSession(true) }
                Thread.sleep(500)
                helper.main { controller.play() }
                Thread.sleep(3000)
                assertEquals(heard, measured())
                helper.main { controller.pause() }
            }
        } finally {
            runBlocking { store.setPrivateSession(true); store.setDiscordEnabled(discord.enabled); store.setLastfmEnabled(lastfm.enabled); store.setListenBrainzEnabled(brainz.enabled); store.setScrobble(playback.scrobble) }
            helper.removeFixturesAndFinishActivity()
        }
    }
}
