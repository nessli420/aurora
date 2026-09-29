package com.aurora.music.ui.settings.audio

import androidx.compose.foundation.layout.PaddingValues
import com.aurora.music.data.ProcessingRack
import com.aurora.music.data.SignalPath
import com.aurora.music.playback.engine.OutputRatePolicy
import com.aurora.music.ui.screens.settings.ConvolutionLibraryScreen
import com.aurora.music.ui.screens.settings.EqualizerScreen
import com.aurora.music.ui.screens.settings.OutputRateSettings
import com.aurora.music.ui.screens.settings.ProcessingPresetsScreen
import com.aurora.music.ui.screens.settings.ProcessingRackScreen
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test

class ZzProbeTest {
    @Test fun probeEqualizer() {
        AudioSettingsScene("probe-eq") {
            EqualizerScreen(PaddingValues(), {}, {}, {}, {}, {})
        }.use { scene ->
            scene.click(420f, 174f)
            scene.shot("-menu")
            scene.click(20f, 880f)
            scene.click(420f, 463f)
            scene.shot("-autoeq")
        }
    }

    @Test fun probeRack() {
        AudioSettingsScene("probe-rack", seed = { store ->
            store.setProcessingRack(ProcessingRack.recommended(AudioFixtures.customAudio).copy(enabled = true)).getOrThrow()
        }) {
            ProcessingRackScreen(PaddingValues(), MutableStateFlow(AudioFixtures.activePath), {}, {}, {}, {}, {})
        }.use { scene ->
            scene.click(100f, 560f)
            scene.shot("-eq")
            scene.click(400f, 200f)
            scene.shot("-eq2")
        }
    }

    @Test fun probePresets() {
        AudioSettingsScene("probe-presets", seed = { store ->
            store.saveProcessingPreset("Warm evening").getOrThrow()
        }) {
            ProcessingPresetsScreen(PaddingValues(), {}, {})
        }.use { scene ->
            scene.click(796f, 278f)
            scene.shot("-menu")
        }
    }

    @Test fun probeImpulses() {
        AudioSettingsScene("probe-ir", seed = { store ->
            store.importImpulse(AudioFixtures.impulseWav().inputStream(), "Studio room.wav").getOrThrow()
        }) {
            ConvolutionLibraryScreen(PaddingValues(), MutableStateFlow(SignalPath()), {})
        }.use { scene ->
            scene.click(100f, 148f)
            scene.shot("-detail")
        }
    }

    @Test fun probeOutput() {
        AudioSettingsScene("probe-output", height = 500) {
            var policy by remember { mutableStateOf(OutputRatePolicy()) }
            Column { OutputRateSettings(policy) { change -> policy = change(policy) } }
        }.use { scene ->
            scene.click(420f, 69f)
            scene.shot("-menu")
        }
    }
}
