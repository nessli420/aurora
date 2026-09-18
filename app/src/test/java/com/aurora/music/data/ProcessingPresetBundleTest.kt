package com.aurora.music.data

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ProcessingPresetBundleTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun fixture(ir: File? = null) = ProcessingPreset(
        id = UUID.randomUUID().toString(), name = "Portable room", createdAtMs = 1L,
        audio = AudioPrefs(dspMode = DspMode.CUSTOM, dspPreampDb = -4.25f,
            dspParametric = listOf(ParamBand(90f, -2f, 0.8f, BandType.LOW_SHELF)),
            dspConvEnabled = ir != null, dspConvIrPath = ir?.absolutePath.orEmpty(), dspConvIrName = "Room.wav"),
        playback = ProcessingPlaybackPrefs(monoAudio = true, crossfadeSec = 4),
        activeEqProfile = "Room correction", irSha256 = ir?.let(ProcessingPresetBundle::sha256).orEmpty(),
    )

    private fun export(preset: ProcessingPreset, ir: File? = null): ByteArray = ByteArrayOutputStream().also {
        ProcessingPresetBundle.write(preset, ir, it)
    }.toByteArray()

    private fun wav(sample: Short = 1000): ByteArray = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(40); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(1); putShort(2); putInt(48_000); putInt(192_000); putShort(4); putShort(16)
        put("data".toByteArray()); putInt(4); putShort(sample); putShort(sample)
    }.array()

    private fun entries(bytes: ByteArray): LinkedHashMap<String, ByteArray> = linkedMapOf<String, ByteArray>().also { result ->
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result[entry.name] = zip.readBytes()
            }
        }
    }

    private fun archive(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
    }.toByteArray()

    private fun changedManifest(change: (com.google.gson.JsonObject) -> Unit): ByteArray {
        val entries = entries(export(fixture()))
        val manifest = JsonParser.parseString(entries.getValue("manifest.json").toString(Charsets.UTF_8)).asJsonObject
        change(manifest)
        entries["manifest.json"] = manifest.toString().toByteArray()
        return archive(entries)
    }

    private fun rejects(bytes: ByteArray) {
        val root = temporary.newFolder()
        assertTrue(runCatching { ProcessingPresetBundle.read(ByteArrayInputStream(bytes), root).use {} }.isFailure)
        assertEquals("A rejected archive must leave no staging files", 0, root.listFiles()!!.size)
    }

    @Test fun completeSnapshotAndIrRoundTripWithoutPrivatePathsOrOriginalIdentity() {
        val ir = temporary.newFile().apply { writeBytes(wav()) }
        val original = fixture(ir)
        val bytes = export(original, ir)
        val metadata = entries(bytes).getValue("manifest.json").toString(Charsets.UTF_8)
        assertFalse(metadata.contains(ir.absolutePath))
        assertFalse(metadata.contains(original.id))
        ProcessingPresetBundle.read(ByteArrayInputStream(bytes), temporary.root).use { read ->
            assertEquals(original.name, read.preset.name)
            assertNotEquals(original.id, read.preset.id)
            assertEquals(original.audio.copy(dspConvIrPath = ""), read.preset.audio)
            assertEquals(original.playback, read.preset.playback)
            assertEquals(original.rack, read.preset.rack)
            assertEquals(original.activeEqProfile, read.preset.activeEqProfile)
            assertArrayEquals(ir.readBytes(), read.impulseResponse!!.readBytes())
        }
    }

    @Test fun versionOneBundlesMigrateTheirSnapshotWithoutEnablingAGraph() {
        val old = fixture()
        val entries = entries(export(old))
        val manifest = JsonParser.parseString(entries.getValue("manifest.json").toString(Charsets.UTF_8)).asJsonObject
        manifest.addProperty("version", 1)
        manifest.getAsJsonObject("preset").apply { addProperty("schemaVersion", 1); remove("rack") }
        entries["manifest.json"] = manifest.toString().toByteArray()
        ProcessingPresetBundle.read(ByteArrayInputStream(archive(entries)), temporary.root).use { read ->
            assertEquals(3, read.preset.schemaVersion)
            assertEquals(old.audio.copy(dspConvIrPath = ""), read.preset.audio)
            assertEquals(ProcessingRack.legacy(old.audio, old.playback.monoAudio), read.preset.rack)
            assertFalse(read.preset.rack.enabled)
        }
    }

    @Test fun graphConvolutionRequiresItsSharedAssetEvenWhenLegacyConvolutionIsOff() {
        val node = ProcessingRackNode(UUID.randomUUID().toString(), "Room", RackNodeKind.CONVOLUTION,
            wet = 0.75f, audio = AudioPrefs(dspConvMakeupDb = -3f))
        val graph = ProcessingRack(enabled = true, name = "Graph room", nodes = listOf(node))
        val missing = fixture().copy(audio = AudioPrefs(dspConvEnabled = false), rack = graph)
        assertTrue(runCatching { export(missing) }.isFailure)
        val ir = temporary.newFile().apply { writeBytes(wav()) }
        val ready = missing.copy(audio = missing.audio.copy(dspConvIrPath = ir.absolutePath),
            irSha256 = ProcessingPresetBundle.sha256(ir))
        ProcessingPresetBundle.read(ByteArrayInputStream(export(ready, ir)), temporary.root).use { read ->
            assertEquals(graph, read.preset.rack)
            assertFalse(read.preset.audio.dspConvEnabled)
            assertTrue(read.preset.requiresImpulseResponse())
            assertArrayEquals(ir.readBytes(), requireNotNull(read.impulseResponse).readBytes())
        }
    }

    @Test fun aMalformedRackRejectsTheWholePortablePreset() {
        rejects(changedManifest { root ->
            val nodes = root.getAsJsonObject("preset").getAsJsonObject("rack").getAsJsonArray("nodes")
            nodes[0].asJsonObject.addProperty("wet", 1.5)
        })
        rejects(changedManifest { root -> root.getAsJsonObject("preset").getAsJsonObject("rack").addProperty("schemaVersion", 9) })
    }

    @Test fun presetWithoutIrUsesOnlyManifestAndCallerRetainsStreamOwnership() {
        var closed = false
        val output = object : ByteArrayOutputStream() { override fun close() { closed = true } }
        ProcessingPresetBundle.write(fixture(), null, output)
        assertFalse(closed)
        assertEquals(setOf("manifest.json"), entries(output.toByteArray()).keys)
        val input = object : ByteArrayInputStream(output.toByteArray()) { override fun close() { closed = true } }
        ProcessingPresetBundle.read(input, temporary.root).use { assertNull(it.impulseResponse) }
        assertFalse(closed)
    }

    @Test fun irChecksumMismatchIsRejectedAndStagingIsRemoved() {
        val ir = temporary.newFile().apply { writeBytes(wav()) }
        val entries = entries(export(fixture(ir), ir))
        entries["ir.wav"] = byteArrayOf(4, 5, 6)
        rejects(archive(entries))
    }

    @Test fun missingOrUnexpectedIrRejectsTheWholeImport() {
        val ir = temporary.newFile().apply { writeBytes(wav()) }
        rejects(archive(entries(export(fixture(ir), ir)).apply { remove("ir.wav") }))
        rejects(archive(entries(export(fixture())).apply { put("ir.wav", byteArrayOf(1)) }))
        rejects(changedManifest { it.getAsJsonObject("preset").getAsJsonObject("audio").addProperty("dspConvEnabled", true) })
    }

    @Test fun traversalAbsolutePathsAndExtraEntriesAreRejectedWithoutExtraction() {
        listOf("../outside.wav", "/tmp/outside.wav", "C:\\outside.wav", "assets/ir.wav", "folder/", "other.json").forEach { path ->
            rejects(archive(entries(export(fixture())).apply { put(path, byteArrayOf(1)) }))
        }
        rejects(changedManifest { it.getAsJsonObject("preset").getAsJsonObject("audio").addProperty("dspConvIrPath", "/private/ir.wav") })
    }

    @Test fun duplicateZipEntriesAreRejected() {
        val data = archive(linkedMapOf("manifest.json" to entries(export(fixture())).getValue("manifest.json"), "manifest.jsox" to byteArrayOf(1)))
        // ZipOutputStream forbids duplicates, so turn the equally sized second name into a duplicate
        // in both the local file header and central directory.
        val from = "manifest.jsox".toByteArray()
        val to = "manifest.json".toByteArray()
        for (i in 0..data.size - from.size) {
            if (from.indices.all { data[i + it] == from[it] }) to.copyInto(data, i)
        }
        rejects(data)
    }

    @Test fun unsupportedVersionsIncompleteSettingsAndExtraFieldsAreRejected() {
        rejects(changedManifest { it.addProperty("version", 4) })
        rejects(changedManifest { it.addProperty("version", 1.5) })
        rejects(changedManifest { it.addProperty("format", "something-else") })
        rejects(changedManifest { it.addProperty("extra", "unsupported") })
        rejects(changedManifest { it.getAsJsonObject("preset").addProperty("schemaVersion", 4) })
        rejects(changedManifest { it.getAsJsonObject("preset").getAsJsonObject("audio").remove("dspMode") })
        rejects(changedManifest { it.getAsJsonObject("preset").addProperty("id", UUID.randomUUID().toString()) })
    }

    @Test fun metadataExpansionAndInvalidUtf8AreRejected() {
        rejects(archive(mapOf("manifest.json" to ByteArray(512 * 1024 + 1) { ' '.code.toByte() })))
        rejects(archive(mapOf("manifest.json" to byteArrayOf(0xc3.toByte(), 0x28))))
    }

    @Test fun ambiguousAndDeepJsonAreRejectedBeforeSettingsConstruction() {
        val valid = entries(export(fixture())).getValue("manifest.json").toString(Charsets.UTF_8)
        val version = "\"version\":${ProcessingPresetBundle.VERSION}"
        rejects(archive(mapOf("manifest.json" to valid.replace(version, "$version,$version").toByteArray())))
        rejects(archive(mapOf("manifest.json" to ("[".repeat(1000) + "0" + "]".repeat(1000)).toByteArray())))
        rejects(archive(mapOf("manifest.json" to (valid + "{}").toByteArray())))
        rejects(archive(mapOf("manifest.json" to ("/* comment */" + valid).toByteArray())))
    }

    @Test fun oversizedAdvertisedIrSizeIsRejectedBeforeExtraction() {
        val ir = temporary.newFile().apply { writeBytes(wav()) }
        val data = export(fixture(ir), ir)
        for (i in 0..data.size - 46) {
            if (data[i] == 0x50.toByte() && data[i + 1] == 0x4b.toByte() && data[i + 2] == 1.toByte() && data[i + 3] == 2.toByte()) {
                val nameSize = (data[i + 28].toInt() and 255) or ((data[i + 29].toInt() and 255) shl 8)
                if (i + 46 + nameSize <= data.size && String(data, i + 46, nameSize) == "ir.wav") {
                    val size = ProcessingPresetBundle.MAX_IR_BYTES + 1
                    for (byte in 0..3) data[i + 24 + byte] = (size shr (8 * byte)).toByte()
                }
            }
        }
        rejects(data)
    }

    @Test fun truncatedAndNonZipFilesAreRejected() {
        val valid = export(fixture())
        rejects(valid.copyOf(valid.size - 22))
        rejects("not a zip".toByteArray())
        rejects(archive(emptyMap()))
    }

    @Test fun corruptOrUnavailableSourceIrCannotBeExported() {
        val ir = temporary.newFile().apply { writeBytes(wav()) }
        val preset = fixture(ir)
        ir.writeText("changed")
        assertTrue(runCatching { export(preset, ir) }.isFailure)
        assertTrue(runCatching { export(preset) }.isFailure)
        assertTrue(runCatching { export(fixture().copy(audio = AudioPrefs(dspConvEnabled = true))) }.isFailure)
    }

    @Test fun malformedWavCannotBeExportedOrImportedEvenWithMatchingChecksum() {
        val ir = temporary.newFile().apply { writeBytes(wav()) }
        val entries = entries(export(fixture(ir), ir))
        val malformed = listOf(
            wav().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(40, Int.MAX_VALUE) },
            wav().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(22, 0) },
            wav().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(32, 1) },
            wav().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(20, 6) },
            wav().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(24, 0) },
            wav().copyOf(45),
        )
        malformed.forEach { bytes ->
            ir.writeBytes(bytes)
            assertTrue(runCatching { export(fixture(ir), ir) }.isFailure)
            val changed = entries.toMutableMap()
            val json = JsonParser.parseString(changed.getValue("manifest.json").toString(Charsets.UTF_8)).asJsonObject
            json.getAsJsonObject("preset").addProperty("irSha256", ProcessingPresetBundle.sha256(ir))
            changed["manifest.json"] = json.toString().toByteArray()
            changed["ir.wav"] = bytes
            rejects(archive(changed))
        }
    }

    @Test fun floatWavRejectsNonFiniteSamples() {
        val bytes = wav().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(20, 3); putShort(22, 1); putShort(34, 32); putFloat(44, 0.5f)
        } }
        val ir = temporary.newFile().apply { writeBytes(bytes) }
        export(fixture(ir), ir)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putFloat(44, Float.NaN)
        ir.writeBytes(bytes)
        assertTrue(runCatching { export(fixture(ir), ir) }.isFailure)
    }
}
