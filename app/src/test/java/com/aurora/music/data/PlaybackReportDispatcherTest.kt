package com.aurora.music.data

import com.aurora.music.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PlaybackReportDispatcherTest {
    private val song = Song("101", "Track", "Artist", "Album", "", 180)
    private fun report(event: PlaybackReportEvent, session: String = "one") = PlaybackReport(song, session,
        event, if (event == PlaybackReportEvent.STOP) PlaybackReportState.STOPPED else PlaybackReportState.PLAYING,
        12_000, 180_000, 10_000, 1_750_000_000_000)

    @Test fun preservesEventOrderAndTheCapturedProviderAcrossQueuedTransitions() = runBlocking {
        val worker = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + worker)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val actual = mutableListOf<Pair<String, PlaybackReportEvent>>()
        val first = PlaybackReportTarget(song) {
            if (it.event == PlaybackReportEvent.START) { entered.complete(Unit); release.await() }
            actual += "first" to it.event
        }
        val second = PlaybackReportTarget(song.copy(id = "202")) {
            actual += "second" to it.event
            finished.complete(Unit)
        }
        try {
            val dispatcher = PlaybackReportDispatcher(scope)
            dispatcher.submit(first, report(PlaybackReportEvent.START))
            withTimeout(2000) { entered.await() }
            dispatcher.submit(first, report(PlaybackReportEvent.PROGRESS))
            dispatcher.submit(first, report(PlaybackReportEvent.STOP))
            dispatcher.submit(second, report(PlaybackReportEvent.START, "two"))
            assertTrue(actual.isEmpty())
            release.complete(Unit)
            withTimeout(2000) { finished.await() }
            assertEquals(listOf("first" to PlaybackReportEvent.START, "first" to PlaybackReportEvent.PROGRESS,
                "first" to PlaybackReportEvent.STOP, "second" to PlaybackReportEvent.START), actual)
        } finally {
            scope.cancel()
            worker.join()
        }
    }

    @Test fun networkFailuresDoNotKillLaterReportsAndWarningsAreThrottled() = runBlocking {
        val worker = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + worker)
        val finished = CompletableDeferred<Unit>()
        var failed = 0
        val attempts = mutableListOf<PlaybackReportEvent>()
        val target = PlaybackReportTarget(song) {
            attempts += it.event
            if (it.event != PlaybackReportEvent.STOP) throw IOException("Fixture unavailable")
            finished.complete(Unit)
        }
        try {
            val dispatcher = PlaybackReportDispatcher(scope) { failed++ }
            dispatcher.submit(target, report(PlaybackReportEvent.START))
            dispatcher.submit(target, report(PlaybackReportEvent.PROGRESS))
            dispatcher.submit(target, report(PlaybackReportEvent.STOP))
            withTimeout(2000) { finished.await() }
            assertEquals(listOf(PlaybackReportEvent.START, PlaybackReportEvent.PROGRESS, PlaybackReportEvent.STOP), attempts)
            assertEquals(1, failed)
        } finally {
            scope.cancel()
            worker.join()
        }
    }

    @Test fun parentCancellationCancelsPendingIoWithoutFailureNotifications() = runBlocking {
        val worker = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + worker)
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        var failed = 0
        var nextStarted = false
        val target = PlaybackReportTarget(song) {
            entered.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }
        try {
            val dispatcher = PlaybackReportDispatcher(scope) { failed++ }
            dispatcher.submit(target, report(PlaybackReportEvent.START))
            withTimeout(2000) { entered.await() }
            dispatcher.submit(PlaybackReportTarget(song) { nextStarted = true }, report(PlaybackReportEvent.STOP))
            scope.cancel()
            withTimeout(2000) { cancelled.await(); worker.join() }
            assertFalse(nextStarted)
            assertEquals(0, failed)
        } finally {
            scope.cancel()
        }
    }

    @Test fun reportLocalCancellationDoesNotSilentlyDisableAllFutureProviders() = runBlocking {
        val worker = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + worker)
        val finished = CompletableDeferred<Unit>()
        val first = PlaybackReportTarget(song) { throw CancellationException("Fixture request cancelled") }
        val second = PlaybackReportTarget(song.copy(id = "202")) { finished.complete(Unit) }
        try {
            val dispatcher = PlaybackReportDispatcher(scope)
            dispatcher.submit(first, report(PlaybackReportEvent.START))
            dispatcher.submit(second, report(PlaybackReportEvent.START, "two"))
            withTimeout(2000) { finished.await() }
        } finally {
            scope.cancel()
            worker.join()
        }
    }

    @Test fun privacyOptOutDropsQueuedHistoryButStopsTheAlreadyStartedSession() = runBlocking {
        val worker = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + worker)
        var allowed = true
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val actual = mutableListOf<PlaybackReportEvent>()
        val target = PlaybackReportTarget(song) {
            actual += it.event
            if (it.event == PlaybackReportEvent.START) { entered.complete(Unit); release.await() }
            if (it.event == PlaybackReportEvent.STOP) stopped.complete(Unit)
        }
        try {
            val dispatcher = PlaybackReportDispatcher(scope, allowed = { allowed })
            dispatcher.submit(target, report(PlaybackReportEvent.START))
            withTimeout(2000) { entered.await() }
            dispatcher.submit(target, report(PlaybackReportEvent.PROGRESS))
            dispatcher.submit(target, report(PlaybackReportEvent.SCROBBLE))
            dispatcher.submit(target, report(PlaybackReportEvent.STOP))
            allowed = false
            release.complete(Unit)
            withTimeout(2000) { stopped.await() }
            assertEquals(listOf(PlaybackReportEvent.START, PlaybackReportEvent.STOP), actual)
        } finally {
            scope.cancel()
            worker.join()
        }
    }

    @Test fun privacyOptOutNeverSendsStopForAQueuedSessionThatDidNotStart() = runBlocking {
        val worker = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + worker)
        var allowed = true
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val actual = mutableListOf<Pair<String, PlaybackReportEvent>>()
        val first = PlaybackReportTarget(song) {
            actual += it.sessionId to it.event
            if (it.event == PlaybackReportEvent.START) { entered.complete(Unit); release.await() }
        }
        val second = PlaybackReportTarget(song.copy(id = "202")) { actual += it.sessionId to it.event }
        try {
            val dispatcher = PlaybackReportDispatcher(scope, allowed = { allowed })
            dispatcher.submit(first, report(PlaybackReportEvent.START))
            withTimeout(2000) { entered.await() }
            dispatcher.submit(first, report(PlaybackReportEvent.STOP))
            dispatcher.submit(second, report(PlaybackReportEvent.START, "two"))
            dispatcher.submit(second, report(PlaybackReportEvent.STOP, "two"))
            allowed = false
            release.complete(Unit)
            withTimeout(2000) {
                while (actual.size < 2) kotlinx.coroutines.yield()
                repeat(5) { kotlinx.coroutines.yield() }
            }
            assertEquals(listOf("one" to PlaybackReportEvent.START, "one" to PlaybackReportEvent.STOP), actual)
        } finally {
            scope.cancel()
            worker.join()
        }
    }

    @Test fun seekDragBurstKeepsFirstAndLatestProgressAndDoesNotDelayStop() = runBlocking {
        val worker = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + worker)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val actual = mutableListOf<PlaybackReport>()
        val target = PlaybackReportTarget(song) {
            if (it.event == PlaybackReportEvent.START) { entered.complete(Unit); release.await() }
            if (it.event == PlaybackReportEvent.PROGRESS) delay(20)
            actual += it
            if (it.event == PlaybackReportEvent.STOP) stopped.complete(Unit)
        }
        try {
            val dispatcher = PlaybackReportDispatcher(scope)
            dispatcher.submit(target, report(PlaybackReportEvent.START))
            withTimeout(2000) { entered.await() }
            repeat(200) { index ->
                dispatcher.submit(target, report(PlaybackReportEvent.PROGRESS).copy(positionMs = 10_000L + index * 500))
            }
            dispatcher.submit(target, report(PlaybackReportEvent.STOP).copy(positionMs = 109_500))
            assertTrue(actual.isEmpty())
            release.complete(Unit)
            withTimeout(2000) { stopped.await() }
            assertEquals(listOf(PlaybackReportEvent.START, PlaybackReportEvent.PROGRESS,
                PlaybackReportEvent.PROGRESS, PlaybackReportEvent.STOP), actual.map { it.event })
            assertEquals(listOf(10_000L, 109_500L), actual.filter { it.event == PlaybackReportEvent.PROGRESS }.map { it.positionMs })
            assertEquals(109_500, actual.last().positionMs)
        } finally {
            scope.cancel()
            worker.join()
        }
    }

    @Test fun progressCoalescingPreservesStateRateAndScrobbleBoundaries() = runBlocking {
        val worker = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + worker)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val actual = mutableListOf<PlaybackReport>()
        val target = PlaybackReportTarget(song) {
            if (it.event == PlaybackReportEvent.START) { entered.complete(Unit); release.await() }
            actual += it
            if (it.event == PlaybackReportEvent.STOP) stopped.complete(Unit)
        }
        val states = listOf(PlaybackReportState.PLAYING to 1f, PlaybackReportState.PAUSED to 1f,
            PlaybackReportState.PAUSED to 1.5f, PlaybackReportState.PLAYING to 1.5f)
        try {
            val dispatcher = PlaybackReportDispatcher(scope)
            dispatcher.submit(target, report(PlaybackReportEvent.START))
            withTimeout(2000) { entered.await() }
            states.forEachIndexed { index, (state, rate) ->
                repeat(3) { offset ->
                    dispatcher.submit(target, report(PlaybackReportEvent.PROGRESS)
                        .copy(state = state, playbackRate = rate, positionMs = index * 10_000L + offset * 1000))
                }
            }
            dispatcher.submit(target, report(PlaybackReportEvent.SCROBBLE))
            dispatcher.submit(target, report(PlaybackReportEvent.PROGRESS).copy(playbackRate = 1.5f, positionMs = 35_000))
            dispatcher.submit(target, report(PlaybackReportEvent.STOP))
            release.complete(Unit)
            withTimeout(2000) { stopped.await() }
            val progress = actual.filter { it.event == PlaybackReportEvent.PROGRESS }
            assertEquals(listOf(0L, 2000L, 10_000L, 12_000L, 20_000L, 22_000L, 30_000L, 32_000L, 35_000L), progress.map { it.positionMs })
            assertEquals(states.flatMap { listOf(it, it) } + (PlaybackReportState.PLAYING to 1.5f),
                progress.map { it.state to it.playbackRate })
            val scrobble = actual.indexOfFirst { it.event == PlaybackReportEvent.SCROBBLE }
            assertEquals(32_000, actual[scrobble - 1].positionMs)
            assertEquals(35_000, actual[scrobble + 1].positionMs)
            assertEquals(PlaybackReportEvent.STOP, actual.last().event)
        } finally {
            scope.cancel()
            worker.join()
        }
    }
}
