package com.aurora.music.ui.settings.audio

import androidx.compose.foundation.layout.PaddingValues
import com.aurora.music.data.DspMode
import com.aurora.music.data.ProcessingRack
import com.aurora.music.ui.screens.settings.EqualizerScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EqualizerScreenTest {
    private fun equalizer(name: String, events: MutableList<String> = mutableListOf(), seed: suspend (com.aurora.music.data.SettingsStore) -> Unit = {}) =
        AudioSettingsScene(name, seed = seed) {
            EqualizerScreen(PaddingValues(), onBack = { events += "back" }, onOpenLoudness = { events += "loudness" },
                onOpenProcessingPresets = { events += "presets" }, onOpenProcessingRack = { events += "rack" }, onOpenImpulses = { events += "impulses" })
        }

    @Test fun standardEngineRendersAndSwitchesToCustomDsp() {
        equalizer("equalizer-standard").use { scene ->
            val standard = scene.shot()
            assertTrue(standard.distinctColors() > 20)
        }
    }

    @Test fun customDspShowsToneSections() {
        equalizer("equalizer-custom", seed = { store ->
            store.setDspMode(DspMode.CUSTOM)
            store.setDspGraphicBands(AudioFixtures.customAudio.dspGraphicBands)
            store.setDspParametric(AudioFixtures.customAudio.dspParametric)
            store.setDspPreamp(-3f)
        }).use { scene ->
            assertTrue(scene.shot().distinctColors() > 20)
            assertEquals(DspMode.CUSTOM, runBlocking { scene.store.audioPrefs.first().dspMode })
        }
    }

    @Test fun activeRackReplacesTheStandardControls() {
        equalizer("equalizer-rack", seed = { store ->
            store.setProcessingRack(ProcessingRack.recommended(AudioFixtures.customAudio).copy(enabled = true)).getOrThrow()
        }).use { scene ->
            assertTrue(scene.shot().distinctColors() > 10)
        }
    }
}
