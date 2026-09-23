package com.aurora.music.viewmodel

import android.app.Application
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.aurora.music.model.Song
import com.aurora.music.playback.YoutubeResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

internal data class CompanionVideoStatus(
    val ready: Boolean,
    val loading: Boolean,
    val qualityHeight: Int?,
)

// - native playback advances while exoplayer buffers
internal val Player.effectivelyPlaying: Boolean
    get() = isPlaying || (playWhenReady && playbackState == Player.STATE_BUFFERING)

@OptIn(UnstableApi::class)
internal class CompanionVideoController(
    private val app: Application,
    private val scope: CoroutineScope,
    private val resolver: YoutubeResolver,
    private val audioPlayer: () -> Player?,
    private val currentSong: () -> Song,
    private val onStateChanged: (CompanionVideoStatus) -> Unit,
) {
    private var video: ExoPlayer? = null
    private var ready = false
    private var visible = false
    private var loading = false
    private var songId: String? = null
    private var videoId: String? = null
    private var selectedQuality: Int? = 720
    private var lastSeekMs = 0L
    private var searchJob: Job? = null
    private var qualityJob: Job? = null

    val player: Player? get() = video?.takeIf { ready }
    val isReady: Boolean get() = ready
    val qualityHeight: Int? get() = selectedQuality

    fun request(song: Song, onUnavailable: () -> Unit) {
        if (song.id.isBlank() || song.artist.isBlank() || song.title.isBlank() || loading) return
        if (songId == song.id && ready) return
        clear()
        songId = song.id
        loading = true
        publish()
        searchJob = scope.launch {
            val match = withContext(Dispatchers.IO) {
                resolver.findMusicVideo(song.artist, song.title, song.durationSec)
            }
            ensureActive()
            if (currentSong().id != song.id) return@launch
            if (match == null) {
                clear()
                onUnavailable()
                return@launch
            }
            videoId = match.videoId
            selectedQuality = 720
            val candidate = ExoPlayer.Builder(app).build().apply {
                volume = 0f
                setMediaItem(MediaItem.fromUri(match.streamUrl))
            }
            video = candidate
            candidate.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (candidate !== video || playbackState != Player.STATE_READY) return
                    if (!candidate.currentTracks.isTypeSupported(C.TRACK_TYPE_VIDEO)) {
                        clear()
                        onUnavailable()
                        return
                    }
                    ready = true
                    loading = false
                    publish()
                    sync()
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (candidate !== video) return
                    clear()
                    onUnavailable()
                }
            })
            candidate.prepare()
        }
    }

    fun clearIfSongChanged(currentSongId: String) {
        if (songId != null && songId != currentSongId) clear()
    }

    fun setVisible(value: Boolean) {
        visible = value
        sync(forceSeek = value)
    }

    fun sync(forceSeek: Boolean = false) {
        val candidate = video ?: return
        val audio = audioPlayer() ?: return
        if (!ready) return
        if (!visible) {
            candidate.playWhenReady = false
            return
        }
        val position = audio.currentPosition.coerceAtLeast(0)
        val now = SystemClock.elapsedRealtime()
        val steadyDrift = candidate.playbackState == Player.STATE_READY && candidate.isPlaying && audio.isPlaying &&
            abs(candidate.currentPosition - position) > 3_000 && now - lastSeekMs > 15_000
        if (forceSeek || steadyDrift) {
            candidate.seekTo(position)
            lastSeekMs = now
        }
        if (candidate.playbackParameters != audio.playbackParameters) candidate.playbackParameters = audio.playbackParameters
        candidate.playWhenReady = audio.effectivelyPlaying
    }

    fun setQuality(height: Int?): Boolean {
        if (!ready) return false
        val selectedId = videoId ?: return true
        val candidate = video ?: return true
        val selectedSongId = songId ?: return true
        val quality = height?.coerceAtLeast(144)
        if (quality == selectedQuality) return true
        qualityJob?.cancel()
        qualityJob = scope.launch {
            loading = true
            publish()
            val url = withContext(Dispatchers.IO) {
                runCatching { resolver.resolveVisualStream(selectedId, quality) }.getOrNull()
            }
            ensureActive()
            if (selectedSongId != songId || candidate !== video) return@launch
            loading = false
            publish()
            if (url == null) return@launch
            selectedQuality = quality
            publish()
            candidate.setMediaItem(MediaItem.fromUri(url), (audioPlayer()?.currentPosition ?: 0L).coerceAtLeast(0L))
            candidate.prepare()
            sync()
        }
        return true
    }

    fun clear() {
        searchJob?.cancel()
        searchJob = null
        qualityJob?.cancel()
        qualityJob = null
        video?.release()
        video = null
        songId = null
        videoId = null
        selectedQuality = 720
        lastSeekMs = 0L
        ready = false
        visible = false
        loading = false
        publish()
    }

    private fun publish() {
        onStateChanged(CompanionVideoStatus(ready, loading, selectedQuality.takeIf { ready }))
    }
}
