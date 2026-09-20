package com.aurora.music.playback.dsd

import android.content.Context
import androidx.media3.common.Format
import com.aurora.music.playback.usb.UsbPcmCapabilities
import com.aurora.music.playback.usb.UsbPcmStatus
import com.aurora.music.playback.usb.UsbPcmTransport
import com.decent.usbaudio.UsbAudioDevice
import com.decent.usbaudio.UsbAudioDeviceInfo
import com.decent.usbaudio.UsbAudioFormat
import com.decent.usbaudio.UsbAudioStream

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NativeDsdUsbTransport(context: Context, private val wire: DsdWireFormat) : UsbPcmTransport {
    private val usb = UsbAudioDevice.getInstance(context)
    private var info: UsbAudioDeviceInfo? = null
    private var selected: UsbAudioFormat? = null
    private var stream: UsbAudioStream? = null
    private var clock: Int? = null

    override fun capabilities(source: Format): UsbPcmCapabilities {
        val device = usb.findUsbAudioDevice() ?: error("No USB DAC connected.")
        check(device.vendorId == 0x2972 && device.productId == 0x0062) { "Raw DSD is not supported for this DAC. Use PCM conversion." }
        check(usb.hasPermission(device)) { "USB permission is required." }
        val sourceInfo = requireNotNull(DsdSourceInfo.from(source))
        require(sourceInfo.channels == 2) { "Raw USB DSD requires stereo." }
        val maximum = if (wire == DsdWireFormat.DOP) 5_644_800 else 11_289_600
        require(sourceInfo.bitRate in DsdFormat.supportedBitRates && sourceInfo.bitRate <= maximum) { "This DSD rate exceeds the DAC's raw output range. Use PCM conversion." }
        val opened = usb.openDevice(device) ?: error(usb.lastFailure ?: "USB could not open.")
        info = opened
        val rate = sourceInfo.bitRate / (wire.sourceBytes * 8)
        val candidates = opened.formats.filter { f ->
            f.channels == 2 && f.fits(rate) && (if (wire == DsdWireFormat.DOP)
                f.unsupportedReason == null && f.validBits >= 24 else f.rawUnsupportedReason == null) &&
                usb.getClockRates(f).any { it.contains(rate) }
        }
        val format = candidates.minByOrNull { it.containerBytes } ?: error("No verified USB clock for this DSD rate.")
        selected = format
        return UsbPcmCapabilities(intArrayOf(rate), format.validBits, format.containerBits)
    }

    override fun start(format: Format) {
        val selected = checkNotNull(selected)
        val configured = if (wire == DsdWireFormat.DOP) usb.configureFormat(selected, format.sampleRate)
            else usb.configureRawFormat(selected, format.sampleRate)
        check(configured.verified) { configured.failure ?: "USB clock could not be verified." }
        clock = configured.observedHz
        val created = UsbAudioStream(checkNotNull(info).fd, selected.interfaceId, selected.endpointOut,
            selected.endpointFeedback, format.sampleRate, 2, selected.containerBits, selected.maxPacketSize,
            selected.validBits, selected.alternateSetting,
            if (wire == DsdWireFormat.DOP) UsbAudioStream.WIRE_DOP else UsbAudioStream.WIRE_NATIVE_DSD)
        try {
            check(created.isReady && created.start()) { "DSD USB stream could not start." }
            stream = created
        } catch (failure: Exception) { created.release(); throw failure }
    }

    override fun write(bytes: ByteArray, encoding: Int) {
        val active = checkNotNull(stream)
        check(active.isAlive) { "USB DAC disconnected." }
        active.writePacked(bytes)
        check(active.isAlive) { active.telemetry.lastError ?: "DSD USB transfer failed." }
    }
    override fun finish() { check(checkNotNull(stream).finish()) { "DSD USB drain failed." } }
    override fun status(): UsbPcmStatus {
        val s = stream?.telemetry ?: return UsbPcmStatus(clockRate = clock)
        return UsbPcmStatus(s.completedFrames, s.pendingFrames, s.packetErrors, s.timeouts, clock, s.lastError)
    }
    override fun interrupt() { stream?.stop() }
    override fun close() { stream?.release(); stream = null; usb.setAltSetting(0); usb.closeDevice() }
}
