package com.aurora.music.localization

import com.aurora.music.R
import java.util.Locale

/** Backend media types remain stable; only their display labels change language. */
fun String.localizedMediaType(): String = when (lowercase(Locale.ROOT)) {
    "on this device" -> appString(R.string.text_on_this_device_a7f962)
    "extension" -> appString(R.string.text_extension_659087)
    "album" -> appString(R.string.text_album_dfb4c9)
    "single" -> appString(R.string.text_single_dd1186)
    "compilation" -> appString(R.string.text_compilation_aad755)
    "soundtrack" -> appString(R.string.text_soundtrack_5ea1bb)
    "live" -> appString(R.string.text_live_65c821)
    "playlist" -> appString(R.string.text_playlist_cd95b4)
    "smart playlist", "smart" -> appString(R.string.text_smart_playlist_f77ad7)
    "liked" -> appString(R.string.text_liked_songs_58c3a9)
    "artist" -> appString(R.string.text_artist_6c3f3d)
    "song", "track" -> appString(R.string.text_track_b1c5a7)
    else -> this
}
