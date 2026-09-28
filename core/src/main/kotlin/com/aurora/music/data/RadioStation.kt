package com.aurora.music.data

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
