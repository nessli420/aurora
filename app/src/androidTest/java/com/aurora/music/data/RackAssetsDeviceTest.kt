package com.aurora.music.data

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.AuroraApplication
import com.aurora.music.data.ir.*
import com.aurora.music.data.routes.ProcessingRouteCodec
import com.aurora.music.data.routes.ProcessingRouteRules
import com.aurora.music.data.rules.PresetRuleCodec
import com.aurora.music.data.rules.PresetRuleSessionCodec
import com.aurora.music.data.rules.PresetRuleSet
import com.aurora.music.playback.ConvolutionProcessor
import com.aurora.music.playback.engine.OutputRateMode
import com.aurora.music.playback.engine.OutputRatePolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class RackAssetsDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store get() = (context.applicationContext as AuroraApplication).container.settingsStore

    private fun assets(): List<File> = listOf("processing-presets", "impulse-library").flatMap {
        File(context.filesDir, it).listFiles()?.filter(File::isFile).orEmpty()
    }

    private fun fixture(block: suspend (File) -> Unit) = runBlocking {
        val original = store.exportPrefs()
        val previousFiles = assets().map { it.canonicalPath }.toSet()
        val root = File(context.cacheDir, "rack-assets-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val empty = ImpulseLibraryCodec.encodeLibrary(emptyList())
            val strings = original.strings - PresetRuleSessionCodec.PREFERENCE_KEY + mapOf(
                ImpulseLibraryCodec.PREFERENCE_KEY to empty, BackupArchive.ACTIVE_RACK_IR_KEY to empty,
                BackupArchive.PRESETS_KEY to "[]", BackupArchive.IR_PATH_KEY to "",
                ProcessingRackCodec.PREFERENCE_KEY to ProcessingRackCodec.encode(ProcessingRack()),
                RackSubchainCodec.PREFERENCE_KEY to RackSubchainCodec.encode(emptyList()),
                ProcessingRouteCodec.PREFERENCE_KEY to ProcessingRouteCodec.encode(ProcessingRouteRules()),
                PresetRuleCodec.PREFERENCE_KEY to PresetRuleCodec.encode(PresetRuleSet()))
            store.restoreBackupPrefs(original.copy(strings = strings,
                booleans = original.booleans + ("dsp_conv_enabled" to false))).getOrThrow()
            store.setAutoEqAutoSwitch(false)
            block(root)
        } finally {
            store.restoreBackupPrefs(original).getOrThrow()
            assertEquals(original, store.exportPrefs())
            assets().filter { it.canonicalPath !in previousFiles }.forEach { it.delete() }
            root.deleteRecursively()
        }
    }

    private suspend fun preparePreset(): Pair<ImpulseLibraryEntry, ProcessingPreset> {
        val entry = store.importImpulse(ByteArrayInputStream(wav()), "Matrix.wav").getOrThrow()
        store.prepareImpulse(entry.id, ImpulsePreparation(1, 6, delayFrames = 2)).getOrThrow()
        val prepared = store.impulseLibrary.first().single()
        store.setProcessingRack(ProcessingRack(enabled = true, nodes = listOf(
            ProcessingRackNode(UUID.randomUUID().toString(), "Matrix", RackNodeKind.CONVOLUTION, impulseId = entry.id)))).getOrThrow()
        store.setOutputRatePolicy(OutputRatePolicy(OutputRateMode.FIXED, 96_000, tpdfDither = true)).getOrThrow()
        return prepared to store.saveProcessingPreset("Frozen matrix").getOrThrow()
    }

    @Test fun savedPresetOwnsOriginalAndPreparedAssetsAfterLibraryChangesAndDeletion() = fixture {
        val (entry, preset) = preparePreset()
        val saved = preset.rackImpulseAssets.single()
        assertNotEquals(entry.id, saved.id)
        assertNotEquals(entry.sourcePath, saved.sourcePath)
        assertNotEquals(entry.prepared!!.path, saved.prepared!!.path)
        val originalBytes = File(saved.sourcePath).readBytes()
        val preparedBytes = File(saved.prepared.path).readBytes()
        store.prepareImpulse(entry.id, ImpulsePreparation(0, 2)).getOrThrow()
        store.deleteImpulse(entry.id).getOrThrow()
        File(entry.sourcePath).writeBytes(byteArrayOf(0))
        store.applyProcessingPreset(preset.id).getOrThrow()
        assertEquals(preset.rack, store.processingRack.first())
        assertEquals(preset.playback.outputRatePolicy, store.outputRatePolicy.first())
        store.deleteProcessingPreset(preset.id).getOrThrow()
        val active = store.processingRackAssets.first().single()
        assertEquals(saved.id, active.id)
        assertArrayEquals(originalBytes, File(active.sourcePath).readBytes())
        assertArrayEquals(preparedBytes, File(active.prepared!!.path).readBytes())
        assertTrue(ConvolutionProcessor.loadWavResult(File(active.prepared.path)).getOrThrow().trueStereo)
    }

    @Test fun portablePresetImportRehomesEveryAssetBeforeApplying() = fixture {
        val (_, preset) = preparePreset()
        val bytes = ByteArrayOutputStream().also { store.exportProcessingPreset(preset.id, it).getOrThrow() }.toByteArray()
        val saved = preset.rackImpulseAssets.single()
        val expected = File(saved.prepared!!.path).readBytes()
        store.deleteProcessingPreset(preset.id).getOrThrow()
        File(saved.sourcePath).delete(); File(saved.prepared.path).delete()
        val imported = store.importProcessingPreset(ByteArrayInputStream(bytes)).getOrThrow()
        assertNotEquals(preset.id, imported.id)
        val asset = imported.rackImpulseAssets.single()
        assertNotEquals(saved.id, asset.id)
        assertNotEquals(saved.sourcePath, asset.sourcePath)
        ProcessingPresetAssets.validateFiles(imported.rackImpulseAssets)
        assertArrayEquals(expected, File(asset.prepared!!.path).readBytes())
        store.applyProcessingPreset(imported.id).getOrThrow()
        assertEquals(asset.id, store.processingRack.first().nodes.single().impulseId)
        assertEquals(asset.id, store.processingRackAssets.first().single().id)
    }

    @Test fun savedSubchainOwnsSharedImpulseAndSurvivesSourceAndSubchainDeletion() = fixture { root ->
        val shared = File(root, "shared.wav").apply { writeBytes(wav()) }
        store.setDspConvIr(shared.absolutePath, "Shared")
        val node = ProcessingRackNode(UUID.randomUUID().toString(), "Shared FIR", RackNodeKind.CONVOLUTION)
        val rack = ProcessingRack(enabled = true, nodes = listOf(node))
        store.setProcessingRack(rack).getOrThrow()
        store.saveRackSubchain(RackSubchainCodec.capture(rack, setOf(node.id), "Saved FIR")).getOrThrow()
        val chain = store.rackSubchains.first().single()
        val asset = chain.impulseAssets.single()
        assertNotEquals(shared.absolutePath, asset.sourcePath)
        assertEquals(asset.id, chain.rack.nodes.single().impulseId)
        shared.writeBytes(byteArrayOf(0))
        store.setDspConvIr("", "")
        store.setProcessingRack(ProcessingRack()).getOrThrow()
        store.appendRackSubchain(chain).getOrThrow()
        store.deleteRackSubchain(chain.id).getOrThrow()
        assertEquals(asset.id, store.processingRack.first().nodes.last().impulseId)
        val active = store.processingRackAssets.first().single()
        assertArrayEquals(wav(), File(active.sourcePath).readBytes())
        ProcessingPresetAssets.validateFiles(listOf(active))
    }

    @Test fun backupRestoresActiveAssetsAfterPresetAndLibraryAreGone() = fixture { root ->
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
        val manager = BackupManager(store, LocalStore(isolated), PlayHistoryStore(isolated), isolated)
        val (entry, preset) = preparePreset()
        store.applyProcessingPreset(preset.id).getOrThrow()
        store.deleteProcessingPreset(preset.id).getOrThrow()
        store.deleteImpulse(entry.id).getOrThrow()
        val active = store.processingRackAssets.first().single()
        val expected = File(active.prepared!!.path).readBytes()
        val backup = ByteArrayOutputStream().also { manager.exportArchive(1, it).getOrThrow() }.toByteArray()
        File(active.sourcePath).delete(); File(active.prepared.path).delete()
        store.setProcessingRack(ProcessingRack()).getOrThrow()
        manager.importArchive(ByteArrayInputStream(backup)).getOrThrow()
        val restored = store.processingRackAssets.first().single()
        assertEquals(active.id, restored.id)
        assertNotEquals(active.sourcePath, restored.sourcePath)
        assertArrayEquals(expected, File(restored.prepared!!.path).readBytes())
        ProcessingPresetAssets.validateFiles(listOf(restored))
        assertTrue(store.processingPresetLibrary.first().presets.isEmpty())
        assertTrue(store.impulseLibrary.first().isEmpty())
    }

    private fun wav(): ByteArray {
        val channels = 4; val frames = 8; val payload = channels * frames * 4
        return ByteBuffer.allocate(44 + payload).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x46464952); putInt(36 + payload); putInt(0x45564157)
            putInt(0x20746d66); putInt(16); putShort(3); putShort(channels.toShort())
            putInt(48_000); putInt(48_000 * channels * 4); putShort((channels * 4).toShort()); putShort(32)
            putInt(0x61746164); putInt(payload)
            repeat(frames) { frame ->
                listOf(.5f, .125f, -.25f, .75f).forEach { gain -> putFloat(if (frame == 2) gain else 0f) }
            }
        }.array()
    }
}
