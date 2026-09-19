package com.aurora.music.playback.compare

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.aurora.music.data.DspMode
import com.aurora.music.data.ProcessingPreset
import com.aurora.music.data.ProcessingRack
import com.aurora.music.data.ProcessingPresetAssets
import com.aurora.music.data.ProcessingPresetBundle
import com.aurora.music.data.requiresImpulseResponse
import com.aurora.music.playback.ConvolutionProcessor
import com.aurora.music.playback.engine.AudioBlock
import com.aurora.music.playback.engine.AudioStreamFormat
import com.aurora.music.playback.engine.ChannelLayout
import com.aurora.music.playback.engine.ProductionSerialRack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ComparisonSource(val rate: Int, val samples: DoubleArray, val warmupFrames: Int, val encoding: Int)

object ComparisonRenderer {
    private const val MAX_SOURCE_FRAMES = 1_000_000
    suspend fun prepare(context: Context, uri: Uri, startSeconds: Int,
        presetA: ProcessingPreset, presetB: ProcessingPreset, relativeVolume: Double = 1.0): ComparisonAudio = withContext(Dispatchers.Default) {
        require(startSeconds in 0..86_400) { "Invalid selection start." }
        val source = withContext(Dispatchers.IO) { decode(context, uri, startSeconds) }
        currentCoroutineContext().ensureActive()
        val first = render(source, presetA, relativeVolume)
        currentCoroutineContext().ensureActive()
        val second = render(source, presetB, relativeVolume)
        ComparisonMatching.match(source.rate, first.first, second.first, first.second, second.second, source.warmupFrames)
    }

    @OptIn(UnstableApi::class)
    suspend fun render(source: ComparisonSource, preset: ProcessingPreset, relativeVolume: Double = 1.0): Pair<DoubleArray, Int> {
        require(preset.rack.enabled || preset.audio.dspMode != DspMode.SYSTEM) {
            "Save a processing rack or software DSP preset for comparison."
        }
        val rack = if (preset.rack.enabled) preset.rack else ProcessingRack.legacy(preset.audio, preset.playback.monoAudio).copy(enabled = true)
        val impulse = if (preset.requiresImpulseResponse()) {
            require(preset.audio.dspConvIrPath.isNotBlank()) { "A preset impulse response is missing." }
            val file = File(preset.audio.dspConvIrPath)
            require(ProcessingPresetBundle.sha256(file) == preset.irSha256) { "A preset impulse response has changed." }
            ConvolutionProcessor.loadWavResult(file).getOrThrow()
        } else null
        ProcessingPresetAssets.validateFiles(preset.rackImpulseAssets)
        val impulses = preset.rackImpulseAssets.associate { entry ->
            entry.id to ConvolutionProcessor.loadWavResult(File(entry.prepared?.path ?: entry.sourcePath)).getOrThrow()
        }
        val graph = ProductionSerialRack.compile(rack, source.rate, impulse, impulses, relativeVolume)
        require(graph.convolutionUnavailableReason == null) { "A preset impulse response is unavailable." }
        val block = AudioBlock(AudioStreamFormat(source.rate, ChannelLayout.STEREO), ProductionSerialRack.INPUT_FRAMES)
        val result = DoubleArray(source.samples.size + graph.tailFrames * 2 + graph.latencyFrames * 2 + 4096)
        var written = 0
        fun drain(): Boolean {
            var progress = false
            while (true) {
                val output = graph.getOutput() ?: break
                require(written + output.sampleCount <= result.size) { "Preset tail exceeds the comparison limit." }
                output.samples.copyInto(result, written, 0, output.sampleCount)
                written += output.sampleCount
                progress = true
            }
            return progress
        }
        var frame = 0
        while (frame * 2 < source.samples.size) {
            currentCoroutineContext().ensureActive()
            val count = minOf(block.capacityFrames, source.samples.size / 2 - frame)
            block.begin(count, frame * 1_000_000L / source.rate, frame.toLong())
            source.samples.copyInto(block.samples, 0, frame * 2, (frame + count) * 2)
            while (!graph.queueInput(block)) check(drain()) { "Comparison processing stalled." }
            frame += count
            drain()
        }
        graph.queueEndOfStream()
        while (!graph.isEnded) {
            currentCoroutineContext().ensureActive()
            check(drain() || graph.isEnded) { "Comparison tail did not drain." }
        }
        return result.copyOf(minOf(written, source.samples.size + graph.latencyFrames * 2)) to graph.latencyFrames
    }

