package com.aurora.music.data

import com.aurora.music.model.Song
import com.aurora.music.util.accentArgbFor

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

const val PODCAST_FEED_PREFIX = "podcast-feed:"

fun PodcastEpisode.toSong(podcastImage: String = "", feedUrl: String = ""): Song = Song(
    id = "podcast:$id",
    title = title.ifBlank { "Episode" },
    artist = podcastTitle,
    album = podcastTitle,
    artworkUrl = imageUrl.ifBlank { podcastImage },
    durationSec = durationSec,
    streamUrl = audioUrl,
    accentArgb = accentArgbFor("podcast:$id"),
    playbackCollection = feedUrl.takeIf { it.isNotBlank() }?.let { PlaybackCollectionIdentity(PODCAST_FEED_PREFIX + it, podcastTitle) },
)

// the show an episode was played from, which its heart follows
fun Song.podcastFeed(): String? = playbackCollection?.id?.takeIf { isPodcast() && it.startsWith(PODCAST_FEED_PREFIX) }?.removePrefix(PODCAST_FEED_PREFIX)

fun Song.isPodcast(): Boolean = id.startsWith("podcast:")
