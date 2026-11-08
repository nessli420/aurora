package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material3.*
import com.aurora.music.ui.theme.TextButton
import com.aurora.music.ui.theme.OutlinedButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.R
import com.aurora.music.desktop.DesktopContainer
import com.aurora.music.localization.appPlural
import com.aurora.music.localization.appString
import com.aurora.music.data.cache.AudioCachePrefs
import kotlinx.coroutines.launch

@Composable
internal fun AudioCacheSettings(container: DesktopContainer) {
    val prefs by container.settingsStore.audioCachePrefs.collectAsStateWithLifecycle(AudioCachePrefs())
    val status by container.audioCache.status.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var choices by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    fun bytes(value: Long): String = if (value >= 1024L * 1024 * 1024)
        appString(R.string.text_2f_gb_56759f).format(value / (1024.0 * 1024 * 1024))
        else appString(R.string.text_1f_mb_ffb598).format(value / (1024.0 * 1024))

    SettingsSectionTitle(appString(R.string.cache_title))
    SettingsGroup {
        SettingsSwitchRow(Icons.Outlined.OfflinePin, appString(R.string.cache_title),
            appString(R.string.cache_description), prefs.enabled) {
            scope.launch { container.settingsStore.setAudioCacheEnabled(it) }
        }
        SettingsRowDivider()
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(when {
                status.failed -> appString(R.string.cache_unavailable)
                !status.ready -> appString(R.string.cache_loading)
                else -> appString(R.string.cache_used, bytes(status.bytes), bytes(prefs.limitBytes))
            }, style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { (status.bytes.toFloat() / prefs.limitBytes).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth())
            Text(appPlural(R.plurals.cache_ready_tracks, status.tracks),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box {
                OutlinedButton(onClick = { choices = true }) {
                    Text(appString(R.string.cache_limit) + ": " + bytes(prefs.limitBytes))
                }
                DropdownMenu(expanded = choices, onDismissRequest = { choices = false }) {
                    AudioCachePrefs.limitsMb.forEach { mb ->
                        DropdownMenuItem(text = { Text(bytes(mb * 1024L * 1024)) }, onClick = {
                            choices = false
                            scope.launch { container.settingsStore.setAudioCacheLimit(mb) }
                        })
                    }
                }
            }
            Text(appString(R.string.cache_policy), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(enabled = status.ready && status.bytes > 0 && !clearing, onClick = { confirmClear = true }) {
                Text(appString(if (clearing) R.string.cache_clearing else R.string.cache_clear))
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, containerColor = com.aurora.music.ui.theme.solidColors().surfaceContainerHigh,
        title = { Text(appString(R.string.cache_clear_title)) },
        text = { Text(appString(R.string.cache_clear_message)) },
        confirmButton = { TextButton(onClick = {
            confirmClear = false
            clearing = true
            scope.launch {
                val result = runCatching { container.audioCache.clear() }
                clearing = false
                message = appString(if (result.isSuccess) R.string.cache_cleared else R.string.cache_clear_failed)
            }
        }) { Text(appString(R.string.cache_clear)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(appString(R.string.text_cancel_77dfd2)) } })
}
