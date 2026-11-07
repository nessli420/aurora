package com.aurora.music.ui.screens.settings

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveValueTest {
    @Test fun dragWritesAreConflatedPerWindowAndTheLastOneIsFlushedOnLeave() = runBlocking {
        val writes = mutableListOf<Float>()
        val live = LiveValue<Float>().apply { write = { writes += it } }
        val job = launch { live.run() }
        yield()

        live.set(1f)
        yield()
        live.set(2f); live.set(3f); live.set(4f)
        delay(LIVE_WRITE_MS * 2 + 60)
        assertEquals(listOf(1f, 4f), writes)

        live.set(5f)
        yield()
        live.set(6f)
        job.cancelAndJoin()
        assertEquals(listOf(1f, 4f, 5f, 6f), writes)
        assertEquals(6f, live.local)
    }
}
