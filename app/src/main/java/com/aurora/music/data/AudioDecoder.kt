package com.aurora.music.data

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder

// synchronous call off the main thread
object AudioDecoder {

    fun interface FormatSink { fun onFormat(sampleRate: Int, channels: Int) }
    // interleaved little-endian 16-bit pcm only the first length shorts are valid
    fun interface PcmSink { fun onPcm(pcm: ShortArray, length: Int) }

    fun decode(
        path: String,
        onFormat: FormatSink,
        onPcm: PcmSink,
        isCancelled: () -> Boolean = { false },
        context: android.content.Context? = null,
    ): Boolean {
        val extractor = MediaExtractor()
        return try {
            if (path.startsWith("content://") && context != null) {
                extractor.setDataSource(context, android.net.Uri.parse(path), null)
            } else if (path.startsWith("http://") || path.startsWith("https://")) {
                extractor.setDataSource(path, emptyMap())
            } else extractor.setDataSource(if (path.startsWith("file:")) android.net.Uri.parse(path).path ?: path else path)
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
            android.util.Log.w("AudioDecoder", "Decode failed: ${t.javaClass.simpleName}")
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
        var encoding = android.media.AudioFormat.ENCODING_PCM_16BIT
        var formatReported = false
        return try {
            inFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
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
                            outBuf.order(ByteOrder.LITTLE_ENDIAN)
                            val shorts = if (encoding == android.media.AudioFormat.ENCODING_PCM_FLOAT) {
                                val floats = outBuf.asFloatBuffer()
                                ShortArray(floats.remaining()) { (floats.get().coerceIn(-1f, 1f) * 32767).toInt().toShort() }
                            } else {
                                val sb = outBuf.asShortBuffer()
                                ShortArray(sb.remaining()).also { sb.get(it) }
                            }
                            val n = shorts.size
                            if (!formatReported) { onFormat.onFormat(sampleRate, channels); formatReported = true }
                            onPcm.onPcm(shorts, n)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val nf = codec.outputFormat
                        runCatching { sampleRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE) }
                        runCatching { channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                        runCatching { encoding = nf.getInteger(MediaFormat.KEY_PCM_ENCODING) }
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
