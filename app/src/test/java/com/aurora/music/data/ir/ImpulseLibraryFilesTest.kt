package com.aurora.music.data.ir

import com.aurora.music.playback.ConvolutionProcessor
import com.aurora.music.playback.engine.SamplePrecision
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ImpulseLibraryFilesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun importKeepsExactPcm32SourceBytesAndPrecision() {
        val bytes = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(1_073_741_825).putInt(-1_073_741_825).putInt(1).putInt(-1).array()
        val file = wav(bytes, 32, 2, 1)
        val original = file.readBytes()
        val entry = ImpulseLibraryFiles.importOriginal(file, "Exact", "source.wav", 123).getOrThrow()
        assertEquals(SamplePrecision.PCM_SIGNED_32, entry.sourceMetadata.precision)
        assertEquals(32, entry.sourceMetadata.validBits)
        assertEquals(2, entry.sourceMetadata.channels)
        assertEquals(1_073_741_825 / 2147483648.0, entry.sourceMetadata.peak, 0.0)
        assertArrayEquals(original, file.readBytes())
        assertNull(entry.prepared)
        assertEquals(file.canonicalFile, ImpulseLibraryFiles.validateAsset(entry, false).getOrThrow().canonicalFile)
    }

    @Test fun monoPreparationRetainsMonoAndTheOriginalSampleRate() {
        val source = floats(floatArrayOf(.25f, -.5f, .75f, .125f), 1, 96_000)
        val entry = imported(source)
        val output = File(folder.root, "prepared.wav")
        val result = ImpulseLibraryFiles.prepare(entry, output, ImpulsePreparation(1, 3)).getOrThrow()
        assertEquals(1, result.sourceMetadata.channels)
        assertEquals(1, result.prepared!!.metadata.channels)
        assertEquals(96_000, result.prepared.metadata.sampleRate)
        assertEquals(2, result.prepared.metadata.frames)
        val decoded = ConvolutionProcessor.loadWavResult(output).getOrThrow()
        assertEquals(1, decoded.sourceChannels)
        assertArrayEquals(doubleArrayOf(-.5, .75), decoded.preciseLeft, 0.0)
        assertArrayEquals(decoded.preciseLeft, decoded.preciseRight, 0.0)
        assertEquals(44 + 2 * 4L, output.length())
    }

    @Test fun stereoTrimIsSynchronousAndJointNormalizationPreservesRelativeLevels() {
        val source = floats(floatArrayOf(1f, -1f, .25f, -.5f, -.125f, .0625f, .75f, .5f), 2)
        val original = source.readBytes()
        val entry = imported(source)
        val options = ImpulsePreparation(1, 3, ImpulseNormalization.PEAK_MINUS_1_DB)
        val preview = ImpulseLibraryFiles.previewPrepared(entry, options).getOrThrow()
        val destination = File(folder.root, "prepared.wav")
        val result = ImpulseLibraryFiles.prepare(entry, destination, options).getOrThrow()
        val decoded = ConvolutionProcessor.loadWavResult(destination).getOrThrow()
        val gain = ImpulseLibraryFiles.NORMALIZED_PEAK / .5
        assertArrayEquals(doubleArrayOf((.25 * gain).toFloat().toDouble(), (-.125 * gain).toFloat().toDouble()), decoded.preciseLeft, 0.0)
        assertArrayEquals(doubleArrayOf((-.5 * gain).toFloat().toDouble(), (.0625 * gain).toFloat().toDouble()), decoded.preciseRight, 0.0)
        assertEquals(result.prepared!!.metadata, preview.metadata)
        assertEquals(preview, ImpulseLibraryFiles.preview(result, true).getOrThrow())
        assertEquals(entry.sourceMetadata, result.sourceMetadata)
        assertEquals(entry.sourceSha256, result.sourceSha256)
        assertArrayEquals(original, source.readBytes())
    }

    @Test fun floatPreparationExplicitlyRoundsPcm32WithoutChangingTheOriginal() {
        val bytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(1_073_741_825).putInt(1).array()
        val source = wav(bytes, 32, 1, 1)
        val entry = imported(source)
        val output = File(folder.root, "copy.wav")
        val prepared = ImpulseLibraryFiles.prepare(entry, output, ImpulsePreparation(0, 2)).getOrThrow()
        assertEquals(SamplePrecision.FLOAT_32, prepared.prepared!!.metadata.precision)
        assertEquals(.5, ConvolutionProcessor.loadWavResult(output).getOrThrow().preciseLeft[0], 0.0)
        assertEquals(1_073_741_825 / 2147483648.0, ConvolutionProcessor.loadWavResult(source).getOrThrow().preciseLeft[0], 0.0)
    }

    @Test fun rejectsSilentNormalizationButAllowsAnUnnormalizedSilentCopy() {
        val source = floats(floatArrayOf(0f, 0f), 1)
        val entry = imported(source)
        val output = File(folder.root, "silent.wav")
        assertTrue(ImpulseLibraryFiles.prepare(entry, output, ImpulsePreparation(0, 2, ImpulseNormalization.PEAK_MINUS_1_DB)).isFailure)
        assertFalse(output.exists())
        assertTrue(ImpulseLibraryFiles.previewPrepared(entry, ImpulsePreparation(0, 2, ImpulseNormalization.PEAK_MINUS_1_DB)).isFailure)
        assertEquals(0.0, ImpulseLibraryFiles.prepare(entry, output, ImpulsePreparation(0, 2)).getOrThrow().prepared!!.metadata.peak, 0.0)
    }

    @Test fun selectionAndExportValidationRejectChangedFilesAndForgedMetadata() {
        val source = floats(floatArrayOf(.5f, -.25f), 1)
        val entry = imported(source)
        assertTrue(ImpulseLibraryFiles.validateAsset(entry.copy(sourceMetadata = entry.sourceMetadata.copy(peak = .25)), false).isFailure)
        RandomAccessFile(source, "rw").use { it.seek(44); it.writeInt(Integer.reverseBytes(.25f.toRawBits())) }
        assertTrue(ImpulseLibraryFiles.validateAsset(entry, false).isFailure)
        assertTrue(ImpulseLibraryFiles.preview(entry).isFailure)
        assertTrue(ImpulseLibraryFiles.prepare(entry, File(folder.root, "copy.wav"), ImpulsePreparation(0, 1)).isFailure)
    }

    @Test fun neverOverwritesAnOriginalOrExistingPreparedFile() {
        val source = floats(floatArrayOf(.25f, -.5f), 1)
        val original = source.readBytes()
        val entry = imported(source)
        assertTrue(ImpulseLibraryFiles.prepare(entry, source, ImpulsePreparation(0, 1)).isFailure)
        val output = folder.newFile("existing.wav").apply { writeText("Keep this file") }
        assertTrue(ImpulseLibraryFiles.prepare(entry, output, ImpulsePreparation(0, 1)).isFailure)
        assertEquals("Keep this file", output.readText())
        assertArrayEquals(original, source.readBytes())
    }

    @Test fun rejectsPreparedSamplesWithValidHashAndMetadataButFalseProvenance() {
        val entry = imported(floats(floatArrayOf(.5f, -.25f, .125f), 1))
        val changed = imported(floats(floatArrayOf(.5f, .25f, -.125f), 1))
        assertEquals(entry.sourceMetadata, changed.sourceMetadata)
        assertTrue(ImpulseLibraryFiles.validateAsset(changed, false).isSuccess)
        val forged = entry.copy(prepared = ImpulsePreparedAsset(changed.sourcePath, changed.sourceSha256,
            changed.sourceMetadata, ImpulsePreparation(0, 3)))
        val validated = ImpulseLibraryCodec.validate(forged)
        val failure = ImpulseLibraryFiles.validateAsset(validated, true).exceptionOrNull()
        assertEquals("The prepared copy does not match its source and trim.", failure?.message)
        assertTrue(ImpulseLibraryFiles.preview(validated, true).isFailure)
    }

    @Test fun waveformBinsRetainExtremaAndCoverEverySample() {
        val source = floats(floatArrayOf(.1f, -.2f, .3f, -.4f, .5f), 1)
        val entry = imported(source)
        val preview = ImpulseLibraryFiles.preview(entry, bins = 2).getOrThrow()
        assertEquals(listOf(ImpulseWaveformBin(-.2f.toDouble(), .1f.toDouble()),
            ImpulseWaveformBin(-.4f.toDouble(), .5)), preview.left)
        assertEquals(preview.left, preview.right)
        assertEquals(5, ImpulseLibraryFiles.preview(entry, bins = 128).getOrThrow().left.size)
        assertTrue(ImpulseLibraryFiles.preview(entry, bins = 0).isFailure)
        assertTrue(ImpulseLibraryFiles.preview(entry, bins = 1025).isFailure)
    }

    @Test fun resourceEstimateFollowsRuntimeResamplingAndStereoPartitionCosts() {
        val metadata = ImpulseMetadata(48_000, 1, 131_072, SamplePrecision.FLOAT_32, 24, .5)
        val exact = ImpulseLibraryFiles.estimate(metadata, 96_000)
        assertEquals(262_144L, exact.targetFrames)
        assertTrue(exact.supported)
        assertEquals(4_194_304L, exact.decodedBytes)
        assertEquals(256, exact.partitionCount)
        assertEquals(33_554_432L, exact.partitionBytes)
        assertFalse(ImpulseLibraryFiles.estimate(metadata, 192_000).supported)
        assertEquals(1L, ImpulseLibraryFiles.estimate(metadata.copy(frames = 1), 8_000).targetFrames)
        assertEquals(441L, ImpulseLibraryFiles.estimate(metadata.copy(frames = 480), 44_100).targetFrames)
    }

    @Test fun rejectsInvalidTrimsNonfiniteSamplesUnsupportedRatesAndMissingVariants() {
        val entry = imported(floats(floatArrayOf(.5f, -.25f), 1))
        for (options in listOf(ImpulsePreparation(1, 1), ImpulsePreparation(-1, 1), ImpulsePreparation(0, 3))) {
            assertTrue(ImpulseLibraryFiles.prepare(entry, File(folder.root, "invalid.wav"), options).isFailure)
        }
        assertTrue(ImpulseLibraryFiles.validateAsset(entry, true).isFailure)
        assertTrue(ImpulseLibraryFiles.importOriginal(floats(floatArrayOf(Float.NaN), 1), "Bad", "bad.wav", 1).isFailure)
        assertTrue(ImpulseLibraryFiles.importOriginal(floats(floatArrayOf(.5f), 1, 768_000), "Bad", "bad.wav", 1).isFailure)
    }

    @Test fun sourceAndPreparedFrameLimitsAreIndependent() {
        val source = floats(FloatArray(262_145) { if (it == 0) .5f else 0f }, 1)
        val entry = imported(source)
        assertEquals(262_145, entry.sourceMetadata.frames)
        assertTrue(ImpulseLibraryFiles.prepare(entry, File(folder.root, "long.wav"), ImpulsePreparation(0, 262_145)).isFailure)
        val copy = ImpulseLibraryFiles.prepare(entry, File(folder.root, "bounded.wav"), ImpulsePreparation(0, 262_144)).getOrThrow()
        assertEquals(262_144, copy.prepared!!.metadata.frames)
        assertFalse(ImpulseLibraryFiles.estimate(entry.sourceMetadata, 48_000).supported)
    }

    private fun imported(file: File) = ImpulseLibraryFiles.importOriginal(file, "Test", "original.wav", 1).getOrThrow()

    private fun floats(samples: FloatArray, channels: Int, rate: Int = 48_000): File {
        val payload = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach(payload::putFloat)
        return wav(payload.array(), 32, channels, 3, rate)
    }

    private fun wav(payload: ByteArray, bits: Int, channels: Int, format: Int, rate: Int = 48_000): File {
        val align = channels * bits / 8
        val buffer = ByteBuffer.allocate(44 + payload.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x46464952); putInt(36 + payload.size); putInt(0x45564157)
            putInt(0x20746d66); putInt(16); putShort(format.toShort()); putShort(channels.toShort())
            putInt(rate); putInt(rate * align); putShort(align.toShort()); putShort(bits.toShort())
            putInt(0x61746164); putInt(payload.size); put(payload)
        }
        return folder.newFile().apply { writeBytes(buffer.array()) }
    }
}