    suspend fun decode(context: Context, uri: Uri, startSeconds: Int): ComparisonSource {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No supported audio track was found.")
            extractor.selectTrack(track)
            val input = extractor.getTrackFormat(track)
            val mime = requireNotNull(input.getString(MediaFormat.KEY_MIME))
            val requestedStartUs = startSeconds * 1_000_000L
            val decodeStartUs = maxOf(0L, requestedStartUs - 2_000_000L)
            extractor.seekTo(decodeStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            var rate = input.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = input.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = if (input.containsKey(MediaFormat.KEY_PCM_ENCODING)) input.getInteger(MediaFormat.KEY_PCM_ENCODING)
                else AudioFormat.ENCODING_PCM_16BIT
            require(rate in 8_000..192_000 && channels in 1..2) { "Comparison supports mono or stereo audio up to 192 kHz." }
            var data = DoubleArray(minOf(rate * 20, MAX_SOURCE_FRAMES) * 2)
            var count = 0
            var firstUs = Long.MIN_VALUE
            var lastProgress = System.nanoTime()
            var untilUs = minOf(requestedStartUs + 15_000_000L, decodeStartUs + MAX_SOURCE_FRAMES * 1_000_000L / rate)
            fun accept(buffer: ByteBuffer, timeUs: Long) {
                val bytes = bytesPerSample(encoding)
                require(bytes > 0 && buffer.remaining() % (bytes * channels) == 0) { "Unsupported decoded PCM format." }
                buffer.order(ByteOrder.LITTLE_ENDIAN)
                val frames = buffer.remaining() / (bytes * channels)
                for (frame in 0 until frames) {
                    val sampleUs = timeUs + frame * 1_000_000L / rate
                    val left = sample(buffer, encoding)
                    val right = if (channels == 2) sample(buffer, encoding) else left
                    if (sampleUs < decodeStartUs || sampleUs >= untilUs) continue
                    if (firstUs == Long.MIN_VALUE) firstUs = sampleUs
                    require(count + 2 <= data.size) { "Audio timestamps exceed the selection limit." }
                    require(left.isFinite() && right.isFinite()) { "The source contains invalid audio samples." }
                    data[count++] = left; data[count++] = right
                }
            }
            if (mime == "audio/raw") {
                val packet = ByteBuffer.allocateDirect(2 * 1024 * 1024)
                while (extractor.sampleTime in 0 until untilUs) {
                    currentCoroutineContext().ensureActive()
                    packet.clear()
                    val size = extractor.readSampleData(packet, 0)
                    if (size < 0) break
                    packet.position(0); packet.limit(size)
                    accept(packet, extractor.sampleTime)
                    if (!extractor.advance()) break
                }
            } else {
                input.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
                decoder = MediaCodec.createDecoderByType(mime)
                decoder.configure(input, null, null, 0); decoder.start()
                val codec = decoder
                val info = MediaCodec.BufferInfo()
                var inputEnded = false; var outputEnded = false
                while (!outputEnded) {
                    currentCoroutineContext().ensureActive()
                    require(System.nanoTime() - lastProgress < 15_000_000_000L) { "Audio decoding timed out." }
                    if (!inputEnded) {
                        val index = codec.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val buffer = requireNotNull(codec.getInputBuffer(index))
                            val time = extractor.sampleTime
                            val size = if (time < 0 || time >= untilUs) -1 else extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                codec.queueInputBuffer(index, 0, size, time, 0)
                                extractor.advance()
                            }
                            lastProgress = System.nanoTime()
                        }
                    }
                    when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            require(count == 0) { "The source changes format during the selection." }
                            val output = codec.outputFormat
                            rate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            encoding = if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) output.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                else AudioFormat.ENCODING_PCM_16BIT
                            require(rate in 8_000..192_000 && channels in 1..2) { "Unsupported comparison format." }
                            data = DoubleArray(minOf(rate * 20, MAX_SOURCE_FRAMES) * 2)
                            untilUs = minOf(requestedStartUs + 15_000_000L, decodeStartUs + MAX_SOURCE_FRAMES * 1_000_000L / rate)
                        }
                        in 0..Int.MAX_VALUE -> {
                            val buffer = requireNotNull(codec.getOutputBuffer(index))
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            if (info.size > 0) accept(buffer, info.presentationTimeUs)
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(index, false)
                            lastProgress = System.nanoTime()
                        }
                    }
                }
            }
            require(count >= rate * 2) { "The selection needs at least one second of audio." }
            val warmup = (((requestedStartUs - firstUs).coerceAtLeast(0) * rate + 500_000) / 1_000_000).toInt()
            return ComparisonSource(rate, data.copyOf(count), warmup, encoding)
        } finally {
            decoder?.let { runCatching { it.stop() }; it.release() }
            extractor.release()
        }
    }

    private fun bytesPerSample(encoding: Int): Int = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> 1
        AudioFormat.ENCODING_PCM_16BIT -> 2
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
        AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 4
        else -> 0
    }

    private fun sample(buffer: ByteBuffer, encoding: Int): Double = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> ((buffer.get().toInt() and 255) - 128) / 128.0
        AudioFormat.ENCODING_PCM_16BIT -> buffer.short / 32768.0
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
            val value = (buffer.get().toInt() and 255) or ((buffer.get().toInt() and 255) shl 8) or (buffer.get().toInt() shl 16)
            value / 8388608.0
        }
        AudioFormat.ENCODING_PCM_32BIT -> buffer.int / 2147483648.0
        AudioFormat.ENCODING_PCM_FLOAT -> buffer.float.toDouble()
        else -> error("Unsupported decoded PCM encoding.")
    }
}
