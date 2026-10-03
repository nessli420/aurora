package com.aurora.music.desktop.audio

import com.aurora.music.desktop.natives.AudioDevice
import com.aurora.music.desktop.natives.AudioDevices
import com.aurora.music.desktop.natives.DeviceEvent
import com.aurora.music.desktop.natives.OutputEncoding
import com.aurora.music.desktop.natives.OutputStatus
import com.aurora.music.desktop.natives.SystemNative
import com.aurora.music.desktop.natives.WasapiOutput
import com.aurora.music.desktop.platform.HostPlatform

interface AudioOutput : AutoCloseable {
    val id: Long
    val deviceId: String?
    val exclusive: Boolean
    val sampleRate: Int
    val encoding: OutputEncoding
    fun write(bytes: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int
    fun resume()
    fun pause()
    fun flush()
    fun status(): OutputStatus
}

interface OutputBackend {
    fun devices(): List<AudioDevice>
    fun mixRate(deviceId: String?): Int?
    fun supportsExclusive(deviceId: String?, sampleRate: Int, encoding: OutputEncoding): Boolean
    fun open(deviceId: String?, exclusive: Boolean, sampleRate: Int, encoding: OutputEncoding, bufferMs: Int): AudioOutput
    fun listen(listener: (DeviceEvent) -> Unit): AutoCloseable
    fun keepAwake(enabled: Boolean)
}

class WasapiAudioOutput(private val output: WasapiOutput) : AudioOutput {
    override val id: Long get() = output.id
    override val deviceId: String? get() = output.deviceId
    override val exclusive: Boolean get() = output.exclusive
    override val sampleRate: Int get() = output.sampleRate
    override val encoding: OutputEncoding get() = output.encoding
    override fun write(bytes: ByteArray, offset: Int, length: Int, timeoutMs: Int) = output.write(bytes, offset, length, timeoutMs)
    override fun resume() = output.resume()
    override fun pause() = output.pause()
    override fun flush() = output.flush()
    override fun status() = output.status()
    override fun close() = output.close()
}

fun defaultOutputBackend(): OutputBackend = if (HostPlatform.isWindows) WasapiBackend else JavaSoundBackend

val outputApiName: String get() = if (HostPlatform.isWindows) "WASAPI" else "Java Sound"

object WasapiBackend : OutputBackend {
    override fun devices() = AudioDevices.list()
    override fun mixRate(deviceId: String?) = AudioDevices.mixFormat(deviceId)?.sampleRate
    override fun supportsExclusive(deviceId: String?, sampleRate: Int, encoding: OutputEncoding) =
        AudioDevices.supportsExclusive(deviceId, sampleRate, encoding)
    override fun open(deviceId: String?, exclusive: Boolean, sampleRate: Int, encoding: OutputEncoding, bufferMs: Int): AudioOutput =
        WasapiAudioOutput(WasapiOutput.open(deviceId, exclusive, sampleRate = sampleRate, encoding = encoding, bufferMs = bufferMs))
    override fun listen(listener: (DeviceEvent) -> Unit) = AudioDevices.addListener(listener)
    override fun keepAwake(enabled: Boolean) { SystemNative.keepAwake(enabled) }
}
