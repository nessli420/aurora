package com.aurora.music.util

object TrackMatch {
    // live cuts and extended mixes differ by more than this same recording rarely does
    const val DURATION_TOLERANCE_SEC = 4

    fun norm(s: String): String = s.lowercase()
        .replace(Regex("\\((feat|ft)\\.?[^)]*\\)"), " ")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    fun key(artist: String, title: String): String = norm(artist) + "|" + norm(title)
}
