package com.aurora.music.data

import com.aurora.music.model.Song
import com.aurora.music.util.accentFor

// durationSec 0 marks non-seekable live stream
fun RadioStation.toSong(): Song = Song(
    id = "radio:$uuid",
    title = displayName,
    artist = genre,
    album = "Internet radio",
    artworkUrl = faviconUrl.orEmpty(),
    durationSec = 0,
    streamUrl = streamUrl.orEmpty(),
    accent = accentFor("radio:$uuid"),
)

fun Song.isRadio(): Boolean = id.startsWith("radio:")
