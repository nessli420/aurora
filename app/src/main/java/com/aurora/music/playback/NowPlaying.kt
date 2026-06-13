package com.aurora.music.playback

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/** A lightweight now-playing snapshot shared with the home-screen widget + Quick Settings tile. */
data class NowPlaying(
    val title: String = "",
    val artist: String = "",
    val artUri: String = "",
    val isPlaying: Boolean = false,
    val hasTrack: Boolean = false,
)

/** In-memory now-playing state for live observers (widget/tile in the same process). */
object NowPlayingBus {
    val state = MutableStateFlow(NowPlaying())
}

/**
 * Persists the latest now-playing snapshot so the widget/tile can render something meaningful even
 * after the service process has been killed (cold read). Tiny synchronous SharedPreferences store.
 */
class NowPlayingStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("now_playing", Context.MODE_PRIVATE)

    fun save(np: NowPlaying) {
        prefs.edit()
            .putString("title", np.title)
            .putString("artist", np.artist)
            .putString("art", np.artUri)
            .putBoolean("playing", np.isPlaying)
            .putBoolean("has", np.hasTrack)
            .apply()
        NowPlayingBus.state.value = np
    }

    fun load(): NowPlaying = NowPlaying(
        title = prefs.getString("title", "").orEmpty(),
        artist = prefs.getString("artist", "").orEmpty(),
        artUri = prefs.getString("art", "").orEmpty(),
        isPlaying = prefs.getBoolean("playing", false),
        hasTrack = prefs.getBoolean("has", false),
    )

    companion object {
        /** Cold-read the persisted snapshot without holding a store instance. */
        fun read(context: Context): NowPlaying = NowPlayingStore(context).load()
    }
}
