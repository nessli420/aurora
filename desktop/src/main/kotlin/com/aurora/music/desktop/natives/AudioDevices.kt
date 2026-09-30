package com.aurora.music.desktop.natives

import java.util.concurrent.CopyOnWriteArrayList

enum class DeviceKind {
    REMOTE, SPEAKERS, LINE_LEVEL, HEADPHONES, MICROPHONE, HEADSET, HANDSET, DIGITAL_PASSTHROUGH, SPDIF, DIGITAL_DISPLAY, UNKNOWN
}

data class AudioDevice(val id: String, val name: String, val kind: DeviceKind, val isDefault: Boolean)

data class MixFormat(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val validBits: Int,
    val isFloat: Boolean,
    val channelMask: Int,
)

enum class OutputEncoding(val bytesPerSample: Int, val validBits: Int, val isFloat: Boolean = false) {
    S16(2, 16),
    S24(3, 24),
    S24_IN_32(4, 24),
    S32(4, 32),
    F32(4, 32, true),
}

sealed interface DeviceEvent {
    data class DefaultChanged(val deviceId: String?) : DeviceEvent
    data class Added(val deviceId: String) : DeviceEvent
    data class Removed(val deviceId: String) : DeviceEvent
    data class StateChanged(val deviceId: String, val state: Int) : DeviceEvent
    data class StreamInvalidated(val streamId: Long, val deviceId: String?) : DeviceEvent
}

object AudioDevices {
    private val listeners = CopyOnWriteArrayList<(DeviceEvent) -> Unit>()

    fun list(): List<AudioDevice> {
        val default = WasapiNative.defaultDevice()
        val flat = WasapiNative.devices() ?: return emptyList()
        return flat.asList().chunked(3) { (id, name, kind) ->
            AudioDevice(id, name, DeviceKind.entries.getOrElse(kind.toInt()) { DeviceKind.UNKNOWN }, id == default)
        }
    }

    fun mixFormat(deviceId: String? = null): MixFormat? = WasapiNative.mixFormat(deviceId)?.let {
        MixFormat(it[0], it[1], it[2], it[3], it[4] != 0, it[5])
    }

    fun supportsExclusive(deviceId: String?, sampleRate: Int, encoding: OutputEncoding, channels: Int = 2): Boolean =
        WasapiNative.probe(deviceId, true, sampleRate, channels, encoding.ordinal) == 0

    fun addListener(listener: (DeviceEvent) -> Unit): AutoCloseable {
        synchronized(listeners) {
            if (listeners.isEmpty()) WasapiNative.setListener(::dispatch)
            listeners += listener
        }
        return AutoCloseable {
            synchronized(listeners) {
                if (listeners.remove(listener) && listeners.isEmpty()) WasapiNative.setListener(null)
            }
        }
    }

    private fun dispatch(kind: Int, deviceId: String?, value: Long) {
        val event = when (kind) {
            WasapiNative.EVENT_DEFAULT_CHANGED -> DeviceEvent.DefaultChanged(deviceId)
            WasapiNative.EVENT_ADDED -> DeviceEvent.Added(deviceId ?: return)
            WasapiNative.EVENT_REMOVED -> DeviceEvent.Removed(deviceId ?: return)
            WasapiNative.EVENT_STATE_CHANGED -> DeviceEvent.StateChanged(deviceId ?: return, value.toInt())
            WasapiNative.EVENT_STREAM_INVALIDATED -> DeviceEvent.StreamInvalidated(value, deviceId)
            else -> return
        }
        listeners.forEach { it(event) }
    }
}
