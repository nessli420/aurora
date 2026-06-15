package com.aurora.music.data

import com.aurora.music.model.Song

object M3u {

    data class Entry(
        val title: String,
        val artist: String,
        val durationSec: Int,
        val location: String,
    )

    fun write(tracks: List<Song>): String = buildString {
        append("#EXTM3U\n")
        for (s in tracks) {
            append("#EXTINF:").append(s.durationSec).append(',')
            if (s.artist.isNotBlank()) append(s.artist).append(" - ")
            append(s.title).append('\n')
            val location = s.path.ifBlank {
                buildString {
                    if (s.artist.isNotBlank()) append(s.artist).append(" - ")
                    append(s.title)
                    append('.').append(s.suffix.ifBlank { "mp3" })
                }
            }
            append(location).append('\n')
        }
    }

    fun parse(text: String): List<Entry> {
        val out = ArrayList<Entry>()
        var pendingTitle = ""
        var pendingArtist = ""
        var pendingDur = 0
        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("﻿")
            when {
                line.isBlank() -> {}
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    // duration may be fractional or -1
                    val meta = line.substringAfter(':', "")
                    pendingDur = meta.substringBefore(',').trim().toFloatOrNull()?.toInt()?.coerceAtLeast(0) ?: 0
                    val display = meta.substringAfter(',', "").trim()
                    if (display.contains(" - ")) {
                        pendingArtist = display.substringBefore(" - ").trim()
                        pendingTitle = display.substringAfter(" - ").trim()
                    } else {
                        pendingArtist = ""
                        pendingTitle = display
                    }
                }
                line.startsWith("#") -> {}
                else -> {
                    val fileTitle = line.substringAfterLast('/').substringAfterLast('\\')
                        .substringBeforeLast('.').trim()
                    out += Entry(
                        title = pendingTitle.ifBlank { fileTitle },
                        artist = pendingArtist,
                        durationSec = pendingDur,
                        location = line,
                    )
                    pendingTitle = ""; pendingArtist = ""; pendingDur = 0
                }
            }
        }
        return out
    }
}
