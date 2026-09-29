package com.aurora.music.ui.settings.audio

import androidx.compose.foundation.layout.PaddingValues
import com.aurora.music.data.ProcessingRack
import com.aurora.music.data.SignalPath
import com.aurora.music.ui.screens.settings.ProcessingRackScreen
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingRackScreenTest {
    private fun rack(name: String, events: MutableList<String> = mutableListOf(), path: SignalPath = SignalPath(),
                     seed: suspend (com.aurora.music.data.SettingsStore) -> Unit = {}) =
        AudioSettingsScene(name, seed = seed) {
            ProcessingRackScreen(PaddingValues(), MutableStateFlow(path), onBack = { events += "back" }, onOpenPresets = { events += "presets" },
                onOpenSignalPath = { events += "signal" }, onOpenTuning = { events += "tuning" }, onOpenImpulses = { events += "impulses" })
        }

    @Test fun rendersRackStages() {
        rack("rack-list", path = AudioFixtures.activePath, seed = { store ->
            store.setProcessingRack(ProcessingRack.recommended(AudioFixtures.customAudio).copy(enabled = true)).getOrThrow()
        }).use { scene ->
            assertTrue(scene.shot().distinctColors() > 20)
        }
    }
}
