package com.aurora.music.data

import com.aurora.music.model.Song
import com.aurora.music.util.accentFor

// nullable-with-default per gson rule uuid always written
data class RadioStation(
    val uuid: String = "",
    val name: String? = "",
    val streamUrl: String? = "",
    val faviconUrl: String? = "",
    val tags: String? = "",
    val country: String? = "",
    val codec: String? = "",
    val bitrate: Int? = 0,
    val homepage: String? = "",
    val custom: Boolean? = false,
) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: "Radio station"
    val genre: String get() = tags?.split(",")?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
        ?: country?.takeIf { it.isNotBlank() }
        ?: "Internet radio"
}

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
