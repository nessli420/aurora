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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map

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
    val hasVideo: Boolean = false,
    val videoLoading: Boolean = false,
    val videoQualityHeight: Int? = null,
    val videoIsCanvas: Boolean = false,
    val supportsCanvas: Boolean = false,
    val supportsMusicVideo: Boolean = false,
    val longFormSkipButtons: Boolean = true,
    val remote: Boolean = false,
) {
    val durationSec: Int get() = if (isLive) 0 else timelineDurationSec.takeIf { it > 0 } ?: current.durationSec
    val skipButtons: Boolean get() = longFormSkipButtons && !isMix && !isLive &&
        com.aurora.music.data.LongForm.applies(current.id, maxOf(current.durationSec, durationSec))
    val progress: Float get() = if (durationSec == 0) 0f else (positionSec / durationSec).coerceIn(0f, 1f)
    val isCurrentLiked: Boolean get() = likedIds.contains(current.id)
    val hasTrack: Boolean get() = current.id.isNotEmpty()
    // controlling another device keeps the bar, so there is always a way back
    val showsBar: Boolean get() = hasTrack || remote

    fun settled(): PlayerUiState = copy(positionSec = 0f)
}

// the state without the playback position, for views that would otherwise recompose on every tick
val PlayerController.settledState: Flow<PlayerUiState> get() = state.map { it.settled() }.distinctUntilChanged()

interface PlayerController {
    val state: StateFlow<PlayerUiState>
    val outputs: StateFlow<List<AudioDevice>>
    val preferredOutput: StateFlow<String?>
    val exclusiveOutput: StateFlow<Boolean>
    val exclusiveAvailable: Boolean get() = true
    val volume: StateFlow<Float>
    val signalPath: StateFlow<SignalPath> get() = IdleSignalPath
    val messages: Flow<String> get() = emptyFlow()
    val video: StateFlow<com.aurora.music.desktop.video.VideoPlayer?> get() = kotlinx.coroutines.flow.MutableStateFlow(null)

    fun requestMusicVideo(canvas: Boolean, onResult: (String) -> Unit) {}
    fun setVideoVisible(visible: Boolean) {}
    fun setVideoQuality(height: Int?) {}

    // windows will not replace a file that is open, so a file being rewritten is let go of for the duration
    suspend fun <T> releasingFile(path: String, block: suspend () -> T): T = block()

    fun playAll(songs: List<Song>, startIndex: Int = 0, collection: PlaybackCollectionIdentity? = null)
    fun play(song: Song)
    fun playCollection(kind: String, id: String, loaded: List<Song>, startIndex: Int, total: Int)
    fun shuffleCollection(kind: String, id: String, loaded: List<Song>, total: Int)
    fun shufflePlay(songs: List<Song>, collection: PlaybackCollectionIdentity? = null)
    fun startSonicRadio(seed: Song = state.value.current, onResult: (String) -> Unit = {})
    fun startAutoDj(seed: Song = state.value.current, onResult: (String) -> Unit = {})
    val aiDj: com.aurora.music.ai.DjMode? get() = null
    fun startAiDj(onResult: (String) -> Unit = {}) {}
    fun steerAiDj(text: String) {}
    fun switchAiDj() {}
    fun stopAiDj() {}
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
    fun checkLiked(songs: List<Song>)
    fun toggleLike(id: String, kind: String = "song")
    suspend fun likeOptions(song: Song): List<com.aurora.music.data.LikeOption> = emptyList()
    suspend fun setLikedOn(song: Song, option: com.aurora.music.data.LikeOption, liked: Boolean): List<com.aurora.music.data.LikeOption> = emptyList()
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
