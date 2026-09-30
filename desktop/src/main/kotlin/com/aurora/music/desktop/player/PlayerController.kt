package com.aurora.music.desktop.player

import com.aurora.music.R
import com.aurora.music.data.PlaybackCollectionIdentity
import com.aurora.music.data.SignalPath
import com.aurora.music.desktop.natives.AudioDevice
import com.aurora.music.desktop.platform.DesktopSettings
import com.aurora.music.localization.appString
import com.aurora.music.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

enum class RepeatMode { OFF, ALL, ONE }

val EMPTY_SONG = Song("", appString(R.string.text_nothing_playing_13ae37), "", "", "", 0)

private val IdleSignalPath: StateFlow<SignalPath> = MutableStateFlow(SignalPath())

data class PlayerUiState(
    val current: Song = EMPTY_SONG,
    val queue: List<Song> = emptyList(),
    val isPlaying: Boolean = false,
    val positionSec: Float = 0f,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val expanded: Boolean = false,
    val speed: Float = 1.0f,
    val likedIds: Set<String> = emptySet(),
    val currentIndex: Int = 0,
    val sleepTimerMinutes: Int = 0,
    val sleepEndOfTrack: Boolean = false,
    val bpm: Int = 0,
    val camelot: String = "",
    val keyName: String = "",
    val isMix: Boolean = false,
    val timelineDurationSec: Int = 0,
    val isLive: Boolean = false,
) {
    val durationSec: Int get() = if (isLive) 0 else timelineDurationSec.takeIf { it > 0 } ?: current.durationSec
    val progress: Float get() = if (durationSec == 0) 0f else (positionSec / durationSec).coerceIn(0f, 1f)
    val isCurrentLiked: Boolean get() = likedIds.contains(current.id)
    val hasTrack: Boolean get() = current.id.isNotEmpty()
}

interface PlayerController {
    val state: StateFlow<PlayerUiState>
    val outputs: StateFlow<List<AudioDevice>>
    val preferredOutput: StateFlow<String?>
    val exclusiveOutput: StateFlow<Boolean>
    val volume: StateFlow<Float>
    val signalPath: StateFlow<SignalPath> get() = IdleSignalPath
    val messages: Flow<String> get() = emptyFlow()

    fun playAll(songs: List<Song>, startIndex: Int = 0, collection: PlaybackCollectionIdentity? = null)
    fun play(song: Song)
    fun playCollection(kind: String, id: String, loaded: List<Song>, startIndex: Int, total: Int)
    fun shuffleCollection(kind: String, id: String, loaded: List<Song>, total: Int)
    fun shufflePlay(songs: List<Song>, collection: PlaybackCollectionIdentity? = null)
    fun startSonicRadio(seed: Song = state.value.current, onResult: (String) -> Unit = {})
    fun startAutoDj(seed: Song = state.value.current, onResult: (String) -> Unit = {})
    fun addToQueue(song: Song)
    fun playNext(song: Song)
    fun jumpTo(index: Int)
    fun removeFromQueue(index: Int)
    fun clearQueue()
    fun moveQueueItem(from: Int, to: Int)
    fun saveQueueAsPlaylist(name: String, onResult: (String) -> Unit = {})
    fun togglePlay()
    fun seekTo(fraction: Float)
    fun next()
    fun previous()
    fun toggleShuffle()
    fun cycleRepeat()
    fun toggleLikeCurrent()
    fun refreshLikes()
    fun checkLiked(ids: List<String>)
    fun toggleLike(id: String, kind: String = "song")
    fun setExpanded(value: Boolean)
    fun setSpeed(value: Float)
    fun resetSpeedPitch()
    fun setSleepTimer(minutes: Int)
    fun setSleepEndOfTrack()
    fun setPreferredDevice(deviceId: String?)
    fun setExclusiveOutput(enabled: Boolean)
    fun setVolume(value: Float)
    fun toggleMute() = setVolume(if (volume.value > 0f) 0f else DesktopSettings.DEFAULT_UNMUTE_VOLUME)
    fun stopPlayback()
}
