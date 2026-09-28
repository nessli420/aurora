package com.aurora.music.data

import com.google.gson.Gson
import com.aurora.music.data.tuning.MeasurementTextImporter
import com.aurora.music.data.tuning.TuningProjectCodec
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

class BackupArchiveTest {
    private val directory = Files.createTempDirectory("aurora-backup-test-").toFile()
    @After fun clean() { directory.deleteRecursively() }

    @Test fun activeAndRuleRecoveryImpulseAssetsSurvivePortableBackup() {
        val file = impulse()
        val entry = com.aurora.music.data.ir.ImpulseLibraryFiles.importOriginal(file, "Matrix", "matrix.wav", 1).getOrThrow()
        val rack = ProcessingRack(enabled = true, nodes = listOf(
            ProcessingRackNode(UUID.randomUUID().toString(), "FIR", RackNodeKind.CONVOLUTION, impulseId = entry.id)))
        val preset = ProcessingPreset(UUID.randomUUID().toString(), "Frozen", createdAtMs = 1,
            audio = AudioPrefs(), playback = ProcessingPlaybackPrefs(), rack = rack, rackImpulseAssets = listOf(entry))
        val session = com.aurora.music.data.rules.PresetRuleSession(preset, preset,
            UUID.randomUUID().toString(), preset.id)
        val backup = AuroraBackup(prefs = PrefsBackup(strings = mapOf(
            ProcessingRackCodec.PREFERENCE_KEY to ProcessingRackCodec.encode(rack),
            BackupArchive.ACTIVE_RACK_IR_KEY to com.aurora.music.data.ir.ImpulseLibraryCodec.encodeLibrary(listOf(entry)),
            com.aurora.music.data.rules.PresetRuleSessionCodec.PREFERENCE_KEY to com.aurora.music.data.rules.PresetRuleSessionCodec.encode(session))))
        val bytes = export(backup)
        assertEquals(2, unzip(bytes).size)
        assertFalse(unzip(bytes).getValue("backup.json").toString(Charsets.UTF_8).contains(file.absolutePath))
        BackupArchive.read(ByteArrayInputStream(bytes), directory).use { imported ->
            val restored = BackupArchive.remap(imported.backup) { reference, expected ->
                imported.assets.getValue(BackupArchive.assetHash(reference)).absolutePath to expected
            }
            val recovery = com.aurora.music.data.rules.PresetRuleSessionCodec.decode(
                restored.prefs.strings[com.aurora.music.data.rules.PresetRuleSessionCodec.PREFERENCE_KEY]).getOrThrow()!!
            ProcessingPresetAssets.validateFiles(recovery.baseline.rackImpulseAssets)
            ProcessingPresetAssets.validateFiles(recovery.applied.rackImpulseAssets)
            assertEquals(1, imported.assets.size)
        }
    }

    @Test fun archiveKeepsMeasurementProjectsAndValidatesThemBeforeRestore() {
        val measurement = MeasurementTextImporter.parse("# Frequency (Hz), SPL (dB), Phase (degrees)\n20, 1, 30\n20000, -2, -400", "Raw phase").getOrThrow()
        val project = TuningProjectCodec.create("Portable measurements").copy(measurementLeft = measurement)
        val library = TuningProjectCodec.encodeLibrary(listOf(project))
        val backup = AuroraBackup(prefs = PrefsBackup(strings = mapOf(TuningProjectCodec.PREFERENCE_KEY to library)))
        val bytes = export(backup)
        BackupArchive.read(ByteArrayInputStream(bytes), directory).use { imported ->
            assertEquals(listOf(project), TuningProjectCodec.decodeLibrary(imported.backup.prefs.strings[TuningProjectCodec.PREFERENCE_KEY]).getOrThrow())
        }
        val entries = unzip(bytes)
        val corrupt = Gson().toJson(backup.copy(version = 2, prefs = backup.prefs.copy(strings = mapOf(TuningProjectCodec.PREFERENCE_KEY to "{broken")))).toByteArray()
        assertFails { BackupArchive.read(ByteArrayInputStream(zip(entries + ("backup.json" to corrupt))), directory) }
    }

