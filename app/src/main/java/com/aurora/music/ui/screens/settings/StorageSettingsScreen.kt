package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import kotlinx.coroutines.launch

@Composable
fun StorageSettingsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val container = LocalContextApp()
    val downloads by container.downloadManager.downloads.collectAsStateWithLifecycle()
    val offline by container.settingsStore.offlineMode.collectAsStateWithLifecycle(initialValue = false)
    val prefs by container.settingsStore.playbackPrefs.collectAsStateWithLifecycle(initialValue = com.aurora.music.data.PlaybackPrefs())
    val isLocal = container.isLocal
    val scope = rememberCoroutineScope()
    val bytes = remember(downloads) { container.downloadManager.totalBytes() }
    val rates = listOf(0, 128, 192, 256, 320)
    val rateLabels = listOf("Lossless", "128", "192", "256", "320")

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar("Downloads & storage", onBack)
        Column(Modifier.fillMaxWidth().padding(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.DownloadDone, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("${downloads.size} downloaded tracks", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(formatBytes(bytes) + " used", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (!isLocal) {
                SettingsSectionTitle("Download quality")
                SegmentedRow("Bitrate", rateLabels, rates.indexOf(prefs.downloadBitrate).coerceAtLeast(0)) { i ->
                    scope.launch { container.settingsStore.setDownloadBitrate(rates[i]) }
                }
            }

            SettingsSectionTitle("Offline")
            SettingsGroup {
                SettingsSwitchRow(Icons.Filled.CloudOff, "Offline mode", "Only show & play downloaded music", offline) { v ->
                    scope.launch { container.settingsStore.setOfflineMode(v) }
                }
            }

            // ReplayGain scan only meaningful for on-device files (servers ship their own gains).
            if (isLocal) {
                val rg by container.replayGainScanner.progress.collectAsStateWithLifecycle()
                SettingsSectionTitle("Volume leveling")
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable(enabled = !rg.running) { container.replayGainScanner.scan() }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (rg.running) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.width(22.dp).height(22.dp), strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Filled.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (rg.running) "Scanning ReplayGain…" else "Scan ReplayGain",
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium,
                        )
                        Text(
                            when {
                                rg.running -> "${rg.done} / ${rg.total} • ${rg.current}"
                                container.replayGainStore.size > 0 -> "${container.replayGainStore.size} tracks analysed — tap to rescan"
                                else -> "Measure loudness (EBU R128) to level playback volume"
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                    if (rg.running) {
                        Text("Cancel", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clip(RoundedCornerShape(50)).clickable { container.replayGainScanner.cancel() }.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                }
                Text(
                    "Applies when ReplayGain is set to Track or Album in Equalizer → Volume leveling.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }

            SettingsSectionTitle("Manage")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(enabled = downloads.isNotEmpty()) { container.downloadManager.clearAll() }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.DeleteSweep, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(12.dp))
                Text("Remove all downloads", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Downloads are stored privately inside the app and removed when you uninstall.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun LocalContextApp() =
    (LocalContext.current.applicationContext as AuroraApplication).container

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) "%.2f GB".format(mb / 1024.0) else "%.1f MB".format(mb)
}
