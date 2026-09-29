package com.aurora.music.ui.settings.audio

import androidx.compose.foundation.layout.PaddingValues
import com.aurora.music.data.SignalPath
import com.aurora.music.ui.screens.settings.SignalPathScreen
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalPathScreenTest {
    @Test fun rendersIdleAndActivePaths() {
        val path = MutableStateFlow(SignalPath())
        val events = mutableListOf<String>()
        AudioSettingsScene("signal-path") {
            SignalPathScreen(PaddingValues(), path, onBack = { events += "back" }, onOpenOutput = { events += "output" })
        }.use { scene ->
            val idle = scene.shot("-idle")
            path.value = AudioFixtures.activePath
            val active = scene.shot("-active")
            assertTrue(active.differsFrom(idle))
        }
    }
}
