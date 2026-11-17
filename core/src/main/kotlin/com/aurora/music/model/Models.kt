package com.aurora.music.model

data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val artworkUrl: String,
    val durationSec: Int,
    val liked: Boolean = false,
    val explicit: Boolean = false,
    val accentArgb: Long = 0xFF28D572,
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

// a comment on the track; soundcloud pins it to a moment along the waveform, an untimed one has -1
data class TrackComment(val id: String, val body: String, val timestampMs: Long, val user: String, val avatarUrl: String,
    val likes: Int = 0, val age: String = "", val liked: Boolean = false)

data class Playlist(
    val id: String,
    val title: String,
    val subtitle: String,
    val coverUrl: String,
    val songCount: Int,
    val accentArgb: Long = 0xFF28D572,
)

data class DetailInfo(
    val title: String,
    val subtitle: String,
    val artUrl: String,
    val accentArgb: Long,
    val isArtist: Boolean,
    val songCount: Int,
    val typeLabel: String,
    val editableDescription: String? = null,
    val description: String = "",
    val followers: Long = 0,
    // the header adds a tracks, length and saves line instead of the backend spelling counts out in the subtitle
    val statsLine: Boolean = false,
    // false hides edit and delete on playlists the account follows but doesn't own
    val editable: Boolean = true,
)

enum class LibraryFilter { ALL, PLAYLISTS, ALBUMS, ARTISTS, SONGS, DOWNLOADED }

enum class LibrarySort { RECENT, ALPHABETICAL, CREATOR, MOST_PLAYED }

enum class LibraryLayout { LIST, GRID }