    @Test fun archiveIncludesSharedImpulseOnceAndRemapsEveryReference() {
        val file = impulse()
        val hash = hash(file.readBytes())
        val rack = ProcessingRack.legacy(AudioPrefs(dspMode = DspMode.CUSTOM, dspConvEnabled = true)).copy(enabled = true)
        val preset = ProcessingPreset(UUID.randomUUID().toString(), "Saved rack", createdAtMs = 1,
            audio = AudioPrefs(dspConvEnabled = true, dspConvIrPath = file.absolutePath),
            playback = ProcessingPlaybackPrefs(), irSha256 = hash, rack = rack)
        val backup = AuroraBackup(prefs = PrefsBackup(strings = mapOf(
            BackupArchive.IR_PATH_KEY to file.absolutePath,
            BackupArchive.PRESETS_KEY to ProcessingPresetCodec.encode(listOf(preset)),
            ProcessingRackCodec.PREFERENCE_KEY to ProcessingRackCodec.encode(rack))))
        val bytes = export(backup)
        val entries = unzip(bytes)
        assertEquals(setOf("backup.json", "assets/$hash.wav"), entries.keys)
        assertFalse(entries.getValue("backup.json").toString(Charsets.UTF_8).contains(file.absolutePath))
        BackupArchive.read(ByteArrayInputStream(bytes), directory).use { imported ->
            assertEquals(1, imported.assets.size)
            assertArrayEquals(file.readBytes(), imported.assets.getValue(hash).readBytes())
            val restored = BackupArchive.remap(imported.backup) { token, expected ->
                assertEquals(hash, BackupArchive.assetHash(token))
                assertTrue(expected.isEmpty() || expected == hash)
                "/restored/ir.wav" to hash
            }
            assertEquals("/restored/ir.wav", restored.prefs.strings[BackupArchive.IR_PATH_KEY])
            val saved = ProcessingPresetCodec.decode(restored.prefs.strings[BackupArchive.PRESETS_KEY]).presets.single()
            assertEquals("/restored/ir.wav", saved.audio.dspConvIrPath)
            assertEquals(rack, saved.rack)
        }
    }

    @Test fun missingOrChangedDependenciesRejectTheWholeArchive() {
        val file = impulse()
        val bytes = export(AuroraBackup(prefs = PrefsBackup(strings = mapOf(BackupArchive.IR_PATH_KEY to file.absolutePath))))
        val entries = unzip(bytes)
        val asset = entries.keys.single { it.startsWith("assets/") }
        assertFails { BackupArchive.read(ByteArrayInputStream(zip(entries - asset)), directory) }
        val corrupt = entries.getValue(asset).copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertFails { BackupArchive.read(ByteArrayInputStream(zip(entries + (asset to corrupt))), directory) }
    }

    @Test fun traversalAndUnreferencedFilesAreRejected() {
        val entries = unzip(export(AuroraBackup()))
        assertFails { BackupArchive.read(ByteArrayInputStream(zip(entries + ("../outside.wav" to byteArrayOf(1)))), directory) }
        assertFails { BackupArchive.read(ByteArrayInputStream(zip(entries + ("assets/${"a".repeat(64)}.wav" to impulse().readBytes()))), directory) }
        assertFalse(File(directory.parentFile, "outside.wav").exists())
    }

    @Test fun duplicateZipEntryRejected() {
        // Two same-length names are patched after creating a legal ZIP, including its directory.
        val bytes = zip(mapOf("backup.json" to Gson().toJson(AuroraBackup(version = 2)).toByteArray(),
            "backuq.json" to byteArrayOf(1)))
        val needle = "backuq.json".toByteArray()
        for (i in 0..bytes.size - needle.size) if (needle.indices.all { bytes[i + it] == needle[it] }) bytes[i + 5] = 'p'.code.toByte()
        assertFails { BackupArchive.read(ByteArrayInputStream(bytes), directory) }
    }

