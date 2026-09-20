package com.aurora.music.playback.dsd

import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.CryptoConfig
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DecoderAudioRenderer

@UnstableApi
class RawDsdAudioRenderer(handler: Handler, listener: AudioRendererEventListener, sink: AudioSink) :
    DecoderAudioRenderer<RawDsdAudioRenderer.Passthrough>(handler, listener, sink) {
    override fun getName() = "AuroraRawDsd"
    override fun supportsFormatInternal(format: Format): Int = when {
        format.sampleMimeType != MIME -> C.FORMAT_UNSUPPORTED_TYPE
        format.cryptoType != C.CRYPTO_TYPE_NONE -> C.FORMAT_UNSUPPORTED_DRM
        DsdSourceInfo.from(format) == null -> C.FORMAT_UNSUPPORTED_SUBTYPE
        else -> C.FORMAT_HANDLED
    }
    override fun createDecoder(format: Format, cryptoConfig: CryptoConfig?) = Passthrough(format)
    override fun getOutputFormat(decoder: Passthrough): Format = decoder.format

    class Failure(message: String, cause: Throwable? = null) : DecoderException(message, cause)
    @Suppress("UNCHECKED_CAST")
    class Passthrough(val format: Format) : SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, Failure>(
        arrayOfNulls<DecoderInputBuffer>(4) as Array<DecoderInputBuffer>,
        arrayOfNulls<SimpleDecoderOutputBuffer>(4) as Array<SimpleDecoderOutputBuffer>) {
        init { setInitialInputBufferSize(8192) }
        override fun getName() = "Aurora DSD passthrough"
        override fun createInputBuffer() = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
        override fun createOutputBuffer() = SimpleDecoderOutputBuffer(::releaseOutputBuffer)
        override fun createUnexpectedDecodeException(error: Throwable) = Failure("DSD transfer failed.", error)
        override fun decode(input: DecoderInputBuffer, output: SimpleDecoderOutputBuffer, reset: Boolean): Failure? {
            val source = input.data ?: return Failure("Missing DSD data.")
            if (source.remaining() > 8192 || source.remaining() % format.channelCount != 0) return Failure("Invalid DSD block.")
            output.init(input.timeUs, source.remaining()).put(source).flip()
            return null
        }
    }
    companion object { const val MIME = "audio/x-aurora-dsd" }
}
