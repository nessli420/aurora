package com.aurora.music.data

import com.aurora.music.model.Song

/**
 * M3U / M3U8 playlist interchange (4.6). Export writes extended M3U with the source file path when
 * the backend exposes one (so other players resolve tracks); import parses #EXTINF metadata and
 * falls back to the location's file name. Matching imported entries to library tracks happens in
 * [MusicRepository.importPlaylist] — this object is pure text in/out.
 */
object M3u {

    /** One imported playlist line-pair: whatever metadata the file gave us. */
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
                    // "#EXTINF:123,Artist - Title" (duration may be fractional or -1).
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
                line.startsWith("#") -> {}  // other directives (#PLAYLIST, #EXTGRP, …)
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
