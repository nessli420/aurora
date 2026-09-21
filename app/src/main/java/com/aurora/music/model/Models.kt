package com.aurora.music.model

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.ui.graphics.Color

data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val artworkUrl: String,
    val durationSec: Int,
    val liked: Boolean = false,
    val explicit: Boolean = false,
    val accent: Color = Color(0xFF28D572),
    val streamUrl: String = "",
    val albumId: String = "",
    val artistId: String = "",
    val suffix: String = "",
    val bitrateKbps: Int = 0,
    val sampleRateHz: Int = 0,
    val bitDepth: Int = 0,
    val replayGainTrack: Float = 0f,
    val replayGainAlbum: Float = 0f,
    val path: String = "",   // source file path when the backend exposes one (M3U export)
    val genre: String = "",
    val playCount: Int = 0,       // server-reported (subsonic child/jellyfin userdata); 0 if unsupported
    val dateAddedSec: Long = 0,   // epoch seconds the server added this file; 0 if unknown
    val playbackSource: com.aurora.music.data.PlaybackSourceIdentity? = null,
    val playbackCollection: com.aurora.music.data.PlaybackCollectionIdentity? = null,
)

data class Album(
    val id: String,
    val title: String,
    val artist: String,
    val artworkUrl: String,
    val year: Int,
    val songCount: Int,
    val durationSec: Int = 0,
    val releaseType: String = "",   // server-provided (opensubsonic/spotify) or "" = infer from size
    val playCount: Int = 0,
) {
    val typeLabel: String get() = releaseTypeLabel(releaseType.ifBlank { inferReleaseType(songCount, durationSec) })
}

// MusicBrainz-ish sizing for servers that don't tag release types
fun inferReleaseType(songCount: Int, durationSec: Int = 0): String = when {
    songCount <= 0 -> "album"
    songCount <= 2 && (durationSec == 0 || durationSec < 15 * 60) -> "single"
    songCount <= 6 && (durationSec == 0 || durationSec < 35 * 60) -> "ep"
    else -> "album"
}

fun releaseTypeLabel(type: String): String = when (type.trim().lowercase()) {
    "ep" -> "EP"
    "single" -> "Single"
    "compilation" -> "Compilation"
    "soundtrack" -> "Soundtrack"
    "live" -> "Live"
    else -> "Album"
}

data class Artist(
    val id: String,
    val name: String,
    val imageUrl: String,
    val monthlyListeners: Long,
)

data class Playlist(
    val id: String,
    val title: String,
    val subtitle: String,
    val coverUrl: String,
    val songCount: Int,
    val accent: Color = Color(0xFF28D572),
)

data class LyricLine(val timeSec: Int, val text: String)

data class DetailInfo(
    val title: String,
    val subtitle: String,
    val artUrl: String,
    val accent: Color,
    val isArtist: Boolean,
    val songCount: Int,
    val typeLabel: String,
)

enum class LibraryFilter(@androidx.annotation.StringRes private val labelRes: Int) {
    ALL(R.string.text_all_6a7208),
    PLAYLISTS(R.string.text_playlists_77b69f),
    ALBUMS(R.string.text_albums_4c45e7),
    ARTISTS(R.string.text_artists_1528d8),
    SONGS(R.string.text_songs_e1404b),
    DOWNLOADED(R.string.text_downloaded_c61970);
    val label: String get() = appString(labelRes)
}

enum class LibrarySort(@androidx.annotation.StringRes private val labelRes: Int) {
    RECENT(R.string.text_recently_added_536963),
    ALPHABETICAL(R.string.text_alphabetical_9d1260),
    CREATOR(R.string.text_creator_817b79),
    MOST_PLAYED(R.string.text_most_played_14202e);
    val label: String get() = appString(labelRes)
}

enum class LibraryLayout { LIST, GRID }
