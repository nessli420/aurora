package com.aurora.music.ui.settings.audio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.aurora.music.navigation.Routes
import com.aurora.music.playback.engine.OutputRateMode
import com.aurora.music.playback.engine.OutputRatePolicy
import com.aurora.music.ui.screens.settings.AdvancedAudioSettingsScreen
import com.aurora.music.ui.screens.settings.LoudnessSettingsScreen
import com.aurora.music.ui.screens.settings.OutputRateSettings
import com.aurora.music.ui.testing.differsFrom
import com.aurora.music.ui.testing.distinctColors
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoudnessAndOutputTest {
    @Test fun loudnessStoresTheLevelingMode() {
        val events = mutableListOf<String>()
        AudioSettingsScene("loudness") {
            LoudnessSettingsScreen(PaddingValues(), onBack = { events += "back" }, onOpenEq = { events += "eq" }, onOpenSignalPath = { events += "signal" })
        }.use { scene ->
            val off = scene.shot()
            scene.click(420f, 156f)
            scene.await(read = { it.audioPrefs.first().replayGain }) { it == 1 }
            assertTrue(scene.shot("-track").differsFrom(off))
            scene.click(400f, 322f)
            scene.click(400f, 389f)
            assertEquals(listOf("eq", "signal"), events)
        }
    }

    @Test fun advancedAudioOpensDesktopDestinations() {
        val opened = mutableListOf<String>()
        AudioSettingsScene("advanced-audio") {
            AdvancedAudioSettingsScreen(PaddingValues(), onBack = {}, onOpen = { opened += it.route })
        }.use { scene ->
            assertTrue(scene.shot().distinctColors() > 10)
            listOf(131f, 198f, 265f, 332f, 442f, 509f, 576f).forEach { scene.click(400f, it) }
            assertEquals(listOf(Routes.SETTINGS_PROCESSING_RACK, Routes.SETTINGS_PROCESSING_PRESETS, Routes.SETTINGS_IMPULSES, Routes.SETTINGS_PRESET_RULES,
                Routes.SETTINGS_TUNING, Routes.SETTINGS_COMPARISON, Routes.SETTINGS_LISTENING), opened)
        }
    }

    @Test fun outputRateMenusEditThePolicy() {
        var latest = OutputRatePolicy()
        AudioSettingsScene("output-rate", height = 500) {
            var policy by remember { mutableStateOf(OutputRatePolicy()) }
            Column { OutputRateSettings(policy) { change -> policy = change(policy).also { latest = it } } }
        }.use { scene ->
            val follow = scene.shot()
            scene.click(420f, 69f)
            scene.click(72f, 173f)
            assertEquals(OutputRateMode.FIXED, latest.mode)
            assertTrue(scene.shot("-fixed").differsFrom(follow))
        }
    }
}
