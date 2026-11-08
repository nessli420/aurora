package com.aurora.music.data

import com.aurora.music.model.Song

/** The sort choices Spotify offers for a playlist or Liked Songs, plus duration from its desktop app. */
enum class TrackSort { CUSTOM, TITLE, ARTIST, ALBUM, RECENTLY_ADDED, DURATION }

/** A sort and its direction. Picking the active choice again flips it, as Spotify's column headers do. */
data class TrackSortOrder(val by: TrackSort = TrackSort.CUSTOM, val descending: Boolean = false) {
    val isDefault: Boolean get() = by == TrackSort.CUSTOM && !descending

    fun pick(choice: TrackSort): TrackSortOrder =
        if (choice == by) copy(descending = !descending)
        // newest first is what "recently added" means; the rest start A to Z or shortest first
        else TrackSortOrder(choice, descending = choice == TrackSort.RECENTLY_ADDED)

    /** Sorted copy; ties and unknown dates keep the collection's own order. */
    fun apply(songs: List<Song>): List<Song> {
        val sorted = when (by) {
            TrackSort.CUSTOM -> songs
            TrackSort.TITLE -> songs.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title.trim() })
            TrackSort.ARTIST -> songs.sortedWith(compareBy<Song, String>(String.CASE_INSENSITIVE_ORDER) { it.artist.trim() }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.album.trim() })
            TrackSort.ALBUM -> songs.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.album.trim() })
            TrackSort.RECENTLY_ADDED -> songs.sortedBy { it.dateAddedSec }
            TrackSort.DURATION -> songs.sortedBy { it.durationSec }
        }
        return if (descending) sorted.reversed() else sorted
    }
}

/** Per-collection sorts kept as one preference, so each playlist remembers its own like Spotify does. */
object TrackSorts {
    private const val LIMIT = 300

    fun decode(raw: String?): Map<String, TrackSortOrder> = raw.orEmpty().lineSequence().mapNotNull { line ->
        val parts = line.split('\t')
        val by = parts.getOrNull(1)?.let { name -> TrackSort.entries.firstOrNull { it.name == name } } ?: return@mapNotNull null
        parts[0].takeIf { it.isNotBlank() }?.let { it to TrackSortOrder(by, parts.getOrNull(2) == "desc") }
    }.toMap(LinkedHashMap())

    /** The newest choice goes last; the default drops out, and only the most recent [LIMIT] are kept. */
    fun encode(sorts: Map<String, TrackSortOrder>, collection: String, order: TrackSortOrder): String {
        val next = LinkedHashMap(sorts)
        next.remove(collection)
        if (!order.isDefault) next[collection] = order
        return next.entries.toList().takeLast(LIMIT).joinToString("\n") { (key, value) ->
            "${key.replace('\t', ' ').replace('\n', ' ')}\t${value.by.name}\t${if (value.descending) "desc" else "asc"}"
        }
    }
}
