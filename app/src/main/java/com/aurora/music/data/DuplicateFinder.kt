package com.aurora.music.data

import com.aurora.music.model.Song
import com.aurora.music.util.TrackMatch

data class DuplicateGroup(val title: String, val artist: String, val songs: List<Song>)

// duration clustering separates different recordings live/extended mixes while grouping format variants
object DuplicateFinder {

    private fun norm(s: String) = TrackMatch.norm(s)

    fun find(songs: List<Song>): List<DuplicateGroup> =
        songs.distinctBy { it.id }
            .groupBy { norm(it.artist) + "|" + norm(it.title) }
            .values
            .filter { it.size >= 2 && norm(it.first().title).isNotBlank() }
            .flatMap { clusterByDuration(it) }
            .filter { it.songs.size >= 2 }
            .sortedBy { it.title.lowercase() }

    private fun clusterByDuration(group: List<Song>): List<DuplicateGroup> {
        val clusters = ArrayList<MutableList<Song>>()
        for (s in group.sortedBy { it.durationSec }) {
            val cur = clusters.lastOrNull()
            if (cur != null && s.durationSec - cur.first().durationSec <= TrackMatch.DURATION_TOLERANCE_SEC) cur.add(s)
            else clusters.add(mutableListOf(s))
        }
        return clusters.map { DuplicateGroup(it.first().title, it.first().artist, it) }
    }
}
