package com.aurora.music.playback.network

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.aurora.music.playback.network.endpoint.ConnectCommand
import com.aurora.music.playback.network.endpoint.ConnectSession
import com.aurora.music.playback.network.endpoint.ConnectStatus
import com.aurora.music.playback.network.endpoint.ConnectTrack
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.*
import java.io.File
import java.security.MessageDigest
import kotlin.math.roundToInt

// mirrors another aurora device's own session; the queue lives there, this only renders and forwards
@UnstableApi
class RemoteSessionPlayer(private val context: Context, val session: ConnectSession) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val artDir = File(context.cacheDir, ART_DIR)
    private var status: ConnectStatus? = session.status.value
    private var artFor: String? = null
    private var artUri: Uri? = null

    init {
        scope.launch { session.status.collect { value -> status = value; value?.current?.id?.let(::loadArt); invalidateState() } }
        scope.launch { session.connected.collect { invalidateState() } }
    }

    val name: String get() = session.name

    fun setShuffle(target: Int) {
        val value = when (target) { 1 -> true; 0 -> false; else -> status?.shuffle != true }
        send(ConnectCommand.Shuffle(value))
    }

    fun cycleRepeat() = send(ConnectCommand.Repeat(when (status?.repeat) { 0 -> 2; 2 -> 1; else -> 0 }))

    override fun getState(): State {
        val current = status ?: return State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAll(Player.COMMAND_RELEASE, Player.COMMAND_STOP).build())
            .setDeviceInfo(DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).build()).build()
        val local = (current.index - current.offset).coerceIn(0, (current.queue.size - 1).coerceAtLeast(0))
        val playlist = current.queue.mapIndexed { i, track ->
            MediaItemData.Builder("${current.offset + i}:${track.id}").setMediaItem(item(track, i == local))
                .setDurationUs(when {
                    i == local && current.durationMs > 0 -> current.durationMs * 1000
                    track.durationSec > 0 -> track.durationSec * 1_000_000L
                    else -> C.TIME_UNSET
                }).setIsSeekable(true).build()
        }
        val volume = current.volume
        val commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP, Player.COMMAND_RELEASE,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SET_REPEAT_MODE,
            Player.COMMAND_SET_SHUFFLE_MODE, Player.COMMAND_SET_SPEED_AND_PITCH,
            Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS,
        ).apply {
            if (volume >= 0f) addAll(Player.COMMAND_GET_VOLUME, Player.COMMAND_SET_VOLUME, Player.COMMAND_GET_DEVICE_VOLUME,
                Player.COMMAND_SET_DEVICE_VOLUME, Player.COMMAND_ADJUST_DEVICE_VOLUME,
                Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS, Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)
        }.build()
        return State.Builder().setAvailableCommands(commands)
            .setPlaylist(playlist).setCurrentMediaItemIndex(if (playlist.isEmpty()) C.INDEX_UNSET else local)
            .setRepeatMode(current.repeat).setShuffleModeEnabled(current.shuffle)
            .setPlaybackParameters(PlaybackParameters(current.speed))
            .setVolume(volume.coerceAtLeast(0f))
            .setDeviceInfo(DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMaxVolume(if (volume >= 0f) STEPS else 0).build())
            .setDeviceVolume((volume.coerceAtLeast(0f) * STEPS).roundToInt())
            .setPlayWhenReady(current.playing, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setPlaybackState(when {
                playlist.isEmpty() -> Player.STATE_IDLE
                !session.connected.value -> Player.STATE_BUFFERING
                else -> Player.STATE_READY
            })
            .setContentPositionMs { session.positionMs() }
            .build()
    }

    private fun item(track: ConnectTrack, current: Boolean): MediaItem = MediaItem.Builder().setMediaId(track.id)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).setAlbumTitle(track.album)
            .apply { if (current) artUri?.takeIf { artFor == track.id }?.let(::setArtworkUri) }
            .setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(Bundle().apply {
                putInt("aurora.durationSec", track.durationSec)
                putString("aurora.albumId", track.albumId)
                putString("aurora.artistId", track.artistId)
            }).build())
        .build()

    private fun loadArt(id: String) {
        if (artFor == id) return
        artFor = id
        artUri = null
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                val bytes = runCatching { session.artwork(id) }.getOrNull() ?: return@withContext null
                artDir.mkdirs()
                artDir.listFiles()?.forEach { it.delete() }
                val name = MessageDigest.getInstance("SHA-1").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
                File(artDir, name).apply { writeBytes(bytes) }
            }
            if (artFor == id && file != null) { artUri = Uri.fromFile(file); invalidateState() }
        }
    }

    private fun send(command: ConnectCommand): ListenableFuture<*> {
        val done = SettableFuture.create<Any?>()
        scope.launch {
            // a song the other device can't find would otherwise change nothing without a word
            if (!session.send(command) && (command is ConnectCommand.Play || command is ConnectCommand.Add))
                session.problem()?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show() }
            done.set(null)
        }
        return done
    }

    private fun tracks(items: List<MediaItem>) = items.map { item ->
        val metadata = item.mediaMetadata
        ConnectTrack(item.mediaId, metadata.title?.toString().orEmpty().take(512), metadata.artist?.toString().orEmpty().take(512),
            metadata.albumTitle?.toString().orEmpty().take(512), metadata.extras?.getInt("aurora.durationSec") ?: 0,
            metadata.extras?.getString("aurora.albumId").orEmpty(), metadata.extras?.getString("aurora.artistId").orEmpty())
    }

    private val offset: Int get() = status?.offset ?: 0

    override fun handleSetPlayWhenReady(playWhenReady: Boolean) = send(ConnectCommand.Playing(playWhenReady))
    override fun handlePrepare(): ListenableFuture<*> = SettableFuture.create<Any?>().apply { set(null) }
    override fun handleStop() = send(ConnectCommand.Stop)
    override fun handleRelease(): ListenableFuture<*> { scope.cancel(); return SettableFuture.create<Any?>().apply { set(null) } }
    override fun handleSetRepeatMode(repeatMode: Int) = send(ConnectCommand.Repeat(repeatMode))
    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean) = send(ConnectCommand.Shuffle(shuffleModeEnabled))
    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters) =
        send(ConnectCommand.Speed(playbackParameters.speed.coerceIn(0.25f, 4f)))
    override fun handleSetVolume(volume: Float) = send(ConnectCommand.Volume(volume.coerceIn(0f, 1f)))
    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int) = handleSetVolume(deviceVolume.toFloat() / STEPS)
    override fun handleIncreaseDeviceVolume(flags: Int) = handleSetVolume((status?.volume ?: 0f) + 1f / STEPS * 5)
    override fun handleDecreaseDeviceVolume(flags: Int) = handleSetVolume((status?.volume ?: 0f) - 1f / STEPS * 5)
    override fun handleSetDeviceMuted(muted: Boolean, flags: Int) = handleSetVolume(if (muted) 0f else 0.3f)

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> = when (seekCommand) {
        Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> send(ConnectCommand.Next)
        Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> send(ConnectCommand.Previous)
        else -> {
            val local = (status?.index ?: 0) - offset
            if (mediaItemIndex != C.INDEX_UNSET && mediaItemIndex != local) {
                send(ConnectCommand.Jump(offset + mediaItemIndex))
                if (positionMs > 0) send(ConnectCommand.Seek(positionMs)) else SettableFuture.create<Any?>().apply { set(null) }
            } else send(ConnectCommand.Seek(positionMs.coerceAtLeast(0)))
        }
    }

    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        if (mediaItems.isEmpty()) return send(ConnectCommand.Clear)
        val start = startIndex.takeIf { it in mediaItems.indices } ?: 0
        val from = (start - 100).coerceIn(0, (mediaItems.size - MAX_TRACKS).coerceAtLeast(0))
        val window = mediaItems.subList(from, minOf(mediaItems.size, from + MAX_TRACKS))
        return send(ConnectCommand.Play(tracks(window), start - from, startPositionMs.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0, status?.playing == true))
    }

    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        val count = status?.queue?.size ?: 0
        return send(ConnectCommand.Add(tracks(mediaItems.take(MAX_TRACKS)), if (index >= count) -1 else offset + index))
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        if (fromIndex == 0 && toIndex >= (status?.queue?.size ?: 0)) return send(ConnectCommand.Clear)
        var last: ListenableFuture<*> = SettableFuture.create<Any?>().apply { set(null) }
        for (i in toIndex - 1 downTo fromIndex) last = send(ConnectCommand.Remove(offset + i))
        return last
    }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
        var last: ListenableFuture<*> = SettableFuture.create<Any?>().apply { set(null) }
        if (newIndex <= fromIndex) for (i in 0 until toIndex - fromIndex) last = send(ConnectCommand.Move(offset + fromIndex + i, offset + newIndex + i))
        else for (i in toIndex - fromIndex - 1 downTo 0) last = send(ConnectCommand.Move(offset + fromIndex + i, offset + newIndex + i))
        return last
    }

    companion object {
        const val ART_DIR = "connect-art"
        private const val STEPS = 100
        private const val MAX_TRACKS = 500
    }
}
