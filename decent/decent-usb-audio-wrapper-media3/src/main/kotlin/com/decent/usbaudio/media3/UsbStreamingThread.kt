package com.decent.usbaudio.media3

import android.util.Log
import com.decent.usbaudio.UsbAudioStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Dedicated thread for USB audio streaming, decoupled from ExoPlayer's
 * render thread.
 *
 * Supports two buffer types:
 * - [FloatBuffer]: float PCM from FFmpeg (MP3, AAC, FLAC via float path)
 * - [RawBuffer]: raw integer PCM from libFLAC (zero float, true bit-perfect)
 *
 * @param usbStream The native USB audio stream to write to.
 *                  Must only be accessed from the USB thread.
 */
class UsbStreamingThread(private val usbStream: UsbAudioStream) {

    companion object {
        private const val TAG = "UsbStreamingThread"
        private const val QUEUE_CAPACITY = 128
        private const val POLL_TIMEOUT_MS = 100L
    }

    /** Sealed class for type-safe audio buffer queueing. */
    private sealed class AudioBuffer {
        class FloatBuffer(val data: FloatArray) : AudioBuffer()
        class RawBuffer(val data: ByteArray, val encoding: Int) : AudioBuffer()
    }

    private val audioQueue = ArrayBlockingQueue<AudioBuffer>(QUEUE_CAPACITY)

    @Volatile
    private var running = false
    @Volatile
    private var paused = false
    private var thread: Thread? = null
    private var dropCount = 0

    fun start() {
        running = true
        thread = Thread({
            Log.i(TAG, "USB streaming thread started")
            while (running) {
                if (paused) {
                    Thread.sleep(50)
                    continue
                }
                val qBefore = audioQueue.size
                when (val buf = audioQueue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    is AudioBuffer.FloatBuffer -> {
                        usbStream.write(buf.data)
                        if (qBefore <= 1) Log.w(TAG, "Queue nearly empty: $qBefore before write")
                    }
                    is AudioBuffer.RawBuffer -> {
                        usbStream.writeRaw(buf.data, buf.encoding)
                        if (qBefore <= 1) Log.w(TAG, "Queue nearly empty: $qBefore before writeRaw")
                    }
                    null -> Log.w(TAG, "Queue EMPTY — poll timeout")
                }
            }
            Log.i(TAG, "USB streaming thread exited")
        }, "UsbStreamingThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /** Enqueue float PCM (FFmpeg path). Non-blocking, drop-oldest on full. */
    fun enqueue(floatBuf: FloatArray) {
        val buf = AudioBuffer.FloatBuffer(floatBuf)
        if (!audioQueue.offer(buf)) {
            audioQueue.poll()
            audioQueue.offer(buf)
            dropCount++
            if (dropCount <= 3 || dropCount % 100 == 0) {
                Log.w(TAG, "Queue full, dropped buffer #$dropCount")
            }
        }
    }

    /** Enqueue raw integer PCM (libFLAC path). Non-blocking, drop-oldest on full. */
    fun enqueueRaw(rawBytes: ByteArray, encoding: Int) {
        val buf = AudioBuffer.RawBuffer(rawBytes, encoding)
        if (!audioQueue.offer(buf)) {
            audioQueue.poll()
            audioQueue.offer(buf)
            dropCount++
            if (dropCount <= 3 || dropCount % 100 == 0) {
                Log.w(TAG, "Queue full, dropped raw buffer #$dropCount")
            }
        }
    }

    fun pauseStreaming() { paused = true }
    fun resumeStreaming() { paused = false }

    fun hasPendingData(): Boolean = !audioQueue.isEmpty()

    fun queueSize(): Int = audioQueue.size

    fun flush() {
        audioQueue.clear()
    }

    fun stop() {
        running = false
        audioQueue.clear()
        thread?.join(2000)
        thread = null
    }
}
