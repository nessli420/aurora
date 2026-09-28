package com.aurora.music.playback

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.data.DownloadManager
import com.aurora.music.data.MediaBackend
import com.aurora.music.data.MusicRepository
import com.aurora.music.data.PlaybackReport
import com.aurora.music.data.PlaybackReportDispatcher
import com.aurora.music.data.PlaybackReportEvent
import com.aurora.music.data.PlaybackReportState
import com.aurora.music.data.PlaybackReportTarget
import com.aurora.music.data.PlaybackSourceIdentity
import com.aurora.music.data.PlexBackend
import com.aurora.music.data.ServerType
import com.aurora.music.data.Session
import com.aurora.music.data.remote.PlexClient
import com.aurora.music.data.remote.appClientInfo
import com.aurora.music.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@UnstableApi
class PlaybackReportingDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun actualPlayerReportsPauseSeekDuplicateTracksAndPrivacyChanges() {
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "playback-report-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = directory
            override fun getApplicationContext(): Context = this
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val allowed = AtomicBoolean(true)
        val reports = CopyOnWriteArrayList<PlaybackReport>()
        val session = Session("http://127.0.0.1:1", "Playback fixture", "", "fixture-token", ServerType.PLEX, "fixture")
        val provider = PlaybackSourceIdentity.fromSession(session, "")
        val delegate = PlexBackend(PlexClient(session, appClientInfo), { 0 }, { it })
        val backend = object : MediaBackend by delegate {
            override fun playbackReportTarget(song: Song): PlaybackReportTarget? {
                if (song.playbackSource?.providerId != provider.providerId) return null
                return PlaybackReportTarget(song) { reports += it }
            }
        }
        val repository = MusicRepository({ backend }, DownloadManager(isolated))
        val dispatcher = PlaybackReportDispatcher(scope, allowed = allowed::get)
        val controller = PlaybackReportingController(repository, dispatcher, allowed::get) { false }
        val audio = File(directory, "fixture.wav").apply { writeBytes(wav(seconds = 6)) }
        val replacementAudio = File(directory, "fixture-quality.wav").apply { writeBytes(audio.readBytes()) }
        val extras = Bundle().apply {
            putString("aurora.songId", "101")
            putString("aurora.rules.provider", provider.providerId)
            putInt("aurora.durationSec", 6)
        }
        val item = MediaItem.Builder().setMediaId("101").setUri(audio.toURI().toString())
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Fixture track").setArtist("Fixture artist")
                .setExtras(extras).build()).build()
        val player = main { ExoPlayer.Builder(context).build().apply { volume = 0f } }
        try {
            main {
                controller.observe(player)
                player.setMediaItems(listOf(item, item))
                player.prepare()
            }
            await("prepared without false start", player, controller) { player.playbackState == Player.STATE_READY }
            sampleFor(300, player, controller)
            assertTrue(reports.isEmpty())

            main { player.play() }
            await("actual playback reported", player, controller) {
                player.currentPosition >= 500 && reports.any { it.event == PlaybackReportEvent.START }
            }
            val firstSession = reports.first { it.event == PlaybackReportEvent.START }.sessionId
            main { player.pause() }
            await("pause reported", player, controller) {
                reports.any { it.sessionId == firstSession && it.state == PlaybackReportState.PAUSED }
            }
            val listenedAtPause = reports.last { it.state == PlaybackReportState.PAUSED }.listenedMs
            sampleFor(2000, player, controller)
            main { player.seekTo(4000) }
            await("paused seek reported", player, controller) {
                reports.any { it.sessionId == firstSession && it.state == PlaybackReportState.PAUSED && it.positionMs >= 4000 }
            }
            assertEquals(listenedAtPause, reports.last().listenedMs)
            assertFalse(reports.any { it.event == PlaybackReportEvent.SCROBBLE })

            main {
                val position = player.currentPosition
                player.replaceMediaItem(player.currentMediaItemIndex,
                    item.buildUpon().setUri(replacementAudio.toURI().toString()).build())
                player.seekTo(player.currentMediaItemIndex, position)
            }
            await("quality replacement is ready", player, controller) { player.playbackState == Player.STATE_READY }
            sampleFor(300, player, controller)
            assertEquals(1, reports.count { it.event == PlaybackReportEvent.START })
            assertFalse(reports.any { it.event == PlaybackReportEvent.STOP })

            main { player.play() }
            await("resume reported", player, controller) {
                reports.any { it.sessionId == firstSession && it.event == PlaybackReportEvent.PROGRESS &&
                    it.state == PlaybackReportState.PLAYING && it.positionMs >= 4000 }
            }
            main { player.seekTo(5900) }
            await("duplicate track gets a new occurrence", player, controller) {
                reports.count { it.event == PlaybackReportEvent.START } == 2
            }
            val secondSession = reports.last { it.event == PlaybackReportEvent.START }.sessionId
            assertNotEquals(firstSession, secondSession)
            val firstStop = reports.indexOfFirst { it.sessionId == firstSession && it.event == PlaybackReportEvent.STOP }
            val secondStart = reports.indexOfFirst { it.sessionId == secondSession && it.event == PlaybackReportEvent.START }
            assertTrue(firstStop in 1 until secondStart)
            assertTrue(reports[firstStop].positionMs >= 5900)
            assertFalse(reports.any { it.sessionId == firstSession && it.event == PlaybackReportEvent.SCROBBLE })

            await("second occurrence qualifies from actual listening", player, controller) {
                reports.any { it.sessionId == secondSession && it.event == PlaybackReportEvent.SCROBBLE }
            }
            val scrobble = reports.single { it.sessionId == secondSession && it.event == PlaybackReportEvent.SCROBBLE }
            assertTrue(scrobble.listenedMs >= 3000)
            allowed.set(false)
            main { controller.sample() }
            await("privacy change stops presence", player, controller) {
                reports.any { it.sessionId == secondSession && it.event == PlaybackReportEvent.STOP }
            }
            val afterPrivacy = reports.size
            sampleFor(400, player, controller)
            assertEquals(afterPrivacy, reports.size)
            assertEquals(1, reports.count { it.sessionId == secondSession && it.event == PlaybackReportEvent.STOP })

            allowed.set(true)
            main { controller.sample() }
            await("reporting resumes in a fresh session", player, controller) {
                reports.count { it.event == PlaybackReportEvent.START } == 3
            }
            val thirdSession = reports.last { it.event == PlaybackReportEvent.START }.sessionId
            assertNotEquals(secondSession, thirdSession)
            main { player.repeatMode = Player.REPEAT_MODE_ONE; player.seekTo(5900) }
            await("repeat one starts another occurrence", player, controller) {
                reports.count { it.event == PlaybackReportEvent.START } == 4
            }
            val fourthSession = reports.last { it.event == PlaybackReportEvent.START }.sessionId
            assertNotEquals(thirdSession, fourthSession)
            assertEquals(1, reports.count { it.sessionId == thirdSession && it.event == PlaybackReportEvent.STOP })
            assertFalse(reports.any { it.sessionId == thirdSession && it.event == PlaybackReportEvent.SCROBBLE })
            main { controller.close() }
            await("closing the controller stops its active session", player, controller) {
                reports.any { it.sessionId == fourthSession && it.event == PlaybackReportEvent.STOP }
            }
            assertEquals(1, reports.count { it.sessionId == fourthSession && it.event == PlaybackReportEvent.STOP })
            assertFalse(reports.any { it.sessionId == fourthSession && it.event == PlaybackReportEvent.SCROBBLE })
        } finally {
            main { controller.close(); player.release() }
            scope.cancel()
            directory.deleteRecursively()
        }
    }

    private fun <T> main(block: () -> T): T {
        val task = FutureTask(Callable(block))
        instrumentation.runOnMainSync(task)
        return task.get(10, TimeUnit.SECONDS)
    }

    private fun await(description: String, player: ExoPlayer, controller: PlaybackReportingController, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (main {
                player.playerError?.let { throw AssertionError(description, it) }
                controller.sample()
                condition()
            }) return
            SystemClock.sleep(50)
        }
        fail("Timed out: $description; ${main { "state=${player.playbackState}, position=${player.currentPosition}" }}")
    }

    private fun sampleFor(durationMs: Long, player: ExoPlayer, controller: PlaybackReportingController) {
        val deadline = SystemClock.elapsedRealtime() + durationMs
        while (SystemClock.elapsedRealtime() < deadline) {
            main {
                player.playerError?.let { throw AssertionError("Fixture playback failed", it) }
                controller.sample()
            }
            SystemClock.sleep(50)
        }
    }

    private fun wav(seconds: Int): ByteArray {
        val rate = 44100
        val dataSize = seconds * rate * 4
        return ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataSize); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(2); putInt(rate); putInt(rate * 4); putShort(4); putShort(16)
            put("data".toByteArray()); putInt(dataSize)
        }.array()
    }
}
