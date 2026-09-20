package com.aurora.music.playback.dsd

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.aurora.music.playback.usb.UsbPcmQueue
import com.aurora.music.playback.usb.UsbPcmStatus
import com.aurora.music.playback.usb.UsbPcmTransport
import java.nio.ByteBuffer

data class DsdUsbTelemetry(val source: Format? = null, val wire: DsdWireFormat? = null,
    val carrierRate: Int = 0, val containerBits: Int = 0, val active: Boolean = false,
    val status: UsbPcmStatus = UsbPcmStatus(), val failure: String? = null, val transportDetail: String? = null)

@UnstableApi
class RawDsdAudioSink(delegate: AudioSink, private val wire: DsdWireFormat,
    private val factory: () -> UsbPcmTransport, private val changed: () -> Unit = {}) : ForwardingAudioSink(delegate) {
    private var format: Format? = null
    private var pending: Format? = null
    private var transport: UsbPcmTransport? = null
    private var queue: UsbPcmQueue? = null
    private var packer: DsdUsbPacker? = null
    private var output: ByteBuffer? = null
    private var playing = false
    private var ending = false
    private var firstUs = C.TIME_UNSET
    private var inputBytes = 0L
    private var position = AudioSink.CURRENT_POSITION_NOT_SET
    @Volatile private var evidence = DsdUsbTelemetry()
    val telemetry get() = evidence.copy(status = transport?.status() ?: evidence.status)

    override fun supportsFormat(format: Format) = format.sampleMimeType == RawDsdAudioRenderer.MIME
    override fun getFormatSupport(format: Format) = if (supportsFormat(format)) AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY else AudioSink.SINK_FORMAT_UNSUPPORTED
    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        if (!supportsFormat(inputFormat) || outputChannels != null)
            throw AudioSink.ConfigurationException("Invalid raw DSD output.", inputFormat)
        pending = inputFormat
    }

    private fun activate() {
        val next = pending ?: format ?: return
        closeTransport()
        format = next; pending = null; ending = false; firstUs = C.TIME_UNSET; inputBytes = 0
        position = AudioSink.CURRENT_POSITION_NOT_SET
        val candidate = factory()
        try {
            val caps = candidate.capabilities(next)
            val rate = caps.rates.single()
            candidate.start(next.buildUpon().setSampleRate(rate).build())
            transport = candidate
            packer = DsdUsbPacker(next.channelCount, wire, caps.containerBits / 8)
            queue = UsbPcmQueue(candidate, C.ENCODING_INVALID, next.channelCount * caps.containerBits / 8).also { if (playing) it.play() }
            evidence = DsdUsbTelemetry(next, wire, rate, caps.containerBits, true, transportDetail = caps.detail)
        } catch (failure: Exception) {
            runCatching { candidate.close() }
            evidence = DsdUsbTelemetry(source = next, wire = wire, failure = failure.message ?: "DSD USB output is unavailable.")
            changed()
            throw AudioSink.ConfigurationException(evidence.failure!!, next)
        }
        changed()
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (pending != null && inputBytes > 0 && queue != null) {
            playToEndOfStream()
            if (!isEnded()) return false
        }
        if (pending != null || queue == null) activate()
        checkFailure()
        if (firstUs == C.TIME_UNSET) firstUs = presentationTimeUs
        if (!drain()) return false
        while (buffer.hasRemaining()) {
            val count = minOf(8192, buffer.remaining())
            val bytes = ByteArray(count)
            buffer.get(bytes)
            inputBytes += count
            output = ByteBuffer.wrap(checkNotNull(packer).pack(bytes))
            if (!drain()) return false
        }
        return true
    }

    private fun drain(): Boolean {
        val data = output ?: return true
        while (data.hasRemaining()) {
            if (queue?.offer(data) != true) { checkFailure(); return false }
        }
        output = null
        return true
    }

    private fun checkFailure() {
        val reason = queue?.failure?.message ?: transport?.status()?.error ?: return
        evidence = evidence.copy(active = false, failure = reason)
        changed()
        throw AudioSink.WriteException(-1, checkNotNull(format), false)
    }

    override fun playToEndOfStream() {
        if (queue == null) return
        checkFailure()
        if (!drain()) return
        if (!ending) { output = ByteBuffer.wrap(checkNotNull(packer).finish()); ending = true }
        if (drain()) queue?.end()
    }
    override fun isEnded() = queue == null || ending && queue?.finished == true
    override fun hasPendingData() = output?.hasRemaining() == true || queue?.pending == true || inputBytes > 0 && !isEnded()
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (firstUs == C.TIME_UNSET || evidence.carrierRate <= 0) return AudioSink.CURRENT_POSITION_NOT_SET
        val source = checkNotNull(DsdSourceInfo.from(format))
        val elapsed = (transport?.status()?.completedFrames ?: 0) * 1_000_000L / evidence.carrierRate
        val duration = inputBytes / source.channels * 8 * 1_000_000L / source.bitRate
        position = maxOf(position, firstUs + minOf(elapsed, duration))
        return position
    }
    override fun play() { playing = true; queue?.play() }
    override fun pause() { playing = false; queue?.pause() }
    override fun setVolume(volume: Float) = Unit
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) = Unit
    override fun getPlaybackParameters() = PlaybackParameters.DEFAULT
    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) = Unit
    override fun getSkipSilenceEnabled() = false
    override fun handleDiscontinuity() = Unit
    override fun flush() { closeTransport(); ending = false; firstUs = C.TIME_UNSET; inputBytes = 0; super.flush() }
    override fun reset() { flush(); format = null; pending = null; playing = false; evidence = DsdUsbTelemetry(); super.reset(); changed() }
    override fun release() { closeTransport(); super.release() }
    private fun closeTransport() {
        val old = queue
        queue = null; output = null; packer = null
        try { if (old != null) old.close() else transport?.close() } finally {
            transport = null; evidence = evidence.copy(active = false); changed()
        }
    }
}
