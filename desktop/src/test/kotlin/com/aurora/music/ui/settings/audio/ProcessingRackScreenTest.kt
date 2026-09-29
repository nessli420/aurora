package com.aurora.music.ui.settings.audio

import androidx.compose.foundation.layout.PaddingValues
import com.aurora.music.data.ProcessingRack
import com.aurora.music.data.SettingsStore
import com.aurora.music.data.SignalPath
import com.aurora.music.ui.screens.settings.ProcessingRackScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingRackScreenTest {
    private val seeded: suspend (SettingsStore) -> Unit = { store ->
        store.setProcessingRack(ProcessingRack.recommended(AudioFixtures.customAudio).copy(enabled = true)).getOrThrow()
    }

    private fun rack(name: String, events: MutableList<String> = mutableListOf(), path: SignalPath = AudioFixtures.activePath) =
        AudioSettingsScene(name, seed = seeded) {
            ProcessingRackScreen(PaddingValues(), MutableStateFlow(path), onBack = { events += "back" }, onOpenPresets = { events += "presets" },
                onOpenSignalPath = { events += "signal" }, onOpenTuning = { events += "tuning" }, onOpenImpulses = { events += "impulses" })
        }

    @Test fun rackSwitchesModeAndLeaves() {
        val events = mutableListOf<String>()
        rack("rack-list", events).use { scene ->
            assertTrue(scene.shot().distinctColors() > 20)
            scene.click(400f, 180f)
            scene.await(read = { it.processingRack.first() }) { it.autoHeadroom }
            scene.click(227f, 112f)
            scene.await(read = { it.processingRack.first() }) { !it.enabled && it.autoHeadroom }
            scene.click(28f, 26f)
            scene.settle(300)
            assertEquals(listOf("back"), events)
        }
    }

    @Test fun stageEditorShowsTheCalculatedResponse() {
        rack("rack-editor").use { scene ->
            val list = scene.settle()
            scene.click(100f, 560f)
            val editor = scene.shot()
            assertTrue(editor.differsFrom(list))
            scene.click(400f, 463f)
            scene.settle(700)
            assertTrue(scene.shot("-response").differsFrom(editor))
            scene.click(28f, 26f)
            assertTrue(scene.settle().differsFrom(editor))
        }
    }
}
