package com.decent.usbaudio

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

data class UsbStreamTelemetry(
    val acceptedFrames: Long = 0,
    val submittedFrames: Long = 0,
    val completedFrames: Long = 0,
    val packetErrors: Long = 0,
    val submitErrors: Long = 0,
    val timeouts: Long = 0,
    val queueEmptyEvents: Long = 0,
    val feedbackPackets: Long = 0,
    val invalidFeedbackPackets: Long = 0,
    val errorCode: Int = 0,
    val feedbackRateHz: Int? = null,
    val inFlightUrbs: Int = 0,
) {
    val pendingFrames get() = (acceptedFrames - completedFrames).coerceAtLeast(0)
    val lastError: String? get() = when (errorCode) {
        0 -> null
        19, 108 -> "USB device disconnected."
        110 -> "USB transfer timed out."
        22 -> "Invalid USB PCM format."
        75 -> "USB packet or buffer capacity exceeded."
        12 -> "USB transfer allocation failed."
        else -> "USB transfer failed (error $errorCode)."
    }
}

class UsbAudioStream(
    fd: Int,
    interfaceId: Int,
    endpointOut: Int,
    endpointFeedback: Int,
    sampleRate: Int,
    val channelCount: Int,
    val bitDepth: Int,
    maxPacketSize: Int,
    val validBits: Int = bitDepth,
    alternateSetting: Int,
    val wireFormat: Int = WIRE_PCM,
) {
    @Volatile var nativeHandle: Long = 0L; private set
    private val lifecycle = ReentrantReadWriteLock(true)
    private var finalTelemetry = UsbStreamTelemetry()

    init {
        require(channelCount in 1..2 && bitDepth in listOf(16, 24, 32) && validBits in 16..bitDepth)
        require(wireFormat in WIRE_PCM..WIRE_NATIVE_DSD)
        require(wireFormat != WIRE_DOP || bitDepth in listOf(24, 32) && validBits >= 24)
        require(wireFormat != WIRE_NATIVE_DSD || bitDepth == 32 && validBits == 32)
        nativeHandle = nativeUsbAudioCreate(fd, interfaceId, endpointOut, endpointFeedback,
            sampleRate, channelCount, bitDepth, maxPacketSize, validBits, alternateSetting, wireFormat)
    }

    val isReady: Boolean get() = lifecycle.read { nativeHandle != 0L }
    val isAlive: Boolean get() = lifecycle.read { nativeHandle != 0L && nativeIsRunning(nativeHandle) }
    val framesWritten: Long get() = lifecycle.read { if (nativeHandle != 0L) nativeGetFramesWritten(nativeHandle) else finalTelemetry.acceptedFrames }
    val telemetry: UsbStreamTelemetry get() = lifecycle.read {
        if (nativeHandle == 0L) return@read finalTelemetry
        val value = nativeGetTelemetry(nativeHandle)
        if (value == null || value.size != 12) return@read finalTelemetry
        UsbStreamTelemetry(value[0], value[1], value[2], value[3], value[4], value[5], value[6],
            value[7], value[8], value[9].toInt(), value[10].toInt().takeIf { value[7] > 0 }, value[11].toInt())
    }
    val hasPendingData: Boolean get() = telemetry.pendingFrames > 0

    fun setAltSetting(altSetting: Int): Boolean = lifecycle.read {
        nativeHandle != 0L && nativeUsbAudioSetAltSetting(nativeHandle, altSetting)
    }

    fun setSampleRate(sampleRateHz: Int, clockSourceId: Int = 0): Boolean = lifecycle.read {
        nativeHandle != 0L && nativeUsbAudioSetSampleRate(nativeHandle, sampleRateHz, clockSourceId)
    }

    fun start(): Boolean = lifecycle.read { nativeHandle != 0L && nativeUsbAudioStart(nativeHandle) }

    fun write(pcmBuffer: FloatArray) = lifecycle.read {
        check(wireFormat == WIRE_PCM)
        require(pcmBuffer.size % channelCount == 0)
        if (nativeHandle != 0L) nativeUsbAudioWrite(nativeHandle, pcmBuffer)
    }

    fun writeRaw(pcmBuffer: ByteArray, encoding: Int) = lifecycle.read {
        check(wireFormat == WIRE_PCM)
        val bits = when (encoding) { 2 -> 16; 0x15 -> 24; 0x16 -> 32; else -> throw IllegalArgumentException("Unsupported PCM encoding.") }
        require(bits <= bitDepth && pcmBuffer.size % (channelCount * (bits / 8)) == 0)
        if (nativeHandle != 0L) nativeUsbAudioWriteRaw(nativeHandle, pcmBuffer, bits)
    }

    fun writePacked(bytes: ByteArray) = lifecycle.read {
        check(wireFormat != WIRE_PCM)
        require(bytes.size <= 1_048_576 && bytes.size % (channelCount * bitDepth / 8) == 0)
        check(nativeHandle != 0L)
        nativeUsbAudioWritePacked(nativeHandle, bytes)
    }

    fun stop() = lifecycle.read { if (nativeHandle != 0L) nativeUsbAudioStop(nativeHandle) }
    fun flush() = lifecycle.read { if (nativeHandle != 0L) nativeFlush(nativeHandle) }
    fun finish(): Boolean = lifecycle.read { nativeHandle != 0L && nativeFinish(nativeHandle) }
    fun drainUrbs(): Int = lifecycle.read { if (nativeHandle != 0L) nativeDrainUrbs(nativeHandle) else 0 }

    fun release() {
        stop()
        lifecycle.write {
            if (nativeHandle == 0L) return@write
            nativeDrainUrbs(nativeHandle)
            finalTelemetry = telemetry
            nativeUsbAudioDestroy(nativeHandle)
            nativeHandle = 0
        }
    }

    private external fun nativeUsbAudioCreate(fd: Int, interfaceId: Int, endpointOut: Int, endpointFeedback: Int,
        sampleRate: Int, channelCount: Int, bitDepth: Int, maxPacketSize: Int, validBits: Int, alternateSetting: Int, wireFormat: Int): Long
    private external fun nativeUsbAudioSetAltSetting(handle: Long, altSetting: Int): Boolean
    private external fun nativeUsbAudioSetSampleRate(handle: Long, sampleRateHz: Int, clockSourceId: Int): Boolean
    private external fun nativeUsbAudioStart(handle: Long): Boolean
    private external fun nativeUsbAudioWrite(handle: Long, pcmBuffer: FloatArray)
    private external fun nativeUsbAudioWriteRaw(handle: Long, pcmBuffer: ByteArray, inputBitDepth: Int)
    private external fun nativeUsbAudioWritePacked(handle: Long, bytes: ByteArray)
    private external fun nativeUsbAudioStop(handle: Long)
    private external fun nativeFlush(handle: Long)
    private external fun nativeFinish(handle: Long): Boolean
    private external fun nativeDrainUrbs(handle: Long): Int
    private external fun nativeUsbAudioDestroy(handle: Long)
    private external fun nativeIsRunning(handle: Long): Boolean
    private external fun nativeGetFramesWritten(handle: Long): Long
    private external fun nativeGetTelemetry(handle: Long): LongArray?

    companion object {
        const val WIRE_PCM = 0
        const val WIRE_DOP = 1
        const val WIRE_NATIVE_DSD = 2
        init { System.loadLibrary("decent_usb_audio") }
        @JvmStatic external fun nativeUsbReset(fd: Int): Int
        @JvmStatic external fun nativeGetUsbSpeed(fd: Int): Int
        @JvmStatic external fun nativeConnectionClosed(fd: Int)
    }
}
