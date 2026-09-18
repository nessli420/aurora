package com.aurora.music.data

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.AuroraApplication
import com.aurora.music.data.ir.*
import com.aurora.music.playback.ConvolutionProcessor
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

class ImpulseLibraryDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store get() = (context.applicationContext as AuroraApplication).container.settingsStore

    private fun fixture(block: suspend (File, MutableList<File>) -> Unit) = runBlocking {
        val original = store.exportPrefs()
        val files = mutableListOf<File>()
        val root = File(context.cacheDir, "ir-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            store.restoreBackupPrefs(original.copy(strings = original.strings + mapOf(
                ImpulseLibraryCodec.PREFERENCE_KEY to ImpulseLibraryCodec.encodeLibrary(emptyList()),
                BackupArchive.PRESETS_KEY to "[]", BackupArchive.IR_PATH_KEY to "",
                ProcessingRackCodec.PREFERENCE_KEY to ProcessingRackCodec.encode(ProcessingRack())),
                booleans = original.booleans + ("dsp_conv_enabled" to false))).getOrThrow()
            store.setAutoEqAutoSwitch(false)
            block(root, files)
        } finally {
            files += store.impulseLibrary.first().flatMap { entry ->
                listOfNotNull(File(entry.sourcePath), entry.prepared?.path?.let(::File))
            }
            store.restoreBackupPrefs(original).getOrThrow()
            assertEquals(original, store.exportPrefs())
            files.distinct().forEach { it.delete() }
            root.deleteRecursively()
        }
    }

    @Test fun importPrepareExportSelectAndDeletePreserveOtherSettingsAndOriginalSamples() = fixture { _, files ->
        val before = store.exportPrefs()
        val bytes = wav()
        val entry = store.importImpulse(ByteArrayInputStream(bytes), "Stereo.wav").getOrThrow()
        files += File(entry.sourcePath)
        val imported = store.exportPrefs()
        assertEquals(before, imported.copy(strings = imported.strings - ImpulseLibraryCodec.PREFERENCE_KEY +
            (ImpulseLibraryCodec.PREFERENCE_KEY to before.strings.getValue(ImpulseLibraryCodec.PREFERENCE_KEY))))
        assertEquals(2, entry.sourceMetadata.channels)
        assertEquals(48_000, entry.sourceMetadata.sampleRate)
        assertArrayEquals(bytes, File(entry.sourcePath).readBytes())
        store.renameImpulse(entry.id, "Room").getOrThrow()
        store.prepareImpulse(entry.id, ImpulsePreparation(1, 3, ImpulseNormalization.PEAK_MINUS_1_DB)).getOrThrow()
        val preparedEntry = store.impulseLibrary.first().single()
        val prepared = requireNotNull(preparedEntry.prepared)
        files += File(prepared.path)
        assertEquals("Room", preparedEntry.name)
        assertArrayEquals(bytes, File(entry.sourcePath).readBytes())
        assertEquals(2, prepared.metadata.frames)
        assertEquals(ImpulseLibraryFiles.NORMALIZED_PEAK, prepared.metadata.peak, 1e-7)
        val ir = ConvolutionProcessor.loadWavResult(File(prepared.path)).getOrThrow()
        assertEquals(ir.preciseLeft[0] / 2, ir.preciseRight[0], 1e-7)
        val originalExport = ByteArrayOutputStream()
        store.exportImpulse(entry.id, false, originalExport).getOrThrow()
        assertArrayEquals(bytes, originalExport.toByteArray())
        val variantExport = ByteArrayOutputStream()
        store.exportImpulse(entry.id, true, variantExport).getOrThrow()
        assertArrayEquals(File(prepared.path).readBytes(), variantExport.toByteArray())
        store.setDspConvMakeup(-3f)
        val beforeSelect = store.exportPrefs()
        store.selectImpulse(entry.id, true).getOrThrow()
        val selected = store.exportPrefs()
        assertEquals(beforeSelect, selected.copy(strings = selected.strings + mapOf(
            "dsp_conv_path" to beforeSelect.strings.getValue("dsp_conv_path"),
            "dsp_conv_name" to beforeSelect.strings.getOrDefault("dsp_conv_name", "")))
            .let { if ("dsp_conv_name" in beforeSelect.strings) it else it.copy(strings = it.strings - "dsp_conv_name") })
        assertEquals(prepared.path, store.audioPrefs.first().dspConvIrPath)
        assertFalse(store.audioPrefs.first().dspConvEnabled)
        val selectedVariant = store.audioPrefs.first()
        assertEquals("Room · Variant", selectedVariant.dspConvIrName)
        store.renameImpulse(entry.id, "  Renamed room  ").getOrThrow()
        assertEquals(selectedVariant.copy(dspConvIrName = "Renamed room · Variant"), store.audioPrefs.first())
        assertEquals("Renamed room", store.impulseLibrary.first().single().name)
        store.selectImpulse(entry.id, false).getOrThrow()
        val selectedOriginal = store.audioPrefs.first()
        store.renameImpulse(entry.id, "Original room").getOrThrow()
        assertEquals(selectedOriginal.copy(dspConvIrName = "Original room"), store.audioPrefs.first())
        store.selectImpulse(entry.id, true).getOrThrow()
        store.deleteImpulse(entry.id).getOrThrow()
        assertTrue(store.impulseLibrary.first().isEmpty())
        assertTrue(File(prepared.path).isFile)
        assertEquals(prepared.path, store.audioPrefs.first().dspConvIrPath)
    }

    @Test fun invalidFilesEditsAndChangedAssetsLeavePreferencesUntouched() = fixture { _, files ->
        val before = store.exportPrefs()
        assertTrue(store.importImpulse(ByteArrayInputStream(byteArrayOf(1, 2, 3)), "Bad.wav").isFailure)
        assertEquals(before, store.exportPrefs())
        val entry = store.importImpulse(ByteArrayInputStream(wav()), "Valid.wav").getOrThrow()
        files += File(entry.sourcePath)
        val saved = store.exportPrefs()
        assertTrue(store.renameImpulse(entry.id, " ").isFailure)
        assertTrue(store.prepareImpulse(entry.id, ImpulsePreparation(2, 2)).isFailure)
        assertTrue(store.selectImpulse(entry.id, true).isFailure)
        assertEquals(saved, store.exportPrefs())
        File(entry.sourcePath).appendBytes(byteArrayOf(0))
        assertTrue(store.selectImpulse(entry.id, false).isFailure)
        assertTrue(store.exportImpulse(entry.id, false, ByteArrayOutputStream()).isFailure)
        assertEquals(saved, store.exportPrefs())
        assertTrue(store.restoreBackupPrefs(saved.copy(strings = saved.strings +
            (ImpulseLibraryCodec.PREFERENCE_KEY to "{bad"))).isFailure)
        assertEquals(saved, store.exportPrefs())
    }

    @Test fun fullBackupIncludesUnselectedOriginalAndVariantAndRemapsBoth() = fixture { root, files ->
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
        val manager = BackupManager(store, LocalStore(isolated), PlayHistoryStore(isolated), isolated)
        val entry = store.importImpulse(ByteArrayInputStream(wav()), "Backup.wav").getOrThrow()
        files += File(entry.sourcePath)
        store.prepareImpulse(entry.id, ImpulsePreparation(1, 3)).getOrThrow()
        val saved = store.impulseLibrary.first().single()
        files += File(requireNotNull(saved.prepared).path)
        val original = File(saved.sourcePath).readBytes()
        val prepared = File(saved.prepared!!.path).readBytes()
        val output = ByteArrayOutputStream()
        manager.exportArchive(1, output).getOrThrow()
        store.deleteImpulse(entry.id).getOrThrow()
        files.forEach { it.delete() }
        manager.importArchive(ByteArrayInputStream(output.toByteArray())).getOrThrow()
        val restored = store.impulseLibrary.first().single()
        assertEquals(saved.id, restored.id)
        assertEquals(saved.sourceMetadata, restored.sourceMetadata)
        assertNotEquals(saved.sourcePath, restored.sourcePath)
        assertArrayEquals(original, File(restored.sourcePath).readBytes())
        assertArrayEquals(prepared, File(restored.prepared!!.path).readBytes())
        assertEquals("", store.audioPrefs.first().dspConvIrPath)
        store.selectImpulse(restored.id, true).getOrThrow()
        assertEquals(restored.prepared.path, store.audioPrefs.first().dspConvIrPath)
        val json = manager.export(2)
        assertTrue(manager.import(json))
        assertTrue(store.impulseLibrary.first().isEmpty())
        assertEquals("", store.audioPrefs.first().dspConvIrPath)
    }

    @Test fun currentLegacyResponseCanBeSavedWithoutChangingItsSelection() = fixture { root, files ->
        val legacy = File(root, "legacy.wav").apply { writeBytes(wav()) }
        store.setDspConvIr(legacy.absolutePath, "Legacy.wav")
        val before = store.audioPrefs.first()
        val entry = store.importCurrentImpulse().getOrThrow()
        files += File(entry.sourcePath)
        assertNotEquals(legacy.absolutePath, entry.sourcePath)
        assertEquals(before, store.audioPrefs.first())
        legacy.writeBytes(byteArrayOf(0))
        assertArrayEquals(wav(), File(entry.sourcePath).readBytes())
    }

    @Test fun longSourceNamesKeepTheirFullNameAndUseABoundedDisplayName() = fixture { _, files ->
        val name = "Room-" + "a".repeat(100) + ".wav"
        val entry = store.importImpulse(ByteArrayInputStream(wav()), name).getOrThrow()
        files += File(entry.sourcePath)
        assertEquals(name, entry.sourceName)
        assertEquals(name.substringBeforeLast('.').take(80), entry.name)
    }

    private fun wav(): ByteArray = ByteBuffer.allocate(60).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(52); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(1); putShort(2); putInt(48_000); putInt(192_000); putShort(4); putShort(16)
        put("data".toByteArray()); putInt(16)
        listOf(0, 0, 16384, 8192, -4096, -2048, 0, 0).forEach { putShort(it.toShort()) }
    }.array()
}
