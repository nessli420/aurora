package com.aurora.music.util

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

// subsonic/jellyfin both emit iso-8601 but jellyfin sometimes lacks a 'Z' suffix
fun parseIsoEpochSec(s: String?): Long {
    if (s.isNullOrBlank()) return 0L
    return runCatching { Instant.parse(s).epochSecond }
        .recoverCatching { OffsetDateTime.parse(s).toEpochSecond() }
        .getOrDefault(0L)
}

// spotify release_date precision varies: "YYYY", "YYYY-MM" or "YYYY-MM-DD"
fun parseReleaseDateEpochSec(s: String?): Long {
    if (s.isNullOrBlank()) return 0L
    val parts = s.split("-")
    val year = parts.getOrNull(0)?.toIntOrNull() ?: return 0L
    val month = (parts.getOrNull(1)?.toIntOrNull() ?: 1).coerceIn(1, 12)
    val day = (parts.getOrNull(2)?.toIntOrNull() ?: 1).coerceIn(1, 28)
    return runCatching { LocalDate.of(year, month, day).atStartOfDay(ZoneOffset.UTC).toEpochSecond() }.getOrDefault(0L)
}
