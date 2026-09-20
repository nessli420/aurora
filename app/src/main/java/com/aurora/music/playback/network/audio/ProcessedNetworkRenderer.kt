package com.aurora.music.playback.network.audio

import android.content.Context
import android.media.AudioFormat
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import com.aurora.music.data.AudioPrefs
import com.aurora.music.data.DspMode
import com.aurora.music.data.ProcessingPlaybackPrefs
import com.aurora.music.data.ProcessingRack
import com.aurora.music.data.ProcessingSnapshot
import com.aurora.music.playback.ImpulseResponse
import com.aurora.music.playback.engine.AudioBlock
import com.aurora.music.playback.engine.AudioStreamFormat
import com.aurora.music.playback.engine.ChannelLayout
import com.aurora.music.playback.engine.PcmBoundary
import com.aurora.music.playback.engine.PcmEncoding
import com.aurora.music.playback.engine.ProductionSerialRack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.UUID

class NetworkRenderingException(message: String) : Exception(message)

data class RenderedNetworkAudio(
    val file: File,
    val durationMs: Long,
    val sourceFrames: Long,
    val sourceSampleRate: Int,
    val graphLatencyFrames: Int,
    val graphTailFrames: Int,
    val processingSummary: String,
    val modified: Boolean,
    val sampleRate: Int = NetworkWaveWriter.SAMPLE_RATE,
    val channels: Int = 2,
    val bits: Int = 16,
    val mimeType: String = NetworkWaveWriter.MIME_TYPE,
)

@OptIn(UnstableApi::class)
object ProcessedNetworkRenderer {
    val drySnapshot: ProcessingSnapshot get() = ProcessingSnapshot(
        AudioPrefs(dspMode = DspMode.OFF, dspConvEnabled = false), ProcessingPlaybackPrefs(),
        rack = ProcessingRack(),
    )

    suspend fun render(
        context: Context,
        mediaItem: MediaItem,
        dataSourceFactory: DataSource.Factory,
        snapshot: ProcessingSnapshot,
        impulses: Map<String, ImpulseResponse> = emptyMap(),
        legacyImpulse: ImpulseResponse? = null,
        relativeVolume: Double = 1.0,
        outputGain: Double = 1.0,
        outputDirectory: File = File(context.cacheDir, "network-audio"),
        onProgress: (Long) -> Unit = {},
    ): RenderedNetworkAudio {
        var published: File? = null
        try {
            return withContext(Dispatchers.IO) {
                val coroutine = currentCoroutineContext()
                val started = System.nanoTime()
                val checkCancelled = {
                    coroutine.ensureActive()
                    if (System.nanoTime() - started > 600_000_000_000L) throw NetworkRenderingException("Audio preparation timed out.")
                }
                if (snapshot.audio.dspMode == DspMode.SYSTEM) throw NetworkRenderingException("Use Custom DSP or Off for processed output.")
                if (!relativeVolume.isFinite() || relativeVolume !in 0.0..1.0 || !outputGain.isFinite() || outputGain !in 0.0..1.0) {
                    throw NetworkRenderingException("Invalid network output volume.")
                }
                NetworkSourceCopy.validate(mediaItem)
                if (!outputDirectory.isDirectory && !outputDirectory.mkdirs()) throw NetworkRenderingException("Audio storage is unavailable.")
                val id = UUID.randomUUID().toString()
                val source = File(outputDirectory, "$id.source")
                val partial = File(outputDirectory, "$id.partial")
                val destination = File(outputDirectory, "$id.wav")
                var complete = false
                try {
                    NetworkSourceCopy.copy(mediaItem, dataSourceFactory, source, checkCancelled)
                    checkCancelled()
                    val result = decode(source, partial, snapshot, impulses, legacyImpulse, relativeVolume, outputGain, checkCancelled, onProgress)
                    checkCancelled()
                    if (!partial.renameTo(destination)) throw NetworkRenderingException("Processed audio could not be saved.")
                    published = destination
                    complete = true
                    result.copy(file = destination)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: NetworkRenderingException) {
                    throw failure
                } catch (_: NetworkAudioSizeException) {
                    throw NetworkRenderingException("Processed audio exceeds the 512 MB limit.")
                } catch (failure: Exception) {
                    throw NetworkRenderingException("The track could not be decoded or processed.")
                } finally {
                    source.delete()
                    partial.delete()
                    if (!complete) destination.delete()
                }
            }
        } catch (cancelled: CancellationException) {
            published?.delete()
            throw cancelled
        }
    }

