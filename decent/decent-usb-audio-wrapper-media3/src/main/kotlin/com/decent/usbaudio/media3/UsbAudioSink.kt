package com.decent.usbaudio.media3

import android.content.Context
import android.hardware.usb.UsbDevice
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.decent.usbaudio.NativeAudioEngine
import com.decent.usbaudio.UsbAudioDevice
import com.decent.usbaudio.UsbAudioStream
import com.decent.usbaudio.UsbAudioFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** direct usb transport with an optional android fallback. */
@OptIn(UnstableApi::class)
class UsbAudioSink(
    private val delegate: AudioSink,
    private val context: Context,
    private val config: UsbAudioSinkConfig = UsbAudioSinkConfig()
) : ForwardingAudioSink(delegate) {

    /** Source file bit depth (16, 24, 32). Auto-detected from NativeAudioEngine. */
    private var trackBitDepth: Int = 0

    /** True when the native engine is running. Read by [NativeEngineAwareLoadControl]
     *  to stop ExoPlayer from loading data (prevents SD card I/O contention).
     *  Temporarily set to false during seek to allow one post-seek load. */
    @Volatile
    var isNativeEngineActive: Boolean = false
        private set


    /** File path of the current track. Set internally by [PlayerIntegrationListener]
     *  from the MediaItem URI. When non-null and pointing to a FLAC file, the native
     *  audio engine is used. For HTTP URIs, this is null (ExoPlayer pipeline fallback). */
    private var currentTrackPath: String? = null

    /** Clean up a finished native engine and apply deferred USB config.
     *  @return true if an engine was cleaned up (caller should restart playback). */
    @Synchronized
    private fun cleanupFinishedEngine(): Boolean {
        val engine = nativeEngine
        if (engine != null && !engine.isRunning) {
            engine.destroy()
            nativeEngine = null
            isNativeEngineActive = false
            activeEnginePath = null
            windowOffsetUs = -1L
            usbStartMediaTimeNeedsInit = true
            Log.i(TAG, "cleanupFinishedEngine: old engine cleared")

            return true
        }
        return false
    }

    /** Creates a native engine if the USB stream is ready and no engine exists.
     *  Replaces the streaming thread fallback if one was set up due to rate mismatch. */
    @Synchronized
    private fun createEngineIfNeeded() {
        if (nativeEngine?.isRunning == true) return  // already running
        val stream = usbAudioStream
        if (stream != null && stream.isAlive) {
            // Clean up dead engine if exists
            val old = nativeEngine
            if (old != null && !old.isRunning) {
                old.destroy()
                nativeEngine = null
                activeEnginePath = null
            }
            if (nativeEngine == null) {
                windowOffsetUs = -1L
                usbStartMediaTimeNeedsInit = true
                startNativeEngineIfFlac(stream)
                // Engine starts paused with engineNeedsInitialSeek = true.
                // Temporarily unblock LoadControl so ExoPlayer sends at least one
                // handleBuffer — needed to capture presentationTimeUs and seek.
                // Without this, the LoadControl blocks immediately and the engine
                // stays paused forever (HTTP→local transition race).
                if (nativeEngine != null) {
                    isNativeEngineActive = false
                }
                Log.i(TAG, "createEngineIfNeeded: engine=${nativeEngine != null}")
            }
        }
    }

    private var usbAudioStream: UsbAudioStream? = null
    private val usbAudioDevice = UsbAudioDevice.getInstance(context)
    private var usbStreamingThread: UsbStreamingThread? = null
    @Volatile private var nativeEngine: NativeAudioEngine? = null
    private var selectedDevice: UsbDevice? = null
    private var selectedFormat: UsbAudioFormat? = null
    private var configuredEncoding = C.ENCODING_INVALID
    @Volatile private var usbFailure: String? = null
    private var lastHealthCheckMs = 0L
    @Volatile private var generation = 0L
    @Volatile private var released = false
    private var cleanupPending = false
    private var delegateReleased = false
    private val failureLock = Any()
    @Volatile private var lastKnownPositionUs = AudioSink.CURRENT_POSITION_NOT_SET
    @Volatile private var currentMediaId: String? = null
    @Volatile private var failedMediaId: String? = null
    @Volatile private var failedMediaPositionUs: Long? = null
    @Volatile private var failedWindowOffsetUs = -1L
    private var pendingNativeRecoveryUs: Long? = null

    /** Optional consumer of the decoded PCM passing through this sink (e.g. an audio visualizer).
     *  Invoked for every buffer regardless of DAC state. The native libFLAC engine path decodes in
     *  C++ and feeds the tap separately via [nativePcmTap]. */
    fun interface PcmTap { fun onPcm(buffer: ByteBuffer, encoding: Int, channelCount: Int, sampleRate: Int) }
    @Volatile var pcmTap: PcmTap? = null

    /** Read-only transport evidence. No paths, USB names, IDs or authenticated URLs escape here. */
    data class PlaybackTelemetry(
        val usbActive: Boolean = false,
        val nativeFlac: Boolean = false,
        val transportRate: Int = 0,
        val transportChannels: Int = 0,
        val transportDepth: Int = 0,
        val transportContainerBits: Int = 0,
        val transportValidBits: Int = 0,
        val observedClockRate: Int? = null,
        val clockRequestAccepted: Boolean = false,
        val sourceRate: Int = 0,
        val sourceChannels: Int = 0,
        val sourceDepth: Int = 0,
        val decodedFormat: Format? = null,
        val nativeSpeed: Double = 1.0,
        val tailSubmitted: Boolean = false,
        val failure: String? = null,
        val drained: Boolean = false,
        val submittedFrames: Long = 0,
        val completedFrames: Long = 0,
        val pendingFrames: Long = 0,
        val packetErrors: Long = 0,
        val timeouts: Long = 0,
    )
    @Volatile private var transportTelemetry = PlaybackTelemetry()
    @Volatile private var observedDecodedFormat: Format? = null
    @Volatile private var nativeSourceRate = 0
    @Volatile private var nativeSourceChannels = 0
    @Volatile private var nativeSourceDepth = 0
    @Volatile private var appliedNativeSpeed = 1.0
    @Volatile private var tailTelemetryEngine: NativeAudioEngine? = null
    val playbackTelemetry: PlaybackTelemetry get() {
        val live = usbAudioStream?.isAlive == true
        val native = live && nativeEngineActive
        val stream = usbAudioStream?.telemetry
        return transportTelemetry.copy(
            usbActive = live, nativeFlac = native,
            sourceRate = if (native) nativeSourceRate else 0,
            sourceChannels = if (native) nativeSourceChannels else 0,
            sourceDepth = if (native) nativeSourceDepth else 0,
            decodedFormat = observedDecodedFormat,
            nativeSpeed = if (native) appliedNativeSpeed else 1.0,
            // The driver exposes no crossfade-complete event. Keep this unknown for this engine.
            tailSubmitted = native && tailTelemetryEngine === nativeEngine,
            failure = usbFailure ?: usbStreamingThread?.failure ?: usbAudioStream?.telemetry?.lastError,
            drained = usbStreamingThread?.isDrained() == true || nativeEngine?.completed == true,
            submittedFrames = stream?.submittedFrames ?: 0,
            completedFrames = stream?.completedFrames ?: 0,
            pendingFrames = stream?.pendingFrames ?: 0,
            packetErrors = stream?.packetErrors ?: 0,
            timeouts = stream?.timeouts ?: 0,
        )
    }

    /** Varispeed ratio (1.0 = untouched bit-perfect). Applied to the native FLAC engine; remembered so a
     *  freshly-created engine for the next track inherits it. The non-FLAC streaming path is unaffected
     *  (those rare bit-perfect streams keep playing at 1.0). */
    @Volatile private var timeStretch: Double = 1.0
    fun setTimeStretch(speed: Float) {
        timeStretch = speed.toDouble()
        nativeEngine?.setSpeed(timeStretch)
        appliedNativeSpeed = timeStretch.coerceIn(0.5, 2.0)
    }

    // ── Crossfade ────────────────────────────────────────────────────
    // Set BEFORE advancing the queue. The outgoing file + position is captured here and applied to the
    // freshly-created INCOMING engine, which decodes it as a fade-out secondary. Tagged with the expected
    // incoming track so a manual skip / non-FLAC transition in the meantime can't bleed the tail into an
    // unrelated track; always one-shot (cleared on the first engine-creation attempt that sees it).
    @Volatile private var pendingTailUri: Uri? = null
    @Volatile private var pendingTailExpectedIncoming: Uri? = null
    private var pendingTailStartUs: Long = 0L
    private var pendingTailFadeMs: Long = 0L
    private var pendingTailCurve: Int = 0
    private var pendingTailProtect: Boolean = true

    fun setPendingTail(outgoingUri: Uri?, incomingUri: Uri?, startUs: Long, fadeMs: Long, curve: Int = 0, protect: Boolean = true) {
        pendingTailUri = outgoingUri
        pendingTailExpectedIncoming = incomingUri
        pendingTailStartUs = startUs
        pendingTailFadeMs = fadeMs
        pendingTailCurve = curve
        pendingTailProtect = protect
    }

    // Read only the FLAC STREAMINFO headers off the main thread before advancing a native queue.
    fun canCrossfadeNative(outgoing: Uri?, incoming: Uri?): Boolean {
        val a = flacFormat(resolveTrackPath(outgoing)) ?: return false
        val b = flacFormat(resolveTrackPath(incoming)) ?: return false
        return a.take(3) == b.take(3) && a[3] <= b[3]
    }

    private fun flacFormat(path: String?): List<Int>? = runCatching {
        if (path == null || !path.endsWith(".flac", true)) return@runCatching null
        val bytes = ByteArray(42).also { data -> java.io.DataInputStream(File(path).inputStream()).use { it.readFully(data) } }
        fun u(i: Int) = bytes[i].toInt() and 255
        if (String(bytes, 0, 4) != "fLaC" || (u(4) and 127) != 0 || u(5) != 0 || u(6) != 0 || u(7) != 34) return@runCatching null
        listOf((u(18) shl 12) or (u(19) shl 4) or (u(20) shr 4), ((u(20) shr 1) and 7) + 1,
            (((u(20) and 1) shl 4) or (u(21) shr 4)) + 1, (u(10) shl 8) or u(11))
    }.getOrNull()

    fun clearPendingTail() {
        pendingTailUri = null
        pendingTailExpectedIncoming = null
    }

    private fun applyPendingTail(engine: NativeAudioEngine, incomingPath: String) {
        val uri = pendingTailUri ?: return
        // one-shot: clear unconditionally so a stale tail can never carry across to a later track
        pendingTailUri = null
        val expected = pendingTailExpectedIncoming
        pendingTailExpectedIncoming = null
        // only mix if THIS engine really is the intended incoming track (guards skip/seek races)
        if (expected != null && resolveTrackPath(expected) != incomingPath) {
            Log.i(TAG, "Crossfade tail discarded — incoming track changed")
            return
        }
        val path = resolveTrackPath(uri) ?: return
        if (!path.lowercase().endsWith(".flac")) return   // only local FLAC has a native engine to mix
        try {
            val pfd = android.os.ParcelFileDescriptor.open(File(path), android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            engine.startTailFade(pfd.fd, pendingTailStartUs, pendingTailFadeMs * 1000L, pendingTailCurve, pendingTailProtect)
            tailTelemetryEngine = engine
            pfd.close()   // native dup'd the fd
            Log.i(TAG, "Crossfade tail queued: ${File(path).name} from ${pendingTailStartUs / 1000}ms over ${pendingTailFadeMs}ms")
        } catch (e: Exception) {
            Log.w(TAG, "applyPendingTail failed: ${e.message}")
        }
    }

    /** Pull the latest mono samples from the native FLAC engine for the visualizer (bit-perfect
     *  local files decode in C++ and never reach handleBuffer, so they're polled instead). */
    fun readNativePcm(out: FloatArray): Int {
        val e = nativeEngine
        return if (e != null && e.isRunning) e.readVisualizer(out) else 0
    }
    val nativeEngineActive: Boolean get() = nativeEngine?.isRunning == true
    val nativeEngineSampleRate: Int get() = currentSampleRate

    // Decoded-stream format, captured in configure() regardless of DAC state so the tap works
    // whether or not a USB device is connected.
    private var tapEncoding: Int = C.ENCODING_PCM_16BIT
    private var tapSampleRate: Int = 0
    private var tapChannelCount: Int = 0

    private var currentEncoding: Int = C.ENCODING_PCM_16BIT
    private var currentSampleRate: Int = 0
    private var currentChannelCount: Int = 0
    private var pendingVolume: Float = 1f
    private var delegateMuted: Boolean = false
    private var handleBufferCallCount: Long = 0

    /**
     * Media timeline offset captured from the first buffer's presentationTimeUs
     * after each flush/init. Maps framesWritten=0 to the correct song position.
     * DefaultAudioSink calls this startMediaTimeUs internally.
     */
    private var usbStartMediaTimeUs: Long = 0L
    private var usbStartMediaTimeNeedsInit: Boolean = true
    /** framesWritten value at the last startMediaTime capture. The USB stream's framesWritten is
     *  cumulative since the stream was created (not reset per track), so the ExoPlayer-pipeline
     *  position must measure frames *since this track started* as a delta from here — otherwise the
     *  previous track's frames carry over and the reported position starts a chunk past 0. */
    private var usbStartFrames: Long = 0L
    private var handledEndOfStream: Boolean = false

    /** ExoPlayer's window offset, captured once per track. Never reset by flush.
     *  Used to convert between ExoPlayer timeline and FLAC absolute position. */
    private var windowOffsetUs: Long = -1L

    /** True when the engine was just created and needs its first seek from handleBuffer.
     *  Prevents play() from resuming the engine before the correct position is known. */
    private var engineNeedsInitialSeek: Boolean = false

    /** Path of the file the current native engine is decoding. Used to detect track changes. */
    private var activeEnginePath: String? = null


    /** Max queue entries before returning false for backpressure (paces ExoPlayer).
     *  Pause responsiveness is handled by pauseStreaming(), not queue size. */
    private val QUEUE_BACKPRESSURE_THRESHOLD = 16

    /** Tracks ExoPlayer's play/pause state so seek-while-paused doesn't auto-resume. */
    private var isPlaying = false

    /** Deferred USB reconfiguration — applied after engine finishes playing. */
    private var deferredRate: Int = 0
    private var deferredChannels: Int = 0
    private var deferredEncoding: Int = 0
    private var hasDeferredConfig: Boolean = false
    private data class PendingConfiguration(val format: Format, val bufferSize: Int, val channels: IntArray?)
    private var pendingConfiguration: PendingConfiguration? = null
    private var currentConfiguration: PendingConfiguration? = null

    private fun signalUsbFailure(reason: String) {
        synchronized(failureLock) {
            if (usbFailure != null) return
            usbFailure = reason
        }
        Log.w(TAG, reason)
        isNativeEngineActive = false
        val token = generation
        val recoveryPositionUs = nativeEngine?.getPositionUs() ?: lastKnownPositionUs.takeIf { it >= 0 }?.let {
            (it - windowOffsetUs.coerceAtLeast(0)).coerceAtLeast(0)
        }
        failedMediaPositionUs = recoveryPositionUs
        failedMediaId = currentMediaId
        failedWindowOffsetUs = windowOffsetUs
        Handler(Looper.getMainLooper()).post {
            if (!released && token == generation && usbFailure == reason) {
                if (!config.allowAndroidFallback) attachedPlayer?.pause()
                else if (recoveryPositionUs != null) attachedPlayer?.seekTo(recoveryPositionUs / 1000)
                config.onUsbFailure(reason)
            }
        }
    }

    private fun settleUsbFailure(): Boolean {
        if (usbFailure == null) return true
        if (!releaseUsbStream()) return false
        usbAudioDevice.setAltSetting(0)
        usbAudioDevice.closeDevice()
        return true
    }

    private fun checkUsbHealth() {
        if (!config.bitPerfectEnabled || usbAudioStream == null) return
        usbStreamingThread?.failure?.let(::signalUsbFailure)
        usbAudioStream?.telemetry?.lastError?.let(::signalUsbFailure)
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastHealthCheckMs >= 250) {
            lastHealthCheckMs = now
            val device = selectedDevice
            if (device == null || !usbAudioDevice.isAttached(device)) signalUsbFailure("USB DAC disconnected.")
            else if (!usbAudioDevice.hasPermission(device)) signalUsbFailure("USB permission is unavailable.")
        }
    }

    override fun supportsFormat(format: Format): Boolean = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatSupport(format: Format): Int = if (config.bitPerfectEnabled && format.sampleMimeType == "audio/raw" &&
        format.pcmEncoding in listOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT) &&
        format.channelCount in 1..2) AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY else delegate.getFormatSupport(format)


    @Synchronized
    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        if (nativeEngine == null && usbStreamingThread?.hasPendingData() == true && usbFailure == null) {
            pendingConfiguration = PendingConfiguration(inputFormat, specifiedBufferSize, outputChannels?.copyOf())
            usbStreamingThread?.endOfStream()
            return
        }
        currentConfiguration = PendingConfiguration(inputFormat, specifiedBufferSize, outputChannels?.copyOf())
        observedDecodedFormat = inputFormat
        val enc = inputFormat.pcmEncoding
        currentEncoding = enc
        tapEncoding = enc
        tapSampleRate = inputFormat.sampleRate
        tapChannelCount = inputFormat.channelCount
        handledEndOfStream = false
        if (nativeEngine?.isRunning == true && currentTrackPath == activeEnginePath) {
            if (inputFormat.sampleRate != currentSampleRate || inputFormat.channelCount != currentChannelCount || enc != configuredEncoding) {
                deferredRate = inputFormat.sampleRate
                deferredChannels = inputFormat.channelCount
                deferredEncoding = enc
                hasDeferredConfig = true
            }
            return
        }
        if (nativeEngine != null && !releaseUsbStream()) return
        generation++
        usbFailure = null
        if (config.bitPerfectEnabled) {
            configureUsbBitPerfect(inputFormat.sampleRate, inputFormat.channelCount, enc)
            if (!settleUsbFailure()) return
            windowOffsetUs = -1L
            usbStartMediaTimeNeedsInit = true
        } else if (!releaseUsbStream()) return
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
        if (usbAudioStream != null || config.bitPerfectEnabled && !config.allowAndroidFallback) {
            muteDelegateIfNeeded()
        } else unmuteDelegateIfNeeded()
    }
    @Synchronized
    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        // Feed the visualizer on every decoded buffer, whether or not a DAC is attached. Absolute
        // indexing inside the tap leaves the buffer position untouched for the real sink below.
        checkUsbHealth()
        if (!settleUsbFailure()) return false
        pendingConfiguration?.let { next ->
            if (usbStreamingThread != null && usbStreamingThread?.isDrained() != true) return false
            pendingConfiguration = null
            configure(next.format, next.bufferSize, next.channels)
        }

        val stream = usbAudioStream
        if (config.bitPerfectEnabled && stream?.isAlive == true) {
            muteDelegateIfNeeded()

            // Fallback engine creation: if no engine and no streaming thread,
            // try creating one now (path and USB rate should both be correct by this point)
            if (nativeEngine == null && usbStreamingThread == null) {
                startNativeEngineIfFlac(stream)
                // Engine starts paused. The usbStartMediaTimeNeedsInit block below
                // will capture presentationTimeUs and seek to the correct position.
            }

            // Capture media timeline offset from first buffer (needed for position tracking)
            if (usbStartMediaTimeNeedsInit) {
                usbStartMediaTimeUs = maxOf(0L, presentationTimeUs)
                // Anchor the cumulative USB frame counter to this track/seek so the pipeline
                // position below counts only frames played since this point.
                usbStartFrames = usbAudioStream?.telemetry?.completedFrames ?: 0L
                usbStartMediaTimeNeedsInit = false
                // Save window offset once per track (not reset by flush/seek).
                // windowOffset = ExoPlayer timeline position of track start (position 0).
                // On fresh start: initialPlayerPosition=0 → offset = pts (correct).
                // On restore at 158s: initialPlayerPosition=158s → offset = pts - 158s (correct).
                if (windowOffsetUs < 0) {
                    windowOffsetUs = presentationTimeUs - initialPlayerPositionUs
                }
                Log.i(TAG, "startMediaTimeUs=$usbStartMediaTimeUs windowOffset=$windowOffsetUs " +
                        "initialPos=${initialPlayerPositionUs / 1000}ms startFrames=$usbStartFrames")

                // After a flush (seek) or initial start, seek the native engine
                // to the correct position and resume it.
                val engine = nativeEngine
                if (engine != null && windowOffsetUs >= 0) {
                    val flacPositionUs = pendingNativeRecoveryUs ?: (presentationTimeUs - windowOffsetUs)
                    if (flacPositionUs >= 0) {
                        engine.seek(flacPositionUs)
                        pendingNativeRecoveryUs = null
                        if (isPlaying) engine.resume()
                        engineNeedsInitialSeek = false
                        Log.i(TAG, "Native engine seek to ${flacPositionUs / 1_000_000}s (playing=$isPlaying)")
                    }
                }
                // Re-block LoadControl now that we have the position.
                // flush() temporarily unblocked it to allow this handleBuffer call.
                if (nativeEngine?.isRunning == true) {
                    isNativeEngineActive = true
                }
            }

            // Native FLAC engine handles decode+USB directly — ignore ExoPlayer data.
            val engine = nativeEngine
            if (engine != null) {
                if (engine.isRunning || engine.completed) {
                    buffer.position(buffer.limit())
                    return true
                }
                signalUsbFailure(engine.error ?: "Native USB decoding stopped before completion.")
                return false
            }

            val thread = usbStreamingThread ?: return false

            // Backpressure: if queue is nearly full, tell ExoPlayer to retry later.
            // This paces the renderer to the USB DAC's consumption rate without
            // depending on the delegate AudioTrack.
            if (thread.queueSize() >= QUEUE_BACKPRESSURE_THRESHOLD) {
                return false
            }

            handleBufferCallCount++
            val frameBytes = PcmUtils.bytesPerSample(currentEncoding) * currentChannelCount
            if (frameBytes <= 0 || buffer.remaining() % frameBytes != 0) {
                signalUsbFailure("Decoded PCM contains an incomplete frame.")
                return false
            }
            val bytes = minOf(buffer.remaining(), 32768 / frameBytes * frameBytes)
            val snapshot: ByteBuffer = buffer.slice().order(ByteOrder.LITTLE_ENDIAN).apply { limit(bytes) }
            val accepted: Boolean

            if (currentEncoding == C.ENCODING_PCM_FLOAT) {
                val totalSamples = snapshot.remaining() / 4
                if (totalSamples > 0) {
                    val floatBuf = FloatArray(totalSamples)
                    snapshot.asFloatBuffer().get(floatBuf)
                    if (handleBufferCallCount <= 3) {
                        Log.i(TAG, "handleBuffer #$handleBufferCallCount: FLOAT samples=$totalSamples")
                    }
                    accepted = thread.enqueue(floatBuf)
                } else accepted = true
            } else {
                val remaining = snapshot.remaining()
                if (remaining > 0) {
                    val rawBytes = ByteArray(remaining)
                    snapshot.get(rawBytes)
                    if (handleBufferCallCount <= 3) {
                        val bps = PcmUtils.bytesPerSample(currentEncoding)
                        Log.i(TAG, "handleBuffer #$handleBufferCallCount: RAW ${bps*8}bit bytes=$remaining")
                    }
                    accepted = thread.enqueueRaw(rawBytes, currentEncoding)
                } else accepted = true
            }

            if (!accepted) return false
            pcmTap?.onPcm(buffer.duplicate().apply { limit(position() + bytes) }, tapEncoding, tapChannelCount, tapSampleRate)
            buffer.position(buffer.position() + bytes)
            return !buffer.hasRemaining()
        }

        if (config.bitPerfectEnabled && !config.allowAndroidFallback) {
            muteDelegateIfNeeded()
            return false
        }
        unmuteDelegateIfNeeded()
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    // ── Position tracking via USB framesWritten ────────────────────────

    private var posLogCount = 0L

    private var engineEndNotified = false

    @Synchronized
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        checkUsbHealth()
        val engine = nativeEngine
        engine?.error?.let(::signalUsbFailure)
        if (usbFailure != null) {
            if (!settleUsbFailure()) return lastKnownPositionUs
            return if (config.allowAndroidFallback) super.getCurrentPositionUs(sourceEnded) else lastKnownPositionUs
        }
        if (config.bitPerfectEnabled && usbAudioStream != null) {
            if (engine?.completed == true && !engineEndNotified) {
                engineEndNotified = true
                val token = generation
                val player = attachedPlayer
                Handler(Looper.getMainLooper()).post {
                    if (!released && token == generation && nativeEngine === engine && engine.completed && attachedPlayer === player) {
                        if (player?.hasNextMediaItem() == true) player.seekToNextMediaItem() else player?.pause()
                    }
                }
            }
            val position = if (engine?.isCreated == true && windowOffsetUs >= 0) {
                windowOffsetUs + engine.getPositionUs()
            } else {
                if (usbStartMediaTimeNeedsInit) return AudioSink.CURRENT_POSITION_NOT_SET
                val frames = (usbAudioStream?.telemetry?.completedFrames ?: 0L) - usbStartFrames
                if (currentSampleRate > 0) usbStartMediaTimeUs + frames.coerceAtLeast(0) * C.MICROS_PER_SECOND / currentSampleRate
                else AudioSink.CURRENT_POSITION_NOT_SET
            }
            lastKnownPositionUs = position
            return position
        }
        return if (config.bitPerfectEnabled && !config.allowAndroidFallback) AudioSink.CURRENT_POSITION_NOT_SET
        else super.getCurrentPositionUs(sourceEnded)
    }

    @Synchronized
    override fun isEnded(): Boolean {
        if (config.bitPerfectEnabled && usbFailure != null && !config.allowAndroidFallback) return false
        if (usbAudioStream != null) {
            nativeEngine?.let { return it.completed && usbFailure == null }
            return handledEndOfStream && usbStreamingThread?.isDrained() == true && usbFailure == null
        }
        return super.isEnded()
    }

    @Synchronized
    override fun hasPendingData(): Boolean {
        if (usbAudioStream != null) {
            nativeEngine?.let { return it.isRunning || !it.completed && it.error == null }
            return usbStreamingThread?.hasPendingData() == true
        }
        return super.hasPendingData()
    }

    @Synchronized
    override fun playToEndOfStream() {
        handledEndOfStream = true
        if (usbAudioStream != null) usbStreamingThread?.endOfStream()
        else if (!config.bitPerfectEnabled || config.allowAndroidFallback) super.playToEndOfStream()
    }
    @Synchronized
    override fun play() {
        if (!isPlaying && config.bitPerfectEnabled && !config.allowAndroidFallback && usbFailure != null) {
            if (!settleUsbFailure()) return
            val position = failedMediaPositionUs?.takeIf { failedMediaId == currentMediaId }
            val retry = pendingConfiguration ?: currentConfiguration
            if (retry != null) {
                pendingConfiguration = null
                configure(retry.format, retry.bufferSize, retry.channels)
                val player = attachedPlayer
                if (usbFailure == null && position != null && nativeEngine != null) {
                    windowOffsetUs = failedWindowOffsetUs
                    pendingNativeRecoveryUs = position
                } else if (usbFailure == null && position != null && player != null) {
                    val token = generation
                    Handler(Looper.getMainLooper()).post {
                        if (!released && generation == token && attachedPlayer === player) {
                            runCatching { player.seekTo(position / 1000) }.onFailure {
                                signalUsbFailure("USB playback could not restore its position.")
                            }
                        }
                    }
                }
            }
        }
        super.play()
        isPlaying = true
        val resumed = if (!engineNeedsInitialSeek) { nativeEngine?.resume(); true } else false
        usbStreamingThread?.resumeStreaming()
        Log.i(TAG, "play() needsSeek=$engineNeedsInitialSeek resumed=$resumed")
    }

    @Synchronized
    override fun pause() {
        isPlaying = false
        if (!engineNeedsInitialSeek) nativeEngine?.pause()
        usbStreamingThread?.pauseStreaming()
        super.pause()
    }

    @Synchronized
    override fun setVolume(volume: Float) {
        pendingVolume = volume
        if (config.bitPerfectEnabled && (usbAudioStream != null || !config.allowAndroidFallback)) {
            muteDelegateIfNeeded()
        } else {
            unmuteDelegateIfNeeded()
        }
    }

    @Synchronized
    override fun flush() {
        super.flush()
        pendingNativeRecoveryUs = null
        if (nativeEngine?.completed == true) {
            if (!releaseUsbStream()) return
            observedDecodedFormat?.let { configure(it, 0, null) }
        }
        pendingConfiguration?.let { next ->
            pendingConfiguration = null
            if (!releaseUsbStream()) return
            configure(next.format, next.bufferSize, next.channels)
        }
        // Native engine handles its own flush/seek internally
        // ExoPlayer pipeline: flush queue + native stream
        if (nativeEngine != null) {
            nativeEngine?.pause()
            engineNeedsInitialSeek = true
        } else usbStreamingThread?.flush()
        if (isPlaying) usbStreamingThread?.resumeStreaming()
        usbStartMediaTimeNeedsInit = true
        handledEndOfStream = false
        // Temporarily unblock LoadControl so ExoPlayer loads at least one chunk
        // after seek. handleBuffer will re-block once it captures presentationTimeUs.
        // Without this, the LoadControl blocks ALL post-seek loading and the engine
        // never knows where to seek to.
        if (nativeEngine?.isRunning == true) {
            isNativeEngineActive = false
        }
    }

    @Synchronized
    override fun reset() {
        generation++
        pendingConfiguration = null
        currentConfiguration = null
        observedDecodedFormat = null
        failedMediaPositionUs = null
        failedMediaId = null
        pendingNativeRecoveryUs = null
        isPlaying = false
        releaseUsbStream()
        super.reset()
        delegateMuted = false
        usbStartMediaTimeNeedsInit = true
        handledEndOfStream = false
        windowOffsetUs = -1L
        lastKnownPositionUs = AudioSink.CURRENT_POSITION_NOT_SET
    }

    @Synchronized
    override fun release() {
        released = true
        generation++
        if (delegateReleased) return
        if (!releaseUsbStream()) {
            if (!cleanupPending) {
                cleanupPending = true
                val worker = usbStreamingThread
                Thread({ worker?.awaitStopped(); release() }, "UsbSinkCleanup").start()
            }
            return
        }
        // App is tearing down: hand the DAC back to the system. releaseUsbStream() deliberately
        // keeps the device open for between-track reuse, so on a real release we must also drop the
        // streaming interface to its zero-bandwidth alt and close the connection. Otherwise the DAC
        // is left claimed in a streaming alt setting (kernel driver detached) and stays wedged for
        // every other app until it's physically unplugged and back in.
        runCatching {
            usbAudioDevice.setAltSetting(0)
            usbAudioDevice.closeDevice()
        }
        delegateReleased = true
        super.release()
    }

    // ── USB bit-perfect configuration ───────────────────────────────

    @Synchronized
    private fun configureUsbBitPerfect(sampleRate: Int, channelCount: Int, encoding: Int) {
        val sourceBits = when (encoding) {
            C.ENCODING_PCM_16BIT -> 16
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_FLOAT -> 24
            C.ENCODING_PCM_32BIT -> 32
            else -> { signalUsbFailure("The decoded PCM format is unsupported."); return }
        }
        val device = usbAudioDevice.findUsbAudioDevice()
        if (device == null) { releaseUsbStream(); signalUsbFailure("No USB DAC is connected."); return }
        if (!usbAudioDevice.hasPermission(device)) {
            releaseUsbStream(); signalUsbFailure("USB permission is unavailable."); return
        }
        if (sampleRate == currentSampleRate && channelCount == currentChannelCount && configuredEncoding == encoding &&
            selectedDevice?.deviceId == device.deviceId && usbAudioStream?.isAlive == true && usbFailure == null) {
            usbStreamingThread?.flush()
            return
        }
        if (!releaseUsbStream()) return
        val info = usbAudioDevice.openDevice(device)
        if (info == null) { signalUsbFailure(usbAudioDevice.lastFailure ?: "The USB DAC could not be opened."); return }
        val nativeFormat = flacFormat(currentTrackPath)?.takeIf { it[0] == sampleRate && it[1] == channelCount }
        val format = usbAudioDevice.selectFormat(sampleRate, channelCount, nativeFormat?.get(2) ?: sourceBits)
        if (format == null) {
            signalUsbFailure("The USB DAC does not support this PCM format."); return
        }
        val clock = usbAudioDevice.configureFormat(format, sampleRate)
        if (!clock.verified) { signalUsbFailure(clock.failure ?: "The USB clock could not be verified."); return }
        val stream = UsbAudioStream(info.fd, format.interfaceId, format.endpointOut, format.endpointFeedback,
            sampleRate, channelCount, format.containerBits, format.maxPacketSize, validBits = format.validBits,
            alternateSetting = format.alternateSetting)
        if (!stream.isReady || !stream.start()) {
            stream.release()
            usbAudioDevice.setAltSetting(0)
            signalUsbFailure("USB output could not start.")
            return
        }
        usbAudioStream = stream
        selectedDevice = device
        selectedFormat = format
        currentSampleRate = sampleRate
        currentChannelCount = channelCount
        configuredEncoding = encoding
        transportTelemetry = PlaybackTelemetry(
            transportRate = sampleRate, transportChannels = channelCount, transportDepth = format.validBits,
            transportContainerBits = format.containerBits, transportValidBits = format.validBits,
            observedClockRate = clock.observedHz, clockRequestAccepted = clock.verified,
        )
        muteDelegateIfNeeded()
        startNativeEngineIfFlac(stream)
    }
    /** Try to start a native FLAC engine. Falls back to ExoPlayer streaming thread. */
    @Synchronized
    private fun startNativeEngineIfFlac(stream: UsbAudioStream) {
        if (nativeEngine != null) return  // already created (synchronized method)

        // Stop existing streaming thread (mutually exclusive with native engine)
        usbStreamingThread?.stop()
        usbStreamingThread = null

        val path = currentTrackPath
        if (path != null && path.lowercase().endsWith(".flac")) {
            val engine = NativeAudioEngine()
            try {
                val fd = android.os.ParcelFileDescriptor.open(
                    File(path), android.os.ParcelFileDescriptor.MODE_READ_ONLY
                )
                val created = engine.createFromFd(fd.fd, stream.nativeHandle)
                fd.close()
                if (created && engine.start(paused = true)) {
                    // Verify FLAC sample rate matches USB stream — prevents distortion
                    // when ExoPlayer's queue and onMediaItemTransition disagree about
                    // which track is playing (e.g., cross-album Recently Played lists).
                    if (engine.getSampleRate() != currentSampleRate || engine.getChannels() != currentChannelCount ||
                        engine.getBitsPerSample() > (selectedFormat?.validBits ?: 0)) {
                        Log.w(TAG, "Rate mismatch: FLAC=${engine.getSampleRate()} USB=$currentSampleRate" +
                                " — falling back to ExoPlayer pipeline")
                        engine.stop()
                        engine.destroy()
                    } else {
                        // Start paused — will resume in handleBuffer after capturing
                        // the correct seek position from ExoPlayer's presentationTimeUs.
                        engine.pause()
                        engine.setSpeed(timeStretch)   // carry the active varispeed onto the new track
                        nativeEngine = engine
                        isNativeEngineActive = true
                        engineNeedsInitialSeek = true
                        engineEndNotified = false
                        activeEnginePath = path
                        trackBitDepth = engine.getBitsPerSample()
                        nativeSourceRate = engine.getSampleRate()
                        nativeSourceChannels = engine.getChannels()
                        nativeSourceDepth = trackBitDepth
                        appliedNativeSpeed = timeStretch.coerceIn(0.5, 2.0)
                        applyPendingTail(engine, path)   // crossfade: mix the outgoing track's tail into this one
                        Log.i(TAG, "Native FLAC engine started (paused, awaiting seek) for: ${File(path).name} ${trackBitDepth}-bit")
                        return
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Native engine failed: ${e.message}")
            }
            engine.destroy()
        }

        val decodedBits = if (currentEncoding == C.ENCODING_PCM_FLOAT) 24 else PcmUtils.bytesPerSample(currentEncoding) * 8
        if (decodedBits > (selectedFormat?.validBits ?: 0)) {
            signalUsbFailure("Decoded PCM exceeds the USB format's valid precision.")
            return
        }
        usbStreamingThread = UsbStreamingThread(stream, ::signalUsbFailure).also {
            it.start()
            if (isPlaying) it.resumeStreaming()
        }
        Log.i(TAG, "Using ExoPlayer pipeline (non-FLAC or engine failed)")
    }

    // ── USB stream release ──────────────────────────────────────────

    @Synchronized
    private fun releaseUsbStream(): Boolean {
        val stream = usbAudioStream ?: return true

        // Stop USB stream FIRST — sets ctx->running=false, which unblocks
        // submitPcmToUrbs inside the native engine's decode thread.
        // Without this, nativeEngine.stop() deadlocks on pthread_join.
        stream.stop()

        // Now safe to stop native engine (decode thread can exit)
        nativeEngine?.stop()
        nativeEngine?.destroy()
        nativeEngine = null
        isNativeEngineActive = false

        // Stop the streaming thread (drains queue, joins thread)
        if (usbStreamingThread?.stop() == false) {
            signalUsbFailure("USB worker did not stop.")
            return false
        }
        usbStreamingThread = null

        // Drain ALL in-flight URBs — MUST complete before setAlt(0)
        val drained = stream.drainUrbs()
        Log.i(TAG, "USB stream drained $drained URBs")

        // Release native context
        stream.release()
        usbAudioStream = null
        transportTelemetry = PlaybackTelemetry()
        tailTelemetryEngine = null
        selectedDevice = null
        selectedFormat = null

        // Keep device connection open between tracks (standard practice)
        if (!config.bitPerfectEnabled || config.allowAndroidFallback) unmuteDelegateIfNeeded()
        Log.i(TAG, "USB audio stream released (device kept open)")
        return true
    }

    // ── Delegate volume management ──────────────────────────────────

    private fun muteDelegateIfNeeded() {
        if (!delegateMuted) { super.setVolume(0f); delegateMuted = true }
    }

    private fun unmuteDelegateIfNeeded() {
        super.setVolume(pendingVolume)
        delegateMuted = false
    }

    // ── Player integration (attachToPlayer) ──────────────────────

    @Volatile private var attachedPlayer: Player? = null
    private var integrationListener: Player.Listener? = null

    /**
     * Attach this sink to an ExoPlayer instance. Registers an internal
     * [Player.Listener] that handles:
     * - Extracting the file path from each [MediaItem]'s URI
     * - Cleaning up finished native engines on track transitions
     * - Creating new native engines for local FLAC files
     * - Advancing to the next track when the native engine reaches EOF
     *
     * Must be called on the main thread, after [ExoPlayer.Builder.build].
     */
    fun attachToPlayer(player: Player) {
        val oldListener = integrationListener
        val oldPlayer = attachedPlayer
        if (oldListener != null && oldPlayer != null) {
            oldPlayer.removeListener(oldListener)
        }

        val listener = PlayerIntegrationListener()
        player.addListener(listener)
        attachedPlayer = player
        integrationListener = listener
        Log.i(TAG, "attachToPlayer: integration listener registered")
    }

    /** Detach from the current player. Call before player.release(). */
    fun detachFromPlayer() {
        val listener = integrationListener
        val player = attachedPlayer
        if (listener != null && player != null) {
            player.removeListener(listener)
        }
        attachedPlayer = null
        integrationListener = null
    }

    /** Player position (us) captured in onMediaItemTransition. Used to calculate
     *  the correct window offset on restore (first handleBuffer pts is at the
     *  restored position, not at 0). */
    @Volatile
    private var initialPlayerPositionUs: Long = 0L

    private inner class PlayerIntegrationListener : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            synchronized(this@UsbAudioSink) {
            if (mediaItem == null) return
            currentMediaId = mediaItem.mediaId

            // The new track's start position, used to compute windowOffset. Only a genuine queue
            // restore (PLAYLIST_CHANGED, e.g. resuming at 158s) carries a real mid-track position.
            // Every other transition (auto-advance, skip, repeat) starts the next track at 0, and
            // reading currentPosition mid-advance is unreliable — it can still report the finished
            // track's end, which corrupts windowOffset and freezes the position at the end.
            initialPlayerPositionUs = if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                (attachedPlayer?.currentPosition ?: 0L) * 1000L
            } else 0L
            Log.i(TAG, "onMediaItemTransition: reason=$reason initialPlayerPos=${initialPlayerPositionUs / 1000}ms")

            val uri = mediaItem.localConfiguration?.uri

            // 1. Clean up finished engine from previous track
            val engineFinished = cleanupFinishedEngine()

            // 2. Resolve file path from URI
            val resolvedPath = resolveTrackPath(uri)
            currentTrackPath = resolvedPath
            if (hasDeferredConfig && nativeEngine == null) {
                hasDeferredConfig = false
                configureUsbBitPerfect(deferredRate, deferredChannels, deferredEncoding)
            }
            Log.i(TAG, "onMediaItemTransition: scheme=${uri?.scheme} local=${resolvedPath != null}")

            // A non-FLAC incoming track will never create a native engine to consume a pending crossfade
            // tail, so drop it now rather than letting it linger and attach to some later FLAC track.
            if (resolvedPath == null || !resolvedPath.lowercase().endsWith(".flac")) clearPendingTail()

            // 3. Create engine if local FLAC
            if (resolvedPath != null) {
                createEngineIfNeeded()
            }

            // 4. If previous engine finished, reset position for new track
            if (engineFinished) {
                attachedPlayer?.seekTo(0)
            }
            }
        }
    }

    /**
     * Resolve a [MediaItem]'s URI to a local file path for the native engine.
     *
     * - `file:///path/to/song.flac` → `/path/to/song.flac`
     * - `/storage/.../song.flac` (bare path) → as-is
     * - `content://media/external/audio/123` → resolved via ContentResolver
     * - `http://` or `https://` → null (ExoPlayer pipeline handles these)
     */
    private fun resolveTrackPath(uri: Uri?): String? {
        if (uri == null) return null
        return when (uri.scheme) {
            "file" -> uri.path
            "content" -> resolveContentUri(uri)
            "http", "https" -> {
                Log.i(TAG, "resolveTrackPath: HTTP URI → ExoPlayer pipeline (no native engine)")
                null
            }
            null -> {
                // Bare path string (no scheme) — common in local music players
                val pathStr = uri.toString()
                if (pathStr.startsWith("/")) pathStr else null
            }
            else -> null
        }
    }

    private fun resolveContentUri(uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Audio.Media.DATA),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveContentUri failed: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "UsbAudioSink"

        /**
         * Wraps a [LoadControl] to suppress ExoPlayer loading when the native
         * FLAC engine is decoding directly to USB. Call BEFORE [ExoPlayer.Builder.build].
         *
         * @param delegate       Your app's LoadControl (e.g., DefaultLoadControl).
         * @param isEngineActive Lambda returning true when native engine is active.
         *                       Typical: `{ usbSink?.isNativeEngineActive == true }`
         */
        @JvmStatic
        @OptIn(UnstableApi::class)
        fun wrapLoadControl(
            delegate: LoadControl,
            isEngineActive: () -> Boolean
        ): LoadControl = NativeEngineAwareLoadControl(delegate, isEngineActive)
    }
}
