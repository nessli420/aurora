package com.aurora.music.desktop.natives

object AlsaNative {
    val available: Boolean by lazy { runCatching { NativeLoader.load("aurora_alsa") }.isSuccess }

    external fun devices(): Array<String>?
    external fun capabilities(device: String, rates: IntArray, channels: Int): IntArray?
    external fun open(device: String, rate: Int, channels: Int, encoding: Int, bufferMs: Int, rates: IntArray): Long
    external fun streamCapabilities(handle: Long): IntArray?
    external fun write(handle: Long, bytes: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int
    external fun resume(handle: Long): Int
    external fun pause(handle: Long): Int
    external fun flush(handle: Long): Int
    external fun status(handle: Long): LongArray?
    external fun errorText(error: Int): String?
    external fun close(handle: Long)
}
