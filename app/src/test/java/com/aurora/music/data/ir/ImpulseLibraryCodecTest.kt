package com.aurora.music.data.ir

import com.aurora.music.playback.engine.SamplePrecision
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ImpulseLibraryCodecTest {
    private val metadata = ImpulseMetadata(48_000, 2, 128, SamplePrecision.PCM_SIGNED_24, 24, .5)
    private fun entry() = ImpulseLibraryEntry(UUID.randomUUID().toString(), "Room", "room.wav",
        "/data/user/0/com.aurora.music/files/impulses/source.wav", "a".repeat(64), metadata, 123)

    @Test fun roundTripPreservesSourceMetadataAndPreparedOperations() {
        val input = entry().copy(prepared = ImpulsePreparedAsset("/data/impulses/prepared.wav", "b".repeat(64),
            metadata.copy(frames = 64, precision = SamplePrecision.FLOAT_32, peak = ImpulseLibraryFiles.NORMALIZED_PEAK.toFloat().toDouble()),
            ImpulsePreparation(32, 96, ImpulseNormalization.PEAK_MINUS_1_DB)))
        assertEquals(listOf(input), ImpulseLibraryCodec.decodeLibrary(ImpulseLibraryCodec.encodeLibrary(listOf(input))).getOrThrow())
        assertEquals(emptyList<ImpulseLibraryEntry>(), ImpulseLibraryCodec.decodeLibrary(null).getOrThrow())
    }

    @Test fun rejectsUnknownMissingDuplicateWronglyTypedAndTrailingFields() {
        val json = ImpulseLibraryCodec.encodeLibrary(listOf(entry()))
        for (bad in listOf(
            json.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
            json.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            json.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
            json.replace("\"prepared\":null", "\"unused\":null"),
            json.replace("\"channels\":2", "\"channels\":null"),
            json.replace("\"frames\":128", "\"frames\":1.5"),
            json.replace("\"createdAtMs\":123", "\"createdAtMs\":9223372036854775808"),
            json.replace("\"peak\":0.5", "\"peak\":1e999"),
            "$json {}"
        )) assertTrue(bad, ImpulseLibraryCodec.decodeLibrary(bad).isFailure)
    }

    @Test fun rejectsOversizedDeepAndMalformedJson() {
        assertTrue(ImpulseLibraryCodec.decodeLibrary(" ".repeat(ImpulseLibraryCodec.MAX_LIBRARY_BYTES + 1)).isFailure)
        assertTrue(ImpulseLibraryCodec.decodeLibrary("[".repeat(20) + "]".repeat(20)).isFailure)
        assertTrue(ImpulseLibraryCodec.decodeLibrary("{'schemaVersion':1,'entries':[]}").isFailure)
        assertTrue(ImpulseLibraryCodec.decodeLibrary("{\"schemaVersion\":1,\"entries\":[],}").isFailure)
    }

    @Test fun enforcesUniqueIdsAndLibraryCapacityBeforeMutation() {
        val current = (0 until 32).map { entry() }
        assertTrue(ImpulseLibraryCodec.upsert(current, entry()).isFailure)
        assertEquals(current, ImpulseLibraryCodec.decodeLibrary(ImpulseLibraryCodec.encodeLibrary(current)).getOrThrow())
        assertTrue(ImpulseLibraryCodec.upsert(listOf(current.first(), current.first()), entry()).isFailure)
        val renamed = current.first().copy(name = " Renamed ")
        val updated = ImpulseLibraryCodec.upsert(current, renamed).getOrThrow()
        assertEquals(32, updated.size)
        assertEquals("Renamed", updated.first().name)
        assertEquals(31, ImpulseLibraryCodec.delete(updated, renamed.id).getOrThrow().size)
    }

    @Test fun rejectsInvalidSourceMetadataNamesHashesAndPaths() {
        val source = entry()
        for (bad in listOf(
            source.copy(id = "1-1-1-1-1"), source.copy(name = "\n"), source.copy(sourceName = "bad\n.wav"),
            source.copy(sourceSha256 = "A".repeat(64)), source.copy(sourcePath = "../source.wav"),
            source.copy(sourcePath = "/data/../source.wav"), source.copy(createdAtMs = -1),
            source.copy(sourceMetadata = metadata.copy(channels = 3)),
            source.copy(sourceMetadata = metadata.copy(frames = 0)),
            source.copy(sourceMetadata = metadata.copy(frames = ImpulseLibraryCodec.MAX_SOURCE_FRAMES + 1)),
            source.copy(sourceMetadata = metadata.copy(sampleRate = 768_000)),
            source.copy(sourceMetadata = metadata.copy(precision = SamplePrecision.FLOAT_64)),
            source.copy(sourceMetadata = metadata.copy(validBits = 25)),
            source.copy(sourceMetadata = metadata.copy(peak = Double.NaN)),
            source.copy(sourceMetadata = metadata.copy(peak = 1.01))
        )) assertTrue(bad.toString(), runCatching { ImpulseLibraryCodec.validate(bad) }.isFailure)
    }

    @Test fun rejectsPreparedMetadataThatContradictsItsOperation() {
        val source = entry()
        val prepared = ImpulsePreparedAsset("/data/prepared.wav", "b".repeat(64),
            metadata.copy(precision = SamplePrecision.FLOAT_32, frames = 64), ImpulsePreparation(0, 64))
        for (bad in listOf(
            prepared.copy(metadata = prepared.metadata.copy(frames = 63)),
            prepared.copy(metadata = prepared.metadata.copy(sampleRate = 96_000)),
            prepared.copy(metadata = prepared.metadata.copy(channels = 1)),
            prepared.copy(metadata = prepared.metadata.copy(precision = SamplePrecision.PCM_SIGNED_24)),
            prepared.copy(preparation = ImpulsePreparation(-1, 63)),
            prepared.copy(preparation = ImpulsePreparation(128, 192)),
            prepared.copy(preparation = ImpulsePreparation(0, 64, ImpulseNormalization.PEAK_MINUS_1_DB)),
            prepared.copy(path = source.sourcePath)
        )) assertTrue(bad.toString(), runCatching { ImpulseLibraryCodec.validate(source.copy(prepared = bad)) }.isFailure)
    }

    @Test fun acceptsPortableReferencesAndIdenticalImmutableAssetsAfterDeduplication() {
        val source = entry().copy(sourcePath = "aurora-ir:" + "a".repeat(64),
            sourceMetadata = metadata.copy(precision = SamplePrecision.FLOAT_32))
        val prepared = ImpulsePreparedAsset(source.sourcePath, source.sourceSha256, source.sourceMetadata, ImpulsePreparation(0, 128))
        val expected = source.copy(prepared = prepared)
        assertEquals(expected, ImpulseLibraryCodec.decodeLibrary(ImpulseLibraryCodec.encodeLibrary(listOf(expected))).getOrThrow().single())
        assertTrue(runCatching { ImpulseLibraryCodec.validate(source.copy(sourcePath = "aurora-ir:../../evil")) }.isFailure)
    }

    @Test fun preparedLengthIsBoundedWithoutConstrainingTheOriginal() {
        val source = metadata.copy(frames = ImpulseLibraryCodec.MAX_SOURCE_FRAMES)
        ImpulseLibraryCodec.validateMetadata(source)
        ImpulseLibraryCodec.validatePreparation(ImpulsePreparation(123, 123 + 262_144), source)
        assertTrue(runCatching { ImpulseLibraryCodec.validatePreparation(ImpulsePreparation(0, 262_145), source) }.isFailure)
    }
}
