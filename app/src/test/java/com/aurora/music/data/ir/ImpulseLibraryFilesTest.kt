package com.aurora.music.data.ir

import com.aurora.music.playback.ConvolutionProcessor
import com.aurora.music.playback.engine.ConvolutionTailMode
import com.aurora.music.playback.engine.BandlimitedResampler
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

    @Test fun trueStereoImportPreparationAndExportKeepAllMatrixPaths() {
        val source = floats(floatArrayOf(.8f, .2f, -.3f, .4f, .1f, -.1f, .05f, -.2f), 4)
        val original = source.readBytes()
        val entry = imported(source)
        assertEquals(4, entry.sourceMetadata.channels)
        val preview = ImpulseLibraryFiles.preview(entry).getOrThrow()
        assertEquals(2, preview.leftToRight.size)
        assertEquals(2, preview.rightToLeft.size)
        val destination = File(folder.root, "matrix-prepared.wav")
        val prepared = ImpulseLibraryFiles.prepare(entry, destination, ImpulsePreparation(0, 2, delayFrames = 3)).getOrThrow()
        val decoded = ConvolutionProcessor.loadWavResult(destination).getOrThrow()
        assertTrue(decoded.trueStereo)
        assertEquals(5, decoded.frameCount)
        assertEquals(3, decoded.alignmentFrames)
        assertEquals(.2f.toDouble(), decoded.preciseLeftToRight!![3], 0.0)
        assertEquals(-.3f.toDouble(), decoded.preciseRightToLeft!![3], 0.0)
        assertEquals(.4f.toDouble(), decoded.preciseRight[3], 0.0)
        assertEquals(prepared.prepared!!.metadata, ImpulseLibraryFiles.preview(prepared, true).getOrThrow().metadata)
        assertArrayEquals(original, source.readBytes())
    }

    @Test fun minimumPhaseMatrixKeepsNegativeCrossPathsAndMonoCancellation() {
        val source = floats(FloatArray(16 * 4).apply {
            this[7 * 4] = .5f; this[7 * 4 + 1] = -.5f
            this[7 * 4 + 2] = -.5f; this[7 * 4 + 3] = .5f
        }, 4)
        val output = File(folder.root, "minimum-matrix.wav")
        val prepared = ImpulseLibraryFiles.prepare(imported(source), output,
            ImpulsePreparation(0, 16, minimumPhase = true, delayFrames = 3)).getOrThrow()
        assertTrue(ImpulseLibraryFiles.validateAsset(prepared, true).isSuccess)
        val impulse = ConvolutionProcessor.loadWavResult(output).getOrThrow()
        assertEquals(3, impulse.alignmentFrames)
        assertEquals(.5, impulse.preciseLeft[3], 1e-12)
        assertEquals(-.5, impulse.preciseLeftToRight!![3], 1e-12)
        assertEquals(-.5, impulse.preciseRightToLeft!![3], 1e-12)
        assertEquals(.5, impulse.preciseRight[3], 1e-12)
        val convolver = impulse.createConvolver(48_000, 16)
        val mono = DoubleArray(7 * 2) { (it / 2 - 3) / 8.0 }
        assertEquals(7, convolver.queueInput(mono, 0, 7))
        convolver.queueEndOfInput(ConvolutionTailMode.FULL)
        val block = DoubleArray(32)
        var frames = 0
        while (!convolver.isEnded) {
            val count = convolver.readOutput(block, 0, 16)
            assertTrue(count > 0)
            repeat(count * 2) { assertEquals("matrix cancellation sample ${frames * 2 + it}", 0.0, block[it], 1e-14) }
            frames += count
        }
        assertEquals(7 + impulse.frameCount - 1, frames)
    }

    @Test fun preparationPreservesTrimmedInheritedAlignmentUntilMinimumPhaseRemovesIt() {
        val source = imported(floats(FloatArray(16).apply { this[3] = .5f }, 1))
        val delayedFile = File(folder.root, "delayed-original.wav")
        ImpulseLibraryFiles.prepare(source, delayedFile, ImpulsePreparation(0, 16, delayFrames = 8)).getOrThrow()
        val delayed = imported(delayedFile)
        fun prepare(name: String, options: ImpulsePreparation): com.aurora.music.playback.ImpulseResponse {
            val output = File(folder.root, name)
            val prepared = ImpulseLibraryFiles.prepare(delayed, output, options).getOrThrow()
            assertTrue(ImpulseLibraryFiles.validateAsset(prepared, true).isSuccess)
            return ConvolutionProcessor.loadWavResult(output).getOrThrow()
        }
        val preserved = prepare("preserved.wav", ImpulsePreparation(2, 20, delayFrames = 4))
        assertEquals(10, preserved.alignmentFrames)
        assertEquals(22, preserved.frameCount)
        assertEquals(.5, preserved.preciseLeft[13], 0.0)
        val minimum = prepare("minimum.wav", ImpulsePreparation(2, 20, minimumPhase = true, delayFrames = 4))
        assertEquals(4, minimum.alignmentFrames)
        assertEquals(.5, minimum.preciseLeft[4], 1e-12)
        assertTrue(minimum.preciseLeft.filterIndexed { i, _ -> i != 4 }.all { kotlin.math.abs(it) < 1e-12 })
        val trimmed = prepare("past-delay.wav", ImpulsePreparation(10, 20, delayFrames = 2))
        assertEquals(2, trimmed.alignmentFrames)
        assertEquals(.5, trimmed.preciseLeft[3], 0.0)
        val beforeArrival = prepare("before-delay.wav", ImpulsePreparation(0, 5, delayFrames = 2))
        assertEquals(2, beforeArrival.alignmentFrames)
        assertTrue(beforeArrival.preciseLeft.all { it == 0.0 })
    }

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
        assertEquals(262_284L, exact.targetFrames)
        assertFalse(exact.supported)
        assertEquals(70, exact.resamplingDelayFrames)
        assertEquals(exact.targetFrames * 16, exact.decodedBytes)
        assertEquals(257, exact.partitionCount)
        assertEquals(33_685_504L, exact.partitionBytes)
        assertFalse(ImpulseLibraryFiles.estimate(metadata, 192_000).supported)
        assertEquals(70L, ImpulseLibraryFiles.estimate(metadata.copy(frames = 1), 8_000).targetFrames)
        assertEquals(735L, ImpulseLibraryFiles.estimate(metadata.copy(frames = 480), 44_100).targetFrames)
    }

    @Test fun resourceEstimateRoundsHalfFramesLikeTheRuntimeResampler() {
        val metadata = ImpulseMetadata(48_000, 1, 240, SamplePrecision.FLOAT_32, 24, .5)
        val estimate = ImpulseLibraryFiles.estimate(metadata, 44_100)
        val converted = BandlimitedResampler.resampleImpulse(DoubleArray(240).apply { this[0] = .5 }, 48_000, 44_100)
        assertEquals(515L, estimate.targetFrames)
        assertEquals(converted.size.toLong(), estimate.targetFrames)
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
