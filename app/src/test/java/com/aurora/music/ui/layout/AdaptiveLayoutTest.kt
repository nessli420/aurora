package com.aurora.music.ui.layout

import org.junit.Assert.*
import org.junit.Test

class AdaptiveLayoutTest {
    @Test fun displayClassificationIgnoresRotationAndAccountsForDensity() {
        assertFalse(isLargeDisplay(1080, 2400, 3f))
        assertFalse(isLargeDisplay(2400, 1080, 3f))
        assertTrue(isLargeDisplay(2560, 1600, 2f))
        assertTrue(isLargeDisplay(1600, 2560, 2f))
        assertFalse(isLargeDisplay(1198, 1920, 2f))
        assertTrue(isLargeDisplay(1200, 1920, 2f))
    }

    @Test fun layoutsRespondToAvailableWindowRatherThanDeviceOrientationAlone() {
        assertFalse(WindowLayout(599, 900).useNavigationRail)
        assertTrue(WindowLayout(600, 900).useNavigationRail)
        assertFalse(WindowLayout(800, 1280).useLandscapePlayer)
        assertTrue(WindowLayout(1280, 800).useLandscapePlayer)
        assertFalse(WindowLayout(599, 800).useLandscapePlayer)
    }
}