    @Test fun centralDirectoryCrcMustMatchBothManifestAndImpulseBytes() {
        val file = impulse()
        val bytes = export(AuroraBackup(prefs = PrefsBackup(strings = mapOf(BackupArchive.IR_PATH_KEY to file.absolutePath))))
        for (name in unzip(bytes).keys) {
            // Payload and SHA-256 are unchanged: ZipFile alone does not check this central CRC.
            val damaged = changeCentralDirectory(bytes, name, 16) { it xor 1 }
            assertArchiveFailsAndCleansStaging(damaged)
        }
    }

    @Test fun actualEntrySizeMustMatchCentralDirectoryInBothDirections() {
        val file = impulse()
        val bytes = export(AuroraBackup(prefs = PrefsBackup(strings = mapOf(BackupArchive.IR_PATH_KEY to file.absolutePath))))
        for (name in unzip(bytes).keys) for (difference in listOf(-1, 1)) {
            assertArchiveFailsAndCleansStaging(changeCentralDirectory(bytes, name, 24) { it + difference })
        }
    }

    @Test fun activeGlobalOrRackConvolutionRequiresTheSharedReference() {
        val rack = ProcessingRack(enabled = true, nodes = listOf(ProcessingRackNode(
            UUID.randomUUID().toString(), "Room", RackNodeKind.CONVOLUTION)))
        val global = AuroraBackup(prefs = PrefsBackup(booleans = mapOf("dsp_conv_enabled" to true)))
        val graph = AuroraBackup(prefs = PrefsBackup(strings = mapOf(
            ProcessingRackCodec.PREFERENCE_KEY to ProcessingRackCodec.encode(rack))))
        for (backup in listOf(global, graph)) {
            assertFails { export(backup) }
            assertArchiveFailsAndCleansStaging(zip(mapOf("backup.json" to Gson().toJson(backup.copy(version = 2)).toByteArray())))
        }
        // A bypassed or entirely dry node has no dependency until it is enabled.
        for (node in listOf(rack.nodes.single().copy(bypass = true), rack.nodes.single().copy(wet = 0f))) {
            val dry = graph.copy(prefs = PrefsBackup(strings = mapOf(
                ProcessingRackCodec.PREFERENCE_KEY to ProcessingRackCodec.encode(rack.copy(nodes = listOf(node))))))
            BackupArchive.read(ByteArrayInputStream(export(dry)), directory).close()
        }
    }

    @Test fun savedPresetRequiresCompletePathAndChecksumForItsActiveConvolver() {
        val file = impulse()
        val digest = hash(file.readBytes())
        val preset = ProcessingPreset(UUID.randomUUID().toString(), "Room", createdAtMs = 1,
            audio = AudioPrefs(dspConvEnabled = true), playback = ProcessingPlaybackPrefs())
        val graph = ProcessingRack(enabled = true, nodes = listOf(ProcessingRackNode(
            UUID.randomUUID().toString(), "Room", RackNodeKind.CONVOLUTION)))
        val invalid = listOf(
            preset,
            preset.copy(irSha256 = digest),
            preset.copy(audio = preset.audio.copy(dspConvIrPath = file.absolutePath)),
            preset.copy(audio = AudioPrefs(), irSha256 = digest),
            preset.copy(audio = AudioPrefs(), rack = graph),
        )
        for (saved in invalid) {
            val backup = AuroraBackup(prefs = PrefsBackup(strings = mapOf(
                BackupArchive.PRESETS_KEY to ProcessingPresetCodec.encode(listOf(saved)))))
            assertFails { export(backup) }
            val portableSaved = saved.copy(audio = saved.audio.copy(dspConvIrPath =
                if (saved.audio.dspConvIrPath.isEmpty()) "" else "aurora-ir:$digest"))
            val portable = backup.copy(version = 2, prefs = PrefsBackup(strings = mapOf(
                BackupArchive.PRESETS_KEY to ProcessingPresetCodec.encode(listOf(portableSaved)))))
            assertArchiveFailsAndCleansStaging(zip(mapOf("backup.json" to Gson().toJson(portable).toByteArray())))
        }
    }

