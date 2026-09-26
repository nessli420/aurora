package com.aurora.music.playback

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.aurora.music.data.MusicRepository
import com.aurora.music.data.PlaybackReportDispatcher
import com.aurora.music.data.PlaybackReportState
import com.aurora.music.data.PlaybackSourceIdentity
import com.aurora.music.model.Song

internal class PlaybackReportingController(
    repository: MusicRepository,
    dispatcher: PlaybackReportDispatcher,
    private val allowed: () -> Boolean,
    private val nativePlaying: (Player) -> Boolean,
) {
    private val tracker = PlaybackReportTracker(repository::playbackReportTarget, dispatcher::submit)
    private var player: Player? = null
    private var transition = false
    private var endingPosition: Long? = null
    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            transition = reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            endingPosition = oldPosition.positionMs
        }

        override fun onEvents(player: Player, events: Player.Events) {
            sample(events.contains(Player.EVENT_POSITION_DISCONTINUITY))
        }
    }

    fun observe(active: Player?) {
        if (active !== player) {
            player?.removeListener(listener)
            player = active
            active?.addListener(listener)
        }
        sample()
    }

    fun sample(discontinuity: Boolean = false) {
        val active = player
        val item = active?.currentMediaItem
        val snapshot = if (active == null || item == null) null else {
            val metadata = item.mediaMetadata
            val extras = metadata.extras
            val duration = active.duration.takeIf { it > 0 }
                ?: (extras?.getInt("aurora.durationSec") ?: 0).toLong() * 1_000
            val id = extras?.getString("aurora.songId")?.takeIf { it.isNotBlank() } ?: item.mediaId
            val provider = extras?.getString("aurora.rules.provider")
            val source = provider?.let { PlaybackSourceIdentity(providerId = it, songId = extras?.getString("aurora.playbackSongId")) }
            val song = Song(id, metadata.title?.toString().orEmpty(), metadata.artist?.toString().orEmpty(),
                metadata.albumTitle?.toString().orEmpty(), metadata.artworkUri?.toString().orEmpty(),
                (duration / 1_000).coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
                albumId = extras?.getString("aurora.albumId").orEmpty(),
                streamUrl = item.localConfiguration?.uri?.toString().orEmpty(), playbackSource = source)
            val state = when {
                active.playbackState == Player.STATE_IDLE || active.playbackState == Player.STATE_ENDED -> PlaybackReportState.STOPPED
                active.isPlaying || nativePlaying(active) -> PlaybackReportState.PLAYING
                active.playWhenReady && active.playbackState == Player.STATE_BUFFERING -> PlaybackReportState.BUFFERING
                else -> PlaybackReportState.PAUSED
            }
            PlaybackReportSnapshot(song, active.currentPosition, duration, state, active.playbackParameters.speed)
        }
        tracker.update(snapshot, SystemClock.elapsedRealtime(), System.currentTimeMillis(), allowed(),
            discontinuity, transition, endingPosition)
        transition = false
        endingPosition = null
    }

    fun close() {
        sample()
        tracker.finish(SystemClock.elapsedRealtime(), countListen = allowed())
        player?.removeListener(listener)
        player = null
    }
}
