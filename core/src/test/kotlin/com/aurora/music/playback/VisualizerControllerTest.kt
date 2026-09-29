package com.aurora.music.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualizerControllerTest {
    @Test fun keepsAnalysingUntilTheLastUserStops() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val visualizer = VisualizerController(scope)
            visualizer.start()
            visualizer.start()
            visualizer.stop()
            assertTrue(visualizer.active)
            visualizer.stop()
            assertFalse(visualizer.active)
            visualizer.stop()
            visualizer.start()
            assertTrue(visualizer.active)
            visualizer.stop()
            assertFalse(visualizer.active)
        } finally {
            scope.cancel()
        }
    }
}
