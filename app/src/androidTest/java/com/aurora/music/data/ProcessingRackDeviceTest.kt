package com.aurora.music.data

import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.AuroraApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

class ProcessingRackDeviceTest {
    private val store get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AuroraApplication).container.settingsStore
    private fun graph(enabled: Boolean = true) = ProcessingRack(enabled = enabled, name = "R1e fixture", nodes = listOf(
        ProcessingRackNode(UUID.randomUUID().toString(), "Trim", RackNodeKind.GAIN, audio = AudioPrefs(dspPreampDb = -4f))))

    @Test fun enablingAndEditingRackPublishesCustomModeAndGraphAtomically() = runBlocking {
        val original = store.exportPrefs()
        try {
            store.setProcessingRack(graph(false)).getOrThrow()
            store.setDspMode(DspMode.OFF)
            val before = store.processingSettings.first()
            val target = graph()
            val observations = mutableListOf<ProcessingSettings>()
            val started = CompletableDeferred<Unit>()
            val reached = CompletableDeferred<Unit>()
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                store.processingSettings.collect { value ->
                    observations += value
                    started.complete(Unit)
                    if (value.rack == target) reached.complete(Unit)
                }
            }
            try {
                withTimeout(5_000) { started.await() }
                store.setProcessingRack(target).getOrThrow()
                withTimeout(5_000) { reached.await() }
            } finally { watcher.cancelAndJoin() }
            assertTrue(observations.all { it == before || it.rack == target && it.audio.dspMode == DspMode.CUSTOM })
            assertEquals(target, store.processingSnapshot.first().rack)
            val changed = target.copy(nodes = target.nodes.map { it.copy(wet = 0.5f) })
            store.setProcessingRack(changed).getOrThrow()
            assertEquals(changed, store.processingRack.first())
            store.setDspMode(DspMode.OFF)
            val stopped = store.processingSettings.first()
            assertFalse(stopped.rack.enabled)
            assertEquals(DspMode.OFF, stopped.audio.dspMode)
        } finally { store.restoreBackupPrefs(original).getOrThrow() }
    }

    @Test fun saveDuplicatePortableImportAndApplyCarryTheCompleteGraph() = runBlocking {
        val original = store.exportPrefs()
        try {
            store.setAutoEqAutoSwitch(false)
            store.setDspConvEnabled(false)
            store.setDspConvIr("", "")
            val target = graph().copy(nodes = graph().nodes + ProcessingRackNode(UUID.randomUUID().toString(), "Output", RackNodeKind.LIMITER))
            store.setProcessingRack(target).getOrThrow()
            val saved = store.saveProcessingPreset("Rack ${UUID.randomUUID()}").getOrThrow()
            assertEquals(target, saved.rack)
            val duplicate = store.duplicateProcessingPreset(saved.id, "Rack copy").getOrThrow()
            assertEquals(target, duplicate.rack)
            val output = ByteArrayOutputStream()
            store.exportProcessingPreset(saved.id, output).getOrThrow()
            store.setProcessingRack(target.copy(enabled = false)).getOrThrow()
            val beforeImport = store.processingSnapshot.first()
            val imported = store.importProcessingPreset(ByteArrayInputStream(output.toByteArray())).getOrThrow()
            assertEquals(target, imported.rack)
            assertEquals(beforeImport, store.processingSnapshot.first())
            store.applyProcessingPreset(imported.id).getOrThrow()
            assertEquals(target, store.processingRack.first())
            assertEquals(DspMode.CUSTOM, store.audioPrefs.first().dspMode)
        } finally { store.restoreBackupPrefs(original).getOrThrow() }
    }

    @Test fun invalidRackOrBackupNeverPartiallyWritesPreferences() = runBlocking {
        val original = store.exportPrefs()
        try {
            val valid = graph()
            store.setProcessingRack(valid).getOrThrow()
            val before = store.exportPrefs()
            assertTrue(store.setProcessingRack(valid.copy(nodes = listOf(valid.nodes[0], valid.nodes[0]))).isFailure)
            assertEquals(before, store.exportPrefs())
            assertTrue(store.restoreBackupPrefs(before.copy(strings = before.strings +
                (ProcessingRackCodec.PREFERENCE_KEY to "{broken"))).isFailure)
            assertEquals(before, store.exportPrefs())
            assertTrue(store.restoreBackupPrefs(before.copy(strings = before.strings + ("dsp_parametric" to "700:bad:1"))).isFailure)
            assertEquals(before, store.exportPrefs())
            assertTrue(store.restoreBackupPrefs(before.copy(floats = before.floats + ("dsp_preamp" to Float.NaN))).isFailure)
            assertEquals(before, store.exportPrefs())
            assertTrue(store.restoreBackupPrefs(before.copy(strings = before.strings + ("dsp_preamp" to "wrong-type"),
                floats = before.floats + ("dsp_preamp" to -4f))).isFailure)
            assertEquals(before, store.exportPrefs())
        } finally { store.restoreBackupPrefs(original).getOrThrow() }
    }

    @Test fun fullBackupReplacementRestoresGraphAndRemovesPreferencesAbsentFromBackup() = runBlocking {
        val original = store.exportPrefs()
        try {
            val target = graph()
            val restored = original.copy(strings = original.strings + (ProcessingRackCodec.PREFERENCE_KEY to ProcessingRackCodec.encode(target)),
                ints = original.ints + ("dsp_mode" to DspMode.OFF))
            store.importPrefs(PrefsBackup(strings = mapOf("r1e_temporary_test_key" to "remove-on-restore")))
            store.restoreBackupPrefs(restored).getOrThrow()
            assertEquals(target, store.processingRack.first())
            assertEquals(DspMode.CUSTOM, store.audioPrefs.first().dspMode)
            assertFalse(store.exportPrefs().strings.containsKey("r1e_temporary_test_key"))
            assertEquals(original.strings["server"], store.exportPrefs().strings["server"])
        } finally { store.restoreBackupPrefs(original).getOrThrow() }
    }

    @Test fun activeRackConvolutionRequiresASavedGlobalAssetEvenIfLegacySwitchIsOff() = runBlocking {
        val original = store.exportPrefs()
        try {
            store.setDspConvEnabled(false); store.setDspConvIr("", "")
            val target = graph().copy(nodes = listOf(ProcessingRackNode(UUID.randomUUID().toString(), "IR", RackNodeKind.CONVOLUTION)))
            store.setProcessingRack(target).getOrThrow()
            val before = store.exportPrefs()
            assertTrue(store.saveProcessingPreset("Missing graph IR").isFailure)
            assertEquals(before, store.exportPrefs())
            store.setProcessingRack(target.copy(nodes = target.nodes.map { it.copy(bypass = true) })).getOrThrow()
            assertTrue(store.saveProcessingPreset("Bypassed graph IR").isSuccess)
        } finally { store.restoreBackupPrefs(original).getOrThrow() }
    }
}
