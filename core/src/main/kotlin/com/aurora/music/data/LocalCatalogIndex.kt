package com.aurora.music.data

import com.aurora.music.model.Song
import com.aurora.music.util.TrackMatch
import kotlin.math.abs

object LocalCatalogIndex {
    fun matchIndex(songs: List<Song>): Map<String, List<Song>> = songs.groupBy { TrackMatch.key(it.artist, it.title) }

    // only substitute on a single unambiguous match so a different version is never swapped in
    fun findMatch(index: Map<String, List<Song>>, artist: String, title: String, durationSec: Int): Song? {
        if (title.isBlank()) return null
        val candidates = index[TrackMatch.key(artist, title)] ?: return null
        val byDuration = candidates.filter { durationSec > 0 && it.durationSec > 0 && abs(it.durationSec - durationSec) <= TrackMatch.DURATION_TOLERANCE_SEC }
        if (byDuration.isNotEmpty()) return byDuration.minByOrNull { abs(it.durationSec - durationSec) }
        return candidates.singleOrNull()?.takeIf { durationSec <= 0 || it.durationSec <= 0 }
    }

    fun browse(path: String, root: String, songs: List<Song>, dirOf: Map<String, String>): Pair<List<String>, List<Song>> {
        val base = path.ifBlank { root }
        if (base.isBlank()) return emptyList<String>() to emptyList()
        val here = songs.filter { dirOf[it.id] == base }.sortedBy { it.title.lowercase() }
        val subdirs = dirOf.values.asSequence()
            .filter { it != base && it.startsWith("$base/") }
            .map { it.removePrefix("$base/").substringBefore('/') }
            .distinct().sortedBy { it.lowercase() }.toList()
        return subdirs to here
    }

    fun commonDir(dirs: Collection<String>): String {
        if (dirs.isEmpty()) return ""
        var prefix = dirs.first().split('/')
        for (d in dirs) {
            val seg = d.split('/')
            var i = 0
            while (i < prefix.size && i < seg.size && prefix[i] == seg[i]) i++
            prefix = prefix.subList(0, i)
        }
        return prefix.joinToString("/")
    }
}
