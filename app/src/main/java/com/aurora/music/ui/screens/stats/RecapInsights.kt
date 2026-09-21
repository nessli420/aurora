package com.aurora.music.ui.screens.stats

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aurora.music.data.*

@Composable
fun RecapInsights(recap: ListeningRecap, previous: ListeningRecap) {
    val artist = recap.artists.maxByOrNull { it.millis } ?: return
    Column(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp))
        .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.tertiaryContainer))).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(appString(R.string.recap_sound), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(artist.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(appString(R.string.text_minutes_of_your_listening_time_3f3db1, (artist.millis / 60_000), (artist.millis * 100 / recap.millis.coerceAtLeast(1))), color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
    if (recap.window.period != RecapPeriod.ALL && previous.window == recap.window.move(-1) && previous.millis > 0) {
        val difference = recap.minutes - previous.minutes
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(appString(R.string.text_total_listening_all_artists_a37e88), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(appString(R.string.text_min_5c8f84, (recap.minutes)), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(recap.window.label, style = MaterialTheme.typography.bodySmall)
                }
                Column(Modifier.weight(1f)) {
                    Text(appString(R.string.text_min_5c8f84, (previous.minutes)), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(previous.window.label, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(if (difference == 0L) appString(R.string.recap_equal)
                else appString(if (difference > 0) R.string.recap_more else R.string.recap_less, (kotlin.math.abs(difference))), style = MaterialTheme.typography.bodyMedium)
            if (recap.window.end.isAfter(java.time.LocalDate.now())) Text(appString(R.string.text_current_period_so_far_compared_with_the_full_previous_period_473f59), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (recap.window.period == RecapPeriod.MONTH || recap.window.period == RecapPeriod.YEAR || recap.window.period == RecapPeriod.WEEK) {
        val monthly = recap.window.period == RecapPeriod.YEAR
        val dates = if (monthly) (0L..11L).map { recap.window.start.plusMonths(it) }
            else (0 until java.time.temporal.ChronoUnit.DAYS.between(recap.window.start, recap.window.end)).map { recap.window.start.plusDays(it) }
        val values = dates.map { date -> if (monthly) recap.dailyMillis.filterKeys { it.month == date.month }.values.sum() else recap.dailyMillis[date] ?: 0 }
        val max = values.maxOrNull()?.coerceAtLeast(1) ?: 1
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(16.dp)) {
            Text(if (monthly) appString(R.string.text_your_year_month_by_month_e19b5f) else appString(R.string.text_your_listening_days_bf7f7d), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().height(90.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                values.forEach { ms -> Box(Modifier.weight(1f).fillMaxHeight((ms.toFloat() / max).coerceIn(.025f, 1f)).clip(RoundedCornerShape(3.dp)).background(if (ms > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (monthly) appString(R.string.text_jan_efed36) else dates.first().toString(), style = MaterialTheme.typography.labelSmall)
                Text(if (monthly) appString(R.string.text_dec_997f59) else dates.last().toString(), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
