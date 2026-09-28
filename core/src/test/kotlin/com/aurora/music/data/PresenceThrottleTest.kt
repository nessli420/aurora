package com.aurora.music.data

import com.aurora.music.data.remote.PresenceThrottle
import org.junit.Assert.*
import org.junit.Test

class PresenceThrottleTest {
    @Test fun rapidSkipsKeepOnlyNewestSongIncludingPause() {
        val throttle = PresenceThrottle<String>()
        throttle.offer("first"); throttle.sent(1000)
        (1..100).forEach { throttle.offer("song $it") }
        assertEquals("song 100", throttle.latest)
        assertEquals(4000L, throttle.delayMs(1500))
        throttle.offer(null)
        assertNull(throttle.latest)
    }
    @Test fun continuousSkippingNeverStarvesFinalUpdateOrExceedsRate() {
        val throttle = PresenceThrottle<String>()
        val times = mutableListOf<Long>()
        var time = 0L
        repeat(20) {
            throttle.offer("$it")
            time += throttle.delayMs(time)
            times += time
            throttle.sent(time)
        }
        assertTrue(times.all { start -> times.count { it >= start && it < start + 20_000 } <= 5 })
        assertEquals("19", throttle.latest)
    }
}
