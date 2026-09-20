package com.aurora.music.playback.network

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.cast.CastPlayer
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.api.PendingResult
import com.aurora.music.playback.network.endpoint.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.URI
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

@UnstableApi
class DlnaNetworkReceiver(private val renderer: DlnaRenderer, private val client: DlnaClient = DlnaClient()) : NetworkReceiver {
    override val name = renderer.name
    override val kind = "DLNA"
    override val host: String = URI(renderer.location).host
    override val supportsVolume: Boolean get() = renderer.renderingControl != null
    private var started = false
    private var seenPlaying = false
    private var pendingPosition = 0L
    private var duration = 0L
    private var lastPosition = 0L
    private var startedAt = 0L
    override suspend fun contentType(mimeType: String): String = withContext(Dispatchers.IO) {
        val offered = if (mimeType in setOf("audio/wav", "audio/x-wav", "audio/vnd.wave"))
            listOf(mimeType, "audio/wav", "audio/x-wav", "audio/vnd.wave").distinct() else listOf(mimeType)
        client.chooseFormat(renderer, offered) ?: throw NetworkOutputException("This receiver does not advertise a compatible audio format.")
    }
    override suspend fun load(item: MediaItem, media: NetworkMedia, positionMs: Long) = withContext(Dispatchers.IO) {
        val mime = client.chooseFormat(renderer, listOf(media.mimeType))
            ?: throw NetworkOutputException("This receiver does not advertise WAV support.")
        client.setUri(renderer, media.url, item.mediaMetadata.title?.toString().orEmpty(), mime, media.durationMs)
        started = false; seenPlaying = false
        pendingPosition = positionMs
        lastPosition = positionMs
        duration = media.durationMs
    }
    override suspend fun playing(value: Boolean) = withContext(Dispatchers.IO) {
        if (value) {
            client.play(renderer)
            startedAt = System.nanoTime()
            if (!started && pendingPosition > 0) client.seek(renderer, pendingPosition)
            started = true
        } else if (started) client.pause(renderer)
    }
    override suspend fun seek(positionMs: Long) = withContext(Dispatchers.IO) {
        if (started) client.seek(renderer, positionMs)
        pendingPosition = positionMs
        lastPosition = positionMs
        startedAt = System.nanoTime()
    }
    override suspend fun volume(value: Float) = withContext(Dispatchers.IO) { if (renderer.renderingControl != null) client.setVolume(renderer, (value * 100).roundToInt().coerceIn(0, 100)) }
    override suspend fun status(): NetworkReceiverState = withContext(Dispatchers.IO) {
        val status = client.status(renderer)
        val playing = status.transportState == "PLAYING"
        if (playing) { seenPlaying = true; lastPosition = status.positionMillis }
        duration = status.durationMillis.takeIf { it > 0 } ?: duration
        val atEnd = duration > 0 && (maxOf(status.positionMillis, lastPosition) >= duration - 1_500 ||
            (duration <= 1_500 && (System.nanoTime() - startedAt) / 1_000_000 >= duration))
        NetworkReceiverState(playing, if (started) status.positionMillis else pendingPosition, duration,
            ended = started && (seenPlaying || duration <= 1_500) && atEnd && status.transportState == "STOPPED",
            volume = status.volume?.div(100f),
            playWhenReady = when (status.transportState) { "PLAYING" -> true; "PAUSED_PLAYBACK", "STOPPED" -> false; else -> null },
            buffering = status.transportState == "TRANSITIONING")
    }
    override suspend fun stop() = withContext(Dispatchers.IO) { client.stop(renderer); started = false; seenPlaying = false }
}

