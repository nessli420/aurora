package com.aurora.music.ui.widget

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.aurora.music.playback.NowPlaying
import com.aurora.music.playback.NowPlayingStore
import com.aurora.music.playback.PlaybackService

/** Quick Settings tile: toggles play/pause and mirrors the current playback state. */
class AuroraTileService : TileService() {

    override fun onStartListening() = render()

    override fun onClick() {
        val np = NowPlayingStore.read(this)
        runCatching {
            val intent = Intent(this, PlaybackService::class.java).setAction(PlaybackService.ACTION_PLAY_PAUSE)
            ContextCompat.startForegroundService(this, intent)
        }
        // Optimistic flip so the tile feels responsive; the next render reconciles with reality.
        render(playingOverride = !np.isPlaying)
    }

    private fun render(playingOverride: Boolean? = null) {
        val tile = qsTile ?: return
        val np = NowPlayingStore.read(this)
        val playing = playingOverride ?: np.isPlaying
        tile.state = if (playing) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (np.hasTrack && np.title.isNotBlank()) np.title else "Aurora"
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (np.hasTrack) (if (playing) "Playing" else "Paused") else "Aurora"
        runCatching {
            tile.icon = Icon.createWithResource(
                this, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            )
        }
        tile.updateTile()
    }
}
