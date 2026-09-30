package com.aurora.music.ui.testing

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.SwingUtilities

class SceneSupportTest {
    private fun applyObservers(): Int = edt {
        val field = Class.forName("androidx.compose.runtime.snapshots.SnapshotKt").getDeclaredField("applyObservers")
        field.isAccessible = true
        (field.get(null) as List<*>).size
    }

    @Test fun composesAndRunsEffectsOnTheEventDispatchThread() {
        val onEdt = CopyOnWriteArrayList<Boolean>()
        val counter = mutableIntStateOf(0)
        EdtScene(32, 32) {
            SideEffect { onEdt += SwingUtilities.isEventDispatchThread() }
            LaunchedEffect(Unit) { snapshotFlow { counter.intValue }.collect { onEdt += SwingUtilities.isEventDispatchThread() } }
        }.use { scene ->
            scene.frame(16)
            counter.intValue++
            repeat(3) { scene.frame(16) }
        }
        assertTrue(onEdt.size >= 3)
        assertTrue(onEdt.all { it })
    }

    @Test fun closingReleasesTheRecomposerAndSnapshotFlows() {
        val baseline = applyObservers()
        val counter = mutableIntStateOf(0)
        EdtScene(32, 32) {
            LaunchedEffect(Unit) { snapshotFlow { counter.intValue }.collect { } }
        }.use { scene ->
            repeat(3) { scene.frame(16) }
            assertTrue(applyObservers() > baseline)
        }
        assertEquals(baseline, applyObservers())
    }
}
