package com.aurora.music.playback.usb

import android.content.Context
import androidx.media3.common.Format
import com.decent.usbaudio.UsbAudioDevice
import com.decent.usbaudio.UsbAudioDeviceInfo
import com.decent.usbaudio.UsbAudioFormat
import com.decent.usbaudio.UsbAudioStream

class NativeUsbPcmTransport(context: Context) : UsbPcmTransport {
    private val device = UsbAudioDevice.getInstance(context)
    private var info: UsbAudioDeviceInfo? = null
    private var selected: UsbAudioFormat? = null
    private var stream: UsbAudioStream? = null
    private var clockRate: Int? = null

    override fun capabilities(source: Format): UsbPcmCapabilities {
        val attached = device.findUsbAudioDevice() ?: error("No USB DAC connected.")
        check(device.hasPermission(attached)) { "USB permission is required." }
        val opened = device.openDevice(attached) ?: error("USB audio format is unsupported.")
        info = opened
        val formats = opened.formats.filter { it.unsupportedReason == null && it.channels == 2 && it.validBits in setOf(16, 24, 32) }
        val format = formats.maxWithOrNull(compareBy<UsbAudioFormat> { it.validBits }.thenBy { it.containerBits })
            ?: error("No supported stereo USB PCM format.")
        selected = format
        val ranges = device.getClockRates(format)
        val candidates = (listOf(source.sampleRate) + RATES + ranges.flatMap { listOf(it.minimum, it.maximum) })
            .distinct().filter { it in 8000..384000 && format.fits(it) && ranges.any { range -> range.contains(it) } }.sorted()
        check(candidates.isNotEmpty()) { "USB clock rates could not be verified." }
        return UsbPcmCapabilities(candidates.toIntArray(), format.validBits, format.containerBits)
    }

    override fun start(format: Format) {
        val selected = checkNotNull(selected)
        val info = checkNotNull(info)
        val clock = device.configureFormat(selected, format.sampleRate)
        check(clock.verified) { clock.failure ?: "USB clock could not be verified." }
        clockRate = clock.observedHz
        val created = UsbAudioStream(info.fd, selected.interfaceId, selected.endpointOut, selected.endpointFeedback,
            format.sampleRate, 2, selected.containerBits, selected.maxPacketSize, validBits = selected.validBits,
            alternateSetting = selected.alternateSetting)
        try {
            check(created.isReady && created.start()) { "USB stream could not start." }
            stream = created
        } catch (failure: Exception) {
            created.stop(); created.drainUrbs(); created.release()
            throw failure
        }
    }

    override fun write(bytes: ByteArray, encoding: Int) {
        val output = checkNotNull(stream)
        check(output.isAlive) { "USB DAC disconnected." }
        output.writeRaw(bytes, encoding)
        check(output.isAlive) { "USB transfer failed." }
    }

    override fun finish() {
        val output = checkNotNull(stream)
        check(output.finish()) { "USB drain failed." }
    }

    override fun status(): UsbPcmStatus {
        val output = stream ?: return UsbPcmStatus(clockRate = clockRate)
        val status = output.telemetry
        return UsbPcmStatus(status.completedFrames, status.pendingFrames,
            status.packetErrors, status.timeouts, clockRate, status.lastError)
    }

    override fun interrupt() { stream?.stop() }
    override fun close() {
        stream?.let { it.stop(); it.drainUrbs(); it.release() }
        stream = null
        device.closeDevice()
    }

    private companion object { val RATES = listOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000) }
}
