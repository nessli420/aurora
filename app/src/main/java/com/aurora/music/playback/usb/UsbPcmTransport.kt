package com.aurora.music.playback.usb

import androidx.media3.common.Format
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class UsbPcmCapabilities(val rates: IntArray, val validBits: Int, val containerBits: Int, val channels: Int = 2)
data class UsbPcmStatus(val completedFrames: Long = 0, val pendingFrames: Long = 0,
    val packetErrors: Long = 0, val timeouts: Long = 0, val clockRate: Int? = null, val error: String? = null)

interface UsbPcmTransport {
    fun capabilities(source: Format): UsbPcmCapabilities
    fun start(format: Format)
    fun write(bytes: ByteArray, encoding: Int)
    fun finish()
    fun status(): UsbPcmStatus
    fun interrupt()
    fun close()
}

internal class UsbPcmQueue(private val transport: UsbPcmTransport, private val encoding: Int, private val stride: Int) {
    private val queue = ArrayBlockingQueue<ByteArray>(8)
    private val lock = java.lang.Object()
    private val outstanding = AtomicLong()
    @Volatile private var running = true
    @Volatile private var paused = true
    @Volatile private var writing = false
    @Volatile private var ending = false
    @Volatile var finished = false; private set
    @Volatile var failure: Throwable? = null; private set
    @Volatile var submittedFrames = 0L; private set
    private val thread = Thread({
        try {
            while (awaitPlaying()) {
                val next = queue.poll(20, TimeUnit.MILLISECONDS)
                if (!awaitPlaying()) break
                if (next != null) {
                    writing = true
                    transport.write(next, encoding)
                    transport.status().error?.let { error(it) }
                    outstanding.addAndGet(-next.size.toLong())
                    writing = false
                } else if (ending && outstanding.get() == 0L) {
                    writing = true
                    transport.finish()
                    transport.status().error?.let { error(it) }
                    writing = false
                    finished = true
                    break
                }
            }
        } catch (problem: Throwable) {
            if (running) failure = problem
        } finally { writing = false }
    }, "AuroraUsbOutput").apply { priority = Thread.MAX_PRIORITY; start() }

    fun offer(input: ByteBuffer): Boolean {
        if (failure != null || !running || ending || queue.remainingCapacity() == 0) return false
        val size = minOf(input.remaining(), 8192 - 8192 % stride)
        require(size % stride == 0)
        val bytes = ByteArray(size)
        input.duplicate().get(bytes)
        outstanding.addAndGet(size.toLong())
        if (!queue.offer(bytes)) { outstanding.addAndGet(-size.toLong()); return false }
        input.position(input.position() + size)
        submittedFrames += size / stride
        return !input.hasRemaining()
    }

    fun play() { paused = false; synchronized(lock) { lock.notifyAll() } }
    fun pause() { paused = true }
    private fun awaitPlaying(): Boolean = synchronized(lock) {
        while (paused && running) lock.wait()
        running
    }
    fun end() { ending = true }
    val pending: Boolean get() = outstanding.get() > 0 || writing || (ending && !finished)
    fun close() {
        running = false
        transport.interrupt()
        synchronized(lock) { lock.notifyAll() }
        thread.interrupt()
        thread.join(3000)
        check(!thread.isAlive) { "USB output did not stop." }
        queue.clear()
        transport.close()
    }
}
