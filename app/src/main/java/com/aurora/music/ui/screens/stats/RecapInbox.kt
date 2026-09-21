package com.aurora.music.ui.screens.stats

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.*
import com.aurora.music.ui.components.Artwork
import com.aurora.music.util.accentFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate

private data class InboxState(val windows: List<RecapWindow>, val seen: Set<String>, val history: List<PlayEvent>, val loading: Boolean,
    val markRead: (Set<String>) -> Unit) {
    val unread get() = windows.count { it.key !in seen }
}

@Composable
private fun rememberInbox(): InboxState {
    val context = LocalContext.current
    val history by (context.applicationContext as AuroraApplication).container.playHistory.history.collectAsStateWithLifecycle()
    val prefs = remember { context.getSharedPreferences("recap_inbox", 0) }
    var seen by remember { mutableStateOf(prefs.getStringSet("seen", emptySet()).orEmpty().toSet()) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "seen") seen = prefs.getStringSet("seen", emptySet()).orEmpty().toSet()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        seen = prefs.getStringSet("seen", emptySet()).orEmpty().toSet()
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) { while (true) { today = LocalDate.now(); delay(60_000) } }
    val recaps by produceState<List<RecapWindow>?>(null, history, today) {
        value = withContext(Dispatchers.Default) { ListeningRecaps.available(history, today) }
    }
    return InboxState(recaps.orEmpty(), seen, history, recaps == null) { keys ->
        val updated = prefs.getStringSet("seen", emptySet()).orEmpty().toSet() + keys
        prefs.edit().putStringSet("seen", updated).apply()
        seen = updated
    }
}

@Composable
fun RecapInboxButton(onOpen: () -> Unit) {
    val inbox = rememberInbox()
    Box {
        com.aurora.music.ui.screens.home.IconPill(Icons.Outlined.Notifications,
            if (inbox.unread > 0) "Alerts, ${inbox.unread} new recaps" else "Alerts", onOpen)
        if (inbox.unread > 0) Badge(Modifier.align(Alignment.TopEnd)) { Text(inbox.unread.coerceAtMost(99).toString()) }
    }
}

@Composable
fun RecapInboxScreen(contentPadding: PaddingValues, onBack: () -> Unit, onOpen: (RecapWindow) -> Unit) {
    val inbox = rememberInbox()
    var filter by rememberSaveable { mutableStateOf("All") }
    val windows = inbox.windows.filter { filter == "All" || it.period.name == filter }
    val featured = windows.firstOrNull()
    val remaining = windows.drop(1)
    fun open(window: RecapWindow) { inbox.markRead(setOf(window.key)); onOpen(window) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Notifications", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (inbox.unread > 0) TextButton(onClick = { inbox.markRead(inbox.windows.map { it.key }.toSet()) }) { Text("Mark all read") }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = contentPadding.calculateBottomPadding() + 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Column(Modifier.padding(top = 14.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Your listening,\nrevisited.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
                    Text(if (inbox.unread > 0) "${inbox.unread} new ${if (inbox.unread == 1) "recap is" else "recaps are"} ready for you." else "The songs, artists and moments that made your days.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(filter == "All", { filter = "All" }, label = { Text("All") }) }
                    items(RecapPeriod.entries.filter { it != RecapPeriod.ALL }) { period ->
                        FilterChip(filter == period.name, { filter = period.name }, label = { Text(period.label) })
                    }
                }
            }
            if (inbox.loading) item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            else if (featured == null) item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.Outlined.Headphones, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("Good listening takes time", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(if (filter == "All") "Listen today and come back tomorrow for your first recap." else "Your ${filter.lowercase()} recap will appear here once the period ends and has some listening to look back on.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else {
                item(key = "featured:${featured.key}") { RecapNotification(featured, inbox.history, featured.key !in inbox.seen, true) { open(featured) } }
                val new = remaining.filter { it.key !in inbox.seen }
                val read = remaining.filter { it.key in inbox.seen }
                if (new.isNotEmpty()) {
                    item { Text("Ready to open", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                    items(new, key = { it.key }) { window -> RecapNotification(window, inbox.history, true, false) { open(window) } }
                }
                if (read.isNotEmpty()) {
                    item { Text("Your archive", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                    items(read, key = { it.key }) { window -> RecapNotification(window, inbox.history, false, false) { open(window) } }
                }
            }
        }
    }
}

@Composable
private fun RecapNotification(window: RecapWindow, history: List<PlayEvent>, unread: Boolean, featured: Boolean, onOpen: () -> Unit) {
    val recap by produceState<ListeningRecap?>(null, history, window) { value = withContext(Dispatchers.Default) { ListeningRecaps.build(history, window) } }
    val top = recap?.artists?.maxByOrNull { it.millis }
    val palette = MaterialTheme.colorScheme
    val brush = if (featured) Brush.linearGradient(listOf(palette.primaryContainer, palette.tertiaryContainer)) else Brush.linearGradient(listOf(palette.surfaceContainerHigh, palette.surfaceContainerHigh))
    val textColor = if (featured) palette.onPrimaryContainer else palette.onSurface
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(if (featured) 28.dp else 20.dp)).background(brush).clickable(onClick = onOpen).padding(if (featured) 22.dp else 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (featured) "LATEST · ${window.period.label.uppercase()} RECAP" else "${window.period.label} recap", style = MaterialTheme.typography.labelLarge, color = textColor, modifier = Modifier.weight(1f))
            if (unread) Box(Modifier.clip(CircleShape).background(palette.primary).padding(horizontal = 10.dp, vertical = 4.dp)) { Text("New", style = MaterialTheme.typography.labelSmall, color = palette.onPrimary) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Artwork(top?.artwork.orEmpty(), accentFor(top?.id.orEmpty()), Modifier.size(if (featured) 80.dp else 56.dp), corner = 16.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(window.label, style = if (featured) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = textColor)
                if (top != null) Text("Led by ${top.name}", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = textColor.copy(alpha = .8f))
            }
        }
        recap?.let { data ->
            Text("${data.minutes} min listened · ${data.plays} plays", style = if (featured) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium, fontWeight = if (featured) FontWeight.Bold else FontWeight.Normal, color = textColor)
        }
        if (featured) Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Open your recap", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = textColor)
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = textColor)
        }
    }
}
