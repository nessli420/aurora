package com.aurora.music.model

import androidx.compose.ui.graphics.Color
import com.aurora.music.R
import com.aurora.music.localization.appString

val Song.accent: Color get() = Color(accentArgb)
val Playlist.accent: Color get() = Color(accentArgb)
val DetailInfo.accent: Color get() = Color(accentArgb)

val LibraryFilter.label: String get() = appString(when (this) {
    LibraryFilter.ALL -> R.string.text_all_6a7208
    LibraryFilter.PLAYLISTS -> R.string.text_playlists_77b69f
    LibraryFilter.ALBUMS -> R.string.text_albums_4c45e7
    LibraryFilter.ARTISTS -> R.string.text_artists_1528d8
    LibraryFilter.SONGS -> R.string.text_songs_e1404b
    LibraryFilter.DOWNLOADED -> R.string.text_downloaded_c61970
})

val LibrarySort.label: String get() = appString(when (this) {
    LibrarySort.RECENT -> R.string.text_recently_added_536963
    LibrarySort.ALPHABETICAL -> R.string.text_alphabetical_9d1260
    LibrarySort.CREATOR -> R.string.text_creator_817b79
    LibrarySort.MOST_PLAYED -> R.string.text_most_played_14202e
})
