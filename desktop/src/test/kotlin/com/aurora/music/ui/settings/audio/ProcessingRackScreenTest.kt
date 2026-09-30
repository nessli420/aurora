package com.aurora.music.ui.settings.audio

import androidx.compose.foundation.layout.PaddingValues
import com.aurora.music.data.ProcessingRack
import com.aurora.music.data.ProcessingRackNode
import com.aurora.music.data.RackNodeKind
import com.aurora.music.data.SettingsStore
import com.aurora.music.data.SignalPath
import com.aurora.music.ui.screens.settings.ProcessingRackScreen
import com.aurora.music.ui.testing.differsFrom
import com.aurora.music.ui.testing.distinctColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ProcessingRackScreenTest {
    private val seeded: suspend (SettingsStore) -> Unit = { store ->
        store.setProcessingRack(ProcessingRack.recommended(AudioFixtures.customAudio).copy(enabled = true)).getOrThrow()
    }

    private fun rack(name: String, events: MutableList<String> = mutableListOf(), path: SignalPath = AudioFixtures.activePath,
                     seed: suspend (SettingsStore) -> Unit = seeded) =
        AudioSettingsScene(name, seed = seed) {
            ProcessingRackScreen(PaddingValues(), MutableStateFlow(path), onBack = { events += "back" }, onOpenPresets = { events += "presets" },
                onOpenSignalPath = { events += "signal" }, onOpenTuning = { events += "tuning" }, onOpenImpulses = { events += "impulses" })
        }

    @Test fun rackSwitchesModeAndLeaves() {
        val events = mutableListOf<String>()
        rack("rack-list", events).use { scene ->
            assertTrue(scene.shot().distinctColors() > 20)
            scene.click(400f, 210f)
            scene.await(read = { it.processingRack.first() }) { it.autoHeadroom }
            scene.click(233f, 142f)
            scene.await(read = { it.processingRack.first() }) { !it.enabled && it.autoHeadroom }
            scene.click(36f, 52f)
            scene.settle(300)
            assertEquals(listOf("back"), events)
        }
    }

    @Test fun stageEditorShowsTheCalculatedResponse() {
        rack("rack-editor").use { scene ->
            val list = scene.settle()
            scene.click(100f, 590f)
            val editor = scene.shot()
            assertTrue(editor.differsFrom(list))
            scene.click(400f, 493f)
            scene.settle(700)
            assertTrue(scene.shot("-response").differsFrom(editor))
            scene.click(36f, 52f)
            assertTrue(scene.settle().differsFrom(editor))
        }
    }

    @Test fun advancedStagesShowTheirOwnControls() {
        val nodes = listOf(
            ProcessingRackNode(UUID.randomUUID().toString(), "Multiband", RackNodeKind.MULTIBAND),
            ProcessingRackNode(UUID.randomUUID().toString(), "Tape colour", RackNodeKind.TONE),
        )
        rack("rack-advanced", path = SignalPath(), seed = { store ->
            store.setProcessingRack(ProcessingRack(enabled = true, name = "Mastering", nodes = nodes)).getOrThrow()
        }).use { scene ->
            val list = scene.shot()
            scene.click(100f, 450f)
            val multiband = scene.shot("-multiband")
            assertTrue(multiband.differsFrom(list))
            scene.click(36f, 52f)
            scene.click(100f, 589f)
            assertTrue(scene.shot("-tone").differsFrom(multiband))
        }
    }
}
