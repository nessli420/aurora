package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.localization.appPlural
import com.aurora.music.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.desktop.ui.LocalDesktopContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun StorageSettingsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val container = LocalDesktopContainer.current
    val paths = container.paths
    val downloads by container.downloadManager.downloads.collectAsStateWithLifecycle()
    val offline by container.settingsStore.offlineMode.collectAsStateWithLifecycle(initialValue = false)
    val prefs by container.settingsStore.playbackPrefs.collectAsStateWithLifecycle(initialValue = com.aurora.music.data.PlaybackPrefs())
    val isLocal = container.isLocal
    val scope = rememberCoroutineScope()
    val bytes = remember(downloads) { container.downloadManager.totalBytes() }
    val rates = listOf(0, 128, 192, 256, 320)
    val rateLabels = listOf(appString(R.string.text_lossless_f3b36f), "128", "192", "256", "320")
    var measured by remember { mutableIntStateOf(0) }
    val sizes by produceState(emptyMap<File, Long>(), measured, downloads) {
        value = withContext(Dispatchers.IO) { listOf(paths.downloads, paths.cache, paths.logs, paths.roaming).associateWith(::folderBytes) }
    }
    fun open(dir: File) { scope.launch(Dispatchers.IO) { runCatching { java.awt.Desktop.getDesktop().open(dir.apply { mkdirs() }) } } }

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar(appString(R.string.text_downloads_storage_f7c580), onBack)
        SettingsScroll(contentPadding) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.DownloadDone, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(appPlural(R.plurals.storage_downloaded_tracks, downloads.size), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
                SettingsSwitchRow(Icons.Filled.CloudOff, appString(R.string.text_offline_mode_66cf31), appString(R.string.cache_offline_summary), offline) { v ->
                    scope.launch { container.settingsStore.setOfflineMode(v) }
                }
            }

            SettingsSectionTitle(appString(R.string.text_folders_19adc4))
            SettingsGroup {
                FolderRow(Icons.Filled.Download, appString(R.string.text_downloads_a862c2), paths.downloads, sizes[paths.downloads]) { open(paths.downloads) }
                SettingsRowDivider()
                FolderRow(Icons.Filled.Image, "Cache", paths.cache, sizes[paths.cache]) { open(paths.cache) }
                SettingsRowDivider()
                FolderRow(Icons.Filled.Description, "Logs", paths.logs, sizes[paths.logs]) { open(paths.logs) }
                SettingsRowDivider()
                FolderRow(Icons.Filled.Folder, appString(R.string.text_app_data_4d9bf9), paths.roaming, sizes[paths.roaming]) { open(paths.roaming) }
            }

            SettingsSectionTitle(appString(R.string.text_manage_bf58d1))
            ActionRow(Icons.Filled.Image, "Clear image cache", MaterialTheme.colorScheme.primary) {
                scope.launch {
                    container.imageLoader.memoryCache?.clear()
                    withContext(Dispatchers.IO) { runCatching { container.imageLoader.diskCache?.clear() } }
                    measured++
                }
            }
            ActionRow(Icons.Filled.DeleteSweep, appString(R.string.text_remove_all_downloads_7bd406), MaterialTheme.colorScheme.error, enabled = downloads.isNotEmpty()) {
                container.downloadManager.clearAll()
            }
        }
    }
}

@Composable
private fun FolderRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, dir: File, bytes: Long?, onOpen: () -> Unit) {
    SettingsNavRow(icon, title, subtitle = dir.absolutePath, value = bytes?.let(::formatBytes), onClick = onOpen)
}

@Composable
private fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, tint: androidx.compose.ui.graphics.Color, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint)
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, color = tint)
    }
}

private fun folderBytes(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return appString(R.string.text_0_mb_f921c9)
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) appString(R.string.text_2f_gb_56759f).format(mb / 1024.0) else appString(R.string.text_1f_mb_ffb598).format(mb)
}
