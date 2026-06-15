package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.DEFAULT_SOURCE_PRIORITY
import com.aurora.music.data.MERGE_NONE
import com.aurora.music.data.ServerType
import com.aurora.music.data.accountKey
import kotlinx.coroutines.launch

private fun tierLabel(t: String) = when (t) {
    "local" -> "On-device file"
    "downloaded" -> "Downloaded"
    "stream" -> "Stream from server"
    else -> t
}
private fun tierSub(t: String) = when (t) {
    "local" -> "A matching file in your device's music library"
    "downloaded" -> "A track downloaded inside the app"
    "stream" -> "Stream from Navidrome / Jellyfin"
    else -> ""
}

@Composable
fun SourcesSettingsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val container = remember { (ctx.applicationContext as AuroraApplication).container }
    val store = container.settingsStore
    val scope = rememberCoroutineScope()

    val preferLocal by store.preferLocalSources.collectAsStateWithLifecycle(initialValue = true)
    val priority by store.sourcePriority.collectAsStateWithLifecycle(initialValue = DEFAULT_SOURCE_PRIORITY)
    val unified by store.unifiedLibrary.collectAsStateWithLifecycle(initialValue = false)
    val mergeKeys by store.mergeSources.collectAsStateWithLifecycle(initialValue = emptySet())
    val saved by store.savedSessions.collectAsStateWithLifecycle(initialValue = emptyList())

    val servers = remember(saved) {
        saved.filter { it.type == ServerType.SUBSONIC || it.type == ServerType.JELLYFIN }.distinctBy { it.accountKey() }
    }

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar("Library & sources", onBack)
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {

            item { SettingsSectionTitle("Best-source playback") }
            item {
                SettingsGroup {
                    SettingsSwitchRow(
                        Icons.Filled.Smartphone, "Prefer local copies",
                        "Play a matching on-device or downloaded file instead of streaming", preferLocal,
                    ) { v -> scope.launch { store.setPreferLocalSources(v) } }
                }
            }
            item {
                Text(
                    "When a track is available from more than one place, Aurora plays it from the first " +
                        "source below that has it. Reorder to taste.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
            }
            itemsIndexed(priority) { idx, tier ->
                PriorityRow(
                    label = tierLabel(tier),
                    subtitle = tierSub(tier),
                    enabled = preferLocal,
                    canUp = idx > 0,
                    canDown = idx < priority.lastIndex,
                    onUp = { scope.launch { store.setSourcePriority(priority.swapped(idx, idx - 1)) } },
                    onDown = { scope.launch { store.setSourcePriority(priority.swapped(idx, idx + 1)) } },
                )
            }

            item { Spacer(Modifier.height(8.dp)) }
            item { SettingsSectionTitle("Unified library") }
            item {
                SettingsGroup {
                    SettingsSwitchRow(
                        Icons.Filled.MergeType, "Merge all sources",
                        "Show albums, artists & playlists from your local files and every included server as one library", unified,
                    ) { v -> scope.launch { store.setUnifiedLibrary(v) } }
                }
            }
            if (unified) {
                item {
                    Text(
                        "Included in the unified library:",
                        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 4.dp),
                    )
                }
                item { SourceRow("On this device", "Local files", included = true, toggleable = false) {} }
                items(servers.size) { i ->
                    val s = servers[i]
                    val key = s.accountKey()
                    val noneSelected = mergeKeys == setOf(MERGE_NONE)
                    val included = !noneSelected && (mergeKeys.isEmpty() || key in mergeKeys)
                    SourceRow(s.typeLabel, s.server.removePrefix("http://").removePrefix("https://"), included = included, toggleable = true) {
                        val current = when {
                            noneSelected -> emptySet()
                            mergeKeys.isEmpty() -> servers.map { it.accountKey() }.toSet()   // empty means all make explicit
                            else -> mergeKeys
                        }
                        val next = if (key in current) current - key else current + key
                        // empty re-reads as all so persist none-sentinel for local only
                        scope.launch { store.setMergeSources(if (next.isEmpty()) setOf(MERGE_NONE) else next) }
                    }
                }
                if (servers.isEmpty()) {
                    item {
                        Text(
                            "Sign into Navidrome or Jellyfin (Settings → Accounts) to merge servers. Your local files are always included.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                }
                item {
                    Text(
                        "Duplicates are shown once and played from the highest-quality source. Spotify isn't merged yet.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

private fun List<String>.swapped(a: Int, b: Int): List<String> {
    if (a !in indices || b !in indices) return this
    return toMutableList().also { val t = it[a]; it[a] = it[b]; it[b] = t }
}

@Composable
private fun PriorityRow(label: String, subtitle: String, enabled: Boolean, canUp: Boolean, canDown: Boolean, onUp: () -> Unit, onDown: () -> Unit) {
    val alpha = if (enabled) 1f else 0.4f
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
        }
        Icon(
            Icons.Filled.KeyboardArrowUp, "Move up",
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled && canUp) 1f else 0.25f),
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(50)).clickable(enabled = enabled && canUp, onClick = onUp).padding(8.dp),
        )
        Icon(
            Icons.Filled.KeyboardArrowDown, "Move down",
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled && canDown) 1f else 0.25f),
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(50)).clickable(enabled = enabled && canDown, onClick = onDown).padding(8.dp),
        )
    }
}

@Composable
private fun SourceRow(title: String, subtitle: String, included: Boolean, toggleable: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Switch(checked = included, enabled = toggleable, onCheckedChange = { onToggle() })
    }
}
