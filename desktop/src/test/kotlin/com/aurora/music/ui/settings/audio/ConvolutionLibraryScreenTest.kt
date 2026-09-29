package com.aurora.music.ui.settings.audio

import androidx.compose.foundation.layout.PaddingValues
import com.aurora.music.data.SignalPath
import com.aurora.music.ui.screens.settings.ConvolutionLibraryScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConvolutionLibraryScreenTest {
    @Test fun opensAnImpulseAndSelectsIt() {
        val events = mutableListOf<String>()
        AudioSettingsScene("impulses", seed = { store ->
            store.importImpulse(AudioFixtures.impulseWav().inputStream(), "Studio room.wav").getOrThrow()
            store.importImpulse(AudioFixtures.impulseWav(4_096, 44_100).inputStream(), "Headphone HRIR.wav").getOrThrow()
        }) {
            ConvolutionLibraryScreen(PaddingValues(), MutableStateFlow(SignalPath()), onBack = { events += "back" })
        }.use { scene ->
            val list = scene.shot("-list")
            assertTrue(list.distinctColors() > 10)
            scene.click(100f, 148f)
            val detail = scene.shot("-detail")
            assertTrue(detail.differsFrom(list))
            scene.click(420f, 532f)
            val studio = scene.await(read = { it.impulseLibrary.first().first() }) { true }
            scene.await(read = { it.audioPrefs.first().dspConvIrPath }) { it == studio.sourcePath }
            scene.click(420f, 648f)
            assertTrue(scene.shot("-prepare").differsFrom(detail))
            scene.click(527f, 837f)
            scene.click(28f, 26f)
            scene.click(28f, 26f)
            assertEquals(listOf("back"), events)
            val entries = scene.await(read = { it.impulseLibrary.first() }) { it.size == 2 }
            assertEquals(listOf("Studio room", "Headphone HRIR"), entries.map { it.name })
        }
    }
}
