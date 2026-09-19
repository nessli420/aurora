package com.aurora.music.playback.usb

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer

data class ProcessedUsbTelemetry(val active: Boolean = false, val source: Format? = null,
    val output: Format? = null, val validBits: Int? = null, val containerBits: Int? = null,
    val softwareGain: Double = 1.0, val status: UsbPcmStatus = UsbPcmStatus(), val fallbackReason: String? = null)

@UnstableApi
class ProcessedUsbAudioSink(
    delegate: AudioSink,
    val processor: UsbGraphProcessor,
    private val transportFactory: () -> UsbPcmTransport,
    private val allowAndroidFallback: Boolean,
    private val volumeScale: () -> Double = { 1.0 },
    private val changed: () -> Unit = {},
) : ForwardingAudioSink(delegate) {
    private data class Configuration(val format: Format, val bufferSize: Int, val channelMap: IntArray?)
    private var current: Configuration? = null
    private var pending: Configuration? = null
    private var transport: UsbPcmTransport? = null
    private var queue: UsbPcmQueue? = null
    private var playing = false
    private var inputSeen = false
    private var ending = false
    private var queueEnded = false
    private var firstTimeUs = C.TIME_UNSET
    private var lastPositionUs = AudioSink.CURRENT_POSITION_NOT_SET
    private var outputStreamOffsetUs = 0L
    private var playerVolume = 1f
    @Volatile private var trimNext = true
    @Volatile private var forcedFallback: String? = null
    @Volatile private var lastFailure: String? = null
    @Volatile private var evidence = ProcessedUsbTelemetry()
    val telemetry: ProcessedUsbTelemetry get() = evidence.copy(status = transport?.status() ?: evidence.status,
        softwareGain = processor.volume, fallbackReason = evidence.fallbackReason ?: lastFailure)

    override fun getFormatSupport(format: Format): Int = if (format.sampleMimeType == androidx.media3.common.MimeTypes.AUDIO_RAW && format.channelCount in 1..2 &&
        UsbGraphProcessor.encoding(format.pcmEncoding) != null) AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        else AudioSink.SINK_FORMAT_UNSUPPORTED
    override fun supportsFormat(format: Format) = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        pending = Configuration(inputFormat, specifiedBufferSize, outputChannels?.copyOf())
        trimNext = true
        if (current == null || !inputSeen) {
            activate()
        }
    }

    private fun activate() {
        val config = pending ?: return
        closeTransport()
        current = config; pending = null
        ending = false; queueEnded = false; inputSeen = false; firstTimeUs = C.TIME_UNSET
        lastPositionUs = AudioSink.CURRENT_POSITION_NOT_SET
        var candidate: UsbPcmTransport? = null
        try {
            forcedFallback?.let { error(it) }
            val opened = transportFactory().also { candidate = it }
            require(config.channelMap == null || config.channelMap.contentEquals(IntArray(config.format.channelCount) { it })) { "USB channel mapping is unsupported." }
            val caps = opened.capabilities(config.format)
            val output = processor.configure(config.format, caps.rates, caps.validBits, trimNext)
            opened.start(output)
            transport = opened
            queue = UsbPcmQueue(opened, output.pcmEncoding, checkNotNull(UsbGraphProcessor.encoding(output.pcmEncoding)).bytesPerSample * 2)
                .also { if (playing) it.play() }
            lastFailure = null
            evidence = ProcessedUsbTelemetry(true, config.format, output, caps.validBits, caps.containerBits)
        } catch (failure: Exception) {
            runCatching { candidate?.close() }
            transport = null
            lastFailure = failure.message ?: "USB output unavailable."
            evidence = ProcessedUsbTelemetry(source = config.format, fallbackReason = lastFailure)
            changed()
            if (!allowAndroidFallback) throw AudioSink.ConfigurationException(evidence.fallbackReason!!, config.format)
            super.configure(config.format, config.bufferSize, config.channelMap)
            if (playing) super.play()
        }
        changed()
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (pending != null) {
            if (inputSeen && queue != null) {
                endUsb()
                if (!checkNotNull(queue).finished) return false
            } else if (inputSeen) {
                super.playToEndOfStream()
                if (!super.isEnded()) return false
            }
            activate()
        }
        if (queue == null) { inputSeen = true; return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount) }
        checkFailure()
        if (firstTimeUs == C.TIME_UNSET) firstTimeUs = presentationTimeUs
        inputSeen = true
        processor.volume = playerVolume.toDouble().coerceIn(0.0, 1.0) * volumeScale().coerceIn(0.0, 1.0)
        repeat(16) {
            if (!drainOutput()) return false
            if (!buffer.hasRemaining()) return true
            processor.queueInput(buffer, presentationTimeUs, presentationTimeUs - outputStreamOffsetUs)
        }
        return !buffer.hasRemaining()
    }

    private fun drainOutput(): Boolean {
        val worker = queue ?: return true
        repeat(16) {
            val output = processor.getOutput() ?: return true
            if (!worker.offer(output)) { checkFailure(); return false }
        }
        return false
    }

    private fun checkFailure() {
        val failure = queue?.failure
        val reported = transport?.status()?.error
        if (failure != null || reported != null) {
            lastFailure = reported ?: failure?.message ?: "USB output failed."
            evidence = evidence.copy(fallbackReason = lastFailure)
            changed()
            throw AudioSink.WriteException(-1, checkNotNull(current).format, false)
        }
    }

    private fun endUsb() {
        checkFailure()
        if (!ending) { processor.queueEndOfStream(); ending = true }
        if (!drainOutput()) return
        if (processor.isEnded && !queueEnded) { queue?.end(); queueEnded = true }
    }

    override fun playToEndOfStream() { if (queue == null) super.playToEndOfStream() else endUsb() }
    override fun isEnded(): Boolean = if (queue == null) super.isEnded() else queueEnded && queue?.finished == true
    override fun hasPendingData(): Boolean = if (queue == null) super.hasPendingData() else
        queue?.pending == true || (inputSeen && !isEnded())

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (queue == null) return super.getCurrentPositionUs(sourceEnded)
        if (firstTimeUs == C.TIME_UNSET) return AudioSink.CURRENT_POSITION_NOT_SET
        val output = checkNotNull(evidence.output)
        val completed = transport?.status()?.completedFrames ?: 0L
        val latencyUs = processor.engine.rackLatencyFrames * 1_000_000L / checkNotNull(current).format.sampleRate
        val elapsed = (completed * 1_000_000L / output.sampleRate - latencyUs).coerceIn(0, processor.inputDurationUs)
        val position = firstTimeUs + elapsed
        lastPositionUs = maxOf(lastPositionUs, position)
        return lastPositionUs
    }

    override fun play() { playing = true; queue?.play() ?: super.play() }
    override fun pause() { playing = false; queue?.pause() ?: super.pause() }
    override fun setVolume(volume: Float) { playerVolume = volume; super.setVolume(volume) }
    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        this.outputStreamOffsetUs = outputStreamOffsetUs
        super.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        super.setPlaybackParameters(if (queue != null) PlaybackParameters.DEFAULT else playbackParameters)
    }
    override fun getPlaybackParameters(): PlaybackParameters = if (queue != null) PlaybackParameters.DEFAULT else super.getPlaybackParameters()
    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) { super.setSkipSilenceEnabled(if (queue == null) skipSilenceEnabled else false) }
    override fun getSkipSilenceEnabled(): Boolean = queue == null && super.getSkipSilenceEnabled()

    fun useAndroidAfterFailure(): Boolean {
        if (!allowAndroidFallback || forcedFallback != null || lastFailure == null) return false
        forcedFallback = lastFailure
        return true
    }
    override fun flush() {
        val hadInput = inputSeen
        closeTransport()
        processor.flush()
        super.flush()
        pending = pending ?: current
        current = null
        inputSeen = false; ending = false; queueEnded = false
        firstTimeUs = C.TIME_UNSET; lastPositionUs = AudioSink.CURRENT_POSITION_NOT_SET
        trimNext = !hadInput
    }
    override fun reset() {
        closeTransport(); processor.reset(); super.reset()
        current = null; pending = null; inputSeen = false; ending = false; trimNext = true
        outputStreamOffsetUs = 0L
        evidence = ProcessedUsbTelemetry()
    }
    override fun release() { closeTransport(); processor.reset(); super.release() }
    private fun closeTransport() {
        val worker = queue
        if (worker != null) worker.close() else transport?.close()
        queue = null
        transport = null
        evidence = evidence.copy(active = false)
    }
}
