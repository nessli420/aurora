package com.aurora.music.ui.settings.audio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.aurora.music.playback.engine.OutputRatePolicy
import com.aurora.music.ui.screens.settings.AdvancedAudioSettingsScreen
import com.aurora.music.ui.screens.settings.LoudnessSettingsScreen
import com.aurora.music.ui.screens.settings.OutputRateSettings
import org.junit.Assert.assertTrue
import org.junit.Test

class LoudnessAndOutputTest {
    @Test fun rendersLoudness() {
        val events = mutableListOf<String>()
        AudioSettingsScene("loudness") {
            LoudnessSettingsScreen(PaddingValues(), onBack = { events += "back" }, onOpenEq = { events += "eq" }, onOpenSignalPath = { events += "signal" })
        }.use { scene -> assertTrue(scene.shot().distinctColors() > 10) }
    }

    @Test fun rendersAdvancedAudio() {
        val opened = mutableListOf<String>()
        AudioSettingsScene("advanced-audio") {
            AdvancedAudioSettingsScreen(PaddingValues(), onBack = {}, onOpen = { opened += it.route })
        }.use { scene -> assertTrue(scene.shot().distinctColors() > 10) }
    }

    @Test fun rendersOutputRate() {
        AudioSettingsScene("output-rate", height = 500) {
            var policy by remember { mutableStateOf(OutputRatePolicy()) }
            Column { OutputRateSettings(policy) { change -> policy = change(policy) } }
        }.use { scene -> assertTrue(scene.shot().distinctColors() > 5) }
    }
}
