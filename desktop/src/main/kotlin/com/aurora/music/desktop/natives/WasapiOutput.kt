package com.aurora.music.desktop.natives

import java.io.IOException

class WasapiException(val hresult: Int) : IOException("WASAPI error 0x%08X".format(hresult)) {
    val deviceInvalidated: Boolean get() = hresult == DEVICE_INVALIDATED

    companion object {
        const val DEVICE_INVALIDATED = 0x88890004.toInt()
        const val DEVICE_IN_USE = 0x8889000A.toInt()
        const val CLOSED = 0x80070006.toInt()
    }
}

data class OutputStatus(
    val framesPlayed: Long,
    val framesBuffered: Long,
    val underruns: Long,
    val playing: Boolean,
    val deviceInvalidated: Boolean,
    val exclusive: Boolean,
    val lastError: Int,
    val latencyMicros: Long,
    val positionNanos: Long,
    val deviceBufferFrames: Int,
    val ringFrames: Int,
)

class WasapiOutput private constructor(
    val id: Long,
    val exclusive: Boolean,
    val sampleRate: Int,
    val channels: Int,
    val encoding: OutputEncoding,
) : AutoCloseable {
    val frameBytes: Int = channels * encoding.bytesPerSample
    val deviceId: String? = WasapiNative.streamDevice(id)

    @Volatile
    var closed = false
        private set

    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset, timeoutMs: Int = -1): Int =
        checked(WasapiNative.write(handle(), bytes, offset, length, timeoutMs))

    fun resume() { checked(WasapiNative.resume(handle())) }

    fun pause() { checked(WasapiNative.pause(handle())) }

    fun flush() { checked(WasapiNative.flush(handle())) }

    fun status(): OutputStatus {
        val values = WasapiNative.status(handle()) ?: throw WasapiException(WasapiException.CLOSED)
        val flags = values[3].toInt()
        return OutputStatus(
            framesPlayed = values[0],
            framesBuffered = values[1],
            underruns = values[2],
            playing = flags and 1 != 0,
            deviceInvalidated = flags and 8 != 0,
            exclusive = flags and 16 != 0,
            lastError = values[4].toInt(),
            latencyMicros = values[5],
            positionNanos = values[6],
            deviceBufferFrames = values[7].toInt(),
            ringFrames = values[8].toInt(),
        )
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        WasapiNative.close(id)
    }

    private fun handle(): Long {
        check(!closed) { "WASAPI output is closed." }
        return id
    }

    private fun checked(result: Int): Int {
        if (result < 0) throw WasapiException(result)
        return result
    }

    companion object {
        fun open(
            deviceId: String? = null,
            exclusive: Boolean = false,
            sampleRate: Int,
            encoding: OutputEncoding = OutputEncoding.F32,
            channels: Int = 2,
            bufferMs: Int = 400,
        ): WasapiOutput {
            val handle = WasapiNative.open(deviceId, exclusive, sampleRate, channels, encoding.ordinal, bufferMs)
            if (handle <= 0) throw WasapiException(handle.toInt())
            return WasapiOutput(handle, exclusive, sampleRate, channels, encoding)
        }
    }
}