    private fun decode(
        source: File, destination: File, snapshot: ProcessingSnapshot,
        impulses: Map<String, ImpulseResponse>, legacyImpulse: ImpulseResponse?,
        relativeVolume: Double, outputGain: Double, checkCancelled: () -> Unit, onProgress: (Long) -> Unit,
    ): RenderedNetworkAudio {
        var writer: NetworkWaveWriter? = null
        try {
            val rack = if (snapshot.audio.dspMode == DspMode.CUSTOM && snapshot.rack.enabled) snapshot.rack
                else ProcessingRack.legacy(snapshot.audio, snapshot.playback.monoAudio).copy(enabled = true)
            var processor: NetworkPcmProcessor? = null
            var decoded: AudioBlock? = null
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var graph: ProductionSerialRack? = null
            fun configure(rate: Int, channels: Int, sampleEncoding: Int, delay: Int = 0, padding: Int = 0) {
                if (processor != null) throw NetworkRenderingException("Tracks that change audio format cannot be rendered.")
                if (rate !in 8_000..192_000 || channels !in 1..2) {
                    throw NetworkRenderingException("Processed output supports mono or stereo audio up to 192 kHz.")
                }
                encoding = sampleEncoding
                if (encoding != AudioFormat.ENCODING_PCM_8BIT && pcmEncoding(encoding) == null) {
                    throw NetworkRenderingException("The decoded sample format is unsupported.")
                }
                if (delay !in 0..131072 || padding !in 0..131072) throw NetworkRenderingException("The track has unsupported gapless metadata.")
                val prepared = try { ProductionSerialRack.compile(rack, rate, legacyImpulse, impulses, relativeVolume) }
                catch (_: Exception) { throw NetworkRenderingException("The processing graph is unavailable at this sample rate.") }
                if (prepared.convolutionUnavailableReason != null) throw NetworkRenderingException("An impulse response is unavailable.")
                graph = prepared
                val outputWriter = NetworkWaveWriter(destination, dither = snapshot.playback.outputRatePolicy.tpdfDither,
                    noiseShaping = snapshot.playback.outputRatePolicy.noiseShaping == true)
                writer = outputWriter
                processor = NetworkPcmProcessor(prepared, outputWriter, outputGain, delay, padding, checkCancelled)
                decoded = AudioBlock(AudioStreamFormat(rate, if (channels == 1) ChannelLayout.MONO else ChannelLayout.STEREO), 256)
            }
            fun accept(buffer: ByteBuffer) {
                val block = checkNotNull(decoded)
                val pcm = pcmEncoding(encoding)
                val stride = block.format.channelCount * (pcm?.bytesPerSample ?: 1)
                if (buffer.remaining() % stride != 0) throw NetworkRenderingException("The decoded audio has an incomplete frame.")
                while (buffer.hasRemaining()) {
                    checkCancelled()
                    block.begin(minOf(block.capacityFrames, buffer.remaining() / stride))
                    if (pcm != null) PcmBoundary.decode(buffer, pcm, block)
                    else for (index in 0 until block.sampleCount) block.samples[index] = ((buffer.get().toInt() and 255) - 128) / 128.0
                    checkNotNull(processor).append(block)
                }
                onProgress(checkNotNull(processor).sourceFrames * 1000 / block.format.sampleRate)
            }
            val wave = NetworkWaveSource.inspect(source)
            if (wave != null) {
                configure(wave.sampleRate, wave.channels, when (wave.encoding) {
                    PcmEncoding.SIGNED_16_LE -> AudioFormat.ENCODING_PCM_16BIT
                    PcmEncoding.SIGNED_24_LE -> AudioFormat.ENCODING_PCM_24BIT_PACKED
                    PcmEncoding.SIGNED_32_LE -> AudioFormat.ENCODING_PCM_32BIT
                    PcmEncoding.FLOAT_32_LE -> AudioFormat.ENCODING_PCM_FLOAT
                    PcmEncoding.FLOAT_64_LE -> FLOAT_64
                    null -> AudioFormat.ENCODING_PCM_8BIT
                })
                RandomAccessFile(source, "r").use { input ->
                    input.seek(wave.dataOffset)
                    val bytes = ByteArray(4096 * wave.frameBytes)
                    var remaining = wave.dataBytes
                    while (remaining > 0) {
                        checkCancelled()
                        val count = minOf(remaining, bytes.size.toLong()).toInt()
                        input.readFully(bytes, 0, count)
                        accept(ByteBuffer.wrap(bytes, 0, count))
                        remaining -= count
                    }
                }
            } else {
                NetworkCompressedDecoder.decode(source, checkCancelled, { format ->
                    configure(format.sampleRate, format.channelCount, format.pcmEncoding,
                        format.encoderDelay.coerceAtLeast(0), format.encoderPadding.coerceAtLeast(0))
                }, ::accept)
            }
            val rendered = processor ?: throw NetworkRenderingException("The track contains no audio.")
            if (rendered.sourceFrames == 0L) throw NetworkRenderingException("The track contains no audio.")
            rendered.finish()
            val prepared = checkNotNull(graph)
            val rate = prepared.format.sampleRate
            return RenderedNetworkAudio(destination, checkNotNull(writer).framesWritten * 1000 / NetworkWaveWriter.SAMPLE_RATE,
                rendered.sourceFrames, rate, rendered.graphLatencyFrames, rendered.graphTailFrames,
                prepared.description, prepared.processingChangesSamples || outputGain != 1.0 || rate != NetworkWaveWriter.SAMPLE_RATE ||
                    encoding != AudioFormat.ENCODING_PCM_16BIT || decoded?.format?.channelCount == 1 || snapshot.playback.outputRatePolicy.tpdfDither)
        } finally { runCatching { writer?.close() } }
    }
    private fun pcmEncoding(value: Int): PcmEncoding? = when (value) {
        AudioFormat.ENCODING_PCM_16BIT -> PcmEncoding.SIGNED_16_LE
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> PcmEncoding.SIGNED_24_LE
        AudioFormat.ENCODING_PCM_32BIT -> PcmEncoding.SIGNED_32_LE
        AudioFormat.ENCODING_PCM_FLOAT -> PcmEncoding.FLOAT_32_LE
        FLOAT_64 -> PcmEncoding.FLOAT_64_LE
        else -> null
    }
    private const val FLOAT_64 = -64
}
