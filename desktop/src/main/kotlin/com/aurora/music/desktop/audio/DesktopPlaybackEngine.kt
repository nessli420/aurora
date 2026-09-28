package com.aurora.music.desktop.audio

import com.aurora.music.desktop.audio.decode.DecoderException
import com.aurora.music.desktop.audio.decode.DecoderInterruptedException
import com.aurora.music.desktop.audio.decode.FfmpegDecoder
import com.aurora.music.desktop.audio.decode.HttpOptions
import com.aurora.music.desktop.audio.decode.StreamInfo
import com.aurora.music.desktop.natives.AudioDevice
import com.aurora.music.desktop.natives.DeviceEvent
import com.aurora.music.desktop.natives.OutputEncoding
import com.aurora.music.desktop.natives.OutputStatus
import com.aurora.music.desktop.natives.WasapiException
import com.aurora.music.mix.MixMath
import com.aurora.music.model.Song
import com.aurora.music.playback.ConvolutionPreparationState
import com.aurora.music.playback.ImpulseResponse
import com.aurora.music.playback.PcmConstants
import com.aurora.music.playback.PcmLevelMeter
import com.aurora.music.playback.VisualizerController
import com.aurora.music.playback.chain.ChainFormat
import com.aurora.music.playback.chain.DspChain
import com.aurora.music.playback.chain.DspChainReport
import com.aurora.music.playback.chain.DspChainSettings
import com.aurora.music.playback.chain.PcmOutputEncoder
import com.aurora.music.playback.chain.applySettings
import com.aurora.music.playback.chain.dspParams
import com.aurora.music.playback.engine.AudioBlock
import com.aurora.music.playback.engine.AudioStreamFormat
import com.aurora.music.playback.engine.ChannelLayout
import com.aurora.music.playback.engine.OutputDitherMode
import com.aurora.music.playback.engine.PcmEncoding
import com.aurora.music.playback.engine.RackNodeMeter
import com.aurora.music.util.AppLog
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import kotlin.random.Random

