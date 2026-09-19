package com.aurora.music.playback

import android.app.SearchManager
import android.content.Intent
import android.os.SystemClock
import android.provider.MediaStore
import androidx.lifecycle.ViewModelProvider
import androidx.media3.session.MediaController
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.aurora.music.AuroraApplication
import com.aurora.music.MainActivity
import com.aurora.music.data.MediaBackend
import com.aurora.music.data.SearchResults
import com.aurora.music.model.Song
import com.aurora.music.viewmodel.PlayerViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class MediaSearchIntentDeviceTest {
    private val fixture = PrecisionPlaybackDeviceTest()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (context.applicationContext as AuroraApplication).container

    @Before fun setup() = fixture.keepTargetForegroundForAudioFocus()
    @After fun cleanup() = fixture.removeFixturesAndFinishActivity()

    @Test fun launchNewIntentAndRecreationHandleEachSearchOnce() = withSearchFixture { controller, first, second, counts, _ ->
        val oldActivity = fixture.main { requireNotNull(currentActivity()) }
        fixture.main { oldActivity.finish() }
        fixture.await("previous activity finishes") { fixture.main { oldActivity.isDestroyed } }
        val launched = instrumentation.startActivitySync(searchIntent(first.title)) as MainActivity
        try {
            awaitPlaying(controller, first.id)
            assertEquals(1, counts[first.title]?.get())
            fixture.main { controller.pause(); controller.seekTo(2_000); launched.recreate() }
            fixture.await("activity recreated") { fixture.main { currentActivity()?.let { it !== launched } == true } }
            assertStable(controller, first.id)
            assertEquals("recreation must not replay the launch", 1, counts[first.title]?.get())
            assertTrue(fixture.main { controller.currentPosition >= 1_900 })

            context.startActivity(searchIntent(second.title))
            awaitPlaying(controller, second.id)
            fixture.main { controller.pause() }
            context.startActivity(searchIntent("No matching fixture"))
            fixture.await("no-match search completes") { counts["No matching fixture"]?.get() == 1 }
            assertStable(controller, second.id)

            context.startActivity(searchIntent(null))
            assertStable(controller, second.id)
            context.startActivity(searchIntent(""))
            awaitPlaying(controller, second.id)
        } finally {
            fixture.main { currentActivity()?.finish() }
        }
    }

    @Test fun lateSearchCannotReplaceNewerManualPlayback() = withSearchFixture { controller, first, second, counts, delayed ->
        val vm = fixture.main { ViewModelProvider(requireNotNull(currentActivity()))[PlayerViewModel::class.java] }
        context.startActivity(searchIntent("Delayed fixture"))
        fixture.await("delayed search begins") { counts["Delayed fixture"]?.get() == 1 }
        fixture.main { vm.play(second) }
        awaitPlaying(controller, second.id)
        fixture.main { controller.pause() }
        delayed.complete(SearchResults(songs = listOf(first)))
        assertStable(controller, second.id)
    }

    private fun withSearchFixture(block: (MediaController, Song, Song, ConcurrentHashMap<String, AtomicInteger>, CompletableDeferred<SearchResults>) -> Unit) {
        fixture.withProcessingFixture(0) { controller, _ ->
            val original = requireNotNull(container.backend)
            val audio = fixture.wav("media-search-intent", 48_000, 48_000 * 15) { _, _ -> 0 }
            val first = Song("search-intent:first", "First fixture", "Fixture artist", "", "", 15, streamUrl = audio.toURI().toString())
            val second = first.copy(id = "search-intent:second", title = "Second fixture")
            val counts = ConcurrentHashMap<String, AtomicInteger>()
            val delayed = CompletableDeferred<SearchResults>()
            val backend = object : MediaBackend by original {
                override suspend fun search(query: String): SearchResults {
                    counts.computeIfAbsent(query) { AtomicInteger() }.incrementAndGet()
                    if (query == "Delayed fixture") return withContext(NonCancellable) { delayed.await() }
                    return SearchResults(songs = listOf(first, second).filter { it.title == query })
                }
            }
            val field = container.javaClass.getDeclaredField("backend").apply { isAccessible = true }
            field.set(container, backend)
            try {
                block(controller, first, second, counts, delayed)
            } finally {
                delayed.complete(SearchResults())
                fixture.main { currentActivity()?.let { ViewModelProvider(it)[PlayerViewModel::class.java].stopPlayback() } }
                field.set(container, original)
            }
        }
    }

    private fun searchIntent(query: String?): Intent = Intent(context, MainActivity::class.java)
        .setAction(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .apply { if (query != null) putExtra(SearchManager.QUERY, query) }

    private fun currentActivity(): MainActivity? = ActivityLifecycleMonitorRegistry.getInstance()
        .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()

    private fun awaitPlaying(controller: MediaController, id: String) = fixture.await("search plays $id", controller) {
        fixture.main { controller.currentMediaItem?.mediaId == id && controller.isPlaying }
    }

    private fun assertStable(controller: MediaController, id: String) {
        val until = SystemClock.elapsedRealtime() + 800
        while (SystemClock.elapsedRealtime() < until) {
            fixture.main {
                assertEquals(listOf(id), (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId })
                assertFalse(controller.playWhenReady)
            }
            SystemClock.sleep(40)
        }
    }
}
