package com.aurora.music.playback

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.aurora.music.data.routes.ProcessingRoute
import com.aurora.music.data.routes.ProcessingRouteKind
import com.aurora.music.data.routes.RouteIdentity
import com.aurora.music.data.routes.OutputDeviceCategory

@UnstableApi
class ConfirmedAudioRoute(private val context: Context, private val changed: () -> Unit = {}) {
    @Volatile private var track: AudioTrack? = null
    private val handler = Handler(Looper.getMainLooper())
    private val listener = AudioRouting.OnRoutingChangedListener { routing ->
        if (routing === track) changed()
    }
    val provider = DefaultAudioSink.AudioTrackProvider { configuration, attributes, session ->
        DefaultAudioSink.AudioTrackProvider.DEFAULT.getAudioTrack(configuration, attributes, session).also { created ->
            synchronized(this) {
                track?.let { runCatching { it.removeOnRoutingChangedListener(listener) } }
                track = created
                created.addOnRoutingChangedListener(listener, handler)
            }
            handler.post { if (track === created) changed() }
        }
    }

    fun snapshot(): ProcessingRoute {
        val device = runCatching { track?.takeIf { it.state == AudioTrack.STATE_INITIALIZED }?.routedDevice }.getOrNull()
            ?: return ProcessingRoute(ProcessingRouteKind.UNKNOWN, label = "Output unconfirmed", detail = "Waiting for AudioTrack routing.")
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outputs = runCatching { manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }.getOrDefault(emptyList())
        val address = device.address.orEmpty()
        val label = device.productName?.toString()?.trim()?.take(120)?.ifBlank { null } ?: "Android output"
        val builtIn = device.type in setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE)
        val unique = outputs.count { it.type == device.type && it.address.orEmpty() == address } == 1
        val serial = if (device.type in USB_TYPES) usbIdentity() else null
        val key = RouteIdentity.key(device.type, address, builtIn, unique, serial)
        val legacy = "${device.type}:${device.productName}"
            .takeIf { outputs.count { it.type == device.type && it.productName.toString() == device.productName.toString() } == 1 }
        return ProcessingRoute(ProcessingRouteKind.ANDROID, key, label,
            if (key == null) "Output identity is unavailable. Select presets manually." else "Confirmed by AudioTrack.", legacy,
            category(device.type))
    }

    private fun usbIdentity(): String? = runCatching {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = manager.deviceList.values.filter { candidate ->
            candidate.deviceClass == UsbConstants.USB_CLASS_AUDIO ||
                (0 until candidate.interfaceCount).any { candidate.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO }
        }.singleOrNull() ?: return null
        val serial = device.serialNumber?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        "${device.vendorId}:${device.productId}:$serial"
    }.getOrNull()

    fun close() = synchronized(this) {
        track?.let { runCatching { it.removeOnRoutingChangedListener(listener) } }
        track = null
    }

    private companion object {
        val USB_TYPES = setOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_USB_HEADSET)
        fun category(type: Int): OutputDeviceCategory = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> OutputDeviceCategory.SPEAKER
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> OutputDeviceCategory.EARPIECE
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> OutputDeviceCategory.HEADPHONES
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST -> OutputDeviceCategory.BLUETOOTH
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> OutputDeviceCategory.HDMI
            in USB_TYPES -> OutputDeviceCategory.USB
            else -> OutputDeviceCategory.OTHER
        }
    }
}
