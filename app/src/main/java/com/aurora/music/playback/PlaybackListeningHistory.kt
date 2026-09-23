package com.aurora.music.playback

import android.os.SystemClock
import androidx.media3.common.Player
import com.aurora.music.data.DiscordRpc
import com.aurora.music.data.PlayHistoryStore
import com.aurora.music.model.Song
import java.time.LocalDate
import java.time.ZoneId

internal class PlaybackListeningHistory(
    private val playHistory: PlayHistoryStore,
    private val discord: DiscordRpc,
) {
    var allowed = false

    private var tick = 0L
    private var song: Song? = null
    private var position = 0L
    private var wasPlaying = false
    private var pendingMs = 0L
    private var day = LocalDate.now()

    fun track(active: Player?, nativeUsbPlaying: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val elapsed = (now - tick).coerceIn(0L, 2000L)
        tick = now
        active ?: return
        val item = active.currentMediaItem
        val id = item?.mediaId.orEmpty()
        val playing = active.isPlaying || nativeUsbPlaying
        val currentPosition = active.currentPosition
        val today = LocalDate.now()
        val newSession = id != song?.id || (currentPosition < position && currentPosition < 1500 && position > 5000) || today != day
        if (newSession || !playing || !allowed) flush()
        if (newSession || !allowed) playHistory.endListeningSession()
        val metadata = item?.mediaMetadata
        val currentSong = Song(id, metadata?.title?.toString().orEmpty(), metadata?.artist?.toString().orEmpty(),
            metadata?.albumTitle?.toString().orEmpty(), metadata?.artworkUri?.toString().orEmpty(),
            metadata?.extras?.getInt("aurora.durationSec")?.takeIf { it > 0 } ?: (active.duration.coerceAtLeast(0) / 1000).toInt(),
            albumId = metadata?.extras?.getString("aurora.albumId").orEmpty(),
            artistId = metadata?.extras?.getString("aurora.artistId").orEmpty())
        discord.update(currentSong, playing && id.isNotBlank(), currentPosition / 1000f)
        if (!newSession && wasPlaying && playing && allowed && id.isNotBlank() &&
            !id.startsWith("radio:") && !id.startsWith("podcast:") && !id.startsWith("aurora-mix:")) {
            pendingMs += elapsed
            if (pendingMs >= 5000) flush()
        }
        song = currentSong
        position = currentPosition
        wasPlaying = playing
        day = today
    }

    fun finish() {
        flush()
        song?.let { discord.update(it, false, 0f) }
        playHistory.endListeningSession()
    }

    private fun flush() {
        val previous = song
        if (previous != null && pendingMs > 0) {
            playHistory.recordListening(previous, pendingMs,
                if (day == LocalDate.now()) System.currentTimeMillis()
                else day.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1)
        }
        pendingMs = 0
    }
}
