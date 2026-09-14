package com.aurora.music.data

import androidx.test.platform.app.InstrumentationRegistry
import com.aurora.music.AuroraApplication
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

/** Exercises the real DataStore while preserving current processing and pre-existing presets. */
class ProcessingPresetBundleDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store get() = (context.applicationContext as AuroraApplication).container.settingsStore

    @Test fun portableIrRestoresAfterOriginalFilesAreRemovedWithoutApplying() = runBlocking {
        val original = store.audioPrefs.first()
        val originalLibrary = store.processingPresetLibrary.first()
        val created = mutableListOf<String>()
        val assets = mutableListOf<File>()
        val source = File(context.cacheDir, "r1b-source-${UUID.randomUUID()}.wav")
        try {
            val wav = impulseWav()
            source.writeBytes(wav)
            store.setDspConvEnabled(false)
            store.setDspConvIr(source.absolutePath, "Portable impulse.wav")
            val saved = store.saveProcessingPreset("R1b ${UUID.randomUUID()}").getOrThrow()
            created += saved.id
            assets += File(saved.audio.dspConvIrPath)
            val output = ByteArrayOutputStream()
            store.exportProcessingPreset(saved.id, output).getOrThrow()
            store.setDspConvIr(original.dspConvIrPath, original.dspConvIrName)
            store.setDspConvEnabled(original.dspConvEnabled)
            val processingBeforeImport = store.processingSnapshot.first()
            val sessionsBeforeImport = store.savedSessions.first()
            val uiBeforeImport = store.uiPrefs.first()
            val alarmsBeforeImport = store.alarmPrefs.first()
            store.deleteProcessingPreset(saved.id).getOrThrow()
            assertTrue(source.delete())
            assertTrue(assets.single().delete())
            val imported = store.importProcessingPreset(ByteArrayInputStream(output.toByteArray())).getOrThrow()
            created += imported.id
            assets += File(imported.audio.dspConvIrPath)
            assertNotEquals(saved.id, imported.id)
            assertEquals(saved.name, imported.name)
            assertNotEquals(saved.audio.dspConvIrPath, imported.audio.dspConvIrPath)
            assertEquals(saved.audio.copy(dspConvIrPath = imported.audio.dspConvIrPath), imported.audio)
            assertEquals(saved.playback, imported.playback)
            assertArrayEquals(wav, File(imported.audio.dspConvIrPath).readBytes())
            assertEquals(saved.irSha256, ProcessingPresetBundle.sha256(File(imported.audio.dspConvIrPath)))
            assertEquals(processingBeforeImport, store.processingSnapshot.first())
            assertEquals(sessionsBeforeImport, store.savedSessions.first())
            assertEquals(uiBeforeImport, store.uiPrefs.first())
            assertEquals(alarmsBeforeImport, store.alarmPrefs.first())
            val second = store.importProcessingPreset(ByteArrayInputStream(output.toByteArray())).getOrThrow()
            created += second.id
            assets += File(second.audio.dspConvIrPath)
            assertNotEquals(imported.id, second.id)
            assertEquals(imported.name, second.name)
        } finally {
            store.setDspConvIr(original.dspConvIrPath, original.dspConvIrName)
            store.setDspConvEnabled(original.dspConvEnabled)
            created.forEach { store.deleteProcessingPreset(it) }
            source.delete()
            assets.filter { file -> file.absolutePath != original.dspConvIrPath &&
                originalLibrary.presets.none { it.audio.dspConvIrPath == file.absolutePath } }.forEach { it.delete() }
        }
        assertEquals(originalLibrary, store.processingPresetLibrary.first())
    }

    @Test fun invalidDependenciesAndTraversalNeverChangeStoredPreferences() = runBlocking {
        val original = store.audioPrefs.first()
        val created = mutableListOf<String>()
        val source = File(context.cacheDir, "r1b-invalid-${UUID.randomUUID()}.wav")
        var asset: File? = null
        try {
            source.writeBytes(impulseWav())
            store.setDspConvEnabled(false)
            store.setDspConvIr(source.absolutePath, "Fixture.wav")
            val saved = store.saveProcessingPreset("R1b rejected ${UUID.randomUUID()}").getOrThrow()
            created += saved.id
            asset = File(saved.audio.dspConvIrPath)
            val output = ByteArrayOutputStream()
            store.exportProcessingPreset(saved.id, output).getOrThrow()
            store.setDspConvIr(original.dspConvIrPath, original.dspConvIrName)
            store.setDspConvEnabled(original.dspConvEnabled)
            val before = store.exportPrefs()
            val filesBefore = File(context.filesDir, "processing-presets").list()?.toSet().orEmpty()
            val entries = mutableMapOf<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries[entry.name] = zip.readBytes()
                }
            }
            val corrupt = entries.toMutableMap().apply { put("ir.wav", byteArrayOf(1, 2, 3)) }
            val traversal = entries.toMutableMap().apply { put("../escape.wav", byteArrayOf(1)) }
            val missing = entries.toMutableMap().apply { remove("ir.wav") }
            listOf(corrupt, traversal, missing).forEach { invalid ->
                val bytes = ByteArrayOutputStream()
                ZipOutputStream(bytes).use { zip -> invalid.forEach { (name, contents) ->
                    zip.putNextEntry(ZipEntry(name)); zip.write(contents); zip.closeEntry()
                } }
                assertTrue(store.importProcessingPreset(ByteArrayInputStream(bytes.toByteArray())).isFailure)
                assertEquals(before, store.exportPrefs())
                assertEquals(filesBefore, File(context.filesDir, "processing-presets").list()?.toSet().orEmpty())
            }
            assertTrue(store.exportProcessingPreset(UUID.randomUUID().toString(), ByteArrayOutputStream()).isFailure)
            assertEquals(before, store.exportPrefs())
        } finally {
            store.setDspConvIr(original.dspConvIrPath, original.dspConvIrName)
            store.setDspConvEnabled(original.dspConvEnabled)
            created.forEach { store.deleteProcessingPreset(it) }
            source.delete()
            asset?.delete()
        }
    }

    private fun impulseWav(): ByteArray = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(40); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(1); putShort(2); putInt(48_000); putInt(192_000); putShort(4); putShort(16)
        put("data".toByteArray()); putInt(4); putShort(1000); putShort(1000)
    }.array()
}
