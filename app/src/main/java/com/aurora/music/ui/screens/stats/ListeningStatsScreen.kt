package com.aurora.music.ui.screens.stats

import com.aurora.music.localization.appPlural

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import com.aurora.music.data.RecapWindow
import com.aurora.music.data.RecapPeriod
import com.aurora.music.data.ListeningRecaps
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.RankedItem
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.SectionHeader
import com.aurora.music.util.accentFor

@Composable
fun ListeningStatsScreen(contentPadding: PaddingValues, onBack: () -> Unit, onPlay: (String) -> Unit, onOpenDetail: (String, String) -> Unit,
    initialWindow: com.aurora.music.data.RecapWindow? = null) {
    val context = LocalContext.current
    val store = (context.applicationContext as AuroraApplication).container.playHistory
    val history by store.history.collectAsStateWithLifecycle()
    var window by remember(initialWindow) { mutableStateOf(initialWindow ?: RecapWindow.containing(RecapPeriod.DAY, java.time.LocalDate.now().minusDays(1))) }
    var byMinutes by remember { mutableStateOf(false) }
    val recap by produceState(ListeningRecaps.build(emptyList(), window), history, window) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { ListeningRecaps.build(history, window) } }
    val previous by produceState(ListeningRecaps.build(emptyList(), window.move(-1)), history, window) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { ListeningRecaps.build(history, window.move(-1)) } }
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(top = topInset + 6.dp, start = 8.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, appString(R.string.text_back_b52b36), modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onBack).padding(8.dp))
            Text(appString(R.string.text_your_listening_recap_2d4efe), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        LazyColumn(contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RecapPeriod.entries.forEach { period ->
                        FilterChip(selected = window.period == period, onClick = { window = RecapWindow.containing(period, if (window.period == RecapPeriod.ALL) java.time.LocalDate.now().minusDays(1) else window.start) }, label = { Text(period.label) })
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = window.period != RecapPeriod.ALL, onClick = { window = window.move(-1) }) { Text("‹", style = MaterialTheme.typography.headlineMedium) }
                    Column(Modifier.weight(1f).clickable(enabled = window.period != RecapPeriod.ALL) {
                        android.app.DatePickerDialog(context, { _, y, m, d -> window = RecapWindow.containing(window.period, java.time.LocalDate.of(y, m + 1, d)) }, window.start.year, window.start.monthValue - 1, window.start.dayOfMonth).apply {
                            datePicker.maxDate = System.currentTimeMillis(); show()
                        }
                    }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(window.label, fontWeight = FontWeight.Bold)
                        Text(if (window.period == RecapPeriod.ALL) appString(R.string.text_your_complete_history_990be7) else if (window.end.isAfter(java.time.LocalDate.now())) appString(R.string.text_in_progress_choose_date_0239bc) else appString(R.string.text_choose_date_e7877f), style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(enabled = window.end <= java.time.LocalDate.now(), onClick = { window = window.move(1) }) { Text("›", style = MaterialTheme.typography.headlineMedium) }
                }
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard("${recap.plays}", appString(R.string.text_plays_a6bc73), Modifier.weight(1f))
                    StatCard("${recap.minutes}", appString(R.string.text_minutes_092f99), Modifier.weight(1f))
                    StatCard("${recap.artists.size}", appString(R.string.text_artists_1528d8), Modifier.weight(1f))
                }
                Text(appString(R.string.text_plays_count_after_30_seconds_or_half_of_a_shorter_song_private_se_333c7b), Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (recap.estimated) Text(appString(R.string.text_older_plays_use_estimated_listening_time_from_track_lengths_768fa0), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (recap.millis == 0L) item { Text(appString(R.string.text_no_listening_recorded_in_this_period_choose_another_date_or_play_ed4ba2), Modifier.padding(24.dp)) }
            else {
                item { RecapInsights(recap, previous) }
                item {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatCard("${recap.songs.size}", appString(R.string.text_unique_songs_5af512), Modifier.weight(1f))
                        StatCard("${recap.albums.size}", appString(R.string.text_albums_4c45e7), Modifier.weight(1f))
                        StatCard("${recap.activeDays}", appString(R.string.text_active_days_340f3c), Modifier.weight(1f))
                    }
                    if (window.period != RecapPeriod.DAY) {
                        Text(appString(R.string.text_busiest_day_min_per_active_day_8cd6fd, (recap.busiestDay), (recap.minutes / recap.activeDays.coerceAtLeast(1))), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                    ListeningClock(recap.hourly.map { (it / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }.toIntArray())
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!byMinutes, { byMinutes = false }, label = { Text(appString(R.string.text_most_played_14202e)) })
                        FilterChip(byMinutes, { byMinutes = true }, label = { Text(appString(R.string.text_most_minutes_0d8b47)) })
                    }
                }
                listOf(appString(R.string.text_top_artists_4920f8) to recap.artists, appString(R.string.text_top_songs_ad7864) to recap.songs, appString(R.string.text_top_albums_4dfdc0) to recap.albums).forEachIndexed { kind, (title, ranks) ->
                    val sorted = if (byMinutes) ranks.sortedByDescending { it.millis } else ranks
                    item { SectionHeader(title, Modifier.padding(16.dp)) }
                    items(sorted.take(if (window.period == RecapPeriod.DAY) 5 else 20).size) { index ->
                        val r = sorted[index]
                        RankRow(index + 1, RankedItem(r.id, r.name, appString(R.string.text_min_plays_a8ae99, (r.millis / 60_000), (r.plays)) + if (kind == 0) "" else " · ${r.artist}", r.artwork, r.plays), kind == 0) {
                            if (r.id.isNotBlank()) { if (kind == 1) onPlay(r.id) else onOpenDetail(if (kind == 0) "artist" else "album", r.id) }
                        }
                    }
                }
                item { RecapSummary(recap) }
            }
        }
    }
}

@Composable
private fun StreakCard(current: Int, longest: Int) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.LocalFireDepartment, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(if (current > 0) appString(R.string.text_day_streak_661711, (current)) else appString(R.string.text_no_active_streak_7f1a27), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(appString(R.string.longest_streak, appPlural(R.plurals.day_count, (longest))), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ListeningClock(byHour: IntArray) {
    val max = (byHour.maxOrNull() ?: 0).coerceAtLeast(1)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(16.dp),
    ) {
        Text(appString(R.string.text_listening_clock_ec2eb4), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().height(80.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (h in 0 until 24) {
                val frac = (byHour[h].toFloat() / max).coerceIn(0.03f, 1f)
                Box(
                    Modifier.weight(1f).fillMaxHeight(frac).clip(RoundedCornerShape(3.dp))
                        .background(if (byHour[h] > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("12a", "6a", "12p", "6p", "11p").forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StatCard(value: String, label: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RankRow(rank: Int, item: RankedItem, circle: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$rank", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(28.dp))
        Artwork(item.artworkUrl, accentFor(item.id.ifBlank { item.name }), Modifier.size(48.dp), corner = if (circle) 48.dp else 10.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name.ifBlank { appString(R.string.text_unknown_bc7819) }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
            Text("${item.count}×", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
    }
}
