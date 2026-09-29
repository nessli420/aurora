package com.aurora.music.ui.settings.audio

import androidx.compose.foundation.layout.PaddingValues
import com.aurora.music.data.ProcessingRack
import com.aurora.music.ui.screens.settings.EqualizerScreen
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ZzProbeTest {
    @Test fun probe() {
        AudioSettingsScene("probe") {
            EqualizerScreen(PaddingValues(), {}, {}, {}, {}, {})
        }.use { scene ->
            scene.shot("-a")
            runBlocking { scene.store.setProcessingRack(ProcessingRack.recommended(AudioFixtures.customAudio).copy(enabled = true)).getOrThrow() }
            scene.shot("-b")
            scene.settle(2000)
            scene.shot("-c")
        }
    }
}
