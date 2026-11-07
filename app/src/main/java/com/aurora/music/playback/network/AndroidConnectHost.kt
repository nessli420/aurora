package com.aurora.music.playback.network

import android.content.Context
import android.media.AudioManager
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import com.aurora.music.AuroraApplication
import com.aurora.music.playback.network.endpoint.*
import com.aurora.music.playback.songMediaItem
import com.aurora.music.ui.screens.player.USEFUL_OUTPUT_TYPES
import com.aurora.music.ui.screens.player.outputLabel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import com.aurora.music.data.remote.AuroraHttp
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.roundToInt

class AndroidConnectHost(
    private val context: Context,
    private val deck: () -> Player,
    private val localRoute: () -> EndpointRoute,
    private val shuffled: () -> Boolean,
    private val shuffle: (Boolean) -> Unit,
) : ConnectHost {
    private val player get() = deck()
    private var observed: Player? = null
    private val container = (context.applicationContext as AuroraApplication).container
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val http by lazy { AuroraHttp.base.newBuilder().callTimeout(15, TimeUnit.SECONDS).build() }
    @Volatile private var liked: Pair<String, Boolean>? = null
    private var likeJob: Job? = null
    private var sleepJob: Job? = null
    @Volatile private var sleepUntil = 0L
    @Volatile private var sleepEndOfTrack = false
    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            if (sleepEndOfTrack && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) { sleepEndOfTrack = false; player.pause() }
        }
    }

    init { scope.launch { follow() } }

    fun follow() {
        val live = player
        if (live === observed) return
        observed?.removeListener(listener)
        observed = live.apply { addListener(listener) }
    }

    private fun <T> onMain(action: () -> T): T = runBlocking { withTimeout(5_000) { withContext(Dispatchers.Main.immediate) { action() } } }

    override fun status(): ConnectStatus {
        val presets = runBlocking { runCatching { container.settingsStore.processingPresetLibrary.first().presets }.getOrDefault(emptyList()) }
            .map { ConnectOption(it.id, it.name) }
        val outputs = listOf(ConnectOption("0", com.aurora.music.localization.appString(com.aurora.music.R.string.text_automatic_ac9041))) +
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.type in USEFUL_OUTPUT_TYPES }
                .map { ConnectOption(it.id.toString(), outputLabel(it)) }
        val streamVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return onMain {
            val current = player.currentMediaItem?.mediaId.orEmpty()
            if (current.isNotEmpty() && liked?.first != current) refreshLiked(current)
            ConnectStatus(
                playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED && player.playbackState != Player.STATE_IDLE,
                positionMs = player.currentPosition.coerceAtLeast(0), durationMs = player.duration.coerceAtLeast(0),
                index = player.currentMediaItemIndex.coerceAtLeast(0),
                shuffle = shuffled(), repeat = player.repeatMode,
                volume = if (localRoute().id == "usb-direct") -1f else streamVolume,
                speed = player.playbackParameters.speed, liked = liked?.takeIf { it.first == current }?.second == true,
                sleepMinutes = if (sleepUntil > System.currentTimeMillis()) ceil((sleepUntil - System.currentTimeMillis()) / 60_000.0).toInt() else 0,
                sleepEndOfTrack = sleepEndOfTrack,
                outputs = outputs, output = container.preferredAudioDeviceId.value.toString(), presets = presets,
                error = player.playerError?.let { "Playback failed on this device." },
            ).windowedFrom(player.mediaItemCount) { index ->
                val item = player.getMediaItemAt(index)
                val metadata = item.mediaMetadata
                ConnectTrack(item.mediaId, metadata.title?.toString().orEmpty().take(512), metadata.artist?.toString().orEmpty().take(512),
                    metadata.albumTitle?.toString().orEmpty().take(512), metadata.extras?.getInt("aurora.durationSec") ?: 0,
                    metadata.extras?.getString("aurora.albumId").orEmpty(), metadata.extras?.getString("aurora.artistId").orEmpty())
            }
        }
    }

    override fun execute(command: ConnectCommand) {
        val songs = when (command) {
            is ConnectCommand.Play -> runBlocking(Dispatchers.IO) { container.repository.resolveConnectTracks(command.tracks) }
            is ConnectCommand.Add -> runBlocking(Dispatchers.IO) { container.repository.resolveConnectTracks(command.tracks) }
            else -> emptyList()
        }
        when (command) {
            is ConnectCommand.Preset -> runBlocking(Dispatchers.IO) { container.settingsStore.applyProcessingPreset(command.id) }
                .getOrElse { throw RendererException("preset_failed", it.message ?: "That preset could not be applied.", 409) }
            is ConnectCommand.Like -> {
                val id = onMain { player.currentMediaItem?.mediaId } ?: return
                liked = id to command.value
                scope.launch(Dispatchers.IO) { runCatching { container.repository.setStarred(id, command.value) } }
            }
            else -> onMain { apply(command, songs) }
        }
    }

    private fun apply(command: ConnectCommand, songs: List<com.aurora.music.model.Song>) {
        when (command) {
            is ConnectCommand.Playing -> {
                if (command.value && player.playbackState == Player.STATE_IDLE) player.prepare()
                if (command.value && player.playbackState == Player.STATE_ENDED) player.seekTo(0, 0)
                player.playWhenReady = command.value
            }
            is ConnectCommand.Seek -> player.seekTo(command.positionMs)
            is ConnectCommand.Jump -> { requireIndex(command.index); player.seekTo(command.index, 0) }
            ConnectCommand.Next -> player.seekToNext()
            ConnectCommand.Previous -> player.seekToPrevious()
            is ConnectCommand.Shuffle -> shuffle(command.value)
            is ConnectCommand.Repeat -> player.repeatMode = command.mode
            is ConnectCommand.Volume -> {
                if (localRoute().id == "usb-direct") throw RendererException("hardware_volume", "Use the DAC's volume control.", 409)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (command.value * audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).roundToInt(), 0)
            }
            is ConnectCommand.Speed -> player.playbackParameters = PlaybackParameters(command.value, player.playbackParameters.pitch)
            is ConnectCommand.Sleep -> sleep(command.minutes, command.endOfTrack)
            is ConnectCommand.Output -> container.preferredAudioDeviceId.value = command.id.toIntOrNull()
                ?: throw RendererException("unsupported_route", "Select an available output on the renderer.", 409)
            is ConnectCommand.Play -> {
                val start = songs.indexOfFirst { it.id == command.tracks[command.index].id }
                if (start < 0) throw RendererException("not_available", "Those songs aren't available on that device.", 409)
                player.setMediaItems(songs.map { songMediaItem(it, container.repository) }, start, command.positionMs)
                shuffle(false)
                player.prepare()
                player.playWhenReady = command.playing
            }
            is ConnectCommand.Add -> {
                if (songs.isEmpty()) throw RendererException("not_available", "Those songs aren't available on that device.", 409)
                val at = if (command.index < 0) player.mediaItemCount else command.index.coerceAtMost(player.mediaItemCount)
                player.addMediaItems(at, songs.map { songMediaItem(it, container.repository) })
            }
            is ConnectCommand.Remove -> { requireIndex(command.index); player.removeMediaItem(command.index) }
            is ConnectCommand.Move -> { requireIndex(command.from); requireIndex(command.to); player.moveMediaItem(command.from, command.to) }
            ConnectCommand.Clear -> player.clearMediaItems()
            ConnectCommand.Stop -> { player.pause(); player.stop() }
            is ConnectCommand.Like, is ConnectCommand.Preset -> Unit
        }
    }

    private fun requireIndex(index: Int) {
        if (index !in 0 until player.mediaItemCount) throw RendererException("invalid_index", "Queue position is unavailable.", 409)
    }

    private fun sleep(minutes: Int, endOfTrack: Boolean) {
        sleepJob?.cancel()
        sleepEndOfTrack = endOfTrack
        sleepUntil = if (minutes > 0 && !endOfTrack) System.currentTimeMillis() + minutes * 60_000L else 0L
        if (sleepUntil > 0) sleepJob = scope.launch {
            delay(minutes * 60_000L)
            sleepUntil = 0L
            player.pause()
        }
    }

    private fun refreshLiked(id: String) {
        likeJob?.cancel()
        liked = id to false
        likeJob = scope.launch(Dispatchers.IO) {
            val value = runCatching { container.repository.likedSongIds(listOf(id)) }.getOrNull()?.contains(id) ?: return@launch
            if (liked?.first == id) liked = id to value
        }
    }

    override fun artwork(id: String): ByteArray? {
        val uri = onMain {
            (0 until player.mediaItemCount).map(player::getMediaItemAt).firstOrNull { it.mediaId == id }?.mediaMetadata?.artworkUri
        } ?: return null
        return runCatching {
            when (uri.scheme) {
                "http", "https" -> http.newCall(Request.Builder().url(uri.toString()).build()).execute().use { response ->
                    response.body?.takeIf { response.isSuccessful }?.byteStream()?.use(::bounded)
                }
                "content", "file", "android.resource" -> context.contentResolver.openInputStream(uri)?.use(::bounded)
                else -> null
            }
        }.getOrNull()
    }

    private fun bounded(input: java.io.InputStream): ByteArray? {
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return bytes.toByteArray()
            if (bytes.size() + count > 8_388_608) return null
            bytes.write(buffer, 0, count)
        }
    }

    fun close() {
        scope.launch(NonCancellable) { observed?.removeListener(listener) }
        sleepJob?.cancel()
        scope.cancel()
    }
}
