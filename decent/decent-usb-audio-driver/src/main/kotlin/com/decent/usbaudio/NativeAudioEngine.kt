package com.decent.usbaudio

import android.util.Log

/**
 * Native FLAC decode → USB audio engine.
 *
 * Bypasses the entire ExoPlayer audio pipeline for FLAC files.
 * A single native C++ thread handles: FLAC decode → bit-depth conversion → USB
 * isochronous transfers. Zero JNI in the hot path.
 *
 * Usage:
 * ```kotlin
 * val engine = NativeAudioEngine()
 * engine.createFromFd(flacFd, usbStreamHandle)
 * engine.start()
 * // ... engine runs autonomously, reports position via getPositionUs()
 * engine.stop()
 * engine.destroy()
 * ```
 *
 * @see UsbAudioStream for USB stream lifecycle management
 */
class NativeAudioEngine {

    private var handle: Long = 0L

    /** True when the engine has been created and not yet destroyed. */
    val isCreated: Boolean get() = handle != 0L

    /** True when the decode thread is actively running. */
    val isRunning: Boolean get() = handle != 0L && nativeIsRunning(handle)

    /**
     * Create the engine from a file descriptor pointing to a FLAC file.
     *
     * @param flacFd      File descriptor for the FLAC file (will be dup'd internally).
     * @param usbHandle   Native handle from [UsbAudioStream] (the USB output context).
     * @return true if creation succeeded (FLAC metadata parsed, buffers allocated).
     */
    fun createFromFd(flacFd: Int, usbHandle: Long): Boolean {
        if (handle != 0L) {
            Log.w(TAG, "Engine already created, destroying first")
            destroy()
        }
        handle = nativeCreateFromFd(flacFd, usbHandle)
        if (handle == 0L) {
            Log.e(TAG, "Failed to create native engine")
            return false
        }
        Log.i(TAG, "Created: ${getSampleRate()}Hz ${getBitsPerSample()}-bit ${getChannels()}ch")
        return true
    }

    /** Start the decode thread. Audio flows immediately to USB. */
    fun start(): Boolean {
        if (handle == 0L) return false
        return nativeStart(handle)
    }

    /** Pause the decode loop (thread stays alive, USB pipeline drains). */
    fun pause() {
        if (handle != 0L) nativePause(handle)
    }

    /** Resume the decode loop after pause. */
    fun resume() {
        if (handle != 0L) nativeResume(handle)
    }

    /**
     * Seek to a position in the FLAC stream.
     * Uses the FLAC seek table for sample-accurate seeking.
     *
     * @param positionUs Target position in microseconds.
     * @return true if seek was accepted (async — actual seek happens in decode thread).
     */
    fun seek(positionUs: Long): Boolean {
        if (handle == 0L) return false
        return nativeSeek(handle, positionUs)
    }

    /** Stop the decode thread (blocks until thread exits). Thread-safe. */
    @Synchronized
    fun stop() {
        if (handle != 0L) nativeStop(handle)
    }

    /** Destroy the engine and free all native resources. Thread-safe / idempotent. */
    @Synchronized
    fun destroy() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    /** Current playback position in microseconds (from decoded frames). */
    fun getPositionUs(): Long =
        if (handle != 0L) nativeGetPositionUs(handle) else 0L

    /** FLAC file sample rate (e.g., 96000). */
    fun getSampleRate(): Int =
        if (handle != 0L) nativeGetSampleRate(handle) else 0

    /** FLAC file channel count (e.g., 2). */
    fun getChannels(): Int =
        if (handle != 0L) nativeGetChannels(handle) else 0

    /** FLAC file bits per sample (e.g., 24). */
    fun getBitsPerSample(): Int =
        if (handle != 0L) nativeGetBitsPerSample(handle) else 0

    // ── JNI declarations ───────────────────────────────────────────

    private external fun nativeCreateFromFd(fd: Int, usbHandle: Long): Long
    private external fun nativeStart(handle: Long): Boolean
    private external fun nativePause(handle: Long)
    private external fun nativeResume(handle: Long)
    private external fun nativeSeek(handle: Long, positionUs: Long): Boolean
    private external fun nativeStop(handle: Long)
    private external fun nativeDestroy(handle: Long)
    private external fun nativeGetPositionUs(handle: Long): Long
    private external fun nativeGetSampleRate(handle: Long): Int
    private external fun nativeGetChannels(handle: Long): Int
    private external fun nativeGetBitsPerSample(handle: Long): Int
    private external fun nativeIsRunning(handle: Long): Boolean

    companion object {
        private const val TAG = "NativeAudioEngine"

        init {
            System.loadLibrary("decent_usb_audio")
        }
    }
}
