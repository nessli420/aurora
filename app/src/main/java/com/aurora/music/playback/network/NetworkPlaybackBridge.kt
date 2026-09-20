package com.aurora.music.playback.network

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.aurora.music.playback.network.endpoint.*
import kotlinx.coroutines.*
import java.io.Closeable
import java.io.File
import kotlin.math.roundToInt

@UnstableApi
class NetworkPlaybackBridge(
    private val context: Context,
    private val player: Player,
    private val manager: NetworkOutputManager,
    private val select: (NetworkTarget?) -> Unit,
    private val localRoute: () -> EndpointRoute,
    private val foreground: (Boolean) -> Unit,
) : NetworkOutputHost, RendererPlayback, Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mediaCache = RendererMediaCache(context)
    @Volatile private var endpoint: RendererEndpoint? = null
    private var endpointJob: Job? = null
    private var clientsJob: Job? = null
    private var pairingJob: Job? = null
    private var desiredReceiver = false
    private var receiverGeneration = 0L
    @Volatile private var closed = false
    private var volumeBefore: Int? = null
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var received = false
    private var receivedFiles = emptyMap<String, File>()

    init { manager.attach(this) }

    override fun connect(target: NetworkTarget) {
        if (desiredReceiver) { manager.update { it.copy(error = "Turn off receiver mode before sending audio.") }; return }
        select(target)
    }
    override fun disconnect() = select(null)
    override fun enableReceiver(enabled: Boolean) {
        if (closed || (desiredReceiver == enabled && (endpoint != null || endpointJob?.isActive == true))) return
        if (enabled && manager.state.value.receiverName != null) {
            manager.update { it.copy(error = "Disconnect network output before enabling the receiver.") }; return
        }
        desiredReceiver = enabled
        val generation = ++receiverGeneration
        endpointJob?.cancel()
        clientsJob?.cancel()
        pairingJob?.cancel()
        if (!enabled) {
            val old = endpoint
            endpoint = null
            if (received) {
                val receiverFiles = cachedQueueFiles()
                if (ownsQueue()) {
                    player.pause(); player.stop(); player.clearMediaItems()
                    mediaCache.retain(emptyList())
                } else mediaCache.retain(receiverFiles)
                received = false
                receivedFiles = emptyMap()
            }
            volumeBefore?.let { audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) }; volumeBefore = null
            manager.update { it.copy(receiverEnabled = false, receiverAddress = null, pairing = null, controllers = emptyList()) }
            foreground(false)
            endpointJob = scope.launch(Dispatchers.IO) {
                withContext(NonCancellable) { old?.stop() }
            }
            return
        }
        manager.update { it.copy(receiverEnabled = true, receiverAddress = null, error = null) }
        endpointJob = scope.launch {
            if (endpoint != null) return@launch
            var candidate: RendererEndpoint? = null
            try {
                foreground(true)
                candidate = RendererEndpoint(context, this@NetworkPlaybackBridge, "Aurora · ${android.os.Build.MODEL}")
                val address = withContext(Dispatchers.IO) { candidate.start() }
                if (closed || generation != receiverGeneration || !desiredReceiver) {
                    withContext(NonCancellable + Dispatchers.IO) { candidate.stop() }
                    return@launch
                }
                endpoint = candidate
                manager.update { it.copy(receiverEnabled = true, receiverAddress = address, error = null) }
                clientsJob = scope.launch { candidate.clients.collect { clients -> manager.update { it.copy(controllers = clients) } } }
                pairingJob = scope.launch { candidate.pairing.collect { pairing -> manager.update { it.copy(pairing = pairing) } } }
            } catch (e: CancellationException) { withContext(NonCancellable + Dispatchers.IO) { candidate?.stop() }; throw e }
            catch (error: Exception) {
                withContext(Dispatchers.IO) { candidate?.stop() }
                if (generation == receiverGeneration) {
                    desiredReceiver = false
                    manager.update { it.copy(receiverEnabled = false, error = if (error is RendererException) error.message else "Receiver could not start on this network.") }
                    foreground(false)
                }
            }
        }
    }
    override fun pairReceiver() { endpoint?.beginPairing() }
    override fun revokeController(id: String) { endpoint?.revokeClient(id) }

    private fun <T> onMain(action: () -> T): T = runBlocking {
        withTimeout(5000) { withContext(Dispatchers.Main.immediate) { action() } }
    }

    override fun status(): RendererStatus = onMain {
        val volumeSteps = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / volumeSteps.coerceAtLeast(1)
        val route = localRoute()
        RendererStatus(playing = player.isPlaying, playWhenReady = player.playWhenReady,
            positionMs = player.currentPosition.coerceAtLeast(0), durationMs = player.duration.coerceAtLeast(0), volume = volume,
            volumeSteps = if (route.id == "usb-direct") 0 else volumeSteps,
            queueIndex = player.currentMediaItemIndex.coerceAtLeast(0),
            repeatMode = player.repeatMode,
            queue = (0 until player.mediaItemCount.coerceAtMost(100)).map { index ->
                val item = player.getMediaItemAt(index)
                EndpointQueueItem(item.mediaId.removePrefix("aurora-renderer:").take(128), item.mediaMetadata.title?.toString().orEmpty().take(512),
                    item.mediaMetadata.artist?.toString().orEmpty().take(512), if (index == player.currentMediaItemIndex) player.duration.coerceAtLeast(0) else 0)
            }, route = route.copy(state = when (player.playbackState) {
                Player.STATE_BUFFERING -> "buffering"; Player.STATE_ENDED -> "ended"; Player.STATE_IDLE -> "idle"; else -> "ready"
            }), error = player.playerError?.let { "Playback failed on the renderer." })
    }

    override fun execute(command: RendererCommand): RendererStatus = executeAuthorized(command) { true }

    override fun executeAuthorized(command: RendererCommand, authorized: () -> Boolean): RendererStatus {
        val receiver = endpoint ?: throw RendererException("receiver_disabled", "Receiver is off.", 409)
        val accessValid = { !closed && endpoint === receiver && authorized() &&
            (command !is RendererCommand.Queue || command.authorizationValid()) }
        val previousQueue = if (command is RendererCommand.Queue) onMain { queueIdentity() } else null
        if (command is RendererCommand.Queue) mediaCache.retain(onMain { cachedQueueFiles() })
        val files = if (command is RendererCommand.Queue) mediaCache.receive(command.tracks, command.sourceHost, accessValid) else null
        var committed = false
        try {
            onMain {
                if (!accessValid()) throw RendererException("unauthorized", "Controller access ended.", 401)
                if (command !is RendererCommand.Queue && !ownsQueue()) queueChanged()
                when (command) {
                    is RendererCommand.Queue -> {
                        if (queueIdentity() != previousQueue) queueChanged()
                        val items = command.tracks.mapIndexed { index, track -> MediaItem.Builder()
                            .setMediaId("aurora-renderer:${track.id}").setUri(Uri.fromFile(files!![index])).setMimeType(track.mimeType)
                            .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist)
                                .setExtras(android.os.Bundle().apply {
                                    putFloat("rgTrack", track.rgTrack); putFloat("rgAlbum", track.rgAlbum)
                                })
                                .setAlbumTitle(track.album).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build()).build() }
                        player.setMediaItems(items, command.startIndex, command.positionMs)
                        player.shuffleModeEnabled = false
                        player.repeatMode = command.repeatMode
                        player.playWhenReady = command.playWhenReady
                        player.prepare(); received = true
                        receivedFiles = command.tracks.mapIndexed { index, track -> track.id to files!![index] }.toMap()
                        committed = true
                    }
                    is RendererCommand.Playing -> player.playWhenReady = command.value
                    is RendererCommand.Seek -> {
                        if (command.index != null && command.index !in 0 until player.mediaItemCount) throw RendererException("invalid_index", "Queue position is unavailable.")
                        player.seekTo(command.index ?: player.currentMediaItemIndex, command.positionMs)
                    }
                    is RendererCommand.Volume -> {
                        if (localRoute().id == "usb-direct") throw RendererException("hardware_volume", "Use the DAC's volume control.", 409)
                        if (volumeBefore == null) volumeBefore = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (command.value * audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).roundToInt(), 0)
                    }
                    is RendererCommand.Route -> if (command.id != localRoute().id)
                        throw RendererException("route_unavailable", "Choose the audio output on the renderer.", 409)
                    is RendererCommand.Repeat -> player.repeatMode = command.mode
                    is RendererCommand.Reorder -> reorder(command.ids)
                    RendererCommand.Next -> player.seekToNextMediaItem()
                    RendererCommand.Previous -> player.seekToPreviousMediaItem()
                    RendererCommand.Stop -> { player.pause(); player.stop() }
                }
            }
            if (files != null) mediaCache.retain(files)
            else if (command is RendererCommand.Reorder) mediaCache.retain(onMain { cachedQueueFiles() })
            return status()
        } catch (e: Exception) { if (!committed) files?.let(mediaCache::discard); throw e }
    }

    private fun queueIdentity(): List<Pair<String, Uri?>> = (0 until player.mediaItemCount).map { index ->
        player.getMediaItemAt(index).let { it.mediaId to it.localConfiguration?.uri }
    }

    private fun ownsQueue(): Boolean = received && (0 until player.mediaItemCount).all { index ->
        val item = player.getMediaItemAt(index)
        val id = item.mediaId.removePrefix("aurora-renderer:")
        item.mediaId.startsWith("aurora-renderer:") && receivedFiles[id]?.let { Uri.fromFile(it) == item.localConfiguration?.uri } == true
    } && player.mediaItemCount == receivedFiles.size

    private fun queueChanged(): Nothing = throw RendererException("queue_changed", "The renderer queue changed. Reconnect.", 409)

    private fun cachedQueueFiles(): List<File> = (0 until player.mediaItemCount).mapNotNull { index ->
        val item = player.getMediaItemAt(index)
        val uri = item.localConfiguration?.uri
        if (uri?.scheme == "file")
            uri.path?.let(::File)?.takeIf { it.parentFile?.canonicalFile == File(context.cacheDir, "renderer-media").canonicalFile }
        else null
    }

    private fun reorder(ids: List<String>) {
        val oldIds = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId.removePrefix("aurora-renderer:") }
        if (ids.distinct().size != ids.size || oldIds.distinct().size != oldIds.size || ids.any { it !in oldIds }) queueChanged()
        val current = player.currentMediaItem?.mediaId?.removePrefix("aurora-renderer:")
        val currentIndex = player.currentMediaItemIndex.coerceAtLeast(0)
        val wanted = player.playWhenReady
        if (ids.isEmpty()) {
            player.pause(); player.stop(); player.clearMediaItems(); receivedFiles = emptyMap()
            return
        }
        for (index in oldIds.indices.reversed()) if (oldIds[index] !in ids) player.removeMediaItem(index)
        ids.forEachIndexed { target, id ->
            val currentPosition = (0 until player.mediaItemCount).first { player.getMediaItemAt(it).mediaId.removePrefix("aurora-renderer:") == id }
            if (currentPosition != target) player.moveMediaItem(currentPosition, target)
        }
        player.shuffleModeEnabled = false
        receivedFiles = receivedFiles.filterKeys { it in ids }
        if (current !in ids) {
            player.seekTo(currentIndex.coerceAtMost(ids.lastIndex), 0)
            player.prepare()
        }
        player.playWhenReady = wanted
    }

    override fun close() {
        closed = true
        desiredReceiver = false
        receiverGeneration++
        endpoint?.stop(); endpoint = null; scope.cancel(); manager.detach(this)
        mediaCache.close()
        volumeBefore?.let { audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) }
    }
}
