package com.aurora.music.data

import com.aurora.music.model.Song

/** Songs that look like the same recording (4.3 dedup pass). */
data class DuplicateGroup(val title: String, val artist: String, val songs: List<Song>)

/**
 * Fuzzy duplicate detection over a song list: tracks match when their normalized (artist, title)
 * agree and their durations sit within a few seconds of each other. Different recordings of the
 * same song (live cuts, extended mixes) usually differ in length, so duration clustering keeps
 * them apart while format/bitrate variants of one recording group together.
 */
object DuplicateFinder {

    private const val DURATION_TOLERANCE_SEC = 4

    fun find(songs: List<Song>): List<DuplicateGroup> =
        songs.distinctBy { it.id }
            .groupBy { norm(it.artist) + "|" + norm(it.title) }
            .values
            .filter { it.size >= 2 && norm(it.first().title).isNotBlank() }
            .flatMap { clusterByDuration(it) }
            .filter { it.songs.size >= 2 }
            .sortedBy { it.title.lowercase() }

    private fun norm(s: String) = s.lowercase()
        .replace(Regex("\\((feat|ft)\\.?[^)]*\\)"), " ")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    private fun clusterByDuration(group: List<Song>): List<DuplicateGroup> {
        val clusters = ArrayList<MutableList<Song>>()
        for (s in group.sortedBy { it.durationSec }) {
            val cur = clusters.lastOrNull()
            if (cur != null && s.durationSec - cur.first().durationSec <= DURATION_TOLERANCE_SEC) cur.add(s)
            else clusters.add(mutableListOf(s))
        }
        return clusters.map { DuplicateGroup(it.first().title, it.first().artist, it) }
    }
}
