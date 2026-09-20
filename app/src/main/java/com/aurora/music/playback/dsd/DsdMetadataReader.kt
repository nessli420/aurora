package com.aurora.music.playback.dsd

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.WorkerThread
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.InterruptedIOException

@UnstableApi
data class DsdFileMetadata(val source: DsdSourceInfo, val durationUs: Long, val metadata: MediaMetadata)

@UnstableApi
object DsdMetadataReader {
    @WorkerThread
    fun read(context: Context, uri: Uri): DsdFileMetadata? {
        if (uri.scheme != "content" && uri.scheme != "file") return null
        val source = DefaultDataSource.Factory(context).createDataSource()
        val extractor = DsdExtractor(decodeAudio = false)
        val output = Output()
        val deadline = SystemClock.elapsedRealtime() + 2_000
        return try {
            val length = source.open(DataSpec(uri))
            var input = DefaultExtractorInput(source, 0, length)
            if (!extractor.sniff(input)) return null
            extractor.init(output)
            val holder = PositionHolder()
            var reads = 0
            while (output.durationUs == null) {
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
                if (++reads > 8_200 || SystemClock.elapsedRealtime() > deadline) return null
                when (extractor.read(input, holder)) {
                    Extractor.RESULT_SEEK -> {
                        source.close()
                        source.open(DataSpec.Builder().setUri(uri).setPosition(holder.position).build())
                        input = DefaultExtractorInput(source, holder.position, length)
                    }
                    Extractor.RESULT_END_OF_INPUT -> return null
                }
            }
            val format = output.format ?: return null
            val info = DsdSourceInfo.from(format) ?: return null
            val builder = MediaMetadata.Builder()
            format.metadata?.let { metadata -> repeat(metadata.length()) { metadata[it].populateMediaMetadata(builder) } }
            DsdFileMetadata(info, checkNotNull(output.durationUs), builder.build())
        } catch (failure: InterruptedIOException) {
            throw failure
        } catch (_: Exception) {
            null
        } finally {
            runCatching { source.close() }
            extractor.release()
        }
    }

    private class Output : ExtractorOutput, TrackOutput {
        var format: Format? = null
        var durationUs: Long? = null
        override fun track(id: Int, type: Int): TrackOutput {
            require(type == C.TRACK_TYPE_AUDIO)
            return this
        }
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) { durationUs = seekMap.durationUs }
        override fun format(format: Format) { this.format = format }
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int = error("Unexpected audio data.")
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) = error("Unexpected audio data.")
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) = error("Unexpected audio data.")
    }
}
