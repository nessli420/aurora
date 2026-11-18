package com.aurora.music.ui.screens.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aurora.music.R
import com.aurora.music.localization.appString
import com.aurora.music.data.PlaylistFolder
import com.aurora.music.ui.theme.Motion
import com.aurora.music.ui.theme.TextButton
import com.aurora.music.ui.theme.paperClip
import com.aurora.music.ui.theme.pressClickable
import kotlinx.coroutines.launch

// what a folder menu asks for; the library shows the matching dialog
internal sealed interface FolderPrompt {
    data class Create(val parentId: String) : FolderPrompt
    data class Rename(val id: String, val name: String) : FolderPrompt
    data class Delete(val id: String, val name: String) : FolderPrompt
    data class Move(val playlistId: String, val from: String) : FolderPrompt
}

class FolderEdits(
    val create: suspend (name: String, parentId: String) -> Boolean,
    val rename: suspend (id: String, name: String) -> Boolean,
    val delete: suspend (id: String) -> Boolean,
    val move: suspend (playlistId: String, folderId: String) -> Boolean,
)

@Composable
internal fun FolderPromptDialog(prompt: FolderPrompt, tree: PlaylistFolder?, edits: FolderEdits, onDismiss: () -> Unit) {
    when (prompt) {
        is FolderPrompt.Create -> FolderNameDialog(appString(R.string.playlist_folder_new), "", appString(R.string.text_create_6e157c), onDismiss) { edits.create(it, prompt.parentId) }
        is FolderPrompt.Rename -> FolderNameDialog(appString(R.string.playlist_folder_rename), prompt.name, appString(R.string.text_save_efc007), onDismiss) { edits.rename(prompt.id, it) }
        is FolderPrompt.Delete -> FolderEditDialog(
            appString(R.string.playlist_folder_delete), appString(R.string.text_delete_f6fdbe), onDismiss,
            body = { Text(appString(R.string.playlist_folder_delete_message, folderName(prompt.name))) },
        ) { edits.delete(prompt.id) }
        is FolderPrompt.Move -> MoveToFolderDialog(tree ?: return, prompt.from, onDismiss) { edits.move(prompt.playlistId, it) }
    }
}

@Composable
internal fun folderName(name: String): String = name.ifBlank { appString(R.string.playlist_folder_untitled) }

@Composable
private fun FolderNameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onSave: suspend (String) -> Boolean) {
    var name by remember { mutableStateOf(initial) }
    FolderEditDialog(title, confirm, onDismiss, ready = name.isNotBlank() && name.trim() != initial.trim(), body = { saving ->
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(appString(R.string.playlist_folder_name)) },
            singleLine = true, enabled = !saving, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
    }) { onSave(name.trim()) }
}

// one write at a time; a failure stays open with the error so nothing is silently lost
@Composable
private fun FolderEditDialog(
    title: String,
    confirm: String,
    onDismiss: () -> Unit,
    ready: Boolean = true,
    body: @Composable (saving: Boolean) -> Unit,
    onConfirm: suspend () -> Boolean,
) {
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                body(saving)
                AnimatedVisibility(failed, enter = fadeIn(Motion.enter()) + expandVertically(Motion.enter()), exit = fadeOut(Motion.exit()) + shrinkVertically(Motion.exit())) {
                    Text(appString(R.string.playlist_folder_failed), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                saving = true
                failed = false
                scope.launch { try { if (onConfirm()) onDismiss() else failed = true } finally { saving = false } }
            }, enabled = ready && !saving) { Text(if (saving) appString(R.string.playlist_saving) else confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text(appString(R.string.text_cancel_77dfd2)) } },
    )
}

@Composable
private fun MoveToFolderDialog(tree: PlaylistFolder, from: String, onDismiss: () -> Unit, onMove: suspend (String) -> Boolean) {
    var picked by remember { mutableStateOf(from) }
    val choices = remember(tree) { listOf(Triple("", "", -1)) + tree.allFolders().map { (f, depth) -> Triple(f.id, f.name, depth) } }
    FolderEditDialog(appString(R.string.playlist_folder_move), appString(R.string.playlist_folder_move), onDismiss, ready = picked != from, body = { saving ->
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            items(choices.size) { i ->
                val (id, name, depth) = choices[i]
                Row(
                    Modifier.fillMaxWidth().paperClip(RoundedCornerShape(12.dp)).pressClickable(enabled = !saving) { picked = id }
                        .padding(start = (12 + 16 * maxOf(depth, 0)).dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (depth < 0) Icons.Filled.LibraryMusic else Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(if (depth < 0) appString(R.string.playlist_folder_none) else folderName(name), style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (id == picked) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }) { onMove(picked) }
}
