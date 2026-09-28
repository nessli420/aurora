package com.aurora.music.data

import androidx.compose.ui.graphics.Color
import com.aurora.music.R
import com.aurora.music.localization.appString
import java.time.format.DateTimeFormatter

val DownloadRow.accent: Color get() = Color(accentArgb)

val SeparatorMatch.label: String get() = appString(when (this) {
    SeparatorMatch.OFF -> R.string.text_off_e3de5a
    SeparatorMatch.SPACED -> R.string.text_spaced_e2dd97
    SeparatorMatch.WORD -> R.string.text_whole_word_6171b6
    SeparatorMatch.ANYWHERE -> R.string.text_anywhere_cdf9bb
})

val RecapPeriod.label: String get() = appString(when (this) {
    RecapPeriod.DAY -> R.string.text_daily_728298
    RecapPeriod.WEEK -> R.string.text_weekly_158f3d
    RecapPeriod.MONTH -> R.string.text_monthly_d31edb
    RecapPeriod.YEAR -> R.string.text_yearly_7622eb
    RecapPeriod.ALL -> R.string.text_all_time_dbad49
})

val RecapWindow.label: String get() = when (period) {
    RecapPeriod.DAY -> start.format(DateTimeFormatter.ofPattern("d MMM yyyy"))
    RecapPeriod.WEEK -> "${start.format(DateTimeFormatter.ofPattern("d MMM"))} – ${end.minusDays(1).format(DateTimeFormatter.ofPattern("d MMM yyyy"))}"
    RecapPeriod.MONTH -> start.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
    RecapPeriod.YEAR -> start.year.toString()
    RecapPeriod.ALL -> appString(R.string.text_all_time_dbad49)
}
