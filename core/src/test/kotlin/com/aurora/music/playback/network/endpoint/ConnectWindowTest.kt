package com.aurora.music.playback.network.endpoint

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectWindowTest {
    @Test fun slicedWindowMatchesTheFullOneAndBuildsOnlyTheSlice() {
        for (size in listOf(0, 1, 299, 300, 301, 1_000)) for (index in listOf(0, 1, 99, 100, 101, 500, 700, 999, 1_500)) {
            val full = (0 until size).map { ConnectTrack("$it", "T$it") }
            var built = 0
            val status = ConnectStatus(index = index).windowedFrom(size) { built++; full[it] }
            val expected = ConnectStatus(index = index).windowed(full)
            assertEquals(expected, status)
            assertEquals(expected.hashCode(), status.hashCode())
            assertEquals(expected.queue.size, built)
        }
    }
}
