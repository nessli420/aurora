package com.aurora.music.desktop.natives

import java.nio.ByteBuffer

fun interface WasapiListener {
    fun onEvent(kind: Int, deviceId: String?, value: Long)
}

object WasapiNative {
    const val EVENT_DEFAULT_CHANGED = 0
    const val EVENT_ADDED = 1
    const val EVENT_REMOVED = 2
    const val EVENT_STATE_CHANGED = 3
    const val EVENT_STREAM_INVALIDATED = 4

    init { NativeLoader.load("aurora_native") }

    external fun devices(): Array<String>?
    external fun defaultDevice(): String?
    external fun mixFormat(deviceId: String?): IntArray?
    external fun probe(deviceId: String?, exclusive: Boolean, sampleRate: Int, channels: Int, encoding: Int): Int
    external fun open(deviceId: String?, exclusive: Boolean, sampleRate: Int, channels: Int, encoding: Int, bufferMs: Int): Long
    external fun write(handle: Long, bytes: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int
    external fun writeDirect(handle: Long, buffer: ByteBuffer, offset: Int, length: Int, timeoutMs: Int): Int
    external fun resume(handle: Long): Int
    external fun pause(handle: Long): Int
    external fun flush(handle: Long): Int
    external fun status(handle: Long): LongArray?
    external fun streamDevice(handle: Long): String?
    external fun close(handle: Long)
    external fun setListener(listener: WasapiListener?): Int
}