@UnstableApi
class AuroraNetworkReceiver(private val peer: PairedRenderer) : NetworkReceiver {
    private val client = RendererClient(peer)
    override val name = peer.name
    override val kind = "Aurora"
    override val host: String = URI(peer.address).host
    override val ownsQueue = true
    override var supportsVolume: Boolean = false
        private set
    override var volumeSteps: Int = 0
        private set
    private var loadedQueue = false
    override suspend fun load(item: MediaItem, media: NetworkMedia, positionMs: Long) =
        loadQueue(listOf(ReceiverQueueItem(java.util.UUID.randomUUID().toString(), item, media)), 0, positionMs, Player.REPEAT_MODE_OFF)
    override suspend fun loadQueue(items: List<ReceiverQueueItem>, index: Int, positionMs: Long, repeatMode: Int) {
        val tracks = items.map { entry ->
            val item = entry.item
            val media = entry.media
            fun replayGain(key: String): Float = item.mediaMetadata.extras?.getFloat(key, 0f)
                ?.takeIf { it.isFinite() }?.coerceIn(-60f, 30f) ?: 0f
            EndpointTrack(entry.uid, item.mediaMetadata.title?.toString().orEmpty(),
                item.mediaMetadata.artist?.toString().orEmpty(), item.mediaMetadata.albumTitle?.toString().orEmpty(),
                media.durationMs, media.mimeType, media.url, rgTrack = replayGain("rgTrack"), rgAlbum = replayGain("rgAlbum"))
        }
        val status = client.commandCancellable(RendererCommand.Queue(tracks, startIndex = index,
            positionMs = positionMs, playWhenReady = false, repeatMode = repeatMode))
        loadedQueue = true
        volumeSteps = status.volumeSteps
        supportsVolume = status.route.id != "usb-direct" && volumeSteps > 0
    }
    override suspend fun playing(value: Boolean) { client.commandCancellable(RendererCommand.Playing(value)) }
    override suspend fun seek(positionMs: Long) { client.commandCancellable(RendererCommand.Seek(positionMs)) }
    override suspend fun seekTo(positionMs: Long, index: Int?) { client.commandCancellable(RendererCommand.Seek(positionMs, index)) }
    override suspend fun reorder(ids: List<String>) {
        if (ids.isNotEmpty() || loadedQueue) client.commandCancellable(RendererCommand.Reorder(ids))
        if (ids.isEmpty()) loadedQueue = false
    }
    override suspend fun repeat(mode: Int) { client.commandCancellable(RendererCommand.Repeat(mode)) }
    override suspend fun volume(value: Float) { if (supportsVolume) client.commandCancellable(RendererCommand.Volume(value)) }
    override suspend fun status(): NetworkReceiverState {
        val status = client.statusCancellable()
        if (status.error != null) throw NetworkOutputException("Playback failed on the renderer.")
        volumeSteps = status.volumeSteps
        supportsVolume = status.route.id != "usb-direct" && volumeSteps > 0
        return NetworkReceiverState(status.playing, status.positionMs, status.durationMs,
            ended = status.route.state == "ended", volume = status.volume.takeIf { supportsVolume },
            playWhenReady = status.playWhenReady, buffering = status.route.state == "buffering",
            queueIds = status.queue.map { it.id }, queueIndex = status.queueIndex, repeatMode = status.repeatMode)
    }
    override suspend fun stop() { if (loadedQueue) client.commandCancellable(RendererCommand.Stop) }
    override fun close() { client.close() }
}

@UnstableApi
class CastNetworkReceiver(
    private val player: CastPlayer,
    override val host: String,
    private val session: CastSession? = null,
) : NetworkReceiver {
    override val name = "Chromecast"
    override val kind = "Cast"
    override val supportsVolume: Boolean get() = observedVolume() != null
    override suspend fun load(item: MediaItem, media: NetworkMedia, positionMs: Long) {
        player.playWhenReady = false
        val metadata = item.mediaMetadata.buildUpon().setArtworkUri(null).setExtras(null).build()
        player.setMediaItem(MediaItem.Builder().setMediaId(item.mediaId).setUri(media.url)
            .setMimeType(media.mimeType).setMediaMetadata(metadata).build(), positionMs)
        player.prepare()
    }
    override suspend fun playing(value: Boolean) { player.playWhenReady = value }
    override suspend fun seek(positionMs: Long) { player.seekTo(positionMs) }
    override suspend fun volume(value: Float) {
        val connected = session?.takeIf { it.isConnected } ?: throw NetworkOutputException("Cast volume is unavailable.")
        connected.volume = value.toDouble().coerceIn(0.0, 1.0)
        if (value > 0) connected.isMute = false
    }
    override suspend fun status(): NetworkReceiverState {
        if (!player.isCastSessionAvailable || player.playerError != null) throw NetworkOutputException("Cast disconnected.")
        return NetworkReceiverState(player.isPlaying, player.currentPosition, player.duration.coerceAtLeast(0),
            player.playbackState == Player.STATE_ENDED, observedVolume(), player.playWhenReady,
            player.playbackState == Player.STATE_BUFFERING)
    }
    override suspend fun stop() {
        player.pause()
        val remote = session?.takeIf { it.isConnected }?.remoteMediaClient
        if (remote == null) {
            player.stop()
            throw NetworkOutputException("Cast did not confirm stop.")
        }
        withTimeout(10_000) {
            awaitResult(remote.requestStatus())
            if (remote.hasMediaSession()) awaitResult(remote.stop())
        }
    }
    private suspend fun awaitResult(request: PendingResult<RemoteMediaClient.MediaChannelResult>) {
        suspendCancellableCoroutine<Unit> { continuation ->
            request.setResultCallback { result ->
                if (continuation.isActive) {
                    if (result.status.isSuccess) continuation.resume(Unit)
                    else continuation.resumeWithException(NetworkOutputException("Cast did not confirm the command."))
                }
            }
            continuation.invokeOnCancellation { request.cancel() }
        }
    }
    private fun observedVolume(): Float? = runCatching {
        session?.takeIf { it.isConnected }?.let { connected ->
            connected.volume.takeIf { it.isFinite() && it in 0.0..1.0 }
                ?.let { if (connected.isMute) 0f else it.toFloat() }
        }
    }.getOrNull()
}
