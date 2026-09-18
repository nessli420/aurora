package com.aurora.music.data.tuning

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class TuningProjectCodecTest {
    private fun curve(name: String = "Left", phase: Boolean = false) = MeasurementTextImporter.parse(
        if (phase) "20 -0.0 -0.0\n40 3.125 -450.25\n80 1 720" else "20 1\n40 3.125\n80 1",
        name, phaseColumn = phase, provenance = MeasurementProvenance("Fixture rig", "Local sweep", "Keep raw values")).getOrThrow()
    private fun project() = TuningProjectCodec.create("Saved desk", 100).copy(measurementLeft = curve(phase = true),
        measurementRight = curve("Right"), target = curve("Custom target"), notes = "Offline project\nNo fetched targets",
        config = TuningFitConfig(channelMode = TuningChannelMode.INDEPENDENT, normalization = TuningNormalization.NONE))
    private fun changed(project: TuningProject = project(), mutation: (JsonObject) -> Unit): String =
        JsonParser.parseString(TuningProjectCodec.encodeProject(project)).asJsonObject.apply(mutation).toString()

    @Test fun completeProjectRoundTripRetainsSourcePhaseProvenanceControlsAndNulls() {
        val original = project()
        val loaded = TuningProjectCodec.decodeProject(TuningProjectCodec.encodeProject(original)).getOrThrow()
        assertEquals(original, loaded)
        assertEquals((-0.0).toRawBits(), loaded.measurementLeft!!.points.first().phaseDegrees!!.toRawBits())
        val empty = TuningProjectCodec.create("Flat target")
        assertEquals(empty, TuningProjectCodec.decodeProject(TuningProjectCodec.encodeProject(empty)).getOrThrow())
        assertNull(empty.target)
        assertNull(empty.measurementLeft)
        assertNull(empty.generatedFit)
    }

    @Test fun datesUseExactIntegersRatherThanLossyDoubleConversion() {
        val original = TuningProjectCodec.create("Exact date", Long.MAX_VALUE)
        assertEquals(original, TuningProjectCodec.decodeProject(TuningProjectCodec.encodeProject(original)).getOrThrow())
        assertTrue(TuningProjectCodec.decodeProject(changed(original) { it.addProperty("createdAtMs", 1.5) }).isFailure)
        assertTrue(TuningProjectCodec.decodeProject(changed(original) { it.addProperty("createdAtMs", -1) }).isFailure)
    }

    @Test fun everyRequiredFieldMustExistAndNullableFieldsMustBeExplicit() {
        val json = JsonParser.parseString(TuningProjectCodec.encodeProject(project())).asJsonObject
        for (key in json.keySet().toList()) assertTrue(key, TuningProjectCodec.decodeProject(changed { it.remove(key) }).isFailure)
        for (key in listOf("id", "name", "notes", "config", "schemaVersion", "createdAtMs", "updatedAtMs"))
            assertTrue(key, TuningProjectCodec.decodeProject(changed { it.add(key, null) }).isFailure)
        for (key in listOf("measurementLeft", "measurementRight", "target", "generatedFit"))
            assertTrue(key, TuningProjectCodec.decodeProject(changed { it.add(key, null) }).isSuccess)
    }

    @Test fun nestedMissingNullUnknownAndWrongTypesFailBeforeDomainConstruction() {
        assertTrue(TuningProjectCodec.decodeProject(changed { it.getAsJsonObject("config").remove("bandBudget") }).isFailure)
        assertTrue(TuningProjectCodec.decodeProject(changed { it.getAsJsonObject("config").addProperty("channelMode", "AUTO") }).isFailure)
        assertTrue(TuningProjectCodec.decodeProject(changed { it.getAsJsonObject("measurementLeft").remove("sourceText") }).isFailure)
        assertTrue(TuningProjectCodec.decodeProject(changed { it.getAsJsonObject("measurementLeft").getAsJsonObject("provenance").add("rig", null) }).isFailure)
        assertTrue(TuningProjectCodec.decodeProject(changed { it.getAsJsonObject("measurementLeft").getAsJsonArray("points")[0].asJsonObject.remove("phaseDegrees") }).isFailure)
        assertTrue(TuningProjectCodec.decodeProject(changed { it.addProperty("unexpected", true) }).isFailure)
        assertTrue(TuningProjectCodec.decodeProject(changed { it.addProperty("schemaVersion", 2) }).isFailure)
    }

    @Test fun duplicateJsonFieldsNonfiniteTokensAndTrailingDocumentsAreRejected() {
        val source = TuningProjectCodec.encodeProject(project())
        assertTrue(TuningProjectCodec.decodeProject(source.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1")).isFailure)
        assertTrue(TuningProjectCodec.decodeProject(source + " {}").isFailure)
        assertTrue(TuningProjectCodec.decodeProject(source.replace("\"maxBoostDb\":6.0", "\"maxBoostDb\":NaN")).isFailure)
        assertTrue(TuningProjectCodec.decodeProject(source.replace("\"maxBoostDb\":6.0", "\"maxBoostDb\":1e999")).isFailure)
        assertTrue(TuningProjectCodec.decodeProject(source.replace("\"maxBoostDb\":6.0", "\"maxBoostDb\":0." + "0".repeat(100))).isFailure)
    }

    @Test fun preservedSourceAndStoredPointsCannotDisagreeOrLosePhase() {
        assertTrue(TuningProjectCodec.decodeProject(changed { it.getAsJsonObject("measurementLeft").getAsJsonArray("points")[1]
            .asJsonObject.addProperty("magnitudeDb", 20) }).isFailure)
        assertTrue(TuningProjectCodec.decodeProject(changed { it.getAsJsonObject("measurementLeft").addProperty("sourceText", "20 1\n40 2") }).isFailure)
        assertTrue(TuningProjectCodec.decodeProject(changed { it.getAsJsonObject("measurementLeft").getAsJsonArray("points")[0]
            .asJsonObject.add("phaseDegrees", null) }).isFailure)
    }

    @Test fun configLimitsAreExplicitAndNeverClamped() {
        val c = TuningFitConfig()
        for (invalid in listOf(c.copy(bandBudget = 129), c.copy(bandBudget = 0), c.copy(sampleRate = 1),
            c.copy(minFrequencyHz = c.maxFrequencyHz), c.copy(maxBoostDb = -1.0), c.copy(maxCutDb = 31.0),
            c.copy(minQ = .01), c.copy(minQ = 9.0, maxQ = 8.0), c.copy(smoothingOctaves = 1.1), c.copy(maxQ = Double.NaN))) {
            assertTrue(runCatching { TuningProjectCodec.validateConfig(invalid) }.isFailure)
        }
        assertEquals(c.copy(bandBudget = 64, minQ = .1), TuningProjectCodec.validateConfig(c.copy(bandBudget = 64, minQ = .1)))
    }

    @Test fun libraryUpsertDeleteAndDuplicateIdsAreAtomicPureOperations() {
        val first = project()
        val library = listOf(first)
        val updated = TuningProjectCodec.upsert(library, first.copy(name = "Renamed")).getOrThrow()
        assertEquals("Saved desk", library.single().name)
        assertEquals("Renamed", updated.single().name)
        assertEquals(updated, TuningProjectCodec.decodeLibrary(TuningProjectCodec.encodeLibrary(updated)).getOrThrow())
        assertTrue(TuningProjectCodec.delete(updated, first.id).getOrThrow().isEmpty())
        assertTrue(TuningProjectCodec.decodeLibrary(null).getOrThrow().isEmpty())
        assertTrue(TuningProjectCodec.decodeLibrary("").isFailure)
        assertTrue(runCatching { TuningProjectCodec.encodeLibrary(listOf(first, first)) }.isFailure)
        assertTrue(TuningProjectCodec.upsert(library, first.copy(name = "")).isFailure)
        assertEquals(first, library.single())
    }

    @Test fun projectCountAndEncodedByteBudgetsAreEnforcedBeforeSaving() {
        val full = List(TuningProjectCodec.MAX_PROJECTS) { TuningProjectCodec.create("Project $it") }
        assertEquals(full, TuningProjectCodec.decodeLibrary(TuningProjectCodec.encodeLibrary(full)).getOrThrow())
        assertTrue(TuningProjectCodec.upsert(full, TuningProjectCodec.create("One too many")).isFailure)
        val raw = ("#" + "x".repeat(1000) + "\n").repeat(510) + "20 1\n40 2"
        val large = MeasurementTextImporter.parse(raw).getOrThrow()
        val projects = List(9) { TuningProjectCodec.create("Large $it").copy(measurementLeft = large, measurementRight = large) }
        assertTrue(runCatching { TuningProjectCodec.encodeLibrary(projects) }.isFailure)
        assertTrue(TuningProjectCodec.decodeProject(" ".repeat(TuningProjectCodec.MAX_PROJECT_BYTES + 1)).isFailure)
    }

    @Test fun fingerprintsTrackNumericInputsAndControlsButNotNamesNotesOrIds() {
        val original = project()
        val key = TuningProjectCodec.inputFingerprint(original)
        assertEquals(key, TuningProjectCodec.inputFingerprint(original.copy(id = UUID.randomUUID().toString(), name = "Copy", notes = "New notes")))
        assertEquals(key, TuningProjectCodec.inputFingerprint(original.copy(measurementLeft = original.measurementLeft!!.copy(
            provenance = MeasurementProvenance("Different label")))))
        assertNotEquals(key, TuningProjectCodec.inputFingerprint(original.copy(config = original.config.copy(bandBudget = 10))))
        assertNotEquals(key, TuningProjectCodec.inputFingerprint(original.copy(target = null)))
        assertNotEquals(key, TuningProjectCodec.inputFingerprint(original.copy(measurementLeft = null)))
    }

    @Test fun validationDetachesCallerOwnedPointAndProjectLists() {
        val original = curve()
        val points = original.points.toMutableList()
        val input = TuningProjectCodec.create("Detached").copy(measurementLeft = original.copy(points = points))
        val frozen = TuningProjectCodec.validate(input)
        points.clear()
        assertEquals(original.points, frozen.measurementLeft!!.points)
        val projects = mutableListOf(frozen)
        val updated = TuningProjectCodec.upsert(projects, frozen.copy(name = "Saved")).getOrThrow()
        projects.clear()
        assertEquals("Saved", updated.single().name)
    }
}
