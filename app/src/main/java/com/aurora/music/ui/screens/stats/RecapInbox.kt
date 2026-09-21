package com.aurora.music.ui.screens.stats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.*
import kotlinx.coroutines.delay
import java.time.LocalDate

@Composable
fun RecapInboxButton(onOpen: (RecapWindow) -> Unit) {
    val context = LocalContext.current
    val history by (context.applicationContext as AuroraApplication).container.playHistory.history.collectAsStateWithLifecycle()
    val prefs = remember { context.getSharedPreferences("recap_inbox", 0) }
    var seen by remember { mutableStateOf(prefs.getStringSet("seen", emptySet()).orEmpty().toSet()) }
    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) { while (true) { today = LocalDate.now(); delay(60_000) } }
    val recaps by produceState(emptyList<RecapWindow>(), history, today) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { ListeningRecaps.available(history, today) } }
    val unread = recaps.count { it.key !in seen }
    var open by remember { mutableStateOf(false) }
    Box {
        com.aurora.music.ui.screens.home.IconPill(Icons.Outlined.Notifications, if (unread > 0) "Alerts, $unread new recaps" else "Alerts") { open = true }
        if (unread > 0) Badge(Modifier.align(Alignment.TopEnd)) { Text(unread.coerceAtMost(99).toString()) }
    }
    if (open) AlertDialog(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 1f), onDismissRequest = { open = false }, title = { Text("Listening recaps") }, text = {
        if (recaps.isEmpty()) Text("Your first recap will be ready tomorrow after you listen today.")
        else LazyColumn(Modifier.heightIn(max = 440.dp)) {
            items(recaps, key = { it.key }) { recap ->
                Column(Modifier.fillMaxWidth().clickable {
                    seen = seen + recap.key; prefs.edit().putStringSet("seen", seen).apply()
                    open = false; onOpen(recap)
                }.padding(vertical = 12.dp)) {
                    Text("${recap.period.label} recap${if (recap.key !in seen) " · New" else ""}", style = MaterialTheme.typography.titleSmall)
                    Text(recap.label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { seen = recaps.map { it.key }.toSet(); prefs.edit().putStringSet("seen", seen).apply(); open = false }) { Text("Mark all read") } }, dismissButton = { TextButton(onClick = { open = false }) { Text("Close") } })
}
