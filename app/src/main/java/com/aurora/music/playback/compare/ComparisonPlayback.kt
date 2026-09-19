package com.aurora.music.playback.compare

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

class ComparisonPlayback(context: Context, private val audio: ComparisonAudio,
    private val expectedDeviceId: Int? = null, private val preferredDeviceId: Int = 0,
    private val onInvalidated: (String) -> Unit) : AutoCloseable {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val running = AtomicBoolean(false)
    private val mixer = ComparisonMixer(audio)
    @Volatile private var requestedA = true
    @Volatile private var revision = 0L
    @Volatile private var track: AudioTrack? = null
    @Volatile private var routeId: Int? = null
    private var worker: Thread? = null
    private val ready = CompletableDeferred<Unit>()
    private val closed = CompletableDeferred<Unit>()
    private val handler = Handler(Looper.getMainLooper())
    val confirmedDeviceId: Int? get() = routeId
    @Volatile var invalidationReason: String? = null
        private set
    val playedFrames: Long get() = track?.playbackHeadPosition?.toLong()?.and(0xffffffffL) ?: 0L
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setOnAudioFocusChangeListener({ change ->
            if (change != AudioManager.AUDIOFOCUS_GAIN && running.get()) invalidate("Audio focus changed. The comparison stopped.")
        }, Handler(Looper.getMainLooper())).build()
    private val routing = AudioRouting.OnRoutingChangedListener { output ->
        if (running.get()) observeRoute(output.routedDevice?.id)
    }

    private fun observeRoute(device: Int?) {
        val required = routeId ?: expectedDeviceId ?: preferredDeviceId.takeIf { it != 0 }
        if (device == null) {
            if (routeId != null) invalidate("The output changed. The comparison stopped.")
        } else if (required != null && device != required) {
            invalidate("The comparison output does not match the selected route.")
        } else routeId = device
    }

    fun start() {
        check(running.compareAndSet(false, true))
        if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            running.set(false)
            invalidationReason = "Audio focus is unavailable."
            closed.complete(Unit)
            error("Audio focus is unavailable.")
        }
        worker = Thread({
            try {
                val minimum = AudioTrack.getMinBufferSize(audio.rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
                require(minimum > 0) { "This output cannot play the comparison format." }
                val output = AudioTrack.Builder().setAudioAttributes(focus.audioAttributes)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(audio.rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum, audio.rate / 20 * 8)).build()
                track = output
                check(output.state == AudioTrack.STATE_INITIALIZED) { "Could not open comparison output." }
                val requested = expectedDeviceId ?: preferredDeviceId.takeIf { it != 0 }
                if (requested != null) {
                    val device = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == requested }
                        ?: error("The selected output is no longer connected.")
                    require(output.setPreferredDevice(device)) { "The selected comparison output is unavailable." }
                }
                output.setVolume(0f)
                output.addOnRoutingChangedListener(routing, handler)
                output.play()
                val block = FloatArray(256 * 2)
                var seenRevision = -1L
                while (running.get()) {
                    observeRoute(output.routedDevice?.id)
                    if (!running.get()) break
                    if (!ready.isCompleted && routeId != null) {
                        output.setVolume(1f)
                        ready.complete(Unit)
                    }
                    val nextRevision = revision
                    if (nextRevision != seenRevision) {
                        mixer.select(requestedA)
                        seenRevision = nextRevision
                    }
                    if (ready.isCompleted) mixer.render(block, 256) else block.fill(0f)
                    var written = 0
                    while (written < block.size && running.get()) {
                        val count = output.write(block, written, block.size - written, AudioTrack.WRITE_BLOCKING)
                        check(count > 0) { "Comparison output stopped accepting audio." }
                        written += count
                    }
                }
            } catch (failure: Exception) {
                if (running.get()) invalidate(failure.message ?: "Comparison output failed.")
            } finally {
                track?.let { output ->
                    runCatching { output.removeOnRoutingChangedListener(routing) }
                    runCatching { output.pause(); output.flush(); output.stop() }
                    runCatching { output.release() }
                }
                track = null
                runCatching { manager.abandonAudioFocusRequest(focus) }
                if (!ready.isCompleted) ready.completeExceptionally(IllegalStateException("Comparison output stopped before routing was confirmed."))
                closed.complete(Unit)
            }
        }, "aurora-comparison").apply { start() }
    }

    fun select(a: Boolean) { requestedA = a; revision++ }

    suspend fun awaitReady() {
        withTimeoutOrNull(5000) { ready.await(); true }
            ?: error("The comparison output did not become ready.")
    }

    fun whenClosed(action: () -> Unit) {
        closed.invokeOnCompletion { handler.post(action) }
    }

    private fun invalidate(reason: String) {
        if (running.compareAndSet(true, false)) {
            invalidationReason = reason
            ready.completeExceptionally(IllegalStateException(reason))
            onInvalidated(reason)
        }
    }

    override fun close() {
        running.set(false)
        track?.let { runCatching { it.pause(); it.flush() } }
        if (worker == null) { manager.abandonAudioFocusRequest(focus); closed.complete(Unit) }
    }
}
