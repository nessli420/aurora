package com.aurora.music.ui.settings.audio

import androidx.compose.foundation.layout.PaddingValues
import com.aurora.music.data.DspMode
import com.aurora.music.ui.screens.settings.ProcessingPresetsScreen
import com.aurora.music.ui.testing.differsFrom
import com.aurora.music.ui.testing.distinctColors
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingPresetsScreenTest {
    private fun presets(name: String, events: MutableList<String>) = AudioSettingsScene(name, seed = { store ->
        store.setDspMode(DspMode.CUSTOM)
        store.setReplayGain(2)
        store.saveProcessingPreset("Warm evening").getOrThrow()
        store.setDspMode(DspMode.OFF)
        store.setReplayGain(1)
        store.saveProcessingPreset("Studio flat").getOrThrow()
    }) {
        ProcessingPresetsScreen(PaddingValues(), onBack = { events += "back" }, onOpenSignalPath = { events += "signal" })
    }

    @Test fun appliesAPresetAndOpensTheSignalPath() {
        val events = mutableListOf<String>()
        presets("presets", events).use { scene ->
            val list = scene.shot()
            assertTrue(list.distinctColors() > 10)
            scene.click(55f, 368f)
            scene.await(read = { it.audioPrefs.first() }) { it.replayGain == 2 && it.dspMode == DspMode.CUSTOM }
            assertTrue(scene.shot("-applied").differsFrom(list))
            scene.click(400f, 599f)
            assertEquals(listOf("signal"), events)
        }
    }

    @Test fun deletesAPresetFromTheActionsMenu() {
        presets("presets-delete", mutableListOf()).use { scene ->
            scene.click(796f, 278f)
            scene.shot("-menu")
            scene.click(741f, 478f)
            scene.shot("-confirm")
            scene.click(620f, 492f)
            val left = scene.await(read = { it.processingPresetLibrary.first().presets }) { it.size == 1 }
            assertEquals("Studio flat", left.single().name)
        }
    }
}
