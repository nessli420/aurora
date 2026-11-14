package com.aurora.music.data

import java.net.URI

/** Downloads are kept, and browsed offline, per kind of source; every server-style library shares one folder. */
enum class DownloadSource(val folder: String, val label: String) {
    SERVER("server", "Library"),
    YOUTUBE("youtube", "YouTube Music"),
    SPOTIFY("spotify", "Spotify"),
    SOUNDCLOUD("soundcloud", "SoundCloud"),
    APPLE_MUSIC("apple", "Apple Music");

    // matches the online library view ids, so a feed or view picked online still means the same offline
    val viewId: String get() = if (this == SERVER) "library" else folder

    companion object {
        fun forView(id: String?): DownloadSource? = if (id == "discovery") YOUTUBE else entries.firstOrNull { it.viewId == id }

        // the provider label starts with the account's type label, which survives merges where the server id is the primary account's
        fun of(providerLabel: String?, serverId: String?): DownloadSource {
            when (providerLabel.orEmpty().substringBefore(" · ")) {
                "YouTube Music" -> return YOUTUBE
                "Spotify" -> return SPOTIFY
                "SoundCloud" -> return SOUNDCLOUD
                "Apple Music" -> return APPLE_MUSIC
            }
            if (!providerLabel.isNullOrBlank()) return SERVER
            val host = runCatching { URI(serverId.orEmpty()).host }.getOrNull().orEmpty()
            return when {
                host == "youtube.com" || host.endsWith(".youtube.com") -> YOUTUBE
                host == "spotify.com" || host.endsWith(".spotify.com") -> SPOTIFY
                host == "soundcloud.com" || host.endsWith(".soundcloud.com") -> SOUNDCLOUD
                host == "music.apple.com" -> APPLE_MUSIC
                else -> SERVER
            }
        }

        fun of(download: DownloadedSong) = of(download.playbackSource?.providerLabel, download.serverId)
    }
}
