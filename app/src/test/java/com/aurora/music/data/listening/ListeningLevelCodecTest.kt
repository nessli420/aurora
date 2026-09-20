package com.aurora.music.data.listening

import com.aurora.music.data.AuroraBackup
import com.aurora.music.data.BackupArchive
import com.google.gson.Gson
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Test

class ListeningLevelCodecTest {
    @Test fun localRoundTripPreservesBindingsAndOptInHistory() {
        val state = state()
        assertEquals(state, ListeningLevelCodec.decode(ListeningLevelCodec.encode(state)))
    }

    @Test fun portableExportRemovesBindingsAndHistory() {
        val json = ListeningLevelCodec.encode(state(), portable = true)
        assertFalse(json.contains(ListeningLevelsTest.key))
        assertFalse(json.contains("\"DAC\""))
        assertFalse(json.contains("1700000000000"))
        val decoded = ListeningLevelCodec.decode(json, portable = true)
        assertNull(decoded.profiles.single().routeKey)
        assertEquals("", decoded.profiles.single().routeLabel)
        assertFalse(decoded.historyEnabled)
        assertTrue(decoded.history.isEmpty())
    }

    @Test fun portableImportCannotRestoreHiddenRouteOrHistory() {
        val decoded = ListeningLevelCodec.decode(ListeningLevelCodec.encode(state()), portable = true)
        assertNull(decoded.profiles.single().routeKey)
        assertFalse(decoded.historyEnabled)
        assertTrue(decoded.history.isEmpty())
    }

    @Test fun partialOrNullPersistedFieldsCannotFabricateCalibration() {
        val valid = ListeningLevelCodec.encode(state())
        listOf("{}", "null", "{\"version\":99,\"profiles\":[]}", "{\"version\":1,\"profiles\":[null]}",
            valid.replace("\"fullScaleVrms\":1.0", "\"fullScaleVrms\":null"),
            valid.replace("\"sensitivityUnit\":\"DB_PER_VOLT\"", "\"sensitivityUnit\":\"unknown\""),
            valid.replace("\"impedanceOhms\":32.0", "\"impedanceOhms\":0.0"))
            .forEach { assertTrue(it, runCatching { ListeningLevelCodec.decode(it) }.isFailure) }
    }

    @Test fun duplicateIdsAndOversizedDocumentsAreRejected() {
        val state = state()
        val duplicate = ListeningLevelCodec.encode(state.copy(profiles = state.profiles + state.profiles))
        assertTrue(runCatching { ListeningLevelCodec.decode(duplicate) }.isFailure)
        assertTrue(runCatching { ListeningLevelCodec.decode(" ".repeat(ListeningLevelCodec.MAX_BYTES + 1)) }.isFailure)
    }

    @Test fun appBackupValidationStripsRouteBindingsAndLevelHistory() {
        val backup = AuroraBackup(listeningProfiles = ListeningLevelCodec.encode(state()))
        val imported = BackupArchive.decodeJson(Gson().toJson(backup))
        val profileJson = requireNotNull(imported.listeningProfiles)
        assertFalse(profileJson.contains(ListeningLevelsTest.key))
        val restored = ListeningLevelCodec.decode(profileJson)
        assertTrue(restored.history.isEmpty())
        assertFalse(restored.historyEnabled)
        assertNull(restored.profiles.single().routeKey)
    }

    @Test fun archiveWritingAlsoSanitizesBindingsAndHistory() {
        val bytes = ByteArrayOutputStream()
        BackupArchive.write(AuroraBackup(listeningProfiles = ListeningLevelCodec.encode(state())), bytes)
        val json = ZipInputStream(bytes.toByteArray().inputStream()).use { archive ->
            assertEquals("backup.json", archive.nextEntry.name)
            archive.readBytes().toString(Charsets.UTF_8)
        }
        assertFalse(json.contains(ListeningLevelsTest.key))
        val restored = ListeningLevelCodec.decode(requireNotNull(BackupArchive.decodeJson(json).listeningProfiles))
        assertTrue(restored.history.isEmpty())
    }

    @Test fun malformedCalibrationAbortsAppBackupValidation() {
        val json = Gson().toJson(AuroraBackup(listeningProfiles = "{\"version\":1,\"profiles\":[{}]}"))
        assertTrue(runCatching { BackupArchive.decodeJson(json) }.isFailure)
        assertNull(BackupArchive.decodeJson(Gson().toJson(AuroraBackup())).listeningProfiles)
    }

    private fun state() = ListeningState(listOf(ListeningLevelsTest.profile()), true,
        listOf(ListeningHistoryEntry(1700000000000L, "Headphones", 82.0, null, 4.0)))
}
