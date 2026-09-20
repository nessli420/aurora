package com.aurora.music.playback.network.audio

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import com.aurora.music.playback.engine.PcmEncoding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

data class DirectNetworkAudio(val file: File, val mimeType: String, val durationMs: Long,
    val summary: String = "Original file. Aurora DSP is bypassed.")

@OptIn(UnstableApi::class)
object DirectNetworkPreparer {
    suspend fun prepare(
        context: Context,
        mediaItem: MediaItem,
        dataSourceFactory: DataSource.Factory,
        outputDirectory: File = File(context.cacheDir, "network-audio"),
        onProgress: (Long) -> Unit = {},
    ): DirectNetworkAudio {
        var published: File? = null
        try {
            return withContext(Dispatchers.IO) {
                NetworkSourceCopy.validate(mediaItem)
                val coroutine = currentCoroutineContext()
                val started = System.nanoTime()
                val checkCancelled = {
                    coroutine.ensureActive()
                    if (System.nanoTime() - started > 600_000_000_000L) throw NetworkRenderingException("Audio preparation timed out.")
                }
                if (!outputDirectory.isDirectory && !outputDirectory.mkdirs()) throw NetworkRenderingException("Audio storage is unavailable.")
                val id = UUID.randomUUID().toString()
                val source = File(outputDirectory, "$id.source")
                var result: File? = null
                var complete = false
                try {
                    NetworkSourceCopy.copy(mediaItem, dataSourceFactory, source, checkCancelled, onProgress)
                    checkCancelled()
                    val (mime, duration) = inspect(source)
                    checkCancelled()
                    val destination = File(outputDirectory, "$id.${extension(mime)}")
                    result = destination
                    if (!source.renameTo(destination)) throw NetworkRenderingException("The source could not be saved.")
                    published = destination
                    complete = true
                    DirectNetworkAudio(destination, mime, duration)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: NetworkRenderingException) {
                    throw failure
                } catch (_: Exception) {
                    throw NetworkRenderingException("The source format could not be shared.")
                } finally {
                    source.delete()
                    if (!complete) result?.delete()
                }
            }
        } catch (cancelled: CancellationException) {
            published?.delete()
            throw cancelled
        }
    }

    private fun inspect(file: File): Pair<String, Long> {
        NetworkWaveSource.inspect(file)?.let { wave ->
            if (wave.sampleRate > 96_000 || wave.encoding !in setOf(PcmEncoding.SIGNED_16_LE, PcmEncoding.SIGNED_24_LE)) {
                throw NetworkRenderingException("Direct network output supports 16/24-bit WAV up to 96 kHz.")
            }
            return "audio/wav" to wave.dataBytes / wave.frameBytes * 1000 / wave.sampleRate
        }
        val prefix = RandomAccessFile(file, "r").use { input ->
            ByteArray(minOf(16L, input.length()).toInt()).also(input::readFully)
        }
        fun starts(value: String) = prefix.size >= value.length &&
            value.indices.all { prefix[it] == value[it].code.toByte() }
        if (starts("#EXTM3U") || starts("<")) throw NetworkRenderingException("Live and segmented sources cannot be shared.")
        if (starts("DSD ") || starts("FRM8")) throw NetworkRenderingException("DSD network output is not supported.")
        val mp4 = prefix.size >= 8 && String(prefix, 4, 4, Charsets.US_ASCII) == "ftyp"
        val flac = starts("fLaC")
        if (flac) return inspectFlac(file)
        val ogg = starts("OggS")
        val mpegHeader = starts("ID3") || prefix.size >= 2 && prefix[0] == 0xff.toByte() && (prefix[1].toInt() and 0xe0) == 0xe0
        val adtsHeader = prefix.size >= 2 && prefix[0] == 0xff.toByte() && (prefix[1].toInt() and 0xf6) == 0xf0
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            if (extractor.drmInitData != null) throw NetworkRenderingException("Protected tracks cannot be shared.")
            val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            if (formats.any { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }) {
                throw NetworkRenderingException("Direct network output supports audio files only.")
            }
            val audio = formats.singleOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw NetworkRenderingException("The source has no supported audio track.")
            val codec = audio.getString(MediaFormat.KEY_MIME)
            val mime = when {
                mp4 && codec in setOf("audio/mp4a-latm", "audio/mpeg") -> "audio/mp4"
                ogg && codec in setOf("audio/vorbis", "audio/opus") -> "audio/ogg"
                mpegHeader && codec == "audio/mpeg" -> "audio/mpeg"
                adtsHeader && codec == "audio/mp4a-latm" -> "audio/aac"
                else -> throw NetworkRenderingException("This source format cannot be shared directly.")
            }
            val rate = if (audio.containsKey(MediaFormat.KEY_SAMPLE_RATE)) audio.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0
            val channels = if (audio.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) audio.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 0
            if (channels !in 1..2 || rate !in 8_000..48_000) {
                throw NetworkRenderingException("Direct compressed output supports mono or stereo audio up to 48 kHz.")
            }
            val duration = if (audio.containsKey(MediaFormat.KEY_DURATION)) audio.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(0) / 1000 else 0
            return mime to duration
        } finally { extractor.release() }
    }

    private fun inspectFlac(file: File): Pair<String, Long> = RandomAccessFile(file, "r").use { input ->
        if (input.length() < 42) throw NetworkRenderingException("The FLAC file is incomplete.")
        input.seek(4)
        val block = input.readInt()
        if (block and 0x7fffffff != 34) throw NetworkRenderingException("The FLAC metadata is unsupported.")
        input.seek(18)
        val info = input.readLong()
        val rate = (info ushr 44).toInt()
        val channels = ((info ushr 41) and 7).toInt() + 1
        val bits = ((info ushr 36) and 31).toInt() + 1
        val frames = info and 0xfffffffffL
        if (rate !in 8_000..96_000 || channels !in 1..2 || bits !in 4..24 || frames == 0L) {
            throw NetworkRenderingException("Direct network output supports mono or stereo FLAC up to 96 kHz and 24-bit.")
        }
        "audio/flac" to frames * 1000 / rate
    }

    private fun extension(mime: String) = when (mime) {
        "audio/wav" -> "wav"
        "audio/mpeg" -> "mp3"
        "audio/flac" -> "flac"
        "audio/ogg" -> "ogg"
        "audio/mp4" -> "m4a"
        else -> "aac"
    }
}
