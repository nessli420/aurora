package com.aurora.music.data.ir

import com.aurora.music.data.ProcessingPresetBundle
import com.aurora.music.playback.ConvolutionProcessor
import com.aurora.music.playback.ImpulseResponse
import com.aurora.music.playback.engine.SamplePrecision
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs

object ImpulseLibraryFiles {
    const val NORMALIZED_PEAK = 0.8912509381337456

    fun importOriginal(file: File, name: String, sourceName: String, nowMs: Long): Result<ImpulseLibraryEntry> = runCatching {
        val loaded = load(file)
        ImpulseLibraryCodec.validate(ImpulseLibraryEntry(UUID.randomUUID().toString(), name, sourceName,
            file.canonicalPath, loaded.sha256, loaded.metadata, nowMs))
    }

    fun validateAsset(entry: ImpulseLibraryEntry, prepared: Boolean): Result<File> = runCatching {
        selected(entry, prepared).file
    }

    fun prepare(entry: ImpulseLibraryEntry, destination: File, preparation: ImpulsePreparation): Result<ImpulseLibraryEntry> = runCatching {
        val source = selected(entry, false)
        val samples = preparedSamples(source, preparation)
        val target = destination.canonicalFile
        require(target != source.file.canonicalFile && entry.prepared?.path?.let { target != File(it).canonicalFile } != false) {
            "The prepared copy must use a new file."
        }
        require(target.createNewFile()) { "The prepared file already exists." }
        try {
            writeFloatWav(target, samples)
            val loaded = load(target)
            ImpulseLibraryCodec.validate(entry.copy(prepared = ImpulsePreparedAsset(target.path,
                loaded.sha256, loaded.metadata, preparation)))
        } catch (failure: Throwable) {
            target.delete()
            throw failure
        }
    }

    fun preview(entry: ImpulseLibraryEntry, prepared: Boolean = false, bins: Int = 128): Result<ImpulsePreview> = runCatching {
        val loaded = selected(entry, prepared)
        waveform(loaded.metadata, loaded.impulse.preciseLeft, loaded.impulse.preciseRight, bins)
    }

    fun previewPrepared(entry: ImpulseLibraryEntry, preparation: ImpulsePreparation, bins: Int = 128): Result<ImpulsePreview> = runCatching {
        val samples = preparedSamples(selected(entry, false), preparation)
        waveform(samples.metadata, samples.left, samples.right, bins)
    }

    fun estimate(metadata: ImpulseMetadata, targetSampleRate: Int, blockSize: Int = 1024): ImpulseResourceEstimate {
        ImpulseLibraryCodec.validateMetadata(metadata)
        require(targetSampleRate in 8_000..768_000) { "Invalid output sample rate." }
        require(blockSize in 16..4096 && blockSize and (blockSize - 1) == 0) { "Invalid convolution block size." }
        val frames = (metadata.frames * (targetSampleRate.toDouble() / metadata.sampleRate)).toLong().coerceAtLeast(1)
        val partitions = ((frames + blockSize - 1) / blockSize).toInt()
        return ImpulseResourceEstimate(targetSampleRate, frames, frames <= ImpulseLibraryCodec.MAX_PREPARED_FRAMES,
            frames * 2 * 8, partitions, partitions.toLong() * 2 * 4 * (2 * blockSize) * 8)
    }

    private data class Loaded(val file: File, val sha256: String, val metadata: ImpulseMetadata, val impulse: ImpulseResponse)
    private data class PreparedSamples(val metadata: ImpulseMetadata, val left: DoubleArray, val right: DoubleArray)

    private fun selected(entry: ImpulseLibraryEntry, prepared: Boolean): Loaded {
        ImpulseLibraryCodec.validate(entry)
        val variant = if (prepared) requireNotNull(entry.prepared) { "Prepare a copy first." } else null
        val path = variant?.path ?: entry.sourcePath
        val expectedHash = variant?.sha256 ?: entry.sourceSha256
        val expectedMetadata = variant?.metadata ?: entry.sourceMetadata
        val loaded = load(File(path))
        require(loaded.sha256 == expectedHash) { "The impulse file has changed." }
        require(loaded.metadata == expectedMetadata) { "Impulse metadata does not match its file." }
        if (variant != null) {
            val expected = preparedSamples(selected(entry, false), variant.preparation)
            require(expected.metadata == loaded.metadata && expected.left.contentEquals(loaded.impulse.preciseLeft) &&
                expected.right.contentEquals(loaded.impulse.preciseRight)) { "The prepared copy does not match its source and trim." }
        }
        return loaded
    }

