package com.aurora.music.localization

import com.aurora.music.R
import com.aurora.music.foryou.DayPart
import com.aurora.music.foryou.GeneratedMix
import com.aurora.music.foryou.MixKind
import com.aurora.music.foryou.MixName
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

fun GeneratedMix.mixTitle(): String = when (kind) {
    MixKind.DAILY -> appString(R.string.foryou_daily, index)
    MixKind.RECOMMENDED -> appString(R.string.foryou_recommended)
    MixKind.DISCOVER -> appString(R.string.foryou_discover)
    MixKind.TIME -> appString(when (part) {
        DayPart.MORNING -> R.string.foryou_morning
        DayPart.AFTERNOON -> R.string.foryou_afternoon
        DayPart.NIGHT -> R.string.foryou_night
        else -> R.string.foryou_evening
    })
}

fun GeneratedMix.mixSubtitle(): String =
    if (artists.isEmpty()) appString(R.string.foryou_title) else appString(R.string.foryou_artists_more, artists.joinToString(", "))

// the saved copy carries its date, so copies of later editions sit side by side
fun GeneratedMix.mixName(): MixName =
    MixName(mixTitle(), mixSubtitle(), "${mixTitle()} · ${LocalDate.now().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))}")

fun GeneratedMix.asPlaylist() = com.aurora.music.model.Playlist(id, mixTitle(), mixSubtitle(), coverUrl, songs.size, com.aurora.music.util.accentArgbFor(slot))
