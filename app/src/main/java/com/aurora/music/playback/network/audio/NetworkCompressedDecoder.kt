package com.aurora.music.playback.network.audio

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.ffmpeg.NetworkFfmpegBridge
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.File
import java.io.EOFException
import java.nio.ByteBuffer

@OptIn(UnstableApi::class)
internal object NetworkCompressedDecoder {
    fun decode(file: File, checkCancelled: () -> Unit, onFormat: (Format) -> Unit, onPcm: (ByteBuffer) -> Unit) {
        var extractor: Extractor? = null
        var candidates = emptyArray<Extractor>()
        val source = FileDataSource()
        var decoder: NetworkFfmpegBridge? = null
        var sourceFormat: Format? = null
        var outputConfigured = false
        var outputEnded = false
        var lastProgress = System.nanoTime()
        fun checkProgress() {
            checkCancelled()
            if (System.nanoTime() - lastProgress > 15_000_000_000L) throw NetworkRenderingException("Audio decoding timed out.")
        }
        fun drain(): Boolean {
            val active = decoder ?: return false
            var progressed = false
            while (true) {
                checkProgress()
                val output = active.output() ?: return progressed
                try {
                    if (output.isEndOfStream) outputEnded = true
                    val bytes = output.data
                    if (!output.isEndOfStream && bytes != null && bytes.hasRemaining()) {
                        if (!outputConfigured) {
                            onFormat(checkNotNull(sourceFormat).buildUpon().setSampleMimeType("audio/raw")
                                .setSampleRate(active.sampleRate()).setChannelCount(active.channels()).setPcmEncoding(active.encoding()).build())
                            outputConfigured = true
                        }
                        onPcm(bytes)
                    }
                    lastProgress = System.nanoTime()
                    progressed = true
                } finally { output.release() }
            }
        }
        fun input(): DecoderInputBuffer {
            while (true) {
                checkProgress()
                checkNotNull(decoder).input()?.let { return it }
                if (!drain()) Thread.sleep(1)
            }
        }
        val sink = object : TrackOutput {
            val pending = ByteArray(2 * 1024 * 1024)
            var count = 0
            override fun format(format: Format) {
                require(format.sampleRate in 8_000..192_000 && format.channelCount in 1..2)
                if (format.drmInitData != null) throw NetworkRenderingException("Protected tracks cannot be rendered.")
                if (format.sampleMimeType == "audio/opus") {
                    throw NetworkRenderingException("Processed output does not support Opus. Use direct output or local playback.")
                }
                if (!FfmpegLibrary.supportsFormat(checkNotNull(format.sampleMimeType))) throw NetworkRenderingException("The audio codec is unsupported.")
                if (format.pcmEncoding == C.ENCODING_PCM_32BIT) throw NetworkRenderingException("This decoder cannot preserve the source precision.")
                check(decoder == null) { "The source changes audio format." }
                sourceFormat = if (format.sampleMimeType == "audio/mpeg" && (format.encoderDelay > 0 || format.encoderPadding > 0)) {
                    if (format.encoderPadding < 529) throw NetworkRenderingException("The track has unsupported gapless metadata.")
                    // ffmpeg's synthesis delay shifts the trim boundary.
                    format.buildUpon().setEncoderDelay(format.encoderDelay.coerceAtLeast(0) + 529)
                        .setEncoderPadding(format.encoderPadding - 529).build()
                } else format
                decoder = NetworkFfmpegBridge(format)
            }
            override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
                checkProgress()
                require(length >= 0 && length <= pending.size - count) { "Audio packet exceeds the size limit." }
                val read = input.read(pending, count, length)
                if (read == C.RESULT_END_OF_INPUT) {
                    if (allowEndOfInput) return read
                    throw NetworkRenderingException("The audio file is incomplete.")
                }
                count += read
                return read
            }
            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
                checkProgress()
                require(length >= 0 && length <= pending.size - count) { "Audio packet exceeds the size limit." }
                data.readBytes(pending, count, length)
                count += length
            }
            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                require(cryptoData == null && size >= 0 && offset in 0..count && size <= count - offset)
                val packet = input()
                packet.clear()
                packet.ensureSpaceForWrite(size)
                checkNotNull(packet.data).put(pending, count - offset - size, size)
                packet.timeUs = timeUs
                packet.flip()
                checkNotNull(decoder).queue(packet)
                if (offset > 0) pending.copyInto(pending, 0, count - offset, count)
                count = offset
                lastProgress = System.nanoTime()
                drain()
            }
        }
        try {
            var length = source.open(DataSpec(Uri.fromFile(file)))
            var reader = DefaultExtractorInput(source, 0, length)
            candidates = DefaultExtractorsFactory().createExtractors()
            extractor = candidates.firstOrNull { candidate ->
                checkProgress()
                try { candidate.sniff(reader) } catch (_: EOFException) { false }
                finally { reader.resetPeekPosition() }
            } ?: throw NetworkRenderingException("The audio container is unsupported.")
            var audioTrack = -1
            extractor.init(object : ExtractorOutput {
                override fun track(id: Int, type: Int): TrackOutput {
                    if (type != C.TRACK_TYPE_AUDIO) return DiscardingTrackOutput()
                    if (audioTrack < 0) audioTrack = id
                    return if (audioTrack == id) sink else DiscardingTrackOutput()
                }
                override fun endTracks() = Unit
                override fun seekMap(seekMap: SeekMap) = Unit
            })
            val seek = PositionHolder()
            while (true) {
                checkProgress()
                val result = extractor.read(reader, seek)
                if (result == Extractor.RESULT_END_OF_INPUT) break
                if (result == Extractor.RESULT_SEEK) {
                    require(seek.position in 0..file.length())
                    source.close()
                    length = source.open(DataSpec.Builder().setUri(Uri.fromFile(file)).setPosition(seek.position).build())
                    reader = DefaultExtractorInput(source, seek.position, if (length == C.LENGTH_UNSET.toLong()) length else seek.position + length)
                }
            }
            val end = input()
            end.clear()
            end.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
            checkNotNull(decoder).queue(end)
            while (!outputEnded) if (!drain()) Thread.sleep(1)
        } finally {
            decoder?.close()
            candidates.forEach { it.release() }
            source.close()
        }
    }
}
