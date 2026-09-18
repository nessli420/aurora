package com.aurora.music.data

import com.aurora.music.data.ir.ImpulseLibraryCodec
import com.aurora.music.data.ir.ImpulseLibraryEntry
import com.aurora.music.data.ir.ImpulseMetadata
import com.aurora.music.data.ir.ImpulsePreparation
import com.aurora.music.data.ir.ImpulsePreparedAsset
import com.aurora.music.playback.engine.SamplePrecision
import com.google.gson.Gson
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class IrLibraryBackupTest {
    private val directory = Files.createTempDirectory("aurora-ir-backup-").toFile()
    private var sequence = 0

    @After fun clean() { directory.deleteRecursively() }

    @Test fun unselectedSourcesAndPreparedCopiesSurviveArchiveAndRemapping() {
        val entries = listOf(impulse(1000, prepared = true), impulse(2000))
        val bytes = export(backup(entries))
        val archive = unzip(bytes)
        assertEquals(4, archive.size)
        assertFalse(archive.getValue("backup.json").toString(Charsets.UTF_8).contains(directory.absolutePath))
        BackupArchive.read(ByteArrayInputStream(bytes), directory).use { imported ->
            assertEquals(3, imported.assets.size)
            val portable = library(imported.backup)
            assertEquals(entries.map { it.id }, portable.map { it.id })
            assertEquals("aurora-ir:${entries[0].sourceSha256}", portable[0].sourcePath)
            assertEquals("aurora-ir:${entries[0].prepared!!.sha256}", portable[0].prepared!!.path)
            val restored = BackupArchive.remap(imported.backup) { reference, expected ->
                val hash = BackupArchive.assetHash(reference)
                assertEquals(hash, expected)
                imported.assets.getValue(hash).absolutePath to hash
            }
            library(restored).zip(entries).forEach { (actual, original) ->
                assertEquals(original.sourceMetadata, actual.sourceMetadata)
                assertArrayEquals(File(original.sourcePath).readBytes(), File(actual.sourcePath).readBytes())
                original.prepared?.let {
                    assertEquals(it.metadata, actual.prepared!!.metadata)
                    assertEquals(it.preparation, actual.prepared.preparation)
                    assertArrayEquals(File(it.path).readBytes(), File(actual.prepared.path).readBytes())
                }
            }
        }
    }

    @Test fun activeAndPresetReferencesShareThePreparedLibraryAsset() {
        val entry = impulse(4000, prepared = true)
        val prepared = entry.prepared!!
        val preset = preset(File(prepared.path), prepared.sha256)
        val snapshot = backup(listOf(entry)).let { it.copy(prefs = it.prefs.copy(strings = it.prefs.strings + mapOf(
            BackupArchive.IR_PATH_KEY to prepared.path,
            BackupArchive.PRESETS_KEY to ProcessingPresetCodec.encode(listOf(preset))))) }
        BackupArchive.read(ByteArrayInputStream(export(snapshot)), directory).use { imported ->
            assertEquals(2, imported.assets.size)
            assertEquals("aurora-ir:${prepared.sha256}", imported.backup.prefs.strings[BackupArchive.IR_PATH_KEY])
            assertEquals("aurora-ir:${prepared.sha256}", ProcessingPresetCodec.decode(
                imported.backup.prefs.strings[BackupArchive.PRESETS_KEY]).presets.single().audio.dspConvIrPath)
        }
    }

    @Test fun missingChangedAndMisdescribedAssetsFailExport() {
        val entry = impulse(6000, prepared = true)
        val invalid = listOf(
            entry.copy(sourcePath = File(directory, "missing.wav").absolutePath),
            entry.copy(sourceSha256 = "a".repeat(64)),
            entry.copy(sourceMetadata = entry.sourceMetadata.copy(peak = 0.125)),
            entry.copy(prepared = entry.prepared!!.copy(sha256 = "b".repeat(64))),
            entry.copy(prepared = entry.prepared.copy(metadata = entry.prepared.metadata.copy(peak = 0.25))),
        )
        invalid.forEach { assertTrue(runCatching { export(backup(listOf(it))) }.isFailure) }
    }

    @Test fun alteredSourceOrPreparedMetadataFailsBeforeImportReturns() {
        val entry = impulse(7000, prepared = true)
        val bytes = export(backup(listOf(entry)))
        val archive = unzip(bytes)
        val portable = BackupArchive.decodeJson(archive.getValue("backup.json").toString(Charsets.UTF_8))
        val saved = library(portable).single()
        val invalid = listOf(
            saved.copy(sourceMetadata = saved.sourceMetadata.copy(peak = 0.125)),
            saved.copy(prepared = saved.prepared!!.copy(metadata = saved.prepared.metadata.copy(peak = 0.25))),
        )
        invalid.forEach { forged ->
            val manifest = portable.copy(prefs = portable.prefs.copy(strings = portable.prefs.strings +
                (ImpulseLibraryCodec.PREFERENCE_KEY to ImpulseLibraryCodec.encodeLibrary(listOf(forged)))))
            assertRejectedAndClean(zip(archive + ("backup.json" to Gson().toJson(manifest).toByteArray())))
        }
    }

    @Test fun malformedLibraryFailsJsonValidationAndExport() {
        for (invalid in listOf("{broken", "{}", "null", "{\"schemaVersion\":1,\"entries\":[null]}")) {
            val snapshot = AuroraBackup(prefs = PrefsBackup(strings = mapOf(ImpulseLibraryCodec.PREFERENCE_KEY to invalid)))
            assertTrue(runCatching { BackupArchive.decodeJson(Gson().toJson(snapshot)) }.isFailure)
            assertTrue(runCatching { export(snapshot) }.isFailure)
        }
    }

    @Test fun missingPreparedDependencyRejectsAndCleansStaging() {
        val entry = impulse(8000, prepared = true)
        val archive = unzip(export(backup(listOf(entry))))
        assertRejectedAndClean(zip(archive - "assets/${entry.prepared!!.sha256}.wav"))
    }

    @Test fun backupSupportsTheFullLibraryAlongsideOneHundredPresets() {
        val entries = List(32) { impulse(1000 + it, prepared = true) }
        val presets = List(100) {
            val file = wav(2000 + it, floating = false)
            preset(file, sha256(file))
        }
        val active = wav(4000, floating = false)
        val snapshot = backup(entries).let { it.copy(prefs = it.prefs.copy(strings = it.prefs.strings + mapOf(
            BackupArchive.IR_PATH_KEY to active.absolutePath,
            BackupArchive.PRESETS_KEY to ProcessingPresetCodec.encode(presets)))) }
        val bytes = export(snapshot)
        assertEquals(166, unzip(bytes).size)
        BackupArchive.read(ByteArrayInputStream(bytes), directory).use { imported ->
            assertEquals(165, imported.assets.size)
            assertEquals(32, library(imported.backup).size)
            assertEquals(100, ProcessingPresetCodec.decode(imported.backup.prefs.strings[BackupArchive.PRESETS_KEY]).presets.size)
        }
    }

    @Test fun identicalSourceAndPreparedContentCanShareAnArchivedAsset() {
        val file = wav(9000, floating = true)
        val second = File(directory, "identical-copy.wav").also { file.copyTo(it) }
        val metadata = metadata(9000, floating = true)
        val hash = sha256(file)
        val entry = ImpulseLibraryEntry(UUID.randomUUID().toString(), "Shared content", file.name,
            file.absolutePath, hash, metadata, 1, ImpulsePreparedAsset(second.absolutePath, hash, metadata, ImpulsePreparation(0, 4)))
        BackupArchive.read(ByteArrayInputStream(export(backup(listOf(entry)))), directory).use { imported ->
            assertEquals(1, imported.assets.size)
            val resolved = BackupArchive.remap(imported.backup) { reference, expected ->
                imported.assets.getValue(BackupArchive.assetHash(reference)).absolutePath to expected
            }
            val restored = library(resolved).single()
            assertEquals(restored.sourcePath, restored.prepared!!.path)
        }
    }

    private fun impulse(amplitude: Int, prepared: Boolean = false): ImpulseLibraryEntry {
        val source = wav(amplitude, floating = false)
        val copy = if (prepared) wav(amplitude, floating = true) else null
        return ImpulseLibraryEntry(UUID.randomUUID().toString(), "Impulse $amplitude", source.name,
            source.absolutePath, sha256(source), metadata(amplitude, floating = false), 1,
            copy?.let { ImpulsePreparedAsset(it.absolutePath, sha256(it), metadata(amplitude, floating = true), ImpulsePreparation(0, 4)) })
    }

    private fun metadata(amplitude: Int, floating: Boolean) = ImpulseMetadata(48_000, 1, 4,
        if (floating) SamplePrecision.FLOAT_32 else SamplePrecision.PCM_SIGNED_16,
        if (floating) 24 else 16, amplitude / 32768.0)

    private fun wav(amplitude: Int, floating: Boolean): File {
        val bytesPerSample = if (floating) 4 else 2
        val dataBytes = 4 * bytesPerSample
        val bytes = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVEfmt ".toByteArray()).putInt(16)
        bytes.putShort((if (floating) 3 else 1).toShort()).putShort(1).putInt(48_000).putInt(48_000 * bytesPerSample)
        bytes.putShort(bytesPerSample.toShort()).putShort((8 * bytesPerSample).toShort()).put("data".toByteArray()).putInt(dataBytes)
        listOf(amplitude, 0, -amplitude, 0).forEach { if (floating) bytes.putFloat(it / 32768f) else bytes.putShort(it.toShort()) }
        return File(directory, "impulse-${sequence++}.wav").apply { writeBytes(bytes.array()) }
    }

    private fun preset(file: File, hash: String) = ProcessingPreset(UUID.randomUUID().toString(), file.name, createdAtMs = 1,
        audio = AudioPrefs(dspConvIrPath = file.absolutePath), playback = ProcessingPlaybackPrefs(), irSha256 = hash)

    private fun backup(entries: List<ImpulseLibraryEntry>) = AuroraBackup(prefs = PrefsBackup(strings = mapOf(
        ImpulseLibraryCodec.PREFERENCE_KEY to ImpulseLibraryCodec.encodeLibrary(entries))))

    private fun library(backup: AuroraBackup) = ImpulseLibraryCodec.decodeLibrary(backup.prefs.strings[ImpulseLibraryCodec.PREFERENCE_KEY]).getOrThrow()
    private fun export(backup: AuroraBackup) = ByteArrayOutputStream().also { BackupArchive.write(backup, it) }.toByteArray()
    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun assertRejectedAndClean(bytes: ByteArray) {
        fun staging() = directory.listFiles().orEmpty().filter { it.name.startsWith("backup-import-") }.map { it.name }.toSet()
        val before = staging()
        assertTrue(runCatching { BackupArchive.read(ByteArrayInputStream(bytes), directory).close() }.isFailure)
        assertEquals(before, staging())
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> = linkedMapOf<String, ByteArray>().also { result ->
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; result[entry.name] = zip.readBytes() }
        }
    }

    private fun zip(entries: Map<String, ByteArray>) = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
    }.toByteArray()
}
