package com.aurora.music.util

/**
 * Shared fuzzy track-identity helpers used wherever the app decides two recordings are "the same"
 * without a strong key like ISRC: duplicate detection, M3U import matching, and cross-source
 * best-source resolution (7.1). Normalizes names and defines the duration tolerance for a match.
 */
object TrackMatch {
    /** Live cuts / extended mixes usually differ by more than this; same recording rarely does. */
    const val DURATION_TOLERANCE_SEC = 4

    /** Lowercase, drop "(feat …)/(ft …)", collapse runs of non-alphanumerics — a stable fuzzy token. */
    fun norm(s: String): String = s.lowercase()
        .replace(Regex("\\((feat|ft)\\.?[^)]*\\)"), " ")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    /** Combined `artist|title` key for grouping/looking up the same recording. */
    fun key(artist: String, title: String): String = norm(artist) + "|" + norm(title)
}
