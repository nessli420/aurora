package com.aurora.music.data

import com.aurora.music.model.Song
import com.aurora.music.util.TrackMatch
import kotlin.math.abs

object LocalCatalogIndex {
    fun matchIndex(songs: List<Song>): Map<String, List<Song>> = songs.groupBy { TrackMatch.key(it.artist, it.title) }

    // only substitute on a single unambiguous match so a different version is never swapped in
    fun findMatch(index: Map<String, List<Song>>, artist: String, title: String, durationSec: Int): Song? {
        if (title.isBlank() || index.isEmpty()) return null
        val candidates = index[TrackMatch.key(artist, title)] ?: return null
        val byDuration = candidates.filter { durationSec > 0 && it.durationSec > 0 && abs(it.durationSec - durationSec) <= TrackMatch.DURATION_TOLERANCE_SEC }
        if (byDuration.isNotEmpty()) return byDuration.minByOrNull { abs(it.durationSec - durationSec) }
        return candidates.singleOrNull()?.takeIf { durationSec <= 0 || it.durationSec <= 0 }
    }

    fun fileIndex(songs: List<Song>): Map<String, List<Song>> = songs.filter { it.path.isNotBlank() }.groupBy { segments(it.path).lastOrNull().orEmpty() }

    // a list entry may be absolute, relative or a file url, written on another device; the most folders in common wins
    fun findFile(index: Map<String, List<Song>>, location: String): Song? {
        val wanted = segments(location)
        val candidates = index[wanted.lastOrNull() ?: return null] ?: return null
        val scored = candidates.map { it to sharedTail(wanted, segments(it.path)) }
        val best = scored.maxOf { it.second }
        if (best < minOf(2, wanted.size)) return null
        return scored.filter { it.second == best }.singleOrNull()?.first
    }

    private fun segments(path: String): List<String> {
        val plain = if (path.startsWith("file:", true)) runCatching { java.net.URI(path).path }.getOrNull() ?: path.substringAfter(':') else path
        return plain.replace('\\', '/').lowercase(java.util.Locale.ROOT).split('/').filter { it.isNotBlank() && it != "." && it != ".." }
    }

    private fun sharedTail(a: List<String>, b: List<String>): Int {
        var n = 0
        while (n < a.size && n < b.size && a[a.size - 1 - n] == b[b.size - 1 - n]) n++
        return n
    }

    // tracks keep tag order per album, untagged ones fall back to filename order
    fun browse(path: String, root: String, songs: List<Song>, dirOf: Map<String, String>, positions: Map<String, Int>): Pair<List<String>, List<Song>> {
        val base = path.ifBlank { root }
        if (base.isBlank()) return emptyList<String>() to emptyList()
        val here = songs.filter { dirOf[it.id] == base }
            .sortedWith(compareBy({ it.album.lowercase() }, { positions[it.id] ?: 0 }, { it.path.lowercase() }))
        val prefix = "$base/"
        val subdirs = dirOf.values.asSequence()
            .filter { it != base && it.startsWith(prefix) }
            .map { it.removePrefix(prefix).substringBefore('/') }
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
