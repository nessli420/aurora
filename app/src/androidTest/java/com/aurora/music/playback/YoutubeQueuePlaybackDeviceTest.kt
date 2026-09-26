package com.aurora.music.playback

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@UnstableApi
class YoutubeQueuePlaybackDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun mixedQueueWaitsForSlowYoutubeResolutionAndContinuesWithoutActivity() {
        val entered = AtomicBoolean()
        val ready = CompletableDeferred<Unit>()
        withPlayer(resolve = { _, url ->
            entered.set(true)
            ready.await()
            YoutubeResolver.PlaybackStream(url)
        }) { fixture ->
            main {
                fixture.player.setMediaItems(fixture.mixedQueue())
                fixture.player.prepare()
                fixture.player.play()
            }
            await("server track plays while youtube is resolving", fixture) {
                entered.get() && fixture.player.currentMediaItemIndex == 0 &&
                    fixture.player.isPlaying && fixture.player.currentPosition >= 250
            }
            assertFalse(ready.isCompleted)
            await("slow youtube keeps the queue waiting", fixture) {
                fixture.player.playbackState == Player.STATE_BUFFERING &&
                    fixture.player.playWhenReady && !fixture.player.isPlaying
            }
            ready.complete(Unit)
            await("youtube audio decodes after the delay", fixture) {
                fixture.player.currentMediaItemIndex == 1 && fixture.player.isPlaying &&
                    fixture.player.currentPosition >= 250
            }
            main { assertEquals(fixture.youtubeId, fixture.player.currentMediaItem?.mediaId) }
            await("the next server track plays", fixture) {
                fixture.player.currentMediaItemIndex == 2 && fixture.player.isPlaying &&
                    fixture.player.currentPosition >= 250
            }
            main { assertEquals(3, fixture.player.mediaItemCount) }
            assertTrue(fixture.requests.containsAll(listOf("/first.wav", "/youtube.wav", "/last.wav")))
            assertTrue(fixture.errors.isEmpty())
        }
    }

    @Test fun transientYoutubeResolutionFailureRetriesWithoutAbortingTheMixedQueue() {
        val attempts = AtomicInteger()
        withPlayer(resolve = { _, url ->
            if (attempts.incrementAndGet() == 1) throw IOException("fixture resolver is temporarily unavailable")
            YoutubeResolver.PlaybackStream(url)
        }) { fixture ->
            main {
                fixture.player.setMediaItems(fixture.mixedQueue())
                fixture.player.prepare()
                fixture.player.play()
            }
            await("the current server track survives a future youtube failure", fixture) {
                attempts.get() >= 1 && fixture.player.currentMediaItemIndex == 0 &&
                    fixture.player.isPlaying && fixture.player.currentPosition >= 250
            }
            await("youtube retries and starts", fixture) {
                fixture.player.currentMediaItemIndex == 1 && fixture.player.isPlaying &&
                    fixture.player.currentPosition >= 250
            }
            assertEquals(2, attempts.get())
            await("queue continues after the recovered youtube item", fixture) {
                fixture.player.currentMediaItemIndex == 2 && fixture.player.isPlaying &&
                    fixture.player.currentPosition >= 250
            }
            assertTrue(fixture.errors.isEmpty())
        }
    }

    @Test fun replacingAnUnresolvedYoutubeItemCancelsItWithoutTouchingTheNewQueue() {
        val entered = AtomicBoolean()
        val cancelled = AtomicBoolean()
        val ready = CompletableDeferred<Unit>()
        withPlayer(resolve = { _, url ->
            entered.set(true)
            try {
                ready.await()
                YoutubeResolver.PlaybackStream(url)
            } finally {
                if (!ready.isCompleted) cancelled.set(true)
            }
        }) { fixture ->
            main {
                fixture.player.setMediaItem(fixture.mixedQueue()[1])
                fixture.player.prepare()
                fixture.player.play()
            }
            await("youtube resolution starts", fixture) { entered.get() }
            main {
                fixture.player.setMediaItem(fixture.mixedQueue().last())
                fixture.player.prepare()
                fixture.player.play()
            }
            await("replacement plays and old resolution is cancelled", fixture) {
                cancelled.get() && fixture.player.isPlaying && fixture.player.currentPosition >= 250
            }
            ready.complete(Unit)
            await("late completion leaves replacement intact", fixture) {
                fixture.player.currentPosition >= 1000
            }
            main {
                assertEquals(1, fixture.player.mediaItemCount)
                assertEquals(fixture.lastId, fixture.player.currentMediaItem?.mediaId)
            }
            assertFalse(fixture.requests.contains("/youtube.wav"))
            assertTrue(fixture.errors.isEmpty())
        }
    }

    private class Fixture(
        val player: ExoPlayer,
        val server: MockWebServer,
        val errors: List<PlaybackException>,
        val requests: List<String>,
    ) {
        val youtubeId = "1\u0001fixture1234"
        val lastId = "0\u0001last"

        // MockWebServer.url() does a reverse DNS lookup, so build the URLs here, off the main thread.
        private val queue = listOf(
            item("0\u0001first", server.url("/first.wav").toString()),
            item(youtubeId, "aurora-yt://video/fixture1234"),
            item(lastId, server.url("/last.wav").toString()),
        )

        fun mixedQueue() = queue

        private fun item(id: String, uri: String) = MediaItem.Builder().setMediaId(id).setUri(uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(id.substringAfter('\u0001')).build()).build()
    }

    private fun withPlayer(
        resolve: suspend (MediaItem, String) -> YoutubeResolver.PlaybackStream?,
        block: (Fixture) -> Unit,
    ) {
        val requests = CopyOnWriteArrayList<String>()
        val errors = CopyOnWriteArrayList<PlaybackException>()
        val audio = wav(seconds = 3)
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request.requestUrl!!.encodedPath
                    val start = request.getHeader("Range")?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                    if (start !in audio.indices) return MockResponse().setResponseCode(416)
                    return MockResponse().setHeader("Content-Type", "audio/wav")
                        .setHeader("Accept-Ranges", "bytes")
                        .setResponseCode(if (start > 0) 206 else 200)
                        .apply { if (start > 0) setHeader("Content-Range", "bytes $start-${audio.lastIndex}/${audio.size}") }
                        .setBody(Buffer().write(audio, start, audio.size - start))
                }
            }
            server.start()
            val player = main {
                val context = instrumentation.targetContext
                val sources = YoutubeMediaSourceFactory(
                    DefaultMediaSourceFactory(context), YoutubeResolver(),
                    resolveStream = { resolve(it, server.url("/youtube.wav").toString()) },
                )
                ExoPlayer.Builder(context).setMediaSourceFactory(sources).build().apply {
                    volume = 0f
                    addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) { errors += error }
                    })
                }
            }
            try { block(Fixture(player, server, errors, requests)) }
            finally { main { player.release() } }
        }
    }

    private fun <T> main(block: () -> T): T {
        val task = FutureTask(Callable(block))
        instrumentation.runOnMainSync(task)
        return task.get(10, TimeUnit.SECONDS)
    }

    private fun await(description: String, fixture: Fixture, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            fixture.errors.firstOrNull()?.let { throw AssertionError(description, it) }
            if (main(condition)) return
            SystemClock.sleep(25)
        }
        fail("Timed out: $description; ${main { "state=${fixture.player.playbackState}, index=${fixture.player.currentMediaItemIndex}, position=${fixture.player.currentPosition}" }}")
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
