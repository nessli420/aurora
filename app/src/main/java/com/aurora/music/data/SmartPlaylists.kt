package com.aurora.music.data

import com.aurora.music.model.Song

// field/op/value are plain strings so they stay stable across renames and safe for persisted json
// gson rule every field nullable/defaulted
data class SmartRule(
    val field: String? = "title",
    val op: String? = "contains",
    val value: String? = "",
)

data class SmartPlaylist(
    val id: String? = "",
    val name: String? = "",
    val matchAll: Boolean? = true,        // true = AND false = OR
    val rules: List<SmartRule>? = emptyList(),
    val sortBy: String? = "title",
    val descending: Boolean? = false,
    val limit: Int? = 0,                  // 0 = no limit
)

class SmartPlaylistEngine(
    private val playHistory: PlayHistoryStore,
    private val downloadManager: DownloadManager,
) {

    fun evaluate(sp: SmartPlaylist, source: List<Song>): List<Song> {
        val events = playHistory.history.value
        val playCounts = events.groupingBy { it.songId }.eachCount()
        val lastPlayed = HashMap<String, Long>()
        for (e in events) lastPlayed[e.songId] = maxOf(lastPlayed[e.songId] ?: 0L, e.timestamp)
        val downloaded = downloadManager.downloads.value.keys
        val now = System.currentTimeMillis()

        val rules = sp.rules.orEmpty().filter { !it.field.isNullOrBlank() }
        val matchAll = sp.matchAll ?: true
        val filtered = source.filter { s ->
            when {
                rules.isEmpty() -> true
                matchAll -> rules.all { matches(it, s, playCounts, lastPlayed, downloaded, now) }
                else -> rules.any { matches(it, s, playCounts, lastPlayed, downloaded, now) }
            }
        }.distinctBy { it.id }

        val sorted = when (sp.sortBy ?: "title") {
            "artist" -> filtered.sortedBy { it.artist.lowercase() }
            "album" -> filtered.sortedBy { it.album.lowercase() }
            "duration" -> filtered.sortedBy { it.durationSec }
            "playCount" -> filtered.sortedBy { playCounts[it.id] ?: 0 }
            "lastPlayed" -> filtered.sortedBy { lastPlayed[it.id] ?: 0L }
            "random" -> filtered.shuffled()
            else -> filtered.sortedBy { it.title.lowercase() }
        }
        val ordered = if (sp.descending == true) sorted.reversed() else sorted
        val limit = sp.limit ?: 0
        return if (limit > 0) ordered.take(limit) else ordered
    }

    private fun matches(
        r: SmartRule,
        s: Song,
        playCounts: Map<String, Int>,
        lastPlayed: Map<String, Long>,
        downloaded: Set<String>,
        now: Long,
    ): Boolean = when (r.field) {
        "title", "artist", "album", "format" -> {
            val actual = when (r.field) {
                "title" -> s.title
                "artist" -> s.artist
                "album" -> s.album
                else -> s.suffix
            }
            val v = r.value.orEmpty()
            when (r.op) {
                "is" -> actual.equals(v, true)
                "isNot" -> !actual.equals(v, true)
                "startsWith" -> actual.startsWith(v, true)
                "notContains" -> !actual.contains(v, true)
                else -> actual.contains(v, true)
            }
        }
        "duration", "bitrate", "playCount", "lastPlayedDays" -> {
            val target = r.value.orEmpty().toLongOrNull()
            if (target == null) false else {
                val actual: Long? = when (r.field) {
                    "duration" -> s.durationSec.toLong()
                    "bitrate" -> s.bitrateKbps.toLong()
                    "playCount" -> (playCounts[s.id] ?: 0).toLong()
                    // never played only "more than N days ago" should match
                    else -> lastPlayed[s.id]?.let { (now - it) / DAY_MS }
                }
                when {
                    actual == null -> r.op == "gt"
                    r.op == "gt" -> actual > target
                    r.op == "lt" -> actual < target
                    else -> actual == target
                }
            }
        }
        "liked" -> if (r.op == "isFalse") !s.liked else s.liked
        "downloaded" -> if (r.op == "isFalse") s.id !in downloaded else s.id in downloaded
        else -> true
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
