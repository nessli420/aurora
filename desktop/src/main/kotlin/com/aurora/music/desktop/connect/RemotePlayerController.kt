package com.aurora.music.desktop.connect

import com.aurora.music.R
import com.aurora.music.data.PlaybackCollectionIdentity
import com.aurora.music.desktop.natives.AudioDevice
import com.aurora.music.desktop.natives.DeviceKind
import com.aurora.music.desktop.player.EMPTY_SONG
import com.aurora.music.desktop.player.PlayerController
import com.aurora.music.desktop.player.PlayerUiState
import com.aurora.music.desktop.player.RepeatMode
import com.aurora.music.localization.appString
import com.aurora.music.model.Song
import com.aurora.music.playback.network.endpoint.ConnectCommand
import com.aurora.music.playback.network.endpoint.ConnectSession
import com.aurora.music.playback.network.endpoint.ConnectStatus
import com.aurora.music.playback.network.endpoint.ConnectTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

// mirrors another aurora device's own session; the queue lives there, this renders it and forwards commands
internal class RemotePlayerController(
    val session: ConnectSession,
    private val scope: CoroutineScope,
    private val artDir: File,
    private val onDisconnect: () -> Unit,
) : PlayerController, AutoCloseable {
    private val mutableState = MutableStateFlow(PlayerUiState(current = idle(), remote = true))
    private val mutableOutputs = MutableStateFlow<List<AudioDevice>>(emptyList())
    private val mutablePreferred = MutableStateFlow<String?>(null)
    private val mutableVolume = MutableStateFlow(1f)
    private val volumeRequests = MutableStateFlow<Float?>(null)
    @Volatile private var volumeTouched = 0L
    private var artFor: String? = null
    private var artUrl = ""
    override val state: StateFlow<PlayerUiState> = mutableState.asStateFlow()
    override val outputs: StateFlow<List<AudioDevice>> = mutableOutputs.asStateFlow()
    override val preferredOutput: StateFlow<String?> = mutablePreferred.asStateFlow()
    override val exclusiveOutput: StateFlow<Boolean> = MutableStateFlow(false)
    override val exclusiveAvailable: Boolean get() = false
    override val volume: StateFlow<Float> = mutableVolume.asStateFlow()
    override val messages: Flow<String> = session.error.filterNotNull().map { session.problem() ?: it }
    private val offset: Int get() = session.status.value?.offset ?: 0
    private val jobs = listOf(
        scope.launch { session.status.collect { it?.let(::apply) } },
        scope.launch { session.connected.collect { session.status.value?.let(::apply) ?: mutableState.update { it.copy(current = idle()) } } },
        scope.launch { session.error.collect { session.status.value?.let(::apply) ?: mutableState.update { it.copy(current = idle()) } } },
        scope.launch { volumeRequests.filterNotNull().conflate().collect { session.send(ConnectCommand.Volume(it)) } },
        scope.launch {
            while (true) {
                delay(250)
                if (session.status.value?.playing == true && session.connected.value) mutableState.update { it.copy(positionSec = session.positionMs() / 1000f) }
            }
        },
    )

    val name: String get() = session.name

    private fun idle() = EMPTY_SONG.copy(title = session.name, artist = session.problem()
        ?: appString(if (session.connected.value) R.string.connect_idle_empty else R.string.connect_idle_connecting))

    private fun apply(status: ConnectStatus) {
        val local = status.index - status.offset
        val queue = status.queue.map(ConnectTrack::toSong)
        val current = queue.getOrNull(local)?.let { if (artFor == it.id) it.copy(artworkUrl = artUrl) else it } ?: idle()
        mutableState.update {
            it.copy(current = current, queue = queue.mapIndexed { i, song -> if (i == local) current else song },
                isPlaying = status.playing && session.connected.value, positionSec = session.positionMs() / 1000f, shuffle = status.shuffle,
                repeat = when (status.repeat) { 1 -> RepeatMode.ONE; 2 -> RepeatMode.ALL; else -> RepeatMode.OFF },
                speed = status.speed, likedIds = if (status.liked) setOf(current.id) else emptySet(), currentIndex = local.coerceAtLeast(0),
                sleepTimerMinutes = status.sleepMinutes, sleepEndOfTrack = status.sleepEndOfTrack,
                timelineDurationSec = (status.durationMs / 1000).toInt())
        }
        mutableOutputs.value = status.outputs.filter { it.id.isNotEmpty() }.map { AudioDevice(it.id, it.name, DeviceKind.REMOTE, false) }
        mutablePreferred.value = status.output.ifBlank { null }
        if (status.volume >= 0f && System.currentTimeMillis() - volumeTouched > 1_500) mutableVolume.value = status.volume
        if (current.id.isNotEmpty() && current.id != artFor) loadArt(current.id)
    }

    private fun loadArt(id: String) {
        artFor = id
        artUrl = ""
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                val bytes = runCatching { session.artwork(id) }.getOrNull() ?: return@withContext null
                artDir.mkdirs()
                artDir.listFiles()?.forEach { it.delete() }
                val name = MessageDigest.getInstance("SHA-1").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
                File(artDir, name).apply { writeBytes(bytes) }
            }
            if (artFor == id && file != null) {
                artUrl = com.aurora.music.desktop.platform.desktopFileUri(file.path)
                session.status.value?.let(::apply)
            }
        }
    }

    private fun send(command: ConnectCommand) = session.post(command)

    private fun play(songs: List<Song>, start: Int) {
        if (songs.isEmpty()) return
        val from = (start - 100).coerceIn(0, (songs.size - 500).coerceAtLeast(0))
        val window = songs.subList(from, minOf(songs.size, from + 500))
        send(ConnectCommand.Play(window.map(ConnectTrack::of), (start - from).coerceIn(0, window.lastIndex), 0, true))
    }

    override fun playAll(songs: List<Song>, startIndex: Int, collection: PlaybackCollectionIdentity?) = play(songs, startIndex)
    override fun play(song: Song) = play(listOf(song), 0)
    override fun playCollection(kind: String, id: String, loaded: List<Song>, startIndex: Int, total: Int) = play(loaded, startIndex)
    override fun shuffleCollection(kind: String, id: String, loaded: List<Song>, total: Int) = play(loaded.shuffled(), 0)
    override fun shufflePlay(songs: List<Song>, collection: PlaybackCollectionIdentity?) = play(songs.shuffled(), 0)
    override fun startSonicRadio(seed: Song, onResult: (String) -> Unit) = Unit
    override fun startAutoDj(seed: Song, onResult: (String) -> Unit) = Unit
    override fun addToQueue(song: Song) = send(ConnectCommand.Add(listOf(ConnectTrack.of(song)), -1))
    override fun playNext(song: Song) = send(ConnectCommand.Add(listOf(ConnectTrack.of(song)), (session.status.value?.index ?: -1) + 1))
    override fun jumpTo(index: Int) = send(ConnectCommand.Jump(offset + index))
    override fun removeFromQueue(index: Int) = send(ConnectCommand.Remove(offset + index))
    override fun clearQueue() = send(ConnectCommand.Clear)
    override fun moveQueueItem(from: Int, to: Int) = send(ConnectCommand.Move(offset + from, offset + to))
    override fun saveQueueAsPlaylist(name: String, onResult: (String) -> Unit) = Unit
    override fun togglePlay() {
        val playing = !mutableState.value.isPlaying
        mutableState.update { it.copy(isPlaying = playing) }
        send(ConnectCommand.Playing(playing))
    }
    override fun seekTo(fraction: Float) {
        val duration = mutableState.value.durationSec * 1000L
        if (duration > 0) send(ConnectCommand.Seek((fraction.coerceIn(0f, 1f) * duration).toLong()))
    }
    override fun next() = send(ConnectCommand.Next)
    override fun previous() = send(ConnectCommand.Previous)
    override fun toggleShuffle() = send(ConnectCommand.Shuffle(!mutableState.value.shuffle))
    override fun cycleRepeat() = send(ConnectCommand.Repeat(when (mutableState.value.repeat) {
        RepeatMode.OFF -> 2; RepeatMode.ALL -> 1; RepeatMode.ONE -> 0
    }))
    override fun toggleLikeCurrent() = send(ConnectCommand.Like(!mutableState.value.isCurrentLiked))
    override fun refreshLikes(restart: Boolean) = Unit
    override fun checkLiked(songs: List<com.aurora.music.model.Song>) = Unit
    override fun toggleLike(id: String, kind: String) { if (kind == "song" && id == mutableState.value.current.id) toggleLikeCurrent() }
    override fun setExpanded(value: Boolean) = mutableState.update { it.copy(expanded = value) }
    override fun setSpeed(value: Float) = send(ConnectCommand.Speed((Math.round(value / 0.05f) * 0.05f).coerceIn(0.5f, 2f)))
    override fun resetSpeedPitch() = send(ConnectCommand.Speed(1f))
    override fun setSleepTimer(minutes: Int) = send(ConnectCommand.Sleep(minutes.coerceAtLeast(0), false))
    override fun setSleepEndOfTrack() = send(ConnectCommand.Sleep(0, true))
    override fun setPreferredDevice(deviceId: String?) = send(ConnectCommand.Output(deviceId.orEmpty()))
    override fun setExclusiveOutput(enabled: Boolean) = Unit
    override fun setVolume(value: Float) {
        volumeTouched = System.currentTimeMillis()
        mutableVolume.value = value.coerceIn(0f, 1f)
        volumeRequests.value = mutableVolume.value
    }
    override fun stopPlayback() = onDisconnect()

    override fun close() {
        jobs.forEach { it.cancel() }
    }
}
