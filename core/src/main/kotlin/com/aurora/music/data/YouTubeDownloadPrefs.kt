package com.aurora.music.data

enum class YouTubeDownloadFormat(val extension: String, val mimeType: String) {
    OPUS("opus", "audio/ogg"),
    M4A("m4a", "audio/mp4"),
}

data class YouTubeDownloadPrefs(
    val format: YouTubeDownloadFormat = YouTubeDownloadFormat.OPUS,
    val maxBitrateKbps: Int = 0,
    val anonymous: Boolean = true,
    val embedArtwork: Boolean = true,
) {
    companion object {
        val bitrates = listOf(0, 128, 64)
    }
}

data class YouTubeAudioOption(val url: String, val format: YouTubeDownloadFormat, val bitrateKbps: Int)

data class YouTubeTrackTags(val title: String, val artist: String, val album: String = "", val year: String = "")

object YouTubeDownloads {
    // youtube reports average bitrates a little above the nominal tier, e.g. 130 for 128
    private const val TIER_TOLERANCE = 1.15

    fun pick(options: List<YouTubeAudioOption>, prefs: YouTubeDownloadPrefs,
        supported: Set<YouTubeDownloadFormat> = YouTubeDownloadFormat.entries.toSet()): YouTubeAudioOption? {
        val usable = options.filter { it.format in supported && it.url.isNotBlank() }
        val pool = usable.filter { it.format == prefs.format }.ifEmpty { usable }
        if (pool.isEmpty()) return null
        val capped = if (prefs.maxBitrateKbps > 0) pool.filter { it.bitrateKbps <= prefs.maxBitrateKbps * TIER_TOLERANCE } else pool
        return capped.maxByOrNull { it.bitrateKbps } ?: pool.minByOrNull { it.bitrateKbps }
    }

    /** Reads the album and year from the description YouTube generates for label-provided tracks. */
    fun tags(title: String, uploader: String, description: String, uploadYear: Int?): YouTubeTrackTags {
        val artist = uploader.removeSuffix(" - Topic").trim()
        val lines = description.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.firstOrNull()?.startsWith("Provided to YouTube by") != true || lines.size < 3)
            return YouTubeTrackTags(title.trim(), artist, year = uploadYear?.toString().orEmpty())
        val credits = lines[1].split(" · ").map { it.trim() }.filter { it.isNotEmpty() }
        val released = lines.firstNotNullOfOrNull { Regex("^Released on: (\\d{4})-\\d{2}-\\d{2}$").find(it)?.groupValues?.get(1) }
        return YouTubeTrackTags(
            title = credits.firstOrNull() ?: title.trim(),
            artist = credits.drop(1).joinToString(", ").ifBlank { artist },
            album = lines[2],
            year = released ?: uploadYear?.toString().orEmpty(),
        )
    }

    fun fileName(tags: YouTubeTrackTags, format: YouTubeDownloadFormat): String {
        val stem = listOf(tags.artist, tags.title).filter { it.isNotBlank() }.joinToString(" - ")
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().trim('.')
            .take(120).trim().ifBlank { "YouTube audio" }
        return "$stem.${format.extension}"
    }
}
