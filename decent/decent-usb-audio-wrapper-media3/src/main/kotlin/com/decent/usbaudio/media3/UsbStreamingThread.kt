package com.decent.usbaudio.media3

import android.util.Log
import com.decent.usbaudio.UsbAudioStream
import java.util.ArrayDeque

class UsbStreamingThread(
    private val output: Output,
    private val onFailure: (String) -> Unit = {},
    private val capacity: Int = 16,
) {
    interface Output {
        fun write(data: FloatArray): Boolean
        fun writeRaw(data: ByteArray, encoding: Int): Boolean
        fun finish(): Boolean
        fun flush()
        fun stop()
        fun hasPendingData(): Boolean = false
        fun failureReason(): String? = null
    }

    constructor(stream: UsbAudioStream, onFailure: (String) -> Unit = {}) : this(object : Output {
        override fun write(data: FloatArray): Boolean { stream.write(data); return stream.isAlive }
        override fun writeRaw(data: ByteArray, encoding: Int): Boolean { stream.writeRaw(data, encoding); return stream.isAlive }
        override fun finish(): Boolean = stream.finish()
        override fun flush() {
            val before = stream.telemetry
            val wasAlive = stream.isAlive
            stream.flush()
            Log.i("UsbStreamingThread", "USB flush: running=$wasAlive->${stream.isAlive}; before=$before; after=${stream.telemetry}")
        }
        override fun stop() = stream.stop()
        override fun hasPendingData(): Boolean = stream.hasPendingData
        override fun failureReason(): String? {
            val status = stream.telemetry
            Log.e("UsbStreamingThread", "USB worker failure: running=${stream.isAlive}; $status")
            return status.lastError
        }
    }, onFailure)

    private sealed class Buffer {
        class Floats(val data: FloatArray) : Buffer()
        class Raw(val data: ByteArray, val encoding: Int) : Buffer()
    }

    private val lock = Object()
    private val ioLock = Any()
    private val queue = ArrayDeque<Buffer>()
    private var generation = 0L
    private var running = false
    private var paused = true
    private var writing = false
    private var ending = false
    private var drained = false
    private var flushing = false
    private var thread: Thread? = null
    @Volatile var failure: String? = null
        private set

    init { require(capacity > 0) }

    fun start() = synchronized(lock) {
        check(thread == null)
        running = true
        thread = Thread(::run, "UsbStreamingThread").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    private fun run() {
        while (true) {
            val item: Buffer?
            val token: Long
            synchronized(lock) {
                while (running && (paused || flushing || queue.isEmpty() && (!ending || drained))) lock.wait()
                if (!running) return
                token = generation
                item = queue.pollFirst()
                writing = true
            }
            var success = true
            try {
                synchronized(ioLock) {
                    if (synchronized(lock) { running && token == generation }) success = when (item) {
                        is Buffer.Floats -> output.write(item.data)
                        is Buffer.Raw -> output.writeRaw(item.data, item.encoding)
                        null -> output.finish()
                    }
                }
            } catch (_: Exception) { success = false }
            val failed = synchronized(lock) {
                writing = false
                if (token != generation || !running) false else if (!success) {
                    failure = output.failureReason() ?:
                        if (item == null) "USB output could not drain." else "USB audio transfer failed."
                    running = false
                    queue.clear()
                    true
                } else {
                    if (item == null) drained = true
                    false
                }
            }
            if (failed) { onFailure(failure!!); return }
        }
    }

    fun enqueue(data: FloatArray): Boolean = synchronized(lock) { offer(Buffer.Floats(data)) }
    fun enqueueRaw(data: ByteArray, encoding: Int): Boolean = synchronized(lock) { offer(Buffer.Raw(data, encoding)) }

    private fun offer(buffer: Buffer): Boolean {
        if (!running || ending || queue.size >= capacity) return false
        queue.addLast(buffer)
        lock.notifyAll()
        return true
    }

    fun pauseStreaming() = synchronized(lock) { paused = true }
    fun resumeStreaming() = synchronized(lock) { paused = false; lock.notifyAll() }
    fun queueSize(): Int = synchronized(lock) { queue.size }
    fun hasPendingData(): Boolean = synchronized(lock) { running && (queue.isNotEmpty() || writing || ending && !drained) } ||
        failure == null && output.hasPendingData()
    fun isDrained(): Boolean = synchronized(lock) { ending && drained && failure == null }
    fun endOfStream() = synchronized(lock) { ending = true; lock.notifyAll() }

    fun flush() {
        synchronized(lock) {
            generation++
            flushing = true
            queue.clear()
            ending = false
            drained = false
        }
        try { synchronized(ioLock) { output.flush() } }
        finally { synchronized(lock) { flushing = false; lock.notifyAll() } }
    }

    fun stop(timeoutMs: Long = 2000): Boolean {
        val worker = synchronized(lock) { running = false; queue.clear(); lock.notifyAll(); thread }
        output.stop()
        if (worker !== Thread.currentThread()) worker?.join(timeoutMs.coerceAtLeast(1))
        return (worker?.isAlive != true).also { if (it) synchronized(lock) { thread = null } }
    }

    fun awaitStopped() {
        val worker = synchronized(lock) { thread }
        if (worker !== Thread.currentThread()) worker?.join()
    }
}
