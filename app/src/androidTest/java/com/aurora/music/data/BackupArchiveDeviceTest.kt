package com.aurora.music.data

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.AuroraApplication
import com.google.gson.Gson
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
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Real preference storage, with isolated local/history stores and isolated imported assets. */
class BackupArchiveDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store get() = (context.applicationContext as AuroraApplication).container.settingsStore

    private fun fixture(block: suspend (BackupManager, LocalStore, PlayHistoryStore, File) -> Unit) = runBlocking {
        val original = store.exportPrefs()
        val root = File(context.cacheDir, "backup-test-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
        val local = LocalStore(isolated)
        val history = PlayHistoryStore(isolated)
        try {
            store.restoreBackupPrefs(original.copy(strings = original.strings + mapOf(
                BackupArchive.PRESETS_KEY to "[]", BackupArchive.IR_PATH_KEY to "",
                ProcessingRackCodec.PREFERENCE_KEY to ProcessingRackCodec.encode(ProcessingRack())),
                booleans = original.booleans + ("dsp_conv_enabled" to false))).getOrThrow()
            store.setAutoEqAutoSwitch(false)
            block(BackupManager(store, local, history, isolated), local, history, root)
        } finally {
            store.restoreBackupPrefs(original).getOrThrow()
            // Imported files are owned by this fixture only; original assets are untouched.
            root.deleteRecursively()
        }
    }

    @Test fun fullArchiveRestoresRackPresetsAndIrAfterOriginalAssetDisappears() = fixture { manager, local, history, root ->
        val source = File(root, "source.wav").apply { writeBytes(wav()) }
        val rack = ProcessingRack(enabled = true, name = "Portable rack", nodes = listOf(
            ProcessingRackNode(UUID.randomUUID().toString(), "Room", RackNodeKind.CONVOLUTION)))
        store.setDspConvIr(source.absolutePath, "Room.wav")
        store.setProcessingRack(rack).getOrThrow()
        val saved = store.saveProcessingPreset("Backup fixture").getOrThrow()
        // A preset owns its own copy. This fixture removes only the copy it just created.
        val savedAsset = File(saved.audio.dspConvIrPath)
        try {
            val id = local.createPlaylist("Restored playlist")
            local.addTracks(id, listOf("first", "second"))
            val output = ByteArrayOutputStream()
            manager.exportArchive(42, output).getOrThrow()
            store.setProcessingRack(rack.copy(enabled = false)).getOrThrow()
            store.setDspConvIr("", "")
            store.deleteProcessingPreset(saved.id).getOrThrow()
            source.delete(); savedAsset.delete(); local.deletePlaylist(id)
            // Deliberately fragmented reads exercise archive detection and bounded copying.
            val input = object : ByteArrayInputStream(output.toByteArray()) {
                override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(1, len))
            }
            manager.importArchive(input).getOrThrow()
            assertEquals(rack, store.processingRack.first())
            val restored = store.processingPresetLibrary.first().presets.single()
            assertEquals(saved.id, restored.id)
            assertEquals(rack, restored.rack)
            val active = store.audioPrefs.first().dspConvIrPath
            assertTrue(File(active).canonicalPath.startsWith(root.canonicalPath + File.separator))
            assertEquals(active, restored.audio.dspConvIrPath)
            assertArrayEquals(wav(), File(active).readBytes())
            assertEquals(listOf("first", "second"), local.playlist(id)?.trackIds)
            assertTrue(history.snapshot().isEmpty())
        } finally { savedAsset.delete() }
    }

    @Test fun missingDependencyFailsBeforeAnyStoreChanges() = fixture { manager, local, history, root ->
        val source = File(root, "source.wav").apply { writeBytes(wav()) }
        store.setDspConvIr(source.absolutePath, "Room.wav"); store.setDspConvEnabled(true)
        val output = ByteArrayOutputStream(); manager.exportArchive(42, output).getOrThrow()
        val broken = ByteArrayOutputStream()
        ZipOutputStream(broken).use { target -> ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { sourceZip ->
            while (true) {
                val entry = sourceZip.nextEntry ?: break
                if (entry.name == "backup.json") {
                    target.putNextEntry(ZipEntry(entry.name)); sourceZip.copyTo(target); target.closeEntry()
                }
            }
        } }
        val prefs = store.exportPrefs(); val playlists = local.exportJson(); val events = history.snapshot()
        assertTrue(manager.importArchive(ByteArrayInputStream(broken.toByteArray())).isFailure)
        assertEquals(prefs, store.exportPrefs()); assertEquals(playlists, local.exportJson()); assertEquals(events, history.snapshot())
        assertFalse(File(root, "files/processing-presets").exists())
    }

    @Test fun legacyJsonRestoresWithoutTrustingOldAbsoluteIrPaths() = fixture { manager, _, _, _ ->
        val rack = ProcessingRack(enabled = true, nodes = listOf(
            ProcessingRackNode(UUID.randomUUID().toString(), "Old IR", RackNodeKind.CONVOLUTION)))
        val prefs = store.exportPrefs().let { it.copy(strings = it.strings + mapOf(
            BackupArchive.IR_PATH_KEY to "/obsolete/device/impulse.wav",
            ProcessingRackCodec.PREFERENCE_KEY to ProcessingRackCodec.encode(rack)),
            booleans = it.booleans + ("dsp_conv_enabled" to true)) }
        val json = Gson().toJson(AuroraBackup(prefs = prefs))
        val result = manager.importArchive(ByteArrayInputStream(json.toByteArray())).getOrThrow()
        assertTrue(result.contains("without impulse responses"))
        assertEquals("", store.audioPrefs.first().dspConvIrPath)
        assertFalse(store.audioPrefs.first().dspConvEnabled)
        assertTrue(store.processingRack.first().nodes.single().bypass)
    }

    private fun wav() = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(40); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(1); putShort(2); putInt(48_000); putInt(192_000); putShort(4); putShort(16)
        put("data".toByteArray()); putInt(4); putShort(16384); putShort(8192)
    }.array()
}