class DesktopPlaybackEngine(
    private val backend: OutputBackend = WasapiBackend,
    private val resolver: StreamResolver = StreamResolver.Default,
    config: EngineConfig = EngineConfig(),
    random: Random = Random.Default,
) : PlaybackEngine {
    @Volatile override var config: EngineConfig = config
        private set
    private val mutableState = MutableStateFlow(EngineState())
    override val state: StateFlow<EngineState> = mutableState.asStateFlow()
    private val mutableEvents = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<EngineEvent> = mutableEvents.asSharedFlow()
    private val mutableOutputs = MutableStateFlow<List<AudioDevice>>(emptyList())
    override val outputs: StateFlow<List<AudioDevice>> = mutableOutputs.asStateFlow()
    override val beforeMeter = PcmLevelMeter()
    override val afterMeter = PcmLevelMeter()
    @Volatile override var visualizer: VisualizerController? = null

    private val commands = ConcurrentLinkedQueue<() -> Unit>()
    private val loader = Executors.newCachedThreadPool(daemon("aurora-audio-loader"))
    private val control = Executors.newSingleThreadExecutor(daemon("aurora-audio-control"))
    private val watch = ReadWatch()
    private val chains = List(2) { DspChain() }
    private val decks = Array(2) { Deck(chains[it], watch) }
    private val beforeTap = FloatTap(beforeMeter)
    private val encoder = PcmOutputEncoder()
    private val negotiator = OutputNegotiator(backend)
    private val queue = PlaybackQueue(random)
    private val markers = ArrayDeque<Marker>()
    private val skipped = HashSet<Long>()
    private val bytes = ByteArray(BLOCK_FRAMES * 2 * 4)
    private val bytesView = ByteBuffer.wrap(bytes)
    private val vizChunk = FloatArray(512)
    @Volatile private var volume = 1.0
    @Volatile private var closed = false
    @Volatile private var audioThread: Thread? = null
    @Volatile private var reportChain: DspChain? = null

    private var playWhenReady = false
    private var phase = EnginePhase.IDLE
    private var error: PlaybackFailure? = null
    private var speed = 1.0
    private var requestedDevice: String? = null
    private var exclusive = false
    private var deviceFallback: String? = null
    private var exclusiveBlocked: String? = null
    private var lastFallback: String? = null
    private var output: AudioOutput? = null
    private var negotiated: NegotiatedOutput? = null
    private var openedFor: String? = null
    private var mixBlock = AudioBlock(AudioStreamFormat(48_000, ChannelLayout.STEREO), BLOCK_FRAMES)
    private var frameBytes = 8
    private var running = false
    private var primary: Deck? = null
    private var fading: Deck? = null
    private var crossfade: Crossfade? = null
    private var heardMarker: Marker? = null
    private var renderMarker: Marker? = null
    private var load: Load? = null
    private var prepared: Prefetch? = null
    private var pendingReopen: PendingStart? = null
    private var streamFrames = 0L
    private var pendingOffset = 0
    private var pendingLength = 0
    private var lastStatus: OutputStatus? = null
    private var statusAt = 0L
    private var lastHeard = 0L
    private var lastPositionMs = 0L
    private var contentEnded = false
    private var endError: PlaybackFailure? = null
    private var stalled = false
    private var restartPending = false
    private var failures = 0
    private var master = 1.0
    private var blockUnity = true
    private var lastQuantized = false
    private var sleep: Ramp? = null
    private var wake: Ramp? = null
    private var sleepPauseAt = -1L
    private var beforeRate = 0
    private var vizFill = 0
    private var publishedAt = 0L
    private var awake = false
    private var successorKey = -1L
    private var successorVersion = -1L
    private var successorSkips = -1
    private var successorValue: QueueEntry? = null
    private val listener: AutoCloseable

    init {
        mutableOutputs.value = runCatching { backend.devices() }.getOrDefault(emptyList())
        listener = runCatching { backend.listen { event -> post { onDevice(event) } } }.getOrElse { AutoCloseable {} }
        audioThread = Thread(::run, "aurora-audio").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    override fun setQueue(songs: List<Song>, startIndex: Int, startPositionMs: Long, play: Boolean) {
        val copy = songs.toList()
        post {
            stopAudio()
            cancelPrefetch()
            queue.set(copy, startIndex)
            resetFailures()
            error = null
            playWhenReady = play && queue.size > 0
            if (queue.size > 0) restart(queue.index, startPositionMs, TransitionReason.QUEUE_CHANGED)
            else emptied()
        }
    }

    override fun insert(index: Int, songs: List<Song>) {
        val copy = songs.toList()
        edit { queue.insert(index, copy) }
    }

    override fun append(songs: List<Song>) {
        val copy = songs.toList()
        edit { queue.append(copy) }
    }

    override fun move(from: Int, to: Int) = edit { if (from in 0 until queue.size && to in 0 until queue.size) queue.move(from, to) }

    override fun remove(fromIndex: Int, toIndex: Int) = edit { queue.remove(fromIndex, toIndex) }

    override fun replace(index: Int, song: Song) = edit { if (index in 0 until queue.size) queue.replace(index, song) }

    override fun clear() = post {
        stopAudio()
        cancelPrefetch()
        queue.clear()
        emptied()
    }

    override fun seekTo(index: Int, positionMs: Long) = post {
        if (index !in 0 until queue.size) return@post
        resetFailures()
        seekInternal(index, positionMs)
    }

    override fun seekTo(positionMs: Long) = post {
        val index = heardMarker?.let { queue.indexOf(it.entry.uid) } ?: -1
        if (index < 0) return@post
        resetFailures()
        seekInternal(index, positionMs)
    }

    override fun next() = post {
        val index = queue.next(queue.index, auto = false)
        if (index < 0) return@post
        resetFailures()
        seekInternal(index, 0)
    }

    override fun previousItem() = post {
        val index = queue.previous(queue.index)
        if (index < 0) return@post
        resetFailures()
        seekInternal(index, 0)
    }

    override fun play() = post {
        if (queue.size == 0) return@post
        playWhenReady = true
        resetFailures()
        when {
            phase == EnginePhase.ENDED -> restart(queue.index, 0, TransitionReason.SEEK)
            phase == EnginePhase.IDLE && load == null && primary == null ->
                restart(queue.index.coerceAtLeast(0), heardMarker?.startMs ?: 0, null)
            else -> publish()
        }
    }

    override fun pause() = post {
        pauseInternal()
        publish()
    }

    override fun stop() = post {
        stopAudio()
        playWhenReady = false
        phase = EnginePhase.IDLE
        error = null
        publish()
    }

    override fun setRepeat(mode: RepeatMode) = edit { queue.repeat = mode }

    override fun setShuffle(target: ShuffleTarget, originalOrder: List<String>?) {
        val order = originalOrder?.toList()
        edit { queue.setShuffle(target, order) }
    }

    override fun setSpeed(speed: Float) = post {
        val value = speed.coerceIn(0.5f, 2f).toDouble()
        if (value == this.speed) return@post
        this.speed = value
        if (primary != null) restartAtHeard() else publish()
    }

    override fun setVolume(volume: Float) {
        val value = volume.coerceIn(0f, 1f).toDouble()
        this.volume = value
        chains.forEach { it.engine.relativeVolume = value }
        post { publish() }
    }

    override fun sleepFade(fadeMs: Int) = post {
        if (fadeMs <= 0) {
            sleep = null
            sleepPauseAt = -1
        } else sleep = Ramp(framesFor(fadeMs), 1.0, 0.0)
        publish()
    }

    override fun wakeFade(fadeMs: Int) = post {
        wake = if (fadeMs <= 0) null else Ramp(framesFor(fadeMs), 0.0, 1.0)
        master = targetMaster()
        publish()
    }

    override fun configure(config: EngineConfig) {
        val previous = this.config
        this.config = config
        if (previous.outputRatePolicy == config.outputRatePolicy && previous.bufferMs == config.bufferMs) return
        post {
            val deck = primary
            val track = deck?.track
            if (output != null && track != null && (previous.bufferMs != config.bufferMs || !compatibleOutput(track))) {
                closeOutput()
                restartAtHeard()
            } else {
                deck?.chain?.format?.let { deck.chain.continueWith(it.copy(policy = config.outputRatePolicy)) }
                publish()
            }
        }
    }

    override fun setOutput(deviceId: String?, exclusive: Boolean) = post {
        requestedDevice = deviceId
        this.exclusive = exclusive
        deviceFallback = null
        exclusiveBlocked = null
        negotiator.clear()
        reopenAtHeard()
    }

    override fun applyDsp(settings: DspChainSettings) {
        val params = settings.audio.dspParams(settings.mono)
        synchronized(chains) { chains.forEach { it.engine.applySettings(settings, params) } }
    }

    override fun setLegacyImpulse(impulse: ImpulseResponse?, makeupDb: Float) {
        synchronized(chains) { chains.forEach { it.engine.setImpulse(impulse, makeupDb) } }
    }

    override fun setRackImpulses(impulses: Map<String, ImpulseResponse>) {
        synchronized(chains) { chains.forEach { it.engine.setRackImpulses(impulses) } }
    }

    override fun dspReport(): DspChainReport? = reportChain?.report()

    override fun rackMeters(): List<RackNodeMeter> = reportChain?.engine?.rackMeters().orEmpty()

    override fun close() {
        if (closed) return
        closed = true
        audioThread?.let {
            LockSupport.unpark(it)
            it.join(2_000)
        }
        runCatching { listener.close() }
        loader.shutdownNow()
        control.execute { runCatching { backend.keepAwake(false) } }
        control.shutdown()
        control.awaitTermination(1, TimeUnit.SECONDS)
    }

    private fun post(command: () -> Unit) {
        if (closed) return
        commands += command
        watch.interruptIfStalled(STALLED_READ_NANOS)
        audioThread?.let(LockSupport::unpark)
    }

    private fun edit(change: () -> Unit) = post {
        change()
        reconcile()
        publish()
    }

    private fun run() {
        while (!closed) {
            try {
                while (true) (commands.poll() ?: break).invoke()
                if (restartPending) {
                    restartPending = false
                    restartAtHeard()
                }
                val now = System.nanoTime()
                refreshStatus(now)
                advanceTimeline(now)
                val busy = pump()
                if (playWhenReady) maybePrefetch()
                if (playWhenReady && phase != EnginePhase.IDLE && now - publishedAt >= PUBLISH_NANOS) publish()
                if (!busy && commands.isEmpty()) {
                    LockSupport.parkNanos(this, if (playWhenReady && phase != EnginePhase.IDLE) ACTIVE_PARK_NANOS else IDLE_PARK_NANOS)
                }
            } catch (e: Throwable) {
                AppLog.e(TAG, "Playback engine failure", e)
                runCatching { stopWithError(PlaybackFailure(PlaybackFailure.Kind.OUTPUT, e.message ?: e.javaClass.simpleName)) }
            }
        }
        runCatching {
            stopAudio()
            closeOutput()
            cancelPrefetch()
        }
    }

    private fun seekInternal(index: Int, positionMs: Long) {
        val entry = queue[index]
        val request = load
        val heard = heardMarker
        if (request != null && request.entry.uid == entry.uid && heard?.entry?.uid == entry.uid) {
            val target = positionMs.coerceAtLeast(0)
            request.positionMs = target
            heardMarker = heard.frozenAt(target)
            emit(EngineEvent.Discontinuity(entry, heard.startMs, target))
            publish()
            return
        }
        restart(index, positionMs, TransitionReason.SEEK)
    }

    private fun restart(index: Int, positionMs: Long, reason: TransitionReason?) {
        val entry = queue.entries.getOrNull(index) ?: return
        val reuse = detachTrack(entry.uid)
        stopAudio()
        val previous = heardMarker
        val same = previous?.entry?.uid == entry.uid
        val start = positionMs.coerceAtLeast(0)
        queue.select(index)
        heardMarker = Marker(0, entry, start, 0.0, if (same) previous!!.durationMs else songDurationMs(entry.song),
            previous?.info?.takeIf { same }, previous?.decoderName?.takeIf { same }, null)
        if (reason != null) {
            if (same) emit(EngineEvent.Discontinuity(entry, previous!!.startMs, start))
            else emit(EngineEvent.Transition(previous?.entry, entry, reason, previous?.startMs))
        }
        error = null
        if (sleep?.done == true) {
            sleep = null
            playWhenReady = false
        }
        startLoad(entry, start, reuse)
        publish()
    }

    private fun restartAtHeard() {
        val entry = heardMarker?.entry ?: return publish()
        val index = queue.indexOf(entry.uid)
        if (index < 0 || phase == EnginePhase.ENDED || phase == EnginePhase.IDLE && load == null && primary == null) return publish()
        restart(index, heardPositionMs(), null)
    }

    private fun reopenAtHeard() {
        closeOutput()
        restartAtHeard()
    }

    private fun emptied() {
        val previous = heardMarker
        heardMarker = null
        phase = EnginePhase.IDLE
        if (previous != null) emit(EngineEvent.Transition(previous.entry, null, TransitionReason.QUEUE_CHANGED, previous.startMs))
        publish()
    }

    private fun reconcile() {
        val heard = heardMarker?.entry
        if (heard == null) {
            val current = queue.current ?: return
            heardMarker = Marker(0, current, 0, 0.0, songDurationMs(current.song), null, null, null)
            emit(EngineEvent.Transition(null, current, TransitionReason.QUEUE_CHANGED, null))
            return
        }
        if (queue.indexOf(heard.uid) < 0) return currentRemoved()
        if (phase == EnginePhase.ENDED) {
            successorOf(heard)?.let { restart(queue.indexOf(it.uid), 0, TransitionReason.AUTO) }
            return
        }
        var expected: QueueEntry = heard
        for (i in 0 until markers.size) {
            val marker = markers[i]
            if (marker.reason == null && marker.entry.uid == expected.uid) continue
            if (successorOf(expected)?.uid != marker.entry.uid) return restartAtHeard()
            expected = marker.entry
        }
        val waiting = primary?.pending?.track?.entry ?: pendingReopen?.track?.entry
        if (waiting != null && successorOf(expected)?.uid != waiting.uid) return restartAtHeard()
        val slot = prepared ?: return
        val render = primary?.track?.entry ?: return
        if (successorOf(render)?.uid != slot.entry.uid) cancelPrefetch()
    }

    private fun currentRemoved() {
        if (queue.size == 0) {
            stopAudio()
            cancelPrefetch()
            return emptied()
        }
        if (queue.removedPastEnd || phase == EnginePhase.IDLE && load == null && primary == null) {
            stopAudio()
            val previous = heardMarker
            val current = checkNotNull(queue.current)
            heardMarker = Marker(0, current, 0, 0.0, songDurationMs(current.song), null, null, null)
            emit(EngineEvent.Transition(previous?.entry, current, TransitionReason.QUEUE_CHANGED, previous?.startMs))
            if (queue.removedPastEnd && phase != EnginePhase.IDLE) {
                phase = EnginePhase.ENDED
                emit(EngineEvent.Ended)
            }
            return
        }
        restart(queue.index, 0, TransitionReason.QUEUE_CHANGED)
    }

    private fun detachTrack(uid: Long): OpenTrack? {
        for (deck in decks) {
            val track = deck.track ?: continue
            if (track.entry.uid == uid && !track.interrupt.get()) return deck.detach()
        }
        val slot = prepared ?: return null
        if (slot.entry.uid != uid) return null
        val track = slot.result?.getOrNull() ?: return null
        prepared = null
        return track
    }

    private fun stopAudio() {
        heardMarker = heardMarker?.let { it.frozenAt(positionOf(it, lastHeard)) }
        crossfade = null
        fading = null
        setPrimary(null)
        for (deck in decks) deck.release()
        pendingReopen?.track?.close()
        pendingReopen = null
        cancelLoad()
        resetStream()
    }

    private fun resetStream() {
        output?.let { runCatching { it.flush() } }
        running = false
        streamFrames = 0
        pendingOffset = 0
        pendingLength = 0
        lastHeard = 0
        lastStatus = null
        markers.clear()
        renderMarker = null
        contentEnded = false
        endError = null
        stalled = false
        sleepPauseAt = -1
        encoder.reset()
    }

    private fun startLoad(entry: QueueEntry, positionMs: Long, reuse: OpenTrack?) {
        cancelLoad()
        val request = Load(entry, positionMs, reuse)
        load = request
        phase = EnginePhase.BUFFERING
        submit(request)
    }

    private fun cancelLoad() {
        val request = load ?: return
        load = null
        if (request.track == null) request.interrupt.set(true)
    }

    private fun submit(request: Load) {
        loader.execute {
            val result = runCatching { prepare(request) }
            if (closed) result.getOrNull()?.close() else post { onLoaded(request, result) }
        }
    }

    private fun prepare(request: Load): OpenTrack {
        request.track?.let { reuse ->
            try {
                reuse.position(frameOf(request.positionMs, reuse.rate))
                return reuse
            } catch (e: DecoderException) {
                reuse.close()
            }
        }
        val track = open(request.entry, request.interrupt)
        try {
            track.position(frameOf(request.positionMs, track.rate))
        } catch (e: Exception) {
            track.close()
            throw e
        }
        return track
    }

    private fun open(entry: QueueEntry, interrupt: AtomicBoolean): OpenTrack {
        val stream = resolver.resolve(entry.song)
        return OpenTrack(entry, FfmpegDecoder.open(stream.url, HttpOptions(stream.headers, stream.userAgent), interrupt), interrupt)
    }

    private fun onLoaded(request: Load, result: Result<OpenTrack>) {
        if (load !== request) {
            result.getOrNull()?.close()
            return
        }
        val track = result.getOrElse {
            load = null
            return loadFailed(request.entry, it)
        }
        request.track = track
        if (track.info.seekable && track.startFrame != frameOf(request.positionMs, track.rate)) return submit(request)
        load = null
        begin(track, null)
    }

    private fun loadFailed(entry: QueueEntry, cause: Throwable) {
        val kind = if (cause is UnsupportedStreamException) PlaybackFailure.Kind.UNSUPPORTED_SOURCE else PlaybackFailure.Kind.SOURCE_UNAVAILABLE
        val failure = PlaybackFailure(kind, cause.message ?: "The track could not be opened")
        failed(entry, failure)
        val next = nextPlayable(queue.indexOf(entry.uid))
        if (failures >= config.maxConsecutiveFailures || next < 0) return stopWithError(failure)
        restart(next, 0, TransitionReason.SEEK)
    }

    private fun nextPlayable(from: Int): Int {
        var candidate = queue.next(from, auto = false)
        var guard = queue.size
        while (candidate >= 0 && queue[candidate].uid in skipped && guard-- > 0) candidate = queue.next(candidate, auto = false)
        return candidate.takeIf { it >= 0 && queue[it].uid !in skipped } ?: -1
    }

    private fun begin(track: OpenTrack, reason: TransitionReason?) {
        for (deck in decks) deck.release()
        crossfade = null
        fading = null
        if (!ensureOutput(track.rate)) {
            track.close()
            return
        }
        resetStream()
        val deck = decks[0]
        setPrimary(deck)
        deck.tap = beforeTap
        val format = chainFormat(track)
        val latency = deck.start(track, format, 0)
        decks[1].chain.configure(format)
        addMarker(deck, latency, track, reason)
        phase = EnginePhase.READY
        publish()
    }

    private fun ensureOutput(sourceRate: Int): Boolean {
        val deviceId = activeDeviceId()
        val wanted = try { negotiate(deviceId, sourceRate) } catch (e: Exception) { negotiator.shared(deviceId, e.message) }
        val current = output
        if (current != null && openedFor == deviceId && current.exclusive == wanted.exclusive &&
            current.sampleRate == wanted.sampleRate && current.encoding == wanted.encoding) {
            negotiated = wanted
            return true
        }
        return openOutput(wanted, deviceId)
    }

    private fun negotiate(deviceId: String?, sourceRate: Int): NegotiatedOutput {
        val blocked = exclusiveBlocked
        return if (exclusive && blocked != null) negotiator.shared(deviceId, blocked)
        else negotiator.negotiate(deviceId, exclusive, sourceRate, config.outputRatePolicy)
    }

    private fun compatibleOutput(track: OpenTrack): Boolean {
        val current = output ?: return false
        val wanted = try { negotiate(activeDeviceId(), track.rate) } catch (e: Exception) { return false }
        return current.exclusive == wanted.exclusive && current.sampleRate == wanted.sampleRate && current.encoding == wanted.encoding
    }

    private fun openOutput(wanted: NegotiatedOutput, deviceId: String?): Boolean {
        closeOutput()
        var chosen = wanted
        val opened = try {
            backend.open(wanted.deviceId, wanted.exclusive, wanted.sampleRate, wanted.encoding, config.bufferMs)
        } catch (e: Exception) {
            if (!wanted.exclusive) return failOutput(e)
            val reason = "Exclusive mode is unavailable: ${e.message}"
            exclusiveBlocked = reason
            chosen = negotiator.shared(deviceId, reason)
            try {
                backend.open(chosen.deviceId, false, chosen.sampleRate, chosen.encoding, config.bufferMs)
            } catch (shared: Exception) {
                return failOutput(shared)
            }
        }
        chosen.fallbackReason?.takeIf { it != lastFallback }?.let { emit(EngineEvent.OutputFallback(it)) }
        lastFallback = chosen.fallbackReason
        output = opened
        negotiated = chosen
        openedFor = deviceId
        frameBytes = opened.encoding.bytesPerSample * 2
        if (mixBlock.format.sampleRate != opened.sampleRate) {
            mixBlock = AudioBlock(AudioStreamFormat(opened.sampleRate, ChannelLayout.STEREO), BLOCK_FRAMES)
        }
        afterMeter.configure(opened.encoding.meterEncoding, 2, opened.sampleRate)
        running = false
        lastStatus = null
        return true
    }

    private fun closeOutput() {
        output?.let { runCatching { it.close() } }
        output = null
        negotiated = null
        running = false
        lastStatus = null
    }

    private fun failOutput(cause: Exception): Boolean {
        val failure = PlaybackFailure(PlaybackFailure.Kind.OUTPUT, cause.message ?: "The audio output could not be used")
        emit(EngineEvent.Failed(heardMarker?.entry, failure))
        closeOutput()
        stopWithError(failure)
        return false
    }

    private fun stopWithError(failure: PlaybackFailure) {
        stopAudio()
        playWhenReady = false
        phase = EnginePhase.IDLE
        error = failure
        resetFailures()
        publish()
    }

    private fun pauseInternal() {
        playWhenReady = false
        wake = null
        if (running) {
            output?.let { runCatching { it.pause() } }
            running = false
            lastStatus = null
        }
    }

    private fun refreshStatus(now: Long) {
        val current = output ?: return
        if (lastStatus != null && now - statusAt < STATUS_NANOS) return
        val status = try { current.status() } catch (e: Exception) { return }
        lastStatus = status
        statusAt = now
        if (status.deviceInvalidated) reopenAtHeard()
    }

    private fun heard(now: Long): Long {
        val status = lastStatus ?: return lastHeard
        var frames = status.framesPlayed
        if (running && status.playing) frames += (now - status.positionNanos).coerceAtLeast(0) * outputRate() / 1_000_000_000L
        lastHeard = maxOf(lastHeard, minOf(frames, writtenFrames()))
        return lastHeard
    }

    private fun writtenFrames() = streamFrames - pendingLength / frameBytes

    private fun heardPositionMs(): Long = heardMarker?.let { positionOf(it, lastHeard) } ?: 0

    private fun advanceTimeline(now: Long) {
        val frames = heard(now)
        while (markers.isNotEmpty() && markers.first().frame <= frames) cross(markers.removeFirst())
        val marker = heardMarker
        if (failures > 0 && marker != null && marker.msPerFrame > 0 && positionOf(marker, frames) - marker.startMs >= 1_000) resetFailures()
        if (sleepPauseAt in 0..frames) {
            sleepPauseAt = -1
            sleep = null
            master = volume
            pauseInternal()
            restartAtHeard()
            return
        }
        if (contentEnded && pendingLength == 0 && (lastStatus?.framesPlayed ?: 0) >= streamFrames) finishContent()
    }

    private fun cross(marker: Marker) {
        val previous = heardMarker
        heardMarker = marker
        val index = queue.indexOf(marker.entry.uid)
        if (index >= 0) queue.select(index)
        if (marker.reason != null) {
            val end = previous?.let { if (marker.frame >= it.frame) positionOf(it, marker.frame) else lastPositionMs }
            emit(EngineEvent.Transition(previous?.entry, marker.entry, marker.reason, end))
        }
        publish()
    }

    private fun finishContent() {
        contentEnded = false
        val reopen = pendingReopen
        if (reopen != null) {
            pendingReopen = null
            closeOutput()
            return begin(reopen.track, reopen.reason)
        }
        endError?.let { return stopWithError(it) }
        val next = heardMarker?.entry?.let(::successorOf)
        if (next != null) return restart(queue.indexOf(next.uid), 0, TransitionReason.AUTO)
        output?.let { runCatching { it.pause() } }
        running = false
        phase = EnginePhase.ENDED
        emit(EngineEvent.Ended)
        publish()
    }

    private fun pump(): Boolean {
        val current = output ?: return false
        if (!playWhenReady || phase == EnginePhase.IDLE || phase == EnginePhase.ENDED) return false
        if (!running && (writtenFrames() >= current.sampleRate / 10 || (contentEnded || stalled || pendingLength > 0) && writtenFrames() > 0)) {
            try {
                current.resume()
                running = true
                lastStatus = null
            } catch (e: Exception) {
                return failOutput(e)
            }
        }
        if (pendingLength > 0) return write(current)
        if (contentEnded || primary == null) return false
        val frames = render()
        if (frames == 0 || output !== current) return false
        encode(frames)
        return write(current)
    }

    private fun write(current: AudioOutput): Boolean {
        val written = try {
            current.write(bytes, pendingOffset, pendingLength, WRITE_TIMEOUT_MS)
        } catch (e: WasapiException) {
            if (e.deviceInvalidated) reopenAtHeard() else failOutput(e)
            return false
        } catch (e: Exception) {
            return failOutput(e)
        }
        pendingOffset += written
        pendingLength -= written
        return true
    }

    private fun render(): Int {
        primary?.let { if (crossfade == null) maybeBeginCrossfade(it) }
        val lead = primary ?: return 0
        service(lead)
        val tail = fading
        if (tail != null) service(tail)
        if (restartPending) return 0
        val frames = when {
            tail == null -> lead.fill
            lead.finished && tail.finished -> maxOf(lead.fill, tail.fill)
            lead.finished -> tail.fill
            tail.finished -> lead.fill
            else -> minOf(lead.fill, tail.fill)
        }
        stalled = frames == 0
        if (frames == 0) {
            if (lead.finished && lead.fill == 0 && (tail == null || tail.finished && tail.fill == 0)) contentEnded = true
            return 0
        }
        mix(frames)
        return frames
    }

    private fun service(deck: Deck) {
        repeat(SERVICE_ROUNDS) {
            deck.fill()
            deck.failure?.let {
                deck.failure = null
                readFailed(deck, it)
            }
            if (restartPending || deck.fill == BLOCK_FRAMES) return
            if (deck.draining) {
                val next = deck.pending
                if (next == null || !deck.chain.isEnded) return
                addMarker(deck, deck.restartAfterDrain(), next.track, next.reason)
                return@repeat
            }
            if (!deck.decoderEnded || !advance(deck)) return
        }
    }

    private fun readFailed(deck: Deck, cause: Exception) {
        if (cause is DecoderInterruptedException) {
            restartPending = true
            return
        }
        failed(deck.track?.entry, PlaybackFailure(PlaybackFailure.Kind.DECODE, cause.message ?: "The track could not be decoded"))
    }

    private fun failed(entry: QueueEntry?, failure: PlaybackFailure) {
        failures++
        if (entry != null) skipped += entry.uid
        if (failures >= config.maxConsecutiveFailures) endError = failure
        emit(EngineEvent.Failed(entry, failure))
    }

    private fun advance(deck: Deck): Boolean {
        val track = deck.track ?: return false
        val next = if (deck === fading || endError != null) null else successorOf(track.entry)
        if (next == null) {
            deck.endInput()
            return true
        }
        val slot = prepared
        if (slot == null || slot.entry.uid != next.uid) {
            prefetch(next)
            return false
        }
        val result = slot.result ?: return false
        prepared = null
        val incoming = result.getOrElse {
            failed(next, PlaybackFailure(if (it is UnsupportedStreamException) PlaybackFailure.Kind.UNSUPPORTED_SOURCE
                else PlaybackFailure.Kind.SOURCE_UNAVAILABLE, it.message ?: "The track could not be opened"))
            return true
        }
        val reason = if (next.uid == track.entry.uid) TransitionReason.REPEAT else TransitionReason.AUTO
        val format = chainFormat(incoming)
        val current = checkNotNull(deck.chain.format)
        when {
            !compatibleOutput(incoming) -> {
                pendingReopen = PendingStart(incoming, reason, format)
                deck.endInput()
            }
            current.sourceRate == format.sourceRate && current.resampleRate == format.resampleRate && current.outputRate == format.outputRate ->
                addMarker(deck, deck.switchTo(incoming, format), incoming, reason)
            else -> {
                deck.pending = PendingStart(incoming, reason, format)
                deck.endInput()
            }
        }
        return true
    }

    private fun maybeBeginCrossfade(lead: Deck) {
        val settings = config
        if (settings.crossfadeMs <= 0 || lead.draining || lead.decoderEnded || pendingReopen != null) return
        val track = lead.track ?: return
        if (track.durationMs <= 0) return
        val remaining = remainingMs(lead, track)
        val fade = minOf(settings.crossfadeMs.toLong(), (track.durationMs / speed / 2).toLong())
        if (remaining > fade || remaining < MIN_CROSSFADE_MS) return
        val next = successorOf(track.entry) ?: return
        val slot = prepared?.takeIf { it.entry.uid == next.uid } ?: return
        val incomingTrack = slot.result?.getOrNull() ?: return
        if (!compatibleOutput(incomingTrack)) return
        prepared = null
        val incoming = if (decks[0] === lead) decks[1] else decks[0]
        incoming.release()
        val latency = incoming.start(incomingTrack, chainFormat(incomingTrack), streamFrames)
        lead.tap = null
        incoming.tap = beforeTap
        fading = lead
        setPrimary(incoming)
        crossfade = Crossfade(maxOf(1L, remaining * outputRate() / 1000), settings.crossfadeCurve, settings.crossfadeHeadroom)
        addMarker(incoming, latency, incomingTrack, if (next.uid == track.entry.uid) TransitionReason.REPEAT else TransitionReason.AUTO)
    }

    private fun mix(frames: Int) {
        val block = mixBlock
        block.begin(frames)
        val target = block.samples
        target.fill(0.0, 0, frames * 2)
        val mode = config.replayGain
        val lead = checkNotNull(primary)
        val tail = fading
        val fade = crossfade
        var unity = if (fade == null) add(lead, target, frames, 1.0, 1.0, mode) else {
            val start = MixMath.crossfade(fade.elapsed.toFloat() / fade.frames, fade.curve, fade.headroom)
            val end = MixMath.crossfade((fade.elapsed + frames).toFloat() / fade.frames, fade.curve, fade.headroom)
            add(lead, target, frames, start.second.toDouble(), end.second.toDouble(), mode)
            if (tail != null) add(tail, target, frames, start.first.toDouble(), end.first.toDouble(), mode)
            false
        }
        tapVisualizer(target, frames)
        val from = master
        val to = advanceMaster(frames)
        if (from != 1.0 || to != 1.0) {
            unity = false
            val step = (to - from) / frames
            var i = 0
            while (i < frames) {
                val gain = from + step * i
                target[i * 2] *= gain
                target[i * 2 + 1] *= gain
                i++
            }
        }
        master = to
        blockUnity = unity
        if (fade != null) {
            fade.elapsed += frames
            if (fade.elapsed >= fade.frames) {
                crossfade = null
                tail?.release()
                fading = null
            }
        }
    }

    private fun add(deck: Deck, target: DoubleArray, frames: Int, from: Double, to: Double, mode: Int): Boolean {
        val source = deck.out
        val available = minOf(deck.fill, frames)
        val step = (to - from) / frames
        val base = deck.mixed
        var unity = from == 1.0 && to == 1.0
        var i = 0
        while (i < available) {
            val gain = deck.gain(base + i, mode) * (from + step * i)
            if (gain != 1.0) unity = false
            target[i * 2] += source[i * 2] * gain
            target[i * 2 + 1] += source[i * 2 + 1] * gain
            i++
        }
        deck.consume(frames)
        return unity
    }

    private fun advanceMaster(frames: Int): Double {
        var gain = volume
        sleep?.let { ramp ->
            ramp.elapsed += frames
            gain *= ramp.value
            if (ramp.done && sleepPauseAt < 0) sleepPauseAt = streamFrames + frames
        }
        wake?.let { ramp ->
            ramp.elapsed += frames
            gain *= ramp.value
            if (ramp.done) wake = null
        }
        return gain
    }

    private fun targetMaster() = volume * (sleep?.value ?: 1.0) * (wake?.value ?: 1.0)

    private fun tapVisualizer(samples: DoubleArray, frames: Int) {
        val target = visualizer ?: return
        if (!target.active) return
        val rate = outputRate()
        for (i in 0 until frames * 2) {
            vizChunk[vizFill++] = samples[i].toFloat()
            if (vizFill == vizChunk.size) {
                target.pushFloat(vizChunk, 2, rate)
                vizFill = 0
            }
        }
    }

    private fun encode(frames: Int) {
        val format = checkNotNull(negotiated)
        val quantize = !blockUnity || fading != null || primary?.chain?.needsQuantization() == true
        lastQuantized = quantize
        val mode = if (quantize) config.outputRatePolicy.ditherMode else OutputDitherMode.OFF
        pendingLength = encoder.encode(mixBlock, format.encoding.pcm, format.encoding.bytesPerSample, mode, bytes)
        pendingOffset = 0
        afterMeter.observe(bytesView, 0, pendingLength, renderTimeUs())
        streamFrames += frames
    }

    private fun renderTimeUs(): Long {
        val marker = renderMarker ?: return PcmConstants.TIME_UNSET
        return ((marker.startMs + maxOf(0L, streamFrames - marker.frame) * marker.msPerFrame) * 1000).toLong()
    }

    private fun maybePrefetch() {
        val deck = primary ?: return
        val track = deck.track ?: return
        if (deck.draining || load != null) return
        val next = successorOf(track.entry) ?: return
        if (prepared?.entry?.uid == next.uid) return
        if (!deck.decoderEnded) {
            if (track.durationMs <= 0) return
            if (remainingMs(deck, track) > maxOf(PREFETCH_MS, config.crossfadeMs + CROSSFADE_PREFETCH_MS)) return
        }
        prefetch(next)
    }

    private fun prefetch(entry: QueueEntry) {
        cancelPrefetch()
        val slot = Prefetch(entry)
        prepared = slot
        loader.execute {
            val result = runCatching { open(entry, slot.interrupt) }
            if (closed) result.getOrNull()?.close()
            else post { if (prepared === slot) slot.result = result else result.getOrNull()?.close() }
        }
    }

    private fun cancelPrefetch() {
        val slot = prepared ?: return
        prepared = null
        slot.interrupt.set(true)
        slot.result?.getOrNull()?.close()
    }

    private fun successorOf(entry: QueueEntry): QueueEntry? {
        if (successorKey == entry.uid && successorVersion == queue.version && successorSkips == skipped.size) return successorValue
        val index = queue.indexOf(entry.uid)
        var candidate = if (index < 0) -1 else queue.next(index, auto = true)
        var guard = queue.size
        while (candidate >= 0 && queue[candidate].uid in skipped && guard-- > 0) candidate = queue.next(candidate, auto = false)
        successorKey = entry.uid
        successorVersion = queue.version
        successorSkips = skipped.size
        successorValue = if (candidate >= 0 && queue[candidate].uid !in skipped) queue[candidate] else null
        return successorValue
    }

    private fun addMarker(deck: Deck, deckFrame: Long, track: OpenTrack, reason: TransitionReason?) {
        val marker = Marker(deck.streamStart + deckFrame, track.entry, track.startFrame * 1000 / track.rate,
            1000.0 * speed / outputRate(), track.durationMs, track.info, track.decoder.decoderName, reason)
        markers.addLast(marker)
        if (deck !== primary) return
        renderMarker = marker
        if (beforeRate != track.rate) {
            beforeRate = track.rate
            beforeMeter.configure(PcmConstants.ENCODING_PCM_FLOAT, 2, track.rate)
        }
    }

    private fun onDevice(event: DeviceEvent) {
        negotiator.clear()
        control.execute { runCatching { backend.devices() }.onSuccess { mutableOutputs.value = it } }
        val current = output
        when (event) {
            is DeviceEvent.StreamInvalidated -> if (current != null && event.streamId == current.id) reopenAtHeard()
            is DeviceEvent.DefaultChanged -> if (activeDeviceId() == null && current != null && current.deviceId != event.deviceId) reopenAtHeard()
            is DeviceEvent.Removed -> deviceGone(event.deviceId)
            is DeviceEvent.StateChanged -> if ((event.state and DEVICE_STATE_ACTIVE) == 0) deviceGone(event.deviceId)
            is DeviceEvent.Added -> Unit
        }
    }

    private fun deviceGone(deviceId: String) {
        if (deviceId != requestedDevice || deviceFallback != null) return
        val reason = "The selected output device was disconnected"
        deviceFallback = reason
        emit(EngineEvent.OutputFallback(reason))
        pauseInternal()
        reopenAtHeard()
    }

    private fun activeDeviceId(): String? = if (deviceFallback != null) null else requestedDevice

    private fun setPrimary(deck: Deck?) {
        primary = deck
        reportChain = deck?.chain
    }

    private fun resetFailures() {
        failures = 0
        skipped.clear()
    }

    private fun chainFormat(track: OpenTrack): ChainFormat {
        val format = checkNotNull(negotiated)
        return ChainFormat(track.rate, format.sampleRate, format.encoding.pcm, track.precision, config.outputRatePolicy, speed)
    }

    private fun remainingMs(deck: Deck, track: OpenTrack) = ((track.durationMs - deck.renderPositionMs) / speed).toLong()

    private fun outputRate() = negotiated?.sampleRate ?: 48_000

    private fun framesFor(ms: Int) = maxOf(1L, ms.toLong() * outputRate() / 1000)

    private fun frameOf(ms: Long, rate: Int) = ms * rate / 1000

    private fun positionOf(marker: Marker, frames: Long): Long {
        val position = marker.startMs + (maxOf(0L, frames - marker.frame) * marker.msPerFrame).toLong()
        return if (marker.durationMs > 0) minOf(position, marker.durationMs) else position
    }

    private fun songDurationMs(song: Song) = (song.durationSec * 1000L).takeIf { it > 0 } ?: -1

    private fun emit(event: EngineEvent) {
        mutableEvents.tryEmit(event)
    }

    private fun publish() {
        publishedAt = System.nanoTime()
        val marker = heardMarker
        val position = marker?.let { positionOf(it, lastHeard) } ?: 0
        lastPositionMs = position
        val format = negotiated
        val current = output
        val report = primary?.chain?.report()
        val integer = format != null && format.encoding != OutputEncoding.F32
        val waiting = playWhenReady && phase == EnginePhase.READY && stalled && !contentEnded &&
            pendingLength == 0 && (lastStatus?.framesBuffered ?: 0L) == 0L
        val policy = config.outputRatePolicy
        mutableState.value = EngineState(
            entries = queue.entries,
            index = queue.index,
            positionMs = position,
            durationMs = marker?.durationMs ?: -1,
            playWhenReady = playWhenReady,
            phase = if (waiting) EnginePhase.BUFFERING else phase,
            live = marker != null && marker.durationMs <= 0,
            shuffle = queue.shuffle,
            shuffleRestoreIds = queue.restoreIds,
            repeat = queue.repeat,
            speed = speed.toFloat(),
            volume = volume.toFloat(),
            crossfading = crossfade != null,
            source = marker?.info,
            decoderName = marker?.decoderName,
            output = if (format == null || current == null) null else OutputInfo(current.deviceId,
                mutableOutputs.value.firstOrNull { it.id == current.deviceId }?.name, current.exclusive, current.sampleRate,
                current.encoding, activeDeviceId() == null, deviceFallback ?: format.fallbackReason),
            processing = ProcessingFacts(
                dspActive = report?.processingChangesSamples == true,
                dspDescription = report?.description.orEmpty(),
                preparation = report?.preparation ?: ConvolutionPreparationState.IDLE,
                resampling = report?.resampling == true,
                gainApplied = !blockUnity,
                ditherLabel = if (integer && lastQuantized && policy.ditherMode != OutputDitherMode.OFF)
                    policy.ditherLabel(format!!.sampleRate) else null,
                bitPerfect = integer && format!!.exclusive && !lastQuantized && report?.bitExact == true,
                rateFallbackReason = format?.rateFallbackReason,
            ),
            error = error,
        )
        val playing = playWhenReady && phase == EnginePhase.READY
        if (playing != awake) {
            awake = playing
            control.execute { runCatching { backend.keepAwake(playing) } }
        }
    }

    private class Marker(
        val frame: Long,
        val entry: QueueEntry,
        val startMs: Long,
        val msPerFrame: Double,
        val durationMs: Long,
        val info: StreamInfo?,
        val decoderName: String?,
        val reason: TransitionReason?,
    ) {
        fun frozenAt(positionMs: Long) = Marker(0, entry, positionMs, 0.0, durationMs, info, decoderName, null)
    }

    private class Load(val entry: QueueEntry, @Volatile var positionMs: Long, @Volatile var track: OpenTrack?) {
        val interrupt = AtomicBoolean()
    }

    private class Prefetch(val entry: QueueEntry) {
        val interrupt = AtomicBoolean()
        var result: Result<OpenTrack>? = null
    }

    private class Crossfade(val frames: Long, val curve: String, val headroom: Boolean) {
        var elapsed = 0L
    }

    private class Ramp(val frames: Long, val from: Double, val to: Double) {
        var elapsed = 0L
        val done: Boolean get() = elapsed >= frames
        val value: Double get() = from + (to - from) * (elapsed.toDouble() / frames).coerceIn(0.0, 1.0)
    }

    private companion object {
        const val TAG = "AuroraEngine"
        const val WRITE_TIMEOUT_MS = 10
        const val STATUS_NANOS = 20_000_000L
        const val PUBLISH_NANOS = 100_000_000L
        const val ACTIVE_PARK_NANOS = 2_000_000L
        const val IDLE_PARK_NANOS = 50_000_000L
        const val STALLED_READ_NANOS = 300_000_000L
        const val PREFETCH_MS = 20_000L
        const val CROSSFADE_PREFETCH_MS = 15_000L
        const val MIN_CROSSFADE_MS = 150L
        const val SERVICE_ROUNDS = 8
        const val DEVICE_STATE_ACTIVE = 1

        fun daemon(name: String) = ThreadFactory { task -> Thread(task, name).apply { isDaemon = true } }

        val OutputEncoding.pcm: PcmEncoding get() = when (this) {
            OutputEncoding.S16 -> PcmEncoding.SIGNED_16_LE
            OutputEncoding.S24, OutputEncoding.S24_IN_32 -> PcmEncoding.SIGNED_24_LE
            OutputEncoding.S32 -> PcmEncoding.SIGNED_32_LE
            OutputEncoding.F32 -> PcmEncoding.FLOAT_32_LE
        }

        val OutputEncoding.meterEncoding: Int get() = when (this) {
            OutputEncoding.S16 -> PcmConstants.ENCODING_PCM_16BIT
            OutputEncoding.S24 -> PcmConstants.ENCODING_PCM_24BIT
            OutputEncoding.S24_IN_32, OutputEncoding.S32 -> PcmConstants.ENCODING_PCM_32BIT
            OutputEncoding.F32 -> PcmConstants.ENCODING_PCM_FLOAT
        }
    }
}
