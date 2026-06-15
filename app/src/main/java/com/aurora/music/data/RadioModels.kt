package com.aurora.music.data

import com.aurora.music.model.Song
import com.aurora.music.util.accentFor

/**
 * An internet-radio station. Stations come either from the Radio-Browser directory (a community
 * Icecast/Shoutcast index, keyless) or are added by the user as a custom stream URL. Persisted in
 * [SettingsStore] (favorites + custom), so every field except the [uuid] id is nullable-with-default
 * per the project's Gson rule — Gson injects null into missing non-null Kotlin fields. [uuid] is always
 * written by every creation path; for a custom station it's synthesized from the stream URL.
 */
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

/**
 * Map a station to a playable [Song]. Live streams have no duration, so `durationSec = 0` — the
 * player already treats that as non-seekable (the seek bar collapses and shows a LIVE badge).
 * ReplayGain stays 0 so the DSP applies no gain.
 */
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

/** True for an Aurora radio [Song] (its media id is in the `radio:` namespace). */
fun Song.isRadio(): Boolean = id.startsWith("radio:")
