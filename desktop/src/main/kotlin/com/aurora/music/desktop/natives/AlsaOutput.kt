package com.aurora.music.desktop.natives

class AlsaException(val error: Int, message: String = AlsaErrors.describe(error)) :
    OutputException(message, error in AlsaErrors.INVALIDATED)

object AlsaErrors {
    const val ENOENT = -2
    const val EIO = -5
    const val ENXIO = -6
    const val EBADF = -9
    const val EBUSY = -16
    const val ENODEV = -19
    const val EINVAL = -22
    const val EBADFD = -77
    const val ESHUTDOWN = -108
    val INVALIDATED = setOf(ENODEV, ENXIO, EBADFD, EIO, ESHUTDOWN, ENOENT)

    fun describe(error: Int): String =
        "ALSA error $error" + (runCatching { AlsaNative.errorText(error) }.getOrNull()?.let { " ($it)" } ?: "")
}

class AlsaOutput internal constructor(
    val id: Long,
    val deviceId: String,
    val sampleRate: Int,
    val encoding: OutputEncoding,
    val channels: Int = 2,
) : AutoCloseable {
    val frameBytes: Int = channels * encoding.bytesPerSample

    @Volatile
    var closed = false
        private set

    fun capabilities(): IntArray? = AlsaNative.streamCapabilities(handle())?.takeIf { it.isNotEmpty() && it[0] == 0 }?.let { it.copyOfRange(1, it.size) }

    fun write(bytes: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int = checked(AlsaNative.write(handle(), bytes, offset, length, timeoutMs))

    fun resume() { checked(AlsaNative.resume(handle())) }

    fun pause() { checked(AlsaNative.pause(handle())) }

    fun flush() { checked(AlsaNative.flush(handle())) }

    fun status(): OutputStatus {
        val values = AlsaNative.status(handle()) ?: throw AlsaException(AlsaErrors.EBADF)
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
        AlsaNative.close(id)
    }

    private fun handle(): Long {
        check(!closed) { "ALSA output is closed." }
        return id
    }

    private fun checked(result: Int): Int {
        if (result < 0) throw AlsaException(result)
        return result
    }

    companion object {
        fun open(deviceId: String, sampleRate: Int, encoding: OutputEncoding, bufferMs: Int, rates: IntArray, channels: Int = 2): AlsaOutput {
            val handle = AlsaNative.open(deviceId, sampleRate, channels, encoding.ordinal, bufferMs, rates)
            if (handle <= 0) throw AlsaException(handle.toInt())
            return AlsaOutput(handle, deviceId, sampleRate, encoding, channels)
        }
    }
}
