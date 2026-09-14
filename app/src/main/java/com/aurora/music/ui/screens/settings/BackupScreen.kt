package com.aurora.music.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val result = withContext(Dispatchers.IO) { runCatching {
                    requireNotNull(ctx.contentResolver.openOutputStream(uri)).use {
                        container.backupManager.exportArchive(System.currentTimeMillis(), it).getOrThrow()
                    }
                } }
                confirm(result.fold({ "Backup exported with processing presets and impulse responses" },
                    { it.message ?: "Export failed" }))
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
                confirm(result.getOrElse { it.message ?: "Couldn't read that backup" })
            } finally { busy = false }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar("Backup & restore", onBack)
        Column(Modifier.fillMaxWidth().padding(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {
            SettingsGroup {
                ActionRow(Icons.Filled.Backup, "Export backup", "Settings, racks, presets, impulse responses, playlists and history") {
                    if (!busy) exportLauncher.launch("aurora-backup.zip")
                }
                SettingsRowDivider()
                ActionRow(Icons.Filled.Restore, "Restore backup", "Overwrites current settings & playlists") {
                    if (!busy) importLauncher.launch(arrayOf("application/zip", "application/json", "application/octet-stream"))
                }
            }
            Text(
                if (busy) "Preparing and validating backup…" else "Backups include saved measurement-tuning projects and impulse responses used by current processing and saved presets. Downloaded music is not included. Restore replaces settings, on-device playlists, likes and history. Older JSON backups are accepted, but their impulse responses must be selected again.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
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
