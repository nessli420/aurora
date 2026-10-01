package com.aurora.music.desktop

import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

internal fun DesktopContainer.closeAndJoin() {
    close()
    runBlocking { withTimeout(10_000) { scope.coroutineContext.job.join() } }
}
