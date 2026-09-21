package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

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
    val rateLabels = listOf(appString(R.string.text_lossless_f3b36f), "128", "192", "256", "320")

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar(appString(R.string.text_downloads_storage_f7c580), onBack)
        Column(Modifier.fillMaxWidth().padding(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.DownloadDone, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(appString(R.string.text_downloaded_tracks_98effd, (downloads.size)), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(formatBytes(bytes) + appString(R.string.text_used_4eb2a7), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (!isLocal) {
                SettingsSectionTitle(appString(R.string.text_download_quality_9d14a9))
                SegmentedRow(appString(R.string.text_bitrate_d153e7), rateLabels, rates.indexOf(prefs.downloadBitrate).coerceAtLeast(0)) { i ->
                    scope.launch { container.settingsStore.setDownloadBitrate(rates[i]) }
                }
            }

            SettingsSectionTitle(appString(R.string.text_offline_e01fa7))
            SettingsGroup {
                SettingsSwitchRow(Icons.Filled.CloudOff, appString(R.string.text_offline_mode_66cf31), appString(R.string.text_only_show_play_downloaded_music_c36019), offline) { v ->
                    scope.launch { container.settingsStore.setOfflineMode(v) }
                }
            }

            // ReplayGain scan only meaningful for on-device files (servers ship their own gains).
            if (isLocal) {
                val rg by container.replayGainScanner.progress.collectAsStateWithLifecycle()
                SettingsSectionTitle(appString(R.string.text_volume_leveling_a9df98))
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
                            if (rg.running) appString(R.string.text_scanning_replaygain_c527cc) else appString(R.string.text_scan_replaygain_d09e86),
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium,
                        )
                        Text(
                            when {
                                rg.running -> "${rg.done} / ${rg.total} • ${rg.current}"
                                container.replayGainStore.size > 0 -> appString(R.string.text_tracks_analysed_tap_to_rescan_7fa1f5, (container.replayGainStore.size))
                                else -> appString(R.string.text_measure_loudness_ebu_r128_to_level_playback_volume_30f01c)
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                    if (rg.running) {
                        Text(appString(R.string.text_cancel_77dfd2), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clip(RoundedCornerShape(50)).clickable { container.replayGainScanner.cancel() }.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                }
                Text(
                    appString(R.string.text_applies_when_replaygain_is_set_to_track_or_album_in_equalizer_vol_1cf41c),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }

            SettingsSectionTitle(appString(R.string.text_manage_bf58d1))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(enabled = downloads.isNotEmpty()) { container.downloadManager.clearAll() }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.DeleteSweep, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(12.dp))
                Text(appString(R.string.text_remove_all_downloads_7bd406), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                appString(R.string.text_downloads_are_stored_privately_inside_the_app_and_removed_when_yo_ffbbf6),
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
    if (bytes <= 0) return appString(R.string.text_0_mb_f921c9)
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) appString(R.string.text_2f_gb_56759f).format(mb / 1024.0) else appString(R.string.text_1f_mb_ffb598).format(mb)
}
