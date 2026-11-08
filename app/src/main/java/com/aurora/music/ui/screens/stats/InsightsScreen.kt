package com.aurora.music.ui.screens.stats

import androidx.compose.foundation.Canvas
import com.aurora.music.ui.theme.paperClip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.aurora.music.ui.theme.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.aurora.music.ui.theme.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.R
import com.aurora.music.data.Badge
import com.aurora.music.data.DetailedPlay
import com.aurora.music.data.InsightBadge
import com.aurora.music.data.Insights
import com.aurora.music.data.InsightsPrefs
import com.aurora.music.data.InsightsReport
import com.aurora.music.data.PlaceZones
import com.aurora.music.data.RecapPeriod
import com.aurora.music.data.RecapWindow
import com.aurora.music.data.Share
import com.aurora.music.data.label
import com.aurora.music.localization.appString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

private data class HeatSelection(val days: IntRange, val hours: IntRange) {
    val single: Boolean get() = days.first == days.last && hours.first == hours.last
}

@Composable
fun InsightsScreen(contentPadding: PaddingValues, onBack: () -> Unit, onSetup: () -> Unit, initialWindow: RecapWindow? = null,
    onOpenMap: (() -> Unit)? = null) {
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    val prefs by container.settingsStore.insightsPrefs.collectAsStateWithLifecycle<InsightsPrefs?>(initialValue = null)
    var window by remember(initialWindow) { mutableStateOf(initialWindow ?: RecapWindow.containing(RecapPeriod.MONTH, LocalDate.now())) }
    var selection by remember(window) { mutableStateOf<HeatSelection?>(null) }
    val zone = remember { ZoneId.systemDefault() }
    val enabled = prefs?.enabled == true
    val data by produceState<Pair<List<DetailedPlay>, InsightsReport>?>(null, window, enabled) {
        value = null
        if (!enabled) return@produceState
        value = withContext(Dispatchers.Default) {
            val from = window.start.atStartOfDay(zone).toInstant().toEpochMilli()
            val to = window.end.atStartOfDay(zone).toInstant().toEpochMilli()
            val plays = container.listeningDetails.plays(from, to)
            plays to Insights.build(plays, container.listeningDetails.firstArtistPlays(), from, zone)
        }
    }
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(top = topInset + 6.dp, start = 8.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, appString(R.string.text_back_b52b36),
                modifier = Modifier.size(40.dp).paperClip(CircleShape).clickable(onClick = onBack).padding(8.dp))
            Text(appString(R.string.insights_screen_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        if (prefs == null) return@Column
        if (!enabled) {
            Column(Modifier.padding(24.dp)) {
                Text(appString(R.string.insights_off_body), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onSetup, modifier = Modifier.padding(top = 16.dp)) { Text(appString(R.string.insights_set_up)) }
            }
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {
            item { PeriodPicker(window) { window = it } }
            val current = data
            when {
                current == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
                current.second.empty -> item { Text(appString(R.string.insights_empty_period), Modifier.padding(24.dp)) }
                else -> {
                    val (plays, report) = current
                    item { Overview(report) }
                    if (report.badges.isNotEmpty()) item { Badges(report.badges) }
                    if (onOpenMap != null && prefs?.locationHistory == true) item {
                        com.aurora.music.ui.theme.OutlinedButton(onClick = onOpenMap, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                            Text(appString(R.string.insights_open_map))
                        }
                    }
                    item {
                        Section(appString(R.string.insights_when)) {
                            Heatmap(report.heatmap, selection) { selection = it }
                            Hint(appString(R.string.insights_heatmap_hint))
                            selection?.let { sel ->
                                val slice = remember(sel, plays) { Insights.slice(plays, zone) { d, h -> d in sel.days && h in sel.hours } }
                                Text(selectionLabel(sel), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Metric(appString(R.string.insights_listening_time), duration(slice.minutes))
                                Metric(appString(R.string.insights_plays), "${slice.plays}")
                                if (slice.averageSessionMinutes > 0) Metric(appString(R.string.insights_average_session), duration(slice.averageSessionMinutes.toLong()))
                                slice.topArtist?.let { Metric(appString(R.string.insights_top_artist), it) }
                                if (slice.genres.isNotEmpty()) Metric(appString(R.string.insights_genres), slice.genres.joinToString(" · ") { "${it.label} ${pct(it.fraction)}" })
                                if (slice.zones.isNotEmpty()) Metric(appString(R.string.insights_places), slice.zones.joinToString(" · ") { "${zoneName(it.label)} ${pct(it.fraction)}" })
                                if (slice.outputs.isNotEmpty()) Metric(appString(R.string.insights_outputs), slice.outputs.take(2).joinToString(" · ") { "${outputName(it.label)} ${pct(it.fraction)}" })
                                TextButton(onClick = { selection = null }) { Text(appString(R.string.insights_clear_selection)) }
                            }
                        }
                    }
                    if (report.zones.isNotEmpty()) item {
                        Section(appString(R.string.insights_places)) {
                            report.zones.forEach { z ->
                                Column(Modifier.padding(vertical = 6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(zoneName(z.zone), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                        Text("${duration(z.minutes)} · ${pct(z.fraction)}", style = MaterialTheme.typography.labelLarge)
                                    }
                                    Text(listOfNotNull(z.topGenre, z.topArtist, z.peakHour?.let { appString(R.string.insights_around, hour(it)) }).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    item {
                        Section(appString(R.string.insights_habits)) {
                            report.completion?.let { Metric(appString(R.string.insights_finished), pct(it)) }
                            report.skips?.let { Metric(appString(R.string.insights_skipped), pct(it)) }
                            report.shuffle?.let { Metric(appString(R.string.insights_shuffled), pct(it)) }
                            report.inOrder?.let { Metric(appString(R.string.insights_in_order), pct(it)) }
                            report.repeats?.let { Metric(appString(R.string.insights_repeats), pct(it)) }
                            report.transitions.firstOrNull()?.let { (a, b) -> Metric(appString(R.string.insights_transition), "$a → $b") }
                        }
                    }
                    if (report.outputs.isNotEmpty()) item { Section(appString(R.string.insights_outputs)) { Bars(report.outputs) { outputName(it) } } }
                    if (listOfNotNull(report.lossless, report.hiRes, report.autoEq, report.convolution, report.replayGain, report.bitPerfect).isNotEmpty() || report.formats.isNotEmpty()) item {
                        Section(appString(R.string.insights_audio_path)) {
                            report.lossless?.let { Metric(appString(R.string.insights_lossless), pct(it)) }
                            report.hiRes?.let { Metric(appString(R.string.insights_hi_res), pct(it)) }
                            report.autoEq?.let { Metric("AutoEQ", pct(it)) }
                            report.convolution?.let { Metric(appString(R.string.text_convolution_1c746e), pct(it)) }
                            report.crossfeed?.let { Metric(appString(R.string.text_crossfeed_e6b7b4), pct(it)) }
                            report.replayGain?.let { Metric("ReplayGain", pct(it)) }
                            report.bitPerfect?.let { Metric(appString(R.string.insights_bit_perfect), pct(it)) }
                            if (report.formats.isNotEmpty()) Bars(report.formats) { it }
                        }
                    }
                    if (report.genres.isNotEmpty()) item { Section(appString(R.string.insights_genres)) { Bars(report.genres) { it } } }
                }
            }
        }
    }
}

@Composable
internal fun PeriodPicker(window: RecapWindow, onChange: (RecapWindow) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        RecapPeriod.entries.forEach { period ->
            FilterChip(selected = window.period == period, onClick = { onChange(RecapWindow.containing(period, if (window.period == RecapPeriod.ALL) LocalDate.now() else window.start)) },
                label = { Text(period.label) })
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(enabled = window.period != RecapPeriod.ALL, onClick = { onChange(window.move(-1)) }) { Text("‹", style = MaterialTheme.typography.headlineMedium) }
        Text(window.label, Modifier.weight(1f), fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        TextButton(enabled = window.period != RecapPeriod.ALL && window.end <= LocalDate.now(), onClick = { onChange(window.move(1)) }) {
            Text("›", style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Composable
private fun Overview(report: InsightsReport) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Stat(duration(report.minutes), appString(R.string.insights_listening_time), Modifier.weight(1f))
        Stat("${report.plays}", appString(R.string.insights_plays), Modifier.weight(1f))
        Stat("${report.artists}", appString(R.string.insights_artists), Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Stat("${report.newArtists}", appString(R.string.insights_new_artists), Modifier.weight(1f))
        Stat("${report.sessions}", appString(R.string.insights_sessions), Modifier.weight(1f))
        Stat(duration(report.averageSessionMinutes.toLong()), appString(R.string.insights_average_session), Modifier.weight(1f))
    }
    Text(listOfNotNull(appString(R.string.insights_longest_session, duration(report.longestSessionMinutes.toLong())),
        report.peakHour?.let { appString(R.string.insights_peak_hour, hour(it)) }).joinToString(" · "),
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Badges(badges: List<Badge>) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        badges.forEach { badge ->
            val (title, fact) = badgeText(badge)
            Column(Modifier.width(200.dp).paperClip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(14.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(fact, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun Heatmap(heat: List<List<Long>>, selection: HeatSelection?, onSelect: (HeatSelection?) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.onSurface.copy(alpha = .06f)
    val outline = MaterialTheme.colorScheme.onSurface
    val max = heat.flatten().maxOrNull()?.coerceAtLeast(1L) ?: 1L
    val days = remember { DayOfWeek.entries.map { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) } }
    Row {
        Column(Modifier.padding(end = 6.dp)) {
            days.forEach { Box(Modifier.height(18.dp), contentAlignment = Alignment.Center) { Text(it, style = MaterialTheme.typography.labelSmall) } }
        }
        Column(Modifier.weight(1f)) {
            Canvas(Modifier.fillMaxWidth().height(18.dp * 7).pointerInput(selection) {
                detectTapGestures { p ->
                    val cell = (p.x / (size.width / 24f)).toInt().coerceIn(0, 23) to (p.y / (size.height / 7f)).toInt().coerceIn(0, 6)
                    val (hour, day) = cell
                    val current = selection
                    onSelect(if (current != null && current.single) HeatSelection(minOf(current.days.first, day)..maxOf(current.days.first, day),
                        minOf(current.hours.first, hour)..maxOf(current.hours.first, hour)) else HeatSelection(day..day, hour..hour))
                }
            }) {
                val w = size.width / 24f
                val h = size.height / 7f
                for (d in 0 until 7) for (hour in 0 until 24) {
                    val v = heat[d][hour]
                    val color = if (v == 0L) empty else accent.copy(alpha = (.15f + .85f * sqrt(v.toFloat() / max)).coerceAtMost(1f))
                    drawRoundRect(color, Offset(hour * w + 1, d * h + 1), Size(w - 2, h - 2), CornerRadius(3.dp.toPx()))
                }
                selection?.let { s ->
                    drawRoundRect(outline, Offset(s.hours.first * w, s.days.first * h),
                        Size((s.hours.last - s.hours.first + 1) * w, (s.days.last - s.days.first + 1) * h), CornerRadius(4.dp.toPx()), style = Stroke(2.dp.toPx()))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf(0, 6, 12, 18, 23).forEach { Text(hour(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).paperClip(RoundedCornerShape(18.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    Column(modifier.paperClip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(14.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Bars(shares: List<Share>, name: (String) -> String) {
    shares.take(6).forEach { share ->
        Column(Modifier.padding(vertical = 4.dp)) {
            Row {
                Text(name(share.label), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(pct(share.fraction), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
            LinearProgressIndicator(progress = { share.fraction }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(6.dp).paperClip(CircleShape))
        }
    }
}

@Composable
private fun Hint(text: String) = Text(text, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant)

private fun pct(value: Float) = "${(value * 100).roundToInt()}%"
private fun hour(h: Int) = "%02d:00".format(h)
private fun duration(minutes: Long) = if (minutes >= 120) appString(R.string.insights_hours, minutes / 60) else appString(R.string.insights_minutes, minutes)

private fun selectionLabel(s: HeatSelection): String {
    val names = DayOfWeek.entries.map { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
    val days = if (s.days.first == s.days.last) names[s.days.first] else "${names[s.days.first]}–${names[s.days.last]}"
    return "$days · ${hour(s.hours.first)}–${"%02d:59".format(s.hours.last)}"
}

private fun zoneName(zone: String) = when (zone) {
    PlaceZones.HOME -> appString(R.string.insights_zone_home)
    PlaceZones.WORK -> appString(R.string.insights_zone_work)
    PlaceZones.AWAY -> appString(R.string.insights_zone_away)
    else -> zone
}

private fun outputName(output: String) = when (output) {
    "SPEAKER", "EARPIECE" -> appString(R.string.insights_out_speaker)
    "HEADPHONES" -> appString(R.string.insights_out_wired)
    "BLUETOOTH" -> "Bluetooth"
    "USB" -> "USB"
    "HDMI" -> "HDMI"
    "CAST" -> "Cast"
    else -> appString(R.string.insights_out_other)
}

private fun badgeText(badge: Badge): Pair<String, String> {
    val value = if (badge.kind == InsightBadge.EXPLORER) "${badge.value}" else "${badge.value}%"
    return when (badge.kind) {
        InsightBadge.NIGHT_LISTENER -> appString(R.string.insights_badge_night) to appString(R.string.insights_badge_night_fact, value)
        InsightBadge.MORNING_LISTENER -> appString(R.string.insights_badge_morning) to appString(R.string.insights_badge_morning_fact, value)
        InsightBadge.IN_ORDER_LISTENER -> appString(R.string.insights_badge_in_order) to appString(R.string.insights_badge_in_order_fact, value)
        InsightBadge.SHUFFLER -> appString(R.string.insights_badge_shuffle) to appString(R.string.insights_badge_shuffle_fact, value)
        InsightBadge.EXPLORER -> appString(R.string.insights_badge_explorer) to appString(R.string.insights_badge_explorer_fact, value)
        InsightBadge.REPEAT_HEAVY -> appString(R.string.insights_badge_repeat) to appString(R.string.insights_badge_repeat_fact, value)
        InsightBadge.LOSSLESS -> appString(R.string.insights_badge_lossless) to appString(R.string.insights_badge_lossless_fact, value)
        InsightBadge.COMPLETIONIST -> appString(R.string.insights_badge_finisher) to appString(R.string.insights_badge_finisher_fact, value)
    }
}
