package com.aurora.music.data

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder

/**
 * Decodes a local audio file to interleaved 16-bit PCM via [MediaExtractor] + [MediaCodec], streaming
 * the samples to a callback so long tracks never sit fully in memory. Used by the offline ReplayGain
 * scan and the AcoustID fingerprinter. Synchronous; call off the main thread.
 */
object AudioDecoder {

    /** Reported once before the first PCM callback. */
    fun interface FormatSink { fun onFormat(sampleRate: Int, channels: Int) }
    /** Interleaved little-endian 16-bit PCM; only the first [length] shorts are valid. */
    fun interface PcmSink { fun onPcm(pcm: ShortArray, length: Int) }

    /**
     * @return true if the stream decoded to its end, false on no audio track / error / cancellation.
     */
    fun decode(
        path: String,
        onFormat: FormatSink,
        onPcm: PcmSink,
        isCancelled: () -> Boolean = { false },
    ): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(path)
            var trackIndex = -1
            var inFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i; inFormat = f; break
                }
            }
            if (trackIndex < 0 || inFormat == null) return false
            extractor.selectTrack(trackIndex)
            decodeTrack(extractor, inFormat, onFormat, onPcm, isCancelled)
        } catch (t: Throwable) {
            android.util.Log.w("AudioDecoder", "decode($path) failed: ${t.message}")
            false
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun decodeTrack(
        extractor: MediaExtractor,
        inFormat: MediaFormat,
        onFormat: FormatSink,
        onPcm: PcmSink,
        isCancelled: () -> Boolean,
    ): Boolean {
        val mime = inFormat.getString(MediaFormat.KEY_MIME) ?: return false
        val codec = MediaCodec.createDecoderByType(mime)
        var sampleRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var formatReported = false
        return try {
            codec.configure(inFormat, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false
            while (!sawOutputEOS && !isCancelled()) {
                if (!sawInputEOS) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx >= 0 -> {
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEOS = true
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val sb = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val n = sb.remaining()
                            val shorts = ShortArray(n)
                            sb.get(shorts)
                            if (!formatReported) { onFormat.onFormat(sampleRate, channels); formatReported = true }
                            onPcm.onPcm(shorts, n)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val nf = codec.outputFormat
                        runCatching { sampleRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE) }
                        runCatching { channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                    }
                }
            }
            sawOutputEOS
        } catch (t: Throwable) {
            android.util.Log.w("AudioDecoder", "decodeTrack failed: ${t.message}")
            false
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }
}
