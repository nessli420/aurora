package com.aurora.music.ui.settings.audio

import androidx.compose.foundation.layout.PaddingValues
import com.aurora.music.data.DspMode
import com.aurora.music.ui.screens.settings.ProcessingPresetsScreen
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingPresetsScreenTest {
    @Test fun rendersSavedPresets() {
        val events = mutableListOf<String>()
        AudioSettingsScene("presets", seed = { store ->
            store.setDspMode(DspMode.CUSTOM)
            store.setReplayGain(2)
            store.saveProcessingPreset("Warm evening").getOrThrow()
            store.setReplayGain(1)
            store.saveProcessingPreset("Studio flat").getOrThrow()
        }) {
            ProcessingPresetsScreen(PaddingValues(), onBack = { events += "back" }, onOpenSignalPath = { events += "signal" })
        }.use { scene ->
            assertTrue(scene.shot().distinctColors() > 10)
        }
    }
}
