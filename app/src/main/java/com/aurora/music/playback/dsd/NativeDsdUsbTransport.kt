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
class NativeDsdUsbTransport(context: Context, private val wire: DsdWireFormat,
    private val experimental: Boolean = false) : UsbPcmTransport {
    private val usb = UsbAudioDevice.getInstance(context)
    private var info: UsbAudioDeviceInfo? = null
    private var selected: UsbAudioFormat? = null
    private var stream: UsbAudioStream? = null
    private var clock: Int? = null

    override fun capabilities(source: Format): UsbPcmCapabilities {
        val device = usb.findUsbAudioDevice() ?: error("No USB DAC connected.")
        check(usb.hasPermission(device)) { "USB permission is required." }
        val sourceInfo = requireNotNull(DsdSourceInfo.from(source))
        require(sourceInfo.channels == 2) { "Raw USB DSD requires stereo." }
        val rate = DsdUsbPolicy.carrierRate(device.vendorId, device.productId, sourceInfo.bitRate, wire, experimental)
        val opened = usb.openDevice(device, experimental) ?: error(usb.lastFailure ?: "USB could not open.")
        info = opened
        val candidates = opened.formats.filter { f ->
            f.channels == 2 && f.fits(rate, experimental) && (if (wire == DsdWireFormat.DOP)
                f.pcmUnsupportedReason(experimental) == null && f.validBits >= 24 else f.rawUnsupportedReason(experimental) == null) &&
                usb.getClockRates(f, experimental).any { it.contains(rate) }
        }
        val format = candidates.minByOrNull { it.containerBytes }
            ?: error("No compatible USB format, clock or packet capacity for DSD${sourceInfo.bitRate / 44100} ($rate Hz carrier).")
        selected = format
        val id = String.format(java.util.Locale.ROOT, "%04x:%04x", device.vendorId, device.productId)
        val layout = if (wire == DsdWireFormat.DOP) "DoP" else "MSB32 raw"
        val validation = if (device.vendorId == 0x2972 && device.productId == 0x0062) "KA13 profile" else "Experimental; DAC decoding unverified"
        val detail = "$validation; USB $id; $layout; interface ${format.interfaceId}/${format.alternateSetting}; endpoint ${format.endpointOut}; packet ${format.maxPacketSize} bytes"
        return UsbPcmCapabilities(intArrayOf(rate), format.validBits, format.containerBits, detail = detail)
    }

    override fun start(format: Format) {
        val selected = checkNotNull(selected)
        val configured = if (wire == DsdWireFormat.DOP) usb.configureFormat(selected, format.sampleRate, experimental)
            else usb.configureRawFormat(selected, format.sampleRate, experimental)
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
