package com.aurora.music.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueDragTest {
    @Test
    fun dropTargetRoundsToTheNearestRowAndStaysInRange() {
        assertEquals(5, queueDropTarget(5, 40f, 100f, 3, 20))
        assertEquals(6, queueDropTarget(5, 60f, 100f, 3, 20))
        assertEquals(3, queueDropTarget(5, -260f, 100f, 3, 20))
        assertEquals(3, queueDropTarget(5, -900f, 100f, 3, 20))
        assertEquals(20, queueDropTarget(5, 9_000f, 100f, 3, 20))
        // finger moved 150 px while auto-scroll carried the list 1000 px further
        assertEquals(17, queueDropTarget(5, 1_150f, 100f, 3, 20))
    }

    @Test
    fun edgeScrollOnlyNearTheEndsAndFasterDeeperIn() {
        assertEquals(0f, edgeScrollStep(500f, 1000f, 100f), 0f)
        assertEquals(0f, edgeScrollStep(100f, 1000f, 100f), 0f)
        assertEquals(0f, edgeScrollStep(900f, 1000f, 100f), 0f)
        assertTrue(edgeScrollStep(50f, 1000f, 100f) < 0f)
        assertTrue(edgeScrollStep(950f, 1000f, 100f) > 0f)
        assertTrue(edgeScrollStep(990f, 1000f, 100f) > edgeScrollStep(950f, 1000f, 100f))
        // past the edge, as when the finger leaves the list, caps at full speed
        assertEquals(edgeScrollStep(0f, 1000f, 100f), edgeScrollStep(-400f, 1000f, 100f), 0f)
        assertEquals(edgeScrollStep(1000f, 1000f, 100f), edgeScrollStep(1400f, 1000f, 100f), 0f)
    }
}
