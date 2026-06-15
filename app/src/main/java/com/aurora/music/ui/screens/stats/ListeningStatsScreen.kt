package com.aurora.music.ui.screens.stats

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
fun ListeningStatsScreen(contentPadding: PaddingValues, onBack: () -> Unit, onPlay: (String) -> Unit, onOpenDetail: (String, String) -> Unit) {
    val store = (LocalContext.current.applicationContext as AuroraApplication).container.playHistory
    val history by store.history.collectAsStateWithLifecycle()
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var range by remember { mutableIntStateOf(0) } // 0 week 1 month 2 all

    val now = System.currentTimeMillis()
    val since = when (range) {
        0 -> now - 7L * 24 * 3600 * 1000
        1 -> now - 30L * 24 * 3600 * 1000
        else -> 0L
    }
    val events = remember(history, range) { history.filter { it.timestamp >= since } }
    val artists = remember(events) { store.topArtists(events) }
    val songs = remember(events) { store.topSongs(events) }
    val albums = remember(events) { store.topAlbums(events) }
    val minutes = remember(events) { events.sumOf { it.durationSec.toLong() } / 60 }
    val byHour = remember(events) { store.playsByHour(events) }
    val streak = remember(history) { store.streak() }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(top = topInset + 6.dp, start = 8.dp, end = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onBack).padding(8.dp))
            Spacer(Modifier.width(8.dp))
            Text("Listening stats", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("This week", "This month", "All time").forEachIndexed { i, label ->
                        val sel = i == range
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(50)).background(if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh).clickable { range = i }.padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface) }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard("${events.size}", "Plays", Modifier.weight(1f))
                    StatCard("$minutes", "Minutes", Modifier.weight(1f))
                    StatCard("${artists.size}", "Artists", Modifier.weight(1f))
                }
            }

            if (events.isEmpty()) {
                item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { Text("No plays in this period", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            }

            if (streak.second > 0) {
                item { StreakCard(current = streak.first, longest = streak.second) }
            }
            if (events.isNotEmpty()) {
                item { ListeningClock(byHour) }
            }

            if (artists.isNotEmpty()) {
                item { SectionHeader("Top artists", Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                items(artists.size) { i -> RankRow(i + 1, artists[i], circle = true) { if (artists[i].id.isNotBlank()) onOpenDetail("artist", artists[i].id) } }
            }
            if (songs.isNotEmpty()) {
                item { SectionHeader("Top songs", Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                items(songs.size) { i -> RankRow(i + 1, songs[i], circle = false) { onPlay(songs[i].id) } }
            }
            if (albums.isNotEmpty()) {
                item { SectionHeader("Top albums", Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                items(albums.size) { i -> RankRow(i + 1, albums[i], circle = false) { if (albums[i].id.isNotBlank()) onOpenDetail("album", albums[i].id) } }
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
            Text(if (current > 0) "$current-day streak" else "No active streak", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text("Longest: $longest day${if (longest == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Text("Listening clock", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
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
            Text(item.name.ifBlank { "Unknown" }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
            Text("${item.count}×", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
    }
}
