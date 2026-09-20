package com.aurora.music.playback

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.AuroraApplication
import com.aurora.music.data.AppContainer
import com.aurora.music.data.LocalBackend
import com.aurora.music.data.LocalStore
import com.aurora.music.data.MediaBackend
import com.aurora.music.data.ServerType
import com.aurora.music.data.Session
import com.aurora.music.model.Song
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

/** Exercises the real service actions with a local PCM fixture; no library/account writes. */
class AlarmPlaybackDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun <T> main(block: () -> T): T {
        val task = FutureTask(Callable(block))
        instrumentation.runOnMainSync(task)
        return task.get()
    }

    private fun await(label: String, check: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 8_000
        while (SystemClock.elapsedRealtime() < until) {
            if (check()) return
            SystemClock.sleep(40)
        }
        throw AssertionError("Timed out: $label")
    }

    private fun send(action: String) = main {
        context.startService(Intent(context, PlaybackService::class.java).setAction(action))
    }

    @Test fun alarmActionStartsLocalMusicAndDismissKeepsItStopped() = withFixture { fixture, controller ->
        send(PlaybackService.ACTION_ALARM)
        await("alarm fixture plays") {
            main { controller.currentMediaItem?.mediaId == "song_${fixture.song.id}" && controller.isPlaying }
        }
        main {
            assertEquals(1, controller.mediaItemCount)
            // The wake fade should still be in its first seconds, not immediately full volume.
            assertTrue(controller.volume < 0.5f)
        }
        send(PlaybackService.ACTION_ALARM_DISMISS)
        await("alarm dismissed") { main { !controller.playWhenReady && controller.mediaItemCount == 0 } }
        assertStoppedFor(controller, 800)
        assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications.any {
            it.notification.channelId == "aurora_alarm"
        })
    }

    @Test fun dismissDuringLibraryLookupCannotRestartMusicLater() = withFixture(delayed = true) { fixture, controller ->
        send(PlaybackService.ACTION_ALARM)
        runBlocking { withTimeout(5_000) { fixture.lookupStarted.await() } }
        send(PlaybackService.ACTION_ALARM_DISMISS)
        // Let the service process the dismiss before the delayed library response is released.
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200)
        fixture.lookupGate?.complete(Unit)
        assertStoppedFor(controller, 1_200)
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun alarmStopsNetworkPlaybackAndUsesTheLocalSession() {
        val lifecycle = PrecisionPlaybackDeviceTest()
        lifecycle.keepTargetForegroundForAudioFocus()
        val container = (context.applicationContext as AuroraApplication).container
        val savedPrefs = runBlocking { container.settingsStore.exportPrefs() }
        try {
            runBlocking { container.settingsStore.setDspMode(com.aurora.music.data.DspMode.OFF) }
            withFixture { fixture, controller ->
                com.aurora.music.playback.network.NetworkOutputServiceDeviceTest.FakeDlna().use { receiver ->
                    try {
                        main {
                            controller.setMediaItem(MediaItem.Builder().setMediaId("remote-alarm-fixture")
                                .setUri(fixture.song.streamUrl).setMimeType("audio/wav").build())
                            controller.prepare()
                            controller.play()
                            container.networkOutput.connect(com.aurora.music.playback.network.NetworkTarget.Dlna(receiver.renderer))
                        }
                        await("network fixture playing") { receiver.playing }
                        send(PlaybackService.ACTION_ALARM)
                        await("alarm takes local playback") {
                            main { container.networkOutput.state.value.receiverName == null &&
                                controller.currentMediaItem?.mediaId == "song_${fixture.song.id}" && controller.isPlaying }
                        }
                        assertFalse(receiver.playing)
                        send(PlaybackService.ACTION_ALARM_DISMISS)
                        await("alarm dismissed after network handoff") { main { controller.mediaItemCount == 0 } }
                        assertStoppedFor(controller, 800)
                    } finally {
                        main { container.networkOutput.disconnect() }
                        await("network fixture disconnected") { container.networkOutput.state.value.receiverName == null }
                    }
                }
            }
        } finally {
            runBlocking { container.settingsStore.importPrefs(savedPrefs) }
            lifecycle.removeFixturesAndFinishActivity()
        }
    }

    private fun assertStoppedFor(controller: MediaController, durationMs: Long) {
        val until = SystemClock.elapsedRealtime() + durationMs
        do {
            main {
                assertFalse("Dismissed alarm restarted playback", controller.playWhenReady)
                assertEquals("Dismissed alarm restored its queue", 0, controller.mediaItemCount)
            }
            SystemClock.sleep(40)
        } while (SystemClock.elapsedRealtime() < until)
    }

    private fun withFixture(delayed: Boolean = false, check: (FixtureBackend, MediaController) -> Unit) {
        val container = (context.applicationContext as AuroraApplication).container
        runBlocking { withTimeout(5_000) { container.sessionReady.first { it != null } } }
        assumeFalse("Leave an active exclusive USB route untouched", runBlocking { container.settingsStore.playbackPrefs.first().bitPerfectUsb })
        assumeFalse("Leave an active Mix session untouched", container.mixController.activeProject != null)
        val future = main {
            MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        }
        val controller = future.get(10, TimeUnit.SECONDS)
        if (main { controller.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE }) {
            main { controller.release() }
            assumeFalse("Leave a remote Cast session untouched", true)
        }
        val previous = main {
            QueueSnapshot(
                (0 until controller.mediaItemCount).map(controller::getMediaItemAt),
                controller.currentMediaItemIndex, controller.currentPosition,
                controller.playWhenReady, controller.repeatMode, controller.playbackParameters, controller.volume,
            )
        }
        val backendField = AppContainer::class.java.getDeclaredField("backend").apply { isAccessible = true }
        val originalBackend = container.backend
        val wave = wav()
        val fallback = originalBackend ?: LocalBackend(
            container.localLibrary, LocalStore(context),
            Session("Alarm fixture", "Fixture", "", "local", ServerType.LOCAL),
        )
        val fixture = FixtureBackend(fallback, Song(
            "alarm-qa-${System.nanoTime()}", "Alarm fixture", "Aurora QA", "", "", 12,
            streamUrl = wave.toURI().toString(),
        ), if (delayed) CompletableDeferred() else null)
        try {
            main { controller.pause(); controller.clearMediaItems() }
            backendField.set(container, fixture)
            check(fixture, controller)
        } finally {
            send(PlaybackService.ACTION_ALARM_DISMISS)
            instrumentation.waitForIdleSync()
            SystemClock.sleep(150)
            fixture.lookupGate?.complete(Unit)
            SystemClock.sleep(150)
            // Also clear a late lookup if running against the pre-fix service.
            send(PlaybackService.ACTION_ALARM_DISMISS)
            instrumentation.waitForIdleSync()
            SystemClock.sleep(150)
            backendField.set(container, originalBackend)
            main {
                controller.pause()
                if (previous.items.isNotEmpty()) {
                    controller.setMediaItems(previous.items, previous.index.coerceIn(previous.items.indices), previous.position)
                    controller.prepare()
                } else controller.clearMediaItems()
                controller.repeatMode = previous.repeatMode
                controller.playbackParameters = previous.parameters
                controller.volume = previous.volume
                if (previous.playing && previous.items.isNotEmpty()) controller.play()
                controller.release()
            }
            wave.delete()
        }
    }

    private data class QueueSnapshot(
        val items: List<MediaItem>, val index: Int, val position: Long, val playing: Boolean,
        val repeatMode: Int, val parameters: PlaybackParameters, val volume: Float,
    )

    private class FixtureBackend(
        private val delegate: MediaBackend,
        val song: Song,
        val lookupGate: CompletableDeferred<Unit>?,
    ) : MediaBackend by delegate {
        val lookupStarted = CompletableDeferred<Unit>()
        override suspend fun starredSongs(): List<Song> {
            lookupStarted.complete(Unit)
            lookupGate?.await()
            return listOf(song)
        }
        override suspend fun songFor(id: String): Song? = song.takeIf { id == it.id }
        override suspend fun scrobble(id: String) { /* The test fixture must not be scrobbled to a server. */ }
    }

    private fun wav(): File {
        val file = File(context.cacheDir, "alarm-qa-${System.nanoTime()}.wav")
        val rate = 44_100
        val count = rate * 12
        val size = count * 4
        file.outputStream().buffered().use { out ->
            fun le(value: Int, bytes: Int) { repeat(bytes) { out.write(value ushr (it * 8) and 255) } }
            out.write("RIFF".toByteArray()); le(size + 36, 4); out.write("WAVEfmt ".toByteArray()); le(16, 4)
            le(1, 2); le(2, 2); le(rate, 4); le(rate * 4, 4); le(4, 2); le(16, 2)
            out.write("data".toByteArray()); le(size, 4)
            for (i in 0 until count) {
                val sample = (sin(2 * PI * 440 * i / rate) * 0.03 * 32767).toInt()
                le(sample, 2); le(sample, 2)
            }
        }
        return file
    }
}
