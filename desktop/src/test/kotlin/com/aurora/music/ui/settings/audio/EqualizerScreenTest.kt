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
    private fun equalizer(name: String, events: MutableList<String> = mutableListOf(), height: Int = 900, seed: suspend (SettingsStore) -> Unit = {}) =
        AudioSettingsScene(name, height = height, seed = seed) {
            EqualizerScreen(PaddingValues(), onBack = { events += "back" }, onOpenLoudness = { events += "loudness" },
                onOpenProcessingPresets = { events += "presets" }, onOpenProcessingRack = { events += "rack" }, onOpenImpulses = { events += "impulses" }, onOpenReverb = { events += "reverb" })
        }

    @Test fun standardEngineNavigatesAndSwitchesToCustomDsp() {
        val events = mutableListOf<String>()
        equalizer("equalizer-standard", events).use { scene ->
            val standard = scene.shot()
            assertTrue(standard.distinctColors() > 20)
            scene.click(400f, 239f)
            scene.click(400f, 306f)
            scene.click(400f, 373f)
            scene.click(400f, 807f)
            assertEquals(listOf("rack", "presets", "reverb", "loudness"), events)

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
            scene.click(420f, 490f)
            assertTrue(scene.shot().differsFrom(collapsed))
            scene.click(614f, 552f)
            scene.await(read = { it.squigBaseUrl.first() }) { it == SQUIG_INSTANCES[1].second }
            scene.click(614f, 606f)
            scene.await(read = { it.squigTarget.first() }) { it == SQUIG_TARGETS[1].second }
        }
    }

    @Test fun customDspChangesGraphicLayout() {
        equalizer("equalizer-custom-dsp", height = 1500, seed = { store ->
            store.setDspMode(DspMode.CUSTOM)
            store.setDspGraphicBands(AudioFixtures.customAudio.dspGraphicBands)
            store.setDspParametric(AudioFixtures.customAudio.dspParametric)
            store.setDspPreamp(-3f)
        }).use { scene ->
            assertTrue(scene.shot().distinctColors() > 20)
            scene.click(321f, 1104f)
            val audio = scene.await(read = { it.audioPrefs.first() }) { it.dspGraphicLayout == 1 }
            assertEquals(15, audio.dspGraphicBands.size)
            assertEquals(3f, audio.dspGraphicBands.first())
            assertEquals(3f, audio.dspGraphicBands.last())
            scene.shot("-15band")
            scene.click(606f, 796f)
            scene.shot("-advanced")
            scene.click(788f, 1070f)
            assertEquals(4, scene.await(read = { it.audioPrefs.first() }) { it.dspParametric.size == 4 }.dspParametric.size)
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
