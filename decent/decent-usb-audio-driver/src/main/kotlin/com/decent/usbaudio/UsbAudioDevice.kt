package com.decent.usbaudio

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

class UsbAudioDevice private constructor(private val context: Context) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null
    private var currentDevice: UsbDevice? = null
    private val claimed = mutableListOf<UsbInterface>()
    private var cachedDeviceInfo: UsbAudioDeviceInfo? = null
    private var selectedFormat: UsbAudioFormat? = null
    private val rates = mutableMapOf<Pair<Int, Int>, List<UsbRateRange>>()
    @Volatile var lastFailure: String? = null; private set

    companion object {
        @Volatile private var instance: UsbAudioDevice? = null
        fun getInstance(context: Context): UsbAudioDevice = instance ?: synchronized(this) {
            instance ?: UsbAudioDevice(context.applicationContext).also { instance = it }
        }
    }

    fun isAudioOutput(device: UsbDevice): Boolean = (0 until device.interfaceCount).any { index ->
        val iface = device.getInterface(index)
        iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO && iface.interfaceSubclass == 2 &&
            (0 until iface.endpointCount).any { ep ->
                iface.getEndpoint(ep).let { it.type == UsbConstants.USB_ENDPOINT_XFER_ISOC && it.direction == UsbConstants.USB_DIR_OUT }
            }
    }

    fun findUsbAudioDevice(): UsbDevice? = usbManager.deviceList.values.sortedBy { it.deviceName }.firstOrNull(::isAudioOutput)
    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)
    fun isAttached(device: UsbDevice): Boolean = usbManager.deviceList[device.deviceName]?.deviceId == device.deviceId

    fun requestPermission(device: UsbDevice, callback: (Boolean) -> Unit) {
        if (!isAttached(device)) { callback(false); return }
        if (hasPermission(device)) { callback(true); return }
        val action = context.packageName + ".USB_AUDIO_PERMISSION." + UUID.randomUUID()
        val handler = Handler(Looper.getMainLooper())
        var finished = false
        var receiver: BroadcastReceiver? = null
        lateinit var timeout: Runnable
        fun finish(granted: Boolean) {
            synchronized(handler) {
                if (finished) return
                finished = true
            }
            handler.removeCallbacks(timeout)
            receiver?.let { runCatching { context.unregisterReceiver(it) } }
            callback(granted && isAttached(device) && hasPermission(device))
        }
        timeout = Runnable { finish(false) }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val returned = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                if (returned?.deviceName != device.deviceName || returned.deviceId != device.deviceId) return
                when (intent.action) {
                    action -> finish(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> finish(false)
                }
            }
        }
        val filter = IntentFilter(action).apply { addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        try {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else context.registerReceiver(receiver, filter)
            handler.postDelayed(timeout, 60_000)
            val intent = PendingIntent.getBroadcast(context, device.deviceId,
                Intent(action).setPackage(context.packageName), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_ONE_SHOT)
            usbManager.requestPermission(device, intent)
        } catch (_: RuntimeException) { finish(false) }
    }

    @Synchronized fun openDevice(device: UsbDevice): UsbAudioDeviceInfo? {
        if (!isAttached(device) || !hasPermission(device)) { lastFailure = "USB permission is unavailable."; return null }
        if (currentDevice?.deviceName == device.deviceName && currentDevice?.deviceId == device.deviceId && connection != null)
            return cachedDeviceInfo
        closeDevice()
        val conn = try { usbManager.openDevice(device) } catch (_: RuntimeException) { null }
            ?: run { lastFailure = "The USB device could not be opened."; return null }
        connection = conn; currentDevice = device
        val report = UsbAudioDescriptors.parse(conn.rawDescriptors ?: ByteArray(0))
        val config = ByteArray(1)
        val activeConfiguration = if (conn.controlTransfer(0x80, 8, 0, 0, config, 1, 500) == 1) config[0].toInt() and 255 else -1
        val formats = report.formats.filter { it.configuration == activeConfiguration }
        val speed = UsbAudioStream.nativeGetUsbSpeed(conn.fileDescriptor)
        val supported = formats.filter { it.unsupportedReason == null }
        val best = supported.maxWithOrNull(compareBy<UsbAudioFormat> { it.validBits }.thenBy { it.containerBytes })
        if (best == null || speed != 3) {
            lastFailure = when {
                report.malformed -> "USB descriptors are malformed."
                speed != 3 -> "Direct output requires a verified high-speed USB connection."
                else -> formats.firstOrNull()?.unsupportedReason ?: "No supported USB PCM output was found."
            }
            closeDevice(); return null
        }
        val info = UsbAudioDeviceInfo(conn, conn.fileDescriptor, device.productName ?: "USB audio",
            best.interfaceId, best.endpointOut, best.endpointFeedback, best.maxPacketSize,
            formats.count { it.interfaceId == best.interfaceId }, best.clockSourceId, best.alternateSetting,
            best.containerBits, best.controlInterfaceId, formats, speed)
        cachedDeviceInfo = info; selectedFormat = best; lastFailure = null
        return info
    }

    @Synchronized fun getClockRates(format: UsbAudioFormat): List<UsbRateRange> {
        val conn = connection ?: return emptyList()
        if (cachedDeviceInfo?.formats.orEmpty().none { it === format } || format.unsupportedReason != null) return emptyList()
        val key = format.controlInterfaceId to format.clockSourceId
        rates[key]?.let { return it }
        if (!claimInterface(format.controlInterfaceId)) return emptyList()
        val index = format.clockSourceId shl 8 or format.controlInterfaceId
        val countBytes = ByteArray(2)
        if (conn.controlTransfer(0xa1, 2, 0x0100, index, countBytes, 2, 500) != 2) return emptyList()
        val count = (countBytes[0].toInt() and 255) or ((countBytes[1].toInt() and 255) shl 8)
        if (count !in 1..64) return emptyList()
        val bytes = ByteArray(2 + count * 12)
        if (conn.controlTransfer(0xa1, 2, 0x0100, index, bytes, bytes.size, 500) != bytes.size) return emptyList()
        return runCatching { UsbAudioDescriptors.parseClockRanges(bytes) }.getOrDefault(emptyList()).also { rates[key] = it }
    }

    @Synchronized fun selectFormat(rate: Int, channels: Int, sourceBits: Int): UsbAudioFormat? =
        cachedDeviceInfo?.formats.orEmpty().filter { it.unsupportedReason == null && it.channels == channels &&
            it.validBits >= sourceBits && it.fits(rate) && getClockRates(it).any { range -> range.contains(rate) } }
            .minWithOrNull(compareBy<UsbAudioFormat> { it.containerBytes }.thenBy { it.validBits }.thenBy { it.alternateSetting })

    @Synchronized fun configureFormat(format: UsbAudioFormat, rate: Int): UsbClockStatus {
        fun fail(reason: String, observed: Int? = null, valid: Boolean? = null): UsbClockStatus {
            lastFailure = reason
            return UsbClockStatus(rate, observed, valid, reason)
        }
        val conn = connection ?: return fail("USB is disconnected.")
        if (cachedDeviceInfo?.formats.orEmpty().none { it === format } || format.unsupportedReason != null ||
            !format.fits(rate) || getClockRates(format).none { it.contains(rate) }) return fail("The USB format or rate is unsupported.")
        for (id in listOf(format.controlInterfaceId, format.interfaceId).distinct()) {
            if (!claimInterface(id)) return fail(lastFailure ?: "The USB interface could not be claimed.")
        }
        selectedFormat = format
        if (!setAltSetting(0)) return fail("The USB stream could not be stopped.")
        val observedBefore = readSampleRate().takeIf { it > 0 }
        if (observedBefore != rate) {
            if (format.clockControls and 2 == 0) return fail("The USB clock is read-only.", observedBefore)
            val data = ByteArray(4) { (rate shr (it * 8)).toByte() }
            if (conn.controlTransfer(0x21, 1, 0x0100, format.clockSourceId shl 8 or format.controlInterfaceId, data, 4, 1000) != 4)
                return fail("The USB clock rejected the requested rate.", observedBefore)
        }
        var observed: Int? = null; var valid: Boolean? = null
        repeat(10) {
            observed = readSampleRate().takeIf { it > 0 }; valid = readClockValidity()
            if (observed == rate && valid != false) {
                if (!setAltSetting(format.alternateSetting)) return fail("The USB streaming setting was rejected.", observed, valid)
                lastFailure = null
                return UsbClockStatus(rate, observed, valid, null)
            }
            Thread.sleep(10)
        }
        return fail("The USB clock could not be verified.", observed, valid)
    }

    private fun claimInterface(id: Int): Boolean {
        if (claimed.any { it.id == id }) return true
        val conn = connection ?: return false
        val device = currentDevice ?: return false
        val iface = (0 until device.interfaceCount).map(device::getInterface)
            .firstOrNull { it.id == id && it.alternateSetting == 0 }
        if (iface == null) {
            lastFailure = "The USB interface has no idle setting."
            return false
        }
        if (!conn.claimInterface(iface, true)) {
            lastFailure = "The USB interface could not be claimed."
            return false
        }
        claimed += iface
        return true
    }

    @Synchronized fun setAltSetting(altSetting: Int): Boolean {
        val conn = connection ?: return false
        val device = currentDevice ?: return false
        val selected = selectedFormat ?: return false
        val iface = (0 until device.interfaceCount).map(device::getInterface)
            .firstOrNull { it.id == selected.interfaceId && it.alternateSetting == altSetting } ?: return false
        return conn.setInterface(iface)
    }

    @Synchronized fun readSampleRate(): Int {
        val conn = connection ?: return -1
        val format = selectedFormat ?: return -1
        val bytes = ByteArray(4)
        if (conn.controlTransfer(0xa1, 1, 0x0100, format.clockSourceId shl 8 or format.controlInterfaceId, bytes, 4, 500) != 4) return -1
        var rate = 0L
        for (i in 0..3) rate = rate or ((bytes[i].toLong() and 255) shl (i * 8))
        return rate.takeIf { it in 8000..768000 }?.toInt() ?: -1
    }

    @Synchronized fun readClockValidity(): Boolean? {
        val conn = connection ?: return null
        val format = selectedFormat ?: return null
        if (format.clockControls and 4 == 0) return null
        val value = ByteArray(1)
        if (conn.controlTransfer(0xa1, 1, 0x0200, format.clockSourceId shl 8 or format.controlInterfaceId, value, 1, 500) != 1) return false
        return value[0].toInt() == 1
    }
    fun readClockValid(): Boolean = readClockValidity() == true
    @Synchronized fun setSampleRate(sampleRateHz: Int): Boolean =
        selectedFormat?.let { configureFormat(it, sampleRateHz).verified } == true

    @Synchronized fun findAltSettingForBitDepth(targetBitDepth: Int): Pair<Int, Int> =
        cachedDeviceInfo?.formats.orEmpty().filter { it.unsupportedReason == null && it.validBits >= targetBitDepth }
            .minByOrNull { it.containerBytes }?.let { it.alternateSetting to it.containerBits } ?: (-1 to 0)

    @Synchronized fun closeDevice() {
        val conn = connection
        val fd = conn?.fileDescriptor
        claimed.asReversed().forEach { runCatching { conn?.releaseInterface(it) } }
        claimed.clear(); connection = null; currentDevice = null; cachedDeviceInfo = null; selectedFormat = null; rates.clear()
        conn?.close()
        if (fd != null) UsbAudioStream.nativeConnectionClosed(fd)
    }

    @Synchronized fun resetAndReopen() {
        val device = currentDevice ?: return
        val fd = connection?.fileDescriptor ?: return
        UsbAudioStream.nativeUsbReset(fd)
        closeDevice()
        if (isAttached(device) && hasPermission(device)) openDevice(device)
    }
}
