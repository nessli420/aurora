package com.aurora.music.data

import com.aurora.music.model.Song
import com.aurora.music.util.accentFor

/**
 * A podcast show. Discovered via the Apple/iTunes podcast directory (keyless) and persisted in
 * [SettingsStore] when subscribed, so every field is nullable-with-default per the Gson rule.
 * [feedUrl] (the RSS URL) is the stable id.
 */
data class Podcast(
    val feedUrl: String = "",
    val title: String? = "",
    val author: String? = "",
    val imageUrl: String? = "",
    val description: String? = "",
) {
    val displayTitle: String get() = title?.takeIf { it.isNotBlank() } ?: "Podcast"
}

/**
 * One episode parsed from a show's RSS feed. Transient (re-fetched on open), not persisted — but kept
 * nullable-safe regardless. Unlike radio, episodes have a real [durationSec] so the seek bar works.
 */
data class PodcastEpisode(
    val id: String = "",
    val title: String = "",
    val audioUrl: String = "",
    val imageUrl: String = "",
    val durationSec: Int = 0,
    val pubDateMs: Long = 0,
    val description: String = "",
    val podcastTitle: String = "",
)

/** Map an episode to a playable [Song]. Falls back to the show artwork when the episode has none. */
fun PodcastEpisode.toSong(podcastImage: String = ""): Song = Song(
    id = "podcast:$id",
    title = title.ifBlank { "Episode" },
    artist = podcastTitle,
    album = podcastTitle,
    artworkUrl = imageUrl.ifBlank { podcastImage },
    durationSec = durationSec,
    streamUrl = audioUrl,
    accent = accentFor("podcast:$id"),
)

/** True for an Aurora podcast [Song]. */
fun Song.isPodcast(): Boolean = id.startsWith("podcast:")
