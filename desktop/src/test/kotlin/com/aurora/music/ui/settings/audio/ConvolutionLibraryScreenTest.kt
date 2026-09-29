package com.aurora.music.ui.settings.audio

import androidx.compose.foundation.layout.PaddingValues
import com.aurora.music.data.SignalPath
import com.aurora.music.ui.screens.settings.ConvolutionLibraryScreen
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Test

class ConvolutionLibraryScreenTest {
    @Test fun rendersLibrary() {
        val events = mutableListOf<String>()
        AudioSettingsScene("impulses", seed = { store ->
            store.importImpulse(AudioFixtures.impulseWav().inputStream(), "Studio room.wav").getOrThrow()
            store.importImpulse(AudioFixtures.impulseWav(4_096, 44_100).inputStream(), "Headphone HRIR.wav").getOrThrow()
        }) {
            ConvolutionLibraryScreen(PaddingValues(), MutableStateFlow(SignalPath()), onBack = { events += "back" })
        }.use { scene ->
            assertTrue(scene.shot("-list").distinctColors() > 10)
        }
    }
}
