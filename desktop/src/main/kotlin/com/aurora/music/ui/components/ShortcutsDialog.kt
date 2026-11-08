package com.aurora.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.aurora.music.ui.theme.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aurora.music.R
import com.aurora.music.desktop.ui.Shortcut
import com.aurora.music.desktop.ui.ShortcutKeys
import com.aurora.music.localization.appString
import com.aurora.music.ui.theme.solidColors
import com.aurora.music.ui.theme.themedCorner

@Composable
fun ShortcutsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        containerColor = solidColors().surfaceContainerHigh,
        onDismissRequest = onDismiss,
        title = { Text(appString(R.string.shortcuts_title), fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(Modifier.widthIn(min = 360.dp).heightIn(max = 520.dp)) {
                items(ShortcutKeys.entries.toList()) { (shortcut, keys) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(shortcut.label(), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            keys.forEach { KeyCap(it) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(appString(R.string.text_close_bbfa77)) } },
    )
}

@Composable
private fun KeyCap(keys: String) {
    val accent = MaterialTheme.colorScheme.primary
    val shape = com.aurora.music.ui.theme.paperSquare(RoundedCornerShape(themedCorner(6.dp)))
    Text(
        keys,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = accent,
        modifier = Modifier.background(accent.copy(alpha = .14f), shape).border(1.dp, accent.copy(alpha = .35f), shape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

private fun Shortcut.label(): String = appString(
    when (this) {
        Shortcut.PLAY_PAUSE -> R.string.text_play_pause_14a1d0
        Shortcut.PREVIOUS -> R.string.text_previous_50f942
        Shortcut.NEXT -> R.string.text_next_bc9819
        Shortcut.SEEK_BACK -> R.string.shortcut_seek_back
        Shortcut.SEEK_FORWARD -> R.string.shortcut_seek_forward
        Shortcut.VOLUME_UP -> R.string.shortcut_volume_up
        Shortcut.VOLUME_DOWN -> R.string.shortcut_volume_down
        Shortcut.MUTE -> R.string.text_mute_0f0973
        Shortcut.SHUFFLE -> R.string.text_shuffle_5b772b
        Shortcut.REPEAT -> R.string.text_repeat_659eba
        Shortcut.LIKE -> R.string.text_like_c7e02c
        Shortcut.QUEUE -> R.string.text_view_queue_827a90
        Shortcut.SEARCH -> R.string.text_search_bce064
        Shortcut.SETTINGS -> R.string.text_settings_c7f73b
        Shortcut.FULLSCREEN -> R.string.player_fullscreen
        Shortcut.BACK -> R.string.text_back_b52b36
        Shortcut.SHORTCUTS -> R.string.shortcut_show_list
    }
)
