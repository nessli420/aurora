package com.aurora.music.data

import com.aurora.music.model.Song
import com.aurora.music.util.accentFor

// nullable-with-default fields per gson rule feedUrl is the stable id
data class Podcast(
    val feedUrl: String = "",
    val title: String? = "",
    val author: String? = "",
    val imageUrl: String? = "",
    val description: String? = "",
) {
    val displayTitle: String get() = title?.takeIf { it.isNotBlank() } ?: "Podcast"
}

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

fun Song.isPodcast(): Boolean = id.startsWith("podcast:")
