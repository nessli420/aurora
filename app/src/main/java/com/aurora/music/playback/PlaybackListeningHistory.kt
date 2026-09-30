package com.aurora.music.playback

import android.os.SystemClock
import androidx.media3.common.Player
import com.aurora.music.data.DiscordRpc
import com.aurora.music.data.ListeningAccumulator
import com.aurora.music.data.PlayHistoryStore
import com.aurora.music.model.Song

internal class PlaybackListeningHistory(
    playHistory: PlayHistoryStore,
    private val discord: DiscordRpc,
) {
    private val accumulator = ListeningAccumulator(playHistory)

    var allowed: Boolean
        get() = accumulator.allowed
        set(value) { accumulator.allowed = value }

    fun track(active: Player?, nativeUsbPlaying: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (active == null) {
            accumulator.track(null, false, 0L, now)
            return
        }
        val item = active.currentMediaItem
        val id = item?.mediaId.orEmpty()
        val playing = active.isPlaying || nativeUsbPlaying
        val currentPosition = active.currentPosition
        val metadata = item?.mediaMetadata
        val currentSong = Song(id, metadata?.title?.toString().orEmpty(), metadata?.artist?.toString().orEmpty(),
            metadata?.albumTitle?.toString().orEmpty(), metadata?.artworkUri?.toString().orEmpty(),
            metadata?.extras?.getInt("aurora.durationSec")?.takeIf { it > 0 } ?: (active.duration.coerceAtLeast(0) / 1000).toInt(),
            albumId = metadata?.extras?.getString("aurora.albumId").orEmpty(),
            artistId = metadata?.extras?.getString("aurora.artistId").orEmpty())
        accumulator.track(currentSong, playing, currentPosition, now)
        discord.update(currentSong, playing && id.isNotBlank(), currentPosition / 1000f)
    }

    fun finish() {
        val last = accumulator.song
        accumulator.finish()
        last?.let { discord.update(it, false, 0f) }
    }
}