    @Test fun activeDryRackOwnsConvolutionDespiteDormantGlobalEnableFlag() {
        val convolution = ProcessingRackNode(UUID.randomUUID().toString(), "Room", RackNodeKind.CONVOLUTION)
        for (nodes in listOf(emptyList(), listOf(convolution.copy(bypass = true)), listOf(convolution.copy(wet = 0f)))) {
            val rack = ProcessingRack(enabled = true, nodes = nodes)
            val backup = AuroraBackup(prefs = PrefsBackup(
                strings = mapOf(ProcessingRackCodec.PREFERENCE_KEY to ProcessingRackCodec.encode(rack)),
                booleans = mapOf("dsp_conv_enabled" to true)))
            BackupArchive.read(ByteArrayInputStream(export(backup)), directory).use { imported ->
                assertTrue(imported.assets.isEmpty())
                assertEquals(true, imported.backup.prefs.booleans["dsp_conv_enabled"])
                assertEquals(rack, ProcessingRackCodec.decode(imported.backup.prefs.strings.getValue(
                    ProcessingRackCodec.PREFERENCE_KEY)).getOrThrow())
            }
            val legacy = backup.copy(prefs = backup.prefs.copy(strings = mapOf(
                ProcessingRackCodec.PREFERENCE_KEY to ProcessingRackCodec.encode(rack.copy(enabled = false)))))
            assertFails { export(legacy) }
            assertArchiveFailsAndCleansStaging(zip(mapOf("backup.json" to Gson().toJson(legacy.copy(version = 2)).toByteArray())))
        }
    }

    @Test fun exportLeavesTheCallerOutputOpen() {
        val output = object : ByteArrayOutputStream() {
            var closed = false
            override fun close() { closed = true; super.close() }
        }
        BackupArchive.write(AuroraBackup(), output)
        assertFalse(output.closed)
        BackupArchive.read(ByteArrayInputStream(output.toByteArray()), directory).close()
    }

    @Test fun legacyJsonDefaultsAreExplicitAndMalformedFieldsFailBeforeGson() {
        val parsed = BackupArchive.decodeJson("""{"version":1,"prefs":{"strings":{"theme":"dark"}}}""")
        assertEquals("dark", parsed.prefs.strings["theme"])
        assertTrue(parsed.prefs.floats.isEmpty())
        assertTrue(parsed.playHistory.isEmpty())
        assertFails { BackupArchive.decodeJson("""{"version":1,"prefs":null}""") }
        assertFails { BackupArchive.decodeJson("""{"version":1,"version":2,"prefs":{}}""") }
        assertFails { BackupArchive.decodeJson("""{"version":3,"prefs":{}}""") }
        assertFails { BackupArchive.decodeJson("""{"version":1,"prefs":{"strings":{"x":"a"},"ints":{"x":1}}}""") }
        assertFails { BackupArchive.decodeJson("""{"version":1,"prefs":{"ints":{"x":1.5}}}""") }
        assertFails { BackupArchive.decodeJson("""{"version":1,"prefs":{},"playHistory":[{}]}""") }
    }

    @Test fun invalidRackAndPresetDataCannotEnterBackupRestore() {
        val backup = AuroraBackup(prefs = PrefsBackup(strings = mapOf(ProcessingRackCodec.PREFERENCE_KEY to "{}")))
        assertFails { BackupArchive.decodeJson(Gson().toJson(backup)) }
        val presets = backup.copy(prefs = PrefsBackup(strings = mapOf(BackupArchive.PRESETS_KEY to "[null]")))
        assertFails { BackupArchive.decodeJson(Gson().toJson(presets)) }
    }

    @Test fun numericLexemesAreBoundedBeforeBigDecimalConstruction() {
        fun metadata(number: String) = """{"version":1,"prefs":{"floats":{"value":$number}}}"""
        assertEquals(0f, BackupArchive.decodeJson(metadata("0." + "0".repeat(98))).prefs.floats.getValue("value"))
        assertFails { BackupArchive.decodeJson(metadata("0." + "0".repeat(99))) }
    }

