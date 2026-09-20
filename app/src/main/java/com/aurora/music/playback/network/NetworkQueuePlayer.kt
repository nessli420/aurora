package com.aurora.music.playback.network

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.util.UUID
import kotlin.math.roundToInt

data class NetworkReceiverState(
    val playing: Boolean, val positionMs: Long, val durationMs: Long,
    val ended: Boolean = false, val volume: Float? = null,
    val playWhenReady: Boolean? = null, val buffering: Boolean = false,
    val queueIds: List<String>? = null, val queueIndex: Int? = null, val repeatMode: Int? = null,
)

data class NetworkMedia(
    val url: String, val mimeType: String, val durationMs: Long, val processing: String,
    val sizeBytes: Long = 0,
    val release: () -> Unit,
)

data class ReceiverQueueItem(val uid: String, val item: MediaItem, val media: NetworkMedia)

interface NetworkReceiver : Closeable {
    val name: String
    val kind: String
    val host: String
    val supportsVolume: Boolean get() = true
    val volumeSteps: Int get() = 100
    val ownsQueue: Boolean get() = false
    suspend fun contentType(mimeType: String): String = mimeType
    suspend fun load(item: MediaItem, media: NetworkMedia, positionMs: Long)
    suspend fun playing(value: Boolean)
    suspend fun seek(positionMs: Long)
    suspend fun volume(value: Float)
    suspend fun status(): NetworkReceiverState
    suspend fun stop()
    suspend fun loadQueue(items: List<ReceiverQueueItem>, index: Int, positionMs: Long, repeatMode: Int) {
        throw NetworkOutputException("Receiver queues are unavailable.")
    }
    suspend fun seekTo(positionMs: Long, index: Int? = null) = seek(positionMs)
    suspend fun reorder(ids: List<String>) { throw NetworkOutputException("Receiver queues are unavailable.") }
    suspend fun repeat(mode: Int) = Unit
    override fun close() {}
}

