package com.aurora.music.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTimeTest {
    @Test fun shortTracksStayMinutesAndSeconds() {
        assertEquals("0:00", formatTime(0))
        assertEquals("59:59", formatTime(3599))
    }

    @Test fun longTracksShowHours() {
        assertEquals("1:00:00", formatTime(3600))
        assertEquals("10:23:43", formatTime(37423))
    }
}