    @Test fun nestedLocalLibraryUsesStrictJsonAndUniquePlaylistIdentifiers() {
        val valid = """{"playlists":[{"id":"local:1","title":"Saved","trackIds":["song"]}],"likedIds":["song"]}"""
        fun parse(local: String) = BackupArchive.decodeJson(Gson().toJson(AuroraBackup(localStore = local)))
        assertEquals(valid, parse(valid).localStore)
        listOf(
            """{"likedIds":[],"likedIds":["song"]}""",
            """{"playlists":[{"id":"a","id":"b"}]}""",
            """{"playlists":[{"id":"a"},{"id":"a"}]}""",
            """{"likedIds":[]} {}""",
            """{/* comment */ "likedIds":[]}""",
            """{likedIds:[]}""",
        ).forEach { assertFails { parse(it) } }
    }

    @Test fun sixtyFourBandRackSurvivesTheAppBackupEnvelope() {
        val bands = List(64) { ParamBand(30f + it * 200f, (it % 3 - 1).toFloat(), 1f, it % 3) }
        val rack = ProcessingRack(enabled = true, name = "64 bands", nodes = listOf(ProcessingRackNode(
            UUID.randomUUID().toString(), "EQ", RackNodeKind.EQ, audio = AudioPrefs(dspParametric = bands))))
        val backup = AuroraBackup(prefs = PrefsBackup(strings = mapOf(ProcessingRackCodec.PREFERENCE_KEY to ProcessingRackCodec.encode(rack))))
        BackupArchive.read(ByteArrayInputStream(export(backup)), directory).use { imported ->
            assertEquals(rack, ProcessingRackCodec.decode(imported.backup.prefs.strings.getValue(ProcessingRackCodec.PREFERENCE_KEY)).getOrThrow())
        }
    }

    private fun export(backup: AuroraBackup) = ByteArrayOutputStream().also { BackupArchive.write(backup, it) }.toByteArray()
    private fun assertFails(action: () -> Unit) { assertTrue("Invalid backup was accepted", runCatching(action).isFailure) }
    private fun assertArchiveFailsAndCleansStaging(bytes: ByteArray) {
        fun staging() = directory.listFiles().orEmpty().filter { it.name.startsWith("backup-import-") }.map { it.name }.toSet()
        val before = staging()
        assertFails { BackupArchive.read(ByteArrayInputStream(bytes), directory).close() }
        assertEquals("Rejected archive left staging files", before, staging())
    }
    private fun changeCentralDirectory(bytes: ByteArray, name: String, fieldOffset: Int, change: (Int) -> Int): ByteArray {
        val result = bytes.copyOf()
        val buffer = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
        for (offset in 0..result.size - 46) {
            if (buffer.getInt(offset) != 0x02014b50) continue
            val nameLength = buffer.getShort(offset + 28).toInt() and 0xffff
            if (offset + 46 + nameLength > result.size) continue
            if (String(result, offset + 46, nameLength, Charsets.UTF_8) != name) continue
            buffer.putInt(offset + fieldOffset, change(buffer.getInt(offset + fieldOffset)))
            return result
        }
        error("No central-directory entry for $name")
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun unzip(bytes: ByteArray): Map<String, ByteArray> = linkedMapOf<String, ByteArray>().also { result ->
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip -> while (true) {
            val entry = zip.nextEntry ?: break
            result[entry.name] = zip.readBytes()
        } }
    }
    private fun zip(entries: Map<String, ByteArray>) = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
    }.toByteArray()

    private fun impulse(): File = File(directory, "ir.wav").apply {
        outputStream().use { output ->
            fun le(value: Int, bytes: Int) { repeat(bytes) { output.write(value ushr (8 * it) and 255) } }
            output.write("RIFF".toByteArray()); le(40, 4)
            output.write("WAVEfmt ".toByteArray()); le(16, 4); le(1, 2); le(2, 2)
            le(48_000, 4); le(192_000, 4); le(4, 2); le(16, 2)
            output.write("data".toByteArray()); le(4, 4); le(16_384, 2); le(8_192, 2)
        }
    }
}
