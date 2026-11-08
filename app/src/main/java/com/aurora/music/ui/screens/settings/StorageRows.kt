package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.background
import com.aurora.music.ui.theme.paperClip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.aurora.music.ui.theme.OutlinedButton
import androidx.compose.material3.Text
import com.aurora.music.ui.theme.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aurora.music.R
import com.aurora.music.data.StorageUsage
import com.aurora.music.localization.appString

internal class StorageConfirm(val title: String, val message: String, val confirm: String, val action: () -> Unit)

internal fun storageSize(bytes: Long): String {
    val value = bytes.coerceAtLeast(0)
    return when {
        value >= GIB -> appString(R.string.text_2f_gb_56759f).format(value / GIB.toDouble())
        value >= MIB -> appString(R.string.text_1f_mb_ffb598).format(value / MIB.toDouble())
        else -> appString(R.string.storage_size_kb, ((value + 1023) / 1024).toInt())
    }
}

private const val MIB = 1024L * 1024
private const val GIB = MIB * 1024

internal fun storageDetail(bytes: Long, vararg parts: String): String =
    (listOfNotNull(storageSize(bytes).takeIf { bytes > 0 }) + parts.filter { it.isNotBlank() }).joinToString(" · ")
        .ifBlank { appString(R.string.storage_empty) }

@Composable
internal fun StorageRow(
    icon: ImageVector,
    title: String,
    detail: String?,
    action: String?,
    enabled: Boolean = true,
    working: Boolean = false,
    destructive: Boolean = false,
    onAction: () -> Unit = {},
) {
    RowScaffold(icon, title, detail, onClick = null) {
        when {
            working -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            action != null -> TextButton(onClick = onAction, enabled = enabled) {
                Text(action, color = if (destructive && enabled) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
        }
    }
}

@Composable
internal fun StorageOverview(usage: StorageUsage, clearing: Boolean, onClearCaches: () -> Unit) {
    val parts = listOf(
        Triple(appString(R.string.storage_part_downloads), usage.downloads, MaterialTheme.colorScheme.primary),
        Triple(appString(R.string.storage_part_caches), usage.caches, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
        Triple(appString(R.string.storage_part_analysis), usage.analysis, MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)),
        Triple(appString(R.string.storage_part_other), usage.other, MaterialTheme.colorScheme.outline),
    )
    SettingsGroup {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (usage.ready) appString(R.string.storage_used, storageSize(usage.total)) else appString(R.string.storage_measuring),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            )
            Row(Modifier.fillMaxWidth().height(10.dp).paperClip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                if (usage.ready) parts.filter { it.second > 0 }.forEach { (_, bytes, color) ->
                    Box(Modifier.weight(bytes.toFloat()).fillMaxHeight().background(color))
                }
            }
            parts.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth()) {
                    pair.forEach { (label, bytes, color) ->
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).paperClip(CircleShape).background(color))
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(if (usage.ready) storageSize(bytes) else "–", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
            if (usage.ready) Text(appString(R.string.storage_free, storageSize(usage.free)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onClearCaches, enabled = usage.ready && !clearing && usage.caches > 0) {
                if (clearing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Filled.CleaningServices, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(appString(R.string.storage_clear_caches))
            }
        }
    }
}

@Composable
internal fun StorageConfirmDialog(request: StorageConfirm?, onDismiss: () -> Unit) {
    if (request == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(request.title) },
        text = { Text(request.message) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); request.action() }) { Text(request.confirm, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(appString(R.string.text_cancel_77dfd2)) } },
    )
}
