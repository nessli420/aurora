package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aurora.music.data.BackupManager
import com.aurora.music.desktop.ui.FilePickers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BackupScreen(contentPadding: PaddingValues, backupManager: BackupManager, onBack: () -> Unit, confirm: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    fun exportBackup() {
        val file = FilePickers.saveFile(appString(R.string.text_export_backup_6043b4), "aurora-backup.zip") ?: return
        scope.launch {
            busy = true
            try {
                val result = withContext(Dispatchers.IO) { runCatching {
                    file.outputStream().use { backupManager.exportArchive(System.currentTimeMillis(), it).getOrThrow() }
                } }
                confirm(result.fold({ appString(R.string.text_backup_exported_with_processing_presets_and_impulse_responses_fa67a8) },
                    { it.message ?: appString(R.string.text_export_failed_d6c17e) }))
            } finally { busy = false }
        }
    }
    fun restoreBackup() {
        val file = FilePickers.openFile(appString(R.string.text_restore_backup_a65eaa), listOf("zip", "json")) ?: return
        scope.launch {
            busy = true
            try {
                val result = withContext(Dispatchers.IO) { runCatching {
                    file.inputStream().use { backupManager.importArchive(it).getOrThrow() }
                } }
                confirm(result.getOrElse { it.message ?: appString(R.string.text_couldn_t_read_that_backup_db4478) })
            } finally { busy = false }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar(appString(R.string.text_backup_restore_a16162), onBack)
        SettingsScroll(contentPadding) {
            SettingsGroup {
                ActionRow(Icons.Filled.Backup, appString(R.string.text_export_backup_6043b4), appString(R.string.text_settings_racks_presets_impulse_responses_playlists_and_history_22c4f0)) {
                    if (!busy) exportBackup()
                }
                SettingsRowDivider()
                ActionRow(Icons.Filled.Restore, appString(R.string.text_restore_backup_a65eaa), appString(R.string.text_overwrites_current_settings_playlists_40eadc)) {
                    if (!busy) restoreBackup()
                }
            }
            Text(
                if (busy) appString(R.string.text_preparing_and_validating_backup_d53752) else appString(R.string.text_backups_include_saved_measurement_tuning_projects_and_impulse_res_96c97e),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
