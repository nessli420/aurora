package com.aurora.music.playback

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

data class NowPlaying(
    val title: String = "",
    val artist: String = "",
    val artUri: String = "",
    val isPlaying: Boolean = false,
    val hasTrack: Boolean = false,
)

object NowPlayingBus {
    val state = MutableStateFlow(NowPlaying())
}

// persists snapshot so widget/tile still render after the service process is killed
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
        fun read(context: Context): NowPlaying = NowPlayingStore(context).load()
    }
}