    private fun load(file: File): Loaded {
        require(file.isFile && file.length() in 44..ImpulseLibraryCodec.MAX_WAV_BYTES) { "Impulse file is missing or exceeds 64 MiB." }
        val sha256 = checksum(file)
        ProcessingPresetBundle.validateImpulseResponse(file)
        val impulse = ConvolutionProcessor.loadWavResult(file).getOrThrow()
        val metadata = ImpulseMetadata(impulse.sampleRate, impulse.sourceChannels, impulse.frameCount,
            impulse.sourcePrecision, impulse.sourceValidBits, peak(impulse.preciseLeft, impulse.preciseRight))
        ImpulseLibraryCodec.validateMetadata(metadata)
        require(checksum(file) == sha256) { "The impulse file changed while reading." }
        return Loaded(file, sha256, metadata, impulse)
    }

    private fun checksum(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= ImpulseLibraryCodec.MAX_WAV_BYTES) { "Impulse file exceeds 64 MiB." }
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun preparedSamples(source: Loaded, preparation: ImpulsePreparation): PreparedSamples {
        ImpulseLibraryCodec.validatePreparation(preparation, source.metadata)
        val left = source.impulse.preciseLeft.copyOfRange(preparation.startFrame, preparation.endFrameExclusive)
        val right = source.impulse.preciseRight.copyOfRange(preparation.startFrame, preparation.endFrameExclusive)
        val maximum = peak(left, right)
        val gain = when (preparation.normalization) {
            ImpulseNormalization.NONE -> 1.0
            ImpulseNormalization.PEAK_MINUS_1_DB -> {
                require(maximum > 0.0) { "Cannot normalize a silent trim." }
                NORMALIZED_PEAK / maximum
            }
        }
        for (i in left.indices) {
            left[i] = (left[i] * gain).toFloat().toDouble()
            right[i] = (right[i] * gain).toFloat().toDouble()
        }
        val metadata = source.metadata.copy(frames = left.size, precision = SamplePrecision.FLOAT_32,
            validBits = 24, peak = peak(left, right))
        ImpulseLibraryCodec.validateMetadata(metadata)
        return PreparedSamples(metadata, left, right)
    }

    private fun peak(left: DoubleArray, right: DoubleArray): Double {
        var maximum = 0.0
        for (i in left.indices) maximum = maxOf(maximum, abs(left[i]), abs(right[i]))
        return maximum
    }

    private fun waveform(metadata: ImpulseMetadata, left: DoubleArray, right: DoubleArray, bins: Int): ImpulsePreview {
        require(bins in 1..1024) { "Choose between 1 and 1,024 waveform bins." }
        val count = minOf(bins, metadata.frames)
        fun channel(samples: DoubleArray): List<ImpulseWaveformBin> = List(count) { index ->
            val from = index * samples.size / count
            val until = (index + 1) * samples.size / count
            var min = samples[from]
            var max = min
            for (i in from + 1 until until) { min = minOf(min, samples[i]); max = maxOf(max, samples[i]) }
            ImpulseWaveformBin(min, max)
        }
        return ImpulsePreview(metadata, channel(left), channel(right))
    }

    private fun writeFloatWav(file: File, samples: PreparedSamples) {
        val metadata = samples.metadata
        val alignment = metadata.channels * 4
        val bytes = metadata.frames * alignment
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x46464952); putInt(36 + bytes); putInt(0x45564157)
            putInt(0x20746d66); putInt(16); putShort(3); putShort(metadata.channels.toShort())
            putInt(metadata.sampleRate); putInt(metadata.sampleRate * alignment); putShort(alignment.toShort()); putShort(32)
            putInt(0x61746164); putInt(bytes)
        }
        file.outputStream().buffered().use { output ->
            output.write(header.array())
            val buffer = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
            for (i in samples.left.indices) {
                buffer.putFloat(samples.left[i].toFloat())
                if (metadata.channels == 2) buffer.putFloat(samples.right[i].toFloat())
                if (buffer.remaining() < alignment) { output.write(buffer.array(), 0, buffer.position()); buffer.clear() }
            }
            if (buffer.position() > 0) output.write(buffer.array(), 0, buffer.position())
        }
    }
}
