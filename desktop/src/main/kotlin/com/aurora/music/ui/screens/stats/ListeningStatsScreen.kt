package com.aurora.music.ui.screens.stats

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.rememberDatePickerState
import com.aurora.music.data.RecapWindow
import com.aurora.music.data.RecapPeriod
import com.aurora.music.data.ListeningRecaps
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.desktop.ui.LocalDesktopContainer
import com.aurora.music.data.RankedItem
import com.aurora.music.ui.components.Artwork
import com.aurora.music.data.RecapRank
import com.aurora.music.ui.components.PageHeader
import com.aurora.music.ui.components.SectionHeader
import com.aurora.music.ui.layout.LocalPageGutter
import com.aurora.music.ui.layout.PageMetrics
import com.aurora.music.ui.layout.pagePadding
import com.aurora.music.util.accentFor
import com.aurora.music.data.label
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun ListeningStatsScreen(contentPadding: PaddingValues, onBack: () -> Unit, onPlay: (String) -> Unit, onOpenDetail: (String, String) -> Unit,
    initialWindow: com.aurora.music.data.RecapWindow? = null) {
    val store = LocalDesktopContainer.current.playHistory
    val history by store.history.collectAsStateWithLifecycle()
    var window by remember(initialWindow) { mutableStateOf(initialWindow ?: RecapWindow.containing(RecapPeriod.DAY, java.time.LocalDate.now().minusDays(1))) }
    var byMinutes by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val recap by produceState(ListeningRecaps.build(emptyList(), window), history, window) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { ListeningRecaps.build(history, window) } }
    val previous by produceState(ListeningRecaps.build(emptyList(), window.move(-1)), history, window) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { ListeningRecaps.build(history, window.move(-1)) } }
    val listState = rememberLazyListState()
    Column(Modifier.fillMaxWidth()) {
        PageHeader(appString(R.string.text_your_listening_recap_2d4efe), Modifier.padding(horizontal = LocalPageGutter.current), onBack = onBack)
        BoxWithConstraints {
            val wide = maxWidth - LocalPageGutter.current * 2 >= 1100.dp
            LazyColumn(state = listState, contentPadding = pagePadding(contentPadding)) {
                item {
                    val chips: @Composable (Modifier) -> Unit = { modifier ->
                        Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            RecapPeriod.entries.forEach { period ->
                                FilterChip(selected = window.period == period, onClick = { window = RecapWindow.containing(period, if (window.period == RecapPeriod.ALL) java.time.LocalDate.now().minusDays(1) else window.start) }, label = { Text(period.label) })
                            }
                        }
                    }
                    val navigator: @Composable (Modifier) -> Unit = { modifier ->
                        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
                            TextButton(enabled = window.period != RecapPeriod.ALL, onClick = { window = window.move(-1) }) { Text("‹", style = MaterialTheme.typography.headlineMedium) }
                            Column(Modifier.weight(1f).clickable(enabled = window.period != RecapPeriod.ALL) { picking = true }.pointerHoverIcon(PointerIcon.Hand), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(window.label, fontWeight = FontWeight.Bold)
                                Text(if (window.period == RecapPeriod.ALL) appString(R.string.text_your_complete_history_990be7) else if (window.end.isAfter(java.time.LocalDate.now())) appString(R.string.text_in_progress_choose_date_0239bc) else appString(R.string.text_choose_date_e7877f), style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(enabled = window.end <= java.time.LocalDate.now(), onClick = { window = window.move(1) }) { Text("›", style = MaterialTheme.typography.headlineMedium) }
                        }
                    }
                    if (wide) Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        chips(Modifier.weight(1f))
                        navigator(Modifier.width(420.dp))
                    } else {
                        chips(Modifier.fillMaxWidth().padding(vertical = 12.dp))
                        navigator(Modifier.fillMaxWidth())
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatCard("${recap.plays}", appString(R.string.text_plays_a6bc73), Modifier.weight(1f))
                        StatCard("${recap.minutes}", appString(R.string.text_minutes_092f99), Modifier.weight(1f))
                        StatCard("${recap.artists.size}", appString(R.string.text_artists_1528d8), Modifier.weight(1f))
                        if (wide && recap.millis != 0L) {
                            StatCard("${recap.songs.size}", appString(R.string.text_unique_songs_5af512), Modifier.weight(1f))
                            StatCard("${recap.albums.size}", appString(R.string.text_albums_4c45e7), Modifier.weight(1f))
                            StatCard("${recap.activeDays}", appString(R.string.text_active_days_340f3c), Modifier.weight(1f))
                        }
                    }
                    Text(appString(R.string.text_plays_count_after_30_seconds_or_half_of_a_shorter_song_private_se_333c7b), Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (recap.estimated) Text(appString(R.string.text_older_plays_use_estimated_listening_time_from_track_lengths_768fa0), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (recap.millis == 0L) item { Text(appString(R.string.text_no_listening_recorded_in_this_period_choose_another_date_or_play_ed4ba2), Modifier.padding(vertical = 24.dp)) }
                else {
                    item { RecapInsights(recap, previous, wide) }
                    item {
                        if (!wide) Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatCard("${recap.songs.size}", appString(R.string.text_unique_songs_5af512), Modifier.weight(1f))
                            StatCard("${recap.albums.size}", appString(R.string.text_albums_4c45e7), Modifier.weight(1f))
                            StatCard("${recap.activeDays}", appString(R.string.text_active_days_340f3c), Modifier.weight(1f))
                        }
                        if (window.period != RecapPeriod.DAY) {
                            Text(appString(R.string.text_busiest_day_min_per_active_day_8cd6fd, (recap.busiestDay), (recap.minutes / recap.activeDays.coerceAtLeast(1))),
                                Modifier.padding(top = if (wide) 8.dp else 0.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                        val hourly = recap.hourly.map { (it / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }.toIntArray()
                        if (wide) Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            ListeningClock(hourly, Modifier.weight(1f).fillMaxHeight())
                            if (recap.hasDailyChart()) RecapDays(recap, Modifier.weight(1f).fillMaxHeight())
                        } else ListeningClock(hourly, Modifier.fillMaxWidth().padding(vertical = 6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(!byMinutes, { byMinutes = false }, label = { Text(appString(R.string.text_most_played_14202e)) })
                            FilterChip(byMinutes, { byMinutes = true }, label = { Text(appString(R.string.text_most_minutes_0d8b47)) })
                        }
                    }
                    val rankings = listOf(appString(R.string.text_top_artists_4920f8) to recap.artists, appString(R.string.text_top_songs_ad7864) to recap.songs, appString(R.string.text_top_albums_4dfdc0) to recap.albums)
                        .map { (title, ranks) -> title to (if (byMinutes) ranks.sortedByDescending { it.millis } else ranks).take(if (window.period == RecapPeriod.DAY) 5 else 20) }
                    val rank: @Composable (Int, Int, RecapRank) -> Unit = { kind, index, r ->
                        RankRow(index + 1, RankedItem(r.id, r.name, appString(R.string.text_min_plays_a8ae99, (r.millis / 60_000), (r.plays)) + if (kind == 0) "" else " · ${r.artist}", r.artwork, r.plays), kind == 0) {
                            if (r.id.isNotBlank()) { if (kind == 1) onPlay(r.id) else onOpenDetail(if (kind == 0) "artist" else "album", r.id) }
                        }
                    }
                    if (wide) item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PageMetrics.SectionGap)) {
                            rankings.forEachIndexed { kind, (title, ranks) ->
                                Column(Modifier.weight(1f)) {
                                    SectionHeader(title, Modifier.padding(vertical = 16.dp))
                                    ranks.forEachIndexed { index, r -> rank(kind, index, r) }
                                }
                            }
                        }
                    } else rankings.forEachIndexed { kind, (title, ranks) ->
                        item { SectionHeader(title, Modifier.padding(vertical = 16.dp)) }
                        items(ranks.size) { index -> rank(kind, index, ranks[index]) }
                    }
                    item { RecapSummary(recap) }
                }
            }
        }
    }
    if (picking) RecapDatePicker(window.start, onDismiss = { picking = false }) { date ->
        picking = false
        window = RecapWindow.containing(window.period, date)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecapDatePicker(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), selectableDates = object : SelectableDates {
        override fun isSelectableDate(utcTimeMillis: Long) = !utcDate(utcTimeMillis).isAfter(LocalDate.now())
        override fun isSelectableYear(year: Int) = year <= LocalDate.now().year
    })
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(enabled = state.selectedDateMillis != null, onClick = { state.selectedDateMillis?.let { onPick(utcDate(it)) } }) {
                Text(appString(R.string.text_done_e9b450))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(appString(R.string.text_cancel_77dfd2)) } },
    ) { DatePicker(state) }
}

private fun utcDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

@Composable
private fun ListeningClock(byHour: IntArray, modifier: Modifier) {
    val max = (byHour.maxOrNull() ?: 0).coerceAtLeast(1)
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(16.dp),
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
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
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
