package com.aurora.music.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class PlaybackReportDispatcher(
    scope: CoroutineScope,
    private val allowed: () -> Boolean = { true },
    private val onFailure: () -> Unit = {},
) {
    private val pending = ArrayDeque<Pair<PlaybackReportTarget, PlaybackReport>>()
    private val ready = Channel<Unit>(Channel.CONFLATED)
    private var closed = false
    private var lastFailure = 0L

    init {
        scope.launch {
            val started = mutableSetOf<String>()
            for (signal in ready) {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val (target, report) = synchronized(pending) { pending.removeFirstOrNull() } ?: break
                    if (report.event != PlaybackReportEvent.STOP && !allowed()) continue
                    if (report.event == PlaybackReportEvent.START) started += report.sessionId
                    else if (report.sessionId !in started) continue
                    try {
                        withTimeout(8_000) { target.send(report) }
                    } catch (_: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        failed()
                    } catch (_: Exception) {
                        failed()
                    } finally {
                        if (report.event == PlaybackReportEvent.STOP) started -= report.sessionId
                    }
                }
            }
        }.invokeOnCompletion {
            synchronized(pending) { closed = true; pending.clear() }
            ready.cancel()
        }
    }

    fun submit(target: PlaybackReportTarget, report: PlaybackReport) {
        synchronized(pending) {
            if (closed) return
            // keep the heard interval before a seek and the latest position
            if (report.event == PlaybackReportEvent.PROGRESS && pending.size >= 2 &&
                sameProgress(pending.last().second, report) && sameProgress(pending[pending.lastIndex - 1].second, report)) {
                pending.removeLast()
            }
            pending.addLast(target to report)
        }
        ready.trySend(Unit)
    }

    private fun sameProgress(first: PlaybackReport, second: PlaybackReport): Boolean =
        first.event == PlaybackReportEvent.PROGRESS && first.sessionId == second.sessionId &&
            first.state == second.state && first.playbackRate == second.playbackRate

    private fun failed() {
        val now = System.nanoTime()
        if (lastFailure == 0L || now - lastFailure > 60_000_000_000L) {
            lastFailure = now
            onFailure()
        }
    }
}
