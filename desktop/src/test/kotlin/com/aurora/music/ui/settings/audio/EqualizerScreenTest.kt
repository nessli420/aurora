package com.aurora.music.ui.settings.audio

import androidx.compose.foundation.layout.PaddingValues
import com.aurora.music.data.DspMode
import com.aurora.music.data.ProcessingRack
import com.aurora.music.data.SQUIG_INSTANCES
import com.aurora.music.data.SQUIG_TARGETS
import com.aurora.music.data.SettingsStore
import com.aurora.music.ui.screens.settings.EqualizerScreen
import com.aurora.music.ui.testing.differsFrom
import com.aurora.music.ui.testing.distinctColors
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
            scene.click(400f, 239f)
            scene.click(400f, 306f)
            scene.click(400f, 740f)
            assertEquals(listOf("rack", "presets", "loudness"), events)

            scene.click(420f, 162f)
            scene.shot("-engine-menu")
            scene.click(680f, 214f)
            scene.await(read = { it.audioPrefs.first().dspMode }) { it == DspMode.CUSTOM }
            assertTrue(scene.shot("-custom").differsFrom(standard))
        }
    }

    @Test fun devicePresetsUseLiveSquigLinkOnly() {
        equalizer("equalizer-device-presets").use { scene ->
            val collapsed = scene.settle()
            scene.click(420f, 423f)
            assertTrue(scene.shot().differsFrom(collapsed))
            scene.click(614f, 485f)
            scene.await(read = { it.squigBaseUrl.first() }) { it == SQUIG_INSTANCES[1].second }
            scene.click(614f, 539f)
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
            scene.click(420f, 730f)
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
            scene.click(120f, 250f)
            scene.await(read = { it.processingRack.first() }) { !it.enabled }
            assertTrue(scene.shot("-standard").differsFrom(rack))
        }
    }
}
