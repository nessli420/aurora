package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aurora.music.AuroraApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Portable backups include current/saved processing assets; legacy JSON import remains available. */
@Composable
fun BackupScreen(contentPadding: PaddingValues, onBack: () -> Unit, confirm: (String) -> Unit) {
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var includeSignIns by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val result = withContext(Dispatchers.IO) { runCatching {
                    requireNotNull(ctx.contentResolver.openOutputStream(uri)).use {
                        container.backupManager.exportArchive(System.currentTimeMillis(), it, includeSignIns).getOrThrow()
                    }
                } }
                confirm(result.fold({ appString(R.string.text_backup_exported_with_processing_presets_and_impulse_responses_fa67a8) },
                    { it.message ?: appString(R.string.text_export_failed_d6c17e) }))
            } finally { busy = false }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val result = withContext(Dispatchers.IO) { runCatching {
                    requireNotNull(ctx.contentResolver.openInputStream(uri)).use { container.backupManager.importArchive(it).getOrThrow() }
                } }
                confirm(result.getOrElse { it.message ?: appString(R.string.text_couldn_t_read_that_backup_db4478) })
            } finally { busy = false }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar(appString(R.string.text_backup_restore_a16162), onBack)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {
            SettingsGroup {
                ActionRow(Icons.Filled.Backup, appString(R.string.text_export_backup_6043b4), appString(R.string.text_settings_racks_presets_impulse_responses_playlists_and_history_22c4f0)) {
                    if (!busy) exportLauncher.launch("aurora-backup.zip")
                }
                SettingsRowDivider()
                SettingsSwitchRow(Icons.Filled.Lock, appString(R.string.backup_include_sign_ins), appString(R.string.backup_include_sign_ins_summary), includeSignIns) { includeSignIns = it }
                SettingsRowDivider()
                ActionRow(Icons.Filled.Restore, appString(R.string.text_restore_backup_a65eaa), appString(R.string.text_overwrites_current_settings_playlists_40eadc)) {
                    if (!busy) importLauncher.launch(arrayOf("application/zip", "application/json", "application/octet-stream"))
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
    RowScaffold(icon, title, subtitle, onClick) {}
}