@UnstableApi
class NetworkQueuePlayer(
    val receiver: NetworkReceiver,
    private val render: suspend (MediaItem) -> NetworkMedia,
    private val report: (Boolean, String, String?) -> Unit,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private data class Entry(val item: MediaItem, val uid: String = UUID.randomUUID().toString())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val transport = Mutex()
    private var queue = mutableListOf<Entry>()
    private var index = 0
    private var position = 0L
    private var length = 0L
    private var wanted = false
    private var ready = false
    private var loading = false
    private var ended = false
    private var repeat = Player.REPEAT_MODE_OFF
    private var gain = 1f
    private var volumeKnown = false
    private var audibleGain: Float? = null
    private var pendingVolume: Float? = null
    private var remoteBuffering = false
    private var failure: PlaybackException? = null
    private var queueLimitRejected = false
    private var loadJob: Job? = null
    private var generation = 0L
    private var controlsRevision = 0L
    private var seekRevision = 0L
    private var shuttingDown = false
    private var shutdownCompletion: CompletableDeferred<Unit>? = null
    private var releaseFuture: SettableFuture<Void>? = null
    private var media: NetworkMedia? = null
    private var original: List<String>? = null
    var processingDescription: String = "Preparing audio"
        private set
    var stopConfirmed: Boolean = true
        private set
    val shuffleRestoreIds: List<String>? get() = original?.mapNotNull { uid -> queue.firstOrNull { it.uid == uid }?.item?.mediaId }

    private val pollJob = scope.launch {
            while (isActive) {
                delay(750)
                if (!ready || loading || shuttingDown) continue
                val revision = generation
                val controls = controlsRevision
                try {
                    val state = transport.withLock { receiver.status() }
                    if (revision != generation || controls != controlsRevision || loading || shuttingDown) continue
                    if (receiver.ownsQueue && !mirrorQueue(state)) continue
                    position = state.positionMs.coerceAtLeast(0)
                    length = state.durationMs.takeIf { it > 0 } ?: length
                    remoteBuffering = state.buffering
                    val observedGain = state.volume?.takeIf { it.isFinite() }
                    volumeKnown = observedGain != null
                    observedGain?.let { gain = it.coerceIn(0f, 1f); if (gain > 0) audibleGain = gain }
                    if (receiver.ownsQueue) {
                        ended = state.ended
                        state.playWhenReady?.let { wanted = it && !ended }
                        invalidateState()
                    } else if (state.ended && wanted) advance() else {
                        if (!state.buffering && !state.ended) state.playWhenReady?.let { wanted = it }
                        invalidateState()
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { if (revision == generation && !shuttingDown) fail("Receiver disconnected. Reconnect to resume.") }
            }
    }

    private fun mirrorQueue(state: NetworkReceiverState): Boolean {
        val ids = state.queueIds ?: return true
        val entries = queue.associateBy { it.uid }
        val remoteIndex = state.queueIndex ?: index
        if (ids.size != ids.distinct().size || ids.any { it !in entries } ||
            (ids.isNotEmpty() && remoteIndex !in ids.indices)) {
            fail("Receiver queue changed. Reconnect to resume.")
            return false
        }
        queue = ids.map { entries.getValue(it) }.toMutableList()
        index = if (queue.isEmpty()) 0 else remoteIndex
        state.repeatMode?.takeIf { it in Player.REPEAT_MODE_OFF..Player.REPEAT_MODE_ALL }?.let { repeat = it }
        return true
    }

    private fun fail(message: String) {
        wanted = false
        ready = false
        loading = false
        failure = PlaybackException(message, null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        report(false, processingDescription, message)
        invalidateState()
    }

    private fun load() {
        if (shuttingDown || queueLimitRejected) return
        val previous = loadJob
        previous?.cancel()
        val revision = ++generation
        val item = queue.getOrNull(index)?.item
        ready = false
        loading = item != null
        ended = false
        remoteBuffering = false
        failure = null
        length = 0
        report(loading, if (loading) "Preparing audio" else "", null)
        loadJob = scope.launch {
            var rendered: NetworkMedia? = null
            val preparedQueue = mutableListOf<ReceiverQueueItem>()
            try {
                previous?.join()
                transport.withLock { receiver.stop() }
                releaseMedia()
                if (item == null) {
                    if (receiver.ownsQueue) transport.withLock { receiver.reorder(emptyList()) }
                    wanted = false; invalidateState(); return@launch
                }
                if (receiver.ownsQueue) {
                    var bytes = 0L
                    for (entry in queue.toList()) {
                        val prepared = render(entry.item)
                        preparedQueue.add(ReceiverQueueItem(entry.uid, entry.item, prepared))
                        ensureActive()
                        if (prepared.sizeBytes <= 0 || prepared.sizeBytes > MAX_QUEUE_BYTES - bytes)
                            throw NetworkOutputException("Queue exceeds the 512 MB limit.")
                        bytes += prepared.sizeBytes
                        report(true, "Preparing track ${preparedQueue.size} of ${queue.size}", null)
                    }
                } else {
                    rendered = render(item)
                    ensureActive()
                }
                if (generation != revision) return@launch
                transport.withLock {
                    val seeking = seekRevision
                    if (receiver.ownsQueue) receiver.loadQueue(preparedQueue, index, position, repeat)
                    else receiver.load(item, checkNotNull(rendered), position)
                    ensureActive()
                    if (seeking != seekRevision) receiver.seekTo(position, index.takeIf { receiver.ownsQueue })
                    if (receiver.ownsQueue) receiver.repeat(repeat)
                    pendingVolume?.let { value -> if (receiver.supportsVolume) receiver.volume(value); pendingVolume = null }
                    receiver.playing(wanted)
                }
                ensureActive()
                val prepared = if (receiver.ownsQueue) preparedQueue[index].media else checkNotNull(rendered)
                length = prepared.durationMs
                processingDescription = prepared.processing
                if (!receiver.ownsQueue) { media = prepared; rendered = null }
                ready = true
                loading = false
                report(false, processingDescription, null)
                invalidateState()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (revision == generation) fail(if (e is NetworkOutputException) e.message ?: "Network playback failed." else "Network playback failed. Check the receiver and source format.")
            } finally {
                rendered?.let { runCatching(it.release) }
                preparedQueue.forEach { runCatching(it.media.release) }
            }
        }
        invalidateState()
    }

    private fun syncOwnedQueue(seekCurrent: Boolean = false) {
        if (!receiver.ownsQueue) return
        controlsRevision++
        if (!ready || loading) { load(); return }
        command {
            val ids = queue.map { it.uid }
            val targetIndex = index
            val targetPosition = position
            receiver.reorder(ids)
            if (seekCurrent && ids.isNotEmpty()) receiver.seekTo(targetPosition, targetIndex)
            if (queue.isEmpty()) { wanted = false; ended = false; position = 0; length = 0 }
        }
    }

    private fun command(block: suspend () -> Unit) {
        if (shuttingDown) return
        val revision = generation
        scope.launch {
            try { transport.withLock { if (revision == generation && ready && !shuttingDown) block() } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (revision == generation) fail("Receiver command failed. Reconnect to resume.") }
        }
    }

    private fun advance() {
        when {
            repeat == Player.REPEAT_MODE_ONE -> { position = 0; load() }
            index + 1 < queue.size -> { index++; position = 0; load() }
            repeat == Player.REPEAT_MODE_ALL -> { index = 0; position = 0; load() }
            else -> { ended = true; wanted = false; invalidateState() }
        }
    }

    fun shuffle(target: Int, suppliedOrder: List<String>?) {
        val enable = when (target) { 1 -> true; 0 -> false; else -> original == null }
        val current = queue.getOrNull(index)
        if (enable && original == null) {
            val remaining = queue.toMutableList()
            original = suppliedOrder?.mapNotNull { id ->
                remaining.firstOrNull { it.item.mediaId == id }?.also { remaining.remove(it) }?.uid
            }?.plus(remaining.map { it.uid }) ?: queue.map { it.uid }
            val before = queue.take(index + 1)
            queue = (before + queue.drop(index + 1).shuffled()).toMutableList()
        } else if (!enable && original != null) {
            val remaining = queue.toMutableList()
            queue = (original!!.mapNotNull { uid -> remaining.firstOrNull { it.uid == uid }?.also { remaining.remove(it) } } + remaining).toMutableList()
            original = null
        }
        index = queue.indexOf(current).coerceAtLeast(0)
        syncOwnedQueue()
        invalidateState()
    }

    fun adoptShuffle(restoreIds: List<String>?) {
        val remaining = queue.toMutableList()
        original = restoreIds?.mapNotNull { id ->
            remaining.firstOrNull { it.item.mediaId == id }?.also { remaining.remove(it) }?.uid
        }?.plus(remaining.map { it.uid })
        invalidateState()
    }

    override fun getState(): State {
        val volumeSteps = availableVolumeSteps()
        val playlist = queue.mapIndexed { i, entry ->
            MediaItemData.Builder(entry.uid).setMediaItem(entry.item)
                .setDurationUs(if (i == index && length > 0) length * 1000 else C.TIME_UNSET)
                .setIsSeekable(true).build()
        }
        val commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP, Player.COMMAND_RELEASE,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SET_REPEAT_MODE,
            Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS)
            .apply {
                if (volumeKnown) add(Player.COMMAND_GET_VOLUME)
                if (volumeSteps > 0) addAll(Player.COMMAND_SET_VOLUME, Player.COMMAND_GET_DEVICE_VOLUME,
                    Player.COMMAND_SET_DEVICE_VOLUME, Player.COMMAND_ADJUST_DEVICE_VOLUME,
                    Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS, Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)
            }.build()
        return State.Builder().setAvailableCommands(commands)
            .setPlaylist(playlist).setCurrentMediaItemIndex(if (queue.isEmpty()) C.INDEX_UNSET else index)
            .setRepeatMode(repeat).setShuffleModeEnabled(original != null).setVolume(gain)
            .setDeviceInfo(DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMaxVolume(volumeSteps).build())
            .setDeviceVolume((gain * volumeSteps).roundToInt().coerceIn(0, volumeSteps))
            .setIsDeviceMuted(volumeKnown && gain == 0f)
            .setPlayWhenReady(wanted, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(when { failure != null || queue.isEmpty() || (!ready && !loading) -> Player.STATE_IDLE
                loading || remoteBuffering -> Player.STATE_BUFFERING; ended -> Player.STATE_ENDED; else -> Player.STATE_READY })
            .setPlayerError(failure).setContentPositionMs(position).setContentBufferedPositionMs { position }
            .build()
    }

    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        if (receiver.ownsQueue && mediaItems.size > MAX_QUEUE_TRACKS) return rejectQueue()
        require(mediaItems.size <= 10000) { "Queue is too large." }
        queueLimitRejected = false
        queue = mediaItems.map { Entry(it) }.toMutableList()
        index = startIndex.takeIf { it in queue.indices } ?: 0
        position = startPositionMs.coerceAtLeast(0)
        original = null
        load()
        return Futures.immediateVoidFuture()
    }

    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        if (receiver.ownsQueue && queue.size + mediaItems.size > MAX_QUEUE_TRACKS) return rejectQueue()
        require(queue.size + mediaItems.size <= 10000) { "Queue is too large." }
        queueLimitRejected = false
        val current = queue.getOrNull(this.index)
        queue.addAll(index, mediaItems.map { Entry(it) })
        this.index = queue.indexOf(current).coerceAtLeast(0)
        if ((current == null && queue.isNotEmpty()) || receiver.ownsQueue) load()
        return Futures.immediateVoidFuture()
    }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
        val current = queue.getOrNull(index)
        val moved = queue.subList(fromIndex, toIndex).toList()
        queue.subList(fromIndex, toIndex).clear()
        queue.addAll(newIndex.coerceAtMost(queue.size), moved)
        index = queue.indexOf(current).coerceAtLeast(0)
        syncOwnedQueue()
        return Futures.immediateVoidFuture()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        queueLimitRejected = false
        val current = queue.getOrNull(index)
        queue.subList(fromIndex, toIndex).clear()
        index = queue.indexOf(current).takeIf { it >= 0 } ?: fromIndex.coerceAtMost((queue.size - 1).coerceAtLeast(0))
        if (receiver.ownsQueue) {
            if (current !in queue) { position = 0; ended = false }
            syncOwnedQueue(seekCurrent = current !in queue)
        } else if (current !in queue) load()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> { if (!loading && !ready) load(); return Futures.immediateVoidFuture() }
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (queueLimitRejected) return Futures.immediateVoidFuture()
        controlsRevision++
        wanted = playWhenReady
        if (wanted && ended && receiver.ownsQueue && ready) {
            position = 0; ended = false
            syncOwnedQueue(seekCurrent = true)
            command { receiver.playing(wanted) }
        } else if (wanted && ended) { position = 0; load() }
        else if (wanted && !ready && !loading) load()
        else command { receiver.playing(wanted) }
        return Futures.immediateVoidFuture()
    }
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        controlsRevision++
        seekRevision++
        position = positionMs.coerceAtLeast(0)
        if (receiver.ownsQueue) {
            index = mediaItemIndex
            ended = false
            if (ready) syncOwnedQueue(seekCurrent = true)
        } else if (mediaItemIndex != index) { index = mediaItemIndex; load() }
        else if (ended) load()
        else if (ready) command { receiver.seek(position) }
        return Futures.immediateVoidFuture()
    }
    override fun handleSetVolume(volume: Float): ListenableFuture<*> {
        if (availableVolumeSteps() == 0) return Futures.immediateVoidFuture()
        controlsRevision++
        gain = volume.coerceIn(0f, 1f)
        if (gain > 0) audibleGain = gain
        val requested = gain
        if (!ready) pendingVolume = requested
        else command { if (receiver.supportsVolume) receiver.volume(requested) }
        return Futures.immediateVoidFuture()
    }

    private fun availableVolumeSteps(): Int =
        if (receiver.supportsVolume && volumeKnown) receiver.volumeSteps.coerceIn(0, 1_000) else 0

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> {
        val steps = availableVolumeSteps()
        return if (steps == 0) Futures.immediateVoidFuture()
        else handleSetVolume(deviceVolume.coerceIn(0, steps).toFloat() / steps)
    }

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> =
        handleSetDeviceVolume((gain * availableVolumeSteps()).roundToInt() + 1, flags)

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> =
        handleSetDeviceVolume(((gain * availableVolumeSteps()).roundToInt() - 1).coerceAtLeast(0), flags)

    override fun handleSetDeviceMuted(muted: Boolean, flags: Int): ListenableFuture<*> {
        val steps = availableVolumeSteps()
        if (steps == 0 || muted == (gain == 0f)) return Futures.immediateVoidFuture()
        return handleSetVolume(if (muted) 0f else audibleGain ?: (1f / steps))
    }
    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        controlsRevision++
        repeat = repeatMode
        if (receiver.ownsQueue) command { receiver.repeat(repeat) }
        return Futures.immediateVoidFuture()
    }

    private fun rejectQueue(): ListenableFuture<*> {
        queueLimitRejected = true
        handleStop()
        fail("Aurora receivers support up to 100 tracks per queue.")
        return Futures.immediateVoidFuture()
    }
    override fun handleStop(): ListenableFuture<*> {
        val previous = loadJob
        previous?.cancel()
        val revision = ++generation
        wanted = false; loading = false; ready = false; ended = false
        loadJob = scope.launch {
            previous?.join()
            transport.withLock { if (revision == generation) { runCatching { receiver.stop() }; releaseMedia() } }
        }
        return Futures.immediateVoidFuture()
    }

    suspend fun shutdown() = withContext(Dispatchers.Main.immediate) {
        shutdownCompletion?.let { it.await(); return@withContext }
        val completion = CompletableDeferred<Unit>()
        shutdownCompletion = completion
        shuttingDown = true
        generation++
        wanted = false; loading = false; ready = false
        val previous = loadJob
        previous?.cancel()
        pollJob.cancel()
        withContext(NonCancellable) {
            try {
                withTimeout(10_000) {
                    previous?.join()
                    pollJob.join()
                    transport.withLock {
                        try { receiver.stop(); stopConfirmed = true }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { stopConfirmed = false }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                stopConfirmed = false
                report(false, processingDescription, "Receiver did not confirm stop.")
            } finally {
                releaseMedia()
                invalidateState()
                completion.complete(Unit)
            }
        }
    }

    private fun releaseMedia() { val previous = media; media = null; previous?.let { runCatching(it.release) } }

    override fun handleRelease(): ListenableFuture<*> {
        releaseFuture?.let { return it }
        val future = SettableFuture.create<Void>()
        releaseFuture = future
        scope.launch {
            try { shutdown() } finally {
                runCatching { receiver.close() }
                future.set(null)
                scope.cancel()
            }
        }
        return future
    }

    private companion object {
        const val MAX_QUEUE_TRACKS = 100
        const val MAX_QUEUE_BYTES = 512L * 1024 * 1024
    }
}

class NetworkOutputException(message: String) : Exception(message)
