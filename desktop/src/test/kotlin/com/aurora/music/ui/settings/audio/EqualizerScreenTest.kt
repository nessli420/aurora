package com.aurora.music.ui.settings.audio

import androidx.compose.foundation.layout.PaddingValues
import com.aurora.music.data.DspMode
import com.aurora.music.data.ProcessingRack
import com.aurora.music.data.SQUIG_INSTANCES
import com.aurora.music.data.SQUIG_TARGETS
import com.aurora.music.data.SettingsStore
import com.aurora.music.ui.screens.settings.EqualizerScreen
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EqualizerScreenTest {
    private fun equalizer(name: String, events: MutableList<String> = mutableListOf(), seed: suspend (SettingsStore) -> Unit = {}) =
        AudioSettingsScene(name, seed = seed) {
            EqualizerScreen(PaddingValues(), onBack = { events += "back" }, onOpenLoudness = { events += "loudness" },
                onOpenProcessingPresets = { events += "presets" }, onOpenProcessingRack = { events += "rack" }, onOpenImpulses = { events += "impulses" })
        }

    @Test fun standardEngineNavigatesAndSwitchesToCustomDsp() {
        val events = mutableListOf<String>()
        equalizer("equalizer-standard", events).use { scene ->
            val standard = scene.shot()
            assertTrue(standard.distinctColors() > 20)
            scene.click(400f, 280f)
            scene.click(400f, 346f)
            scene.click(400f, 780f)
            assertEquals(listOf("rack", "presets", "loudness"), events)

            scene.click(420f, 174f)
            scene.shot("-engine-menu")
            scene.click(96f, 232f)
            scene.await(read = { it.audioPrefs.first().dspMode }) { it == DspMode.CUSTOM }
            assertTrue(scene.shot("-custom").differsFrom(standard))
        }
    }

    @Test fun devicePresetsUseLiveSquigLinkOnly() {
        equalizer("equalizer-device-presets").use { scene ->
            val collapsed = scene.settle()
            scene.click(420f, 463f)
            assertTrue(scene.shot().differsFrom(collapsed))
            scene.click(614f, 525f)
            scene.await(read = { it.squigBaseUrl.first() }) { it == SQUIG_INSTANCES[1].second }
            scene.click(614f, 579f)
            scene.await(read = { it.squigTarget.first() }) { it == SQUIG_TARGETS[1].second }
        }
    }

    @Test fun customDspChangesGraphicLayout() {
        equalizer("equalizer-custom-dsp", seed = { store ->
            store.setDspMode(DspMode.CUSTOM)
            store.setDspGraphicBands(AudioFixtures.customAudio.dspGraphicBands)
            store.setDspParametric(AudioFixtures.customAudio.dspParametric)
            store.setDspPreamp(-3f)
        }).use { scene ->
            assertTrue(scene.shot().distinctColors() > 20)
            scene.click(420f, 760f)
            val audio = scene.await(read = { it.audioPrefs.first() }) { it.dspGraphicLayout == 1 }
            assertEquals(List(15) { 0f }, audio.dspGraphicBands)
            scene.shot("-15band")
        }
    }

    @Test fun activeRackReplacesTheStandardControls() {
        equalizer("equalizer-rack", seed = { store ->
            store.setProcessingRack(ProcessingRack.recommended(AudioFixtures.customAudio).copy(enabled = true)).getOrThrow()
        }).use { scene ->
            val rack = scene.shot()
            scene.click(108f, 220f)
            scene.await(read = { it.processingRack.first() }) { !it.enabled }
            assertTrue(scene.shot("-standard").differsFrom(rack))
        }
    }
}
