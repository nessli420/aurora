package com.aurora.music.ui.screens.library

import androidx.compose.foundation.background
import com.aurora.music.ui.theme.paperClip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import com.aurora.music.ui.theme.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aurora.music.R
import com.aurora.music.data.ImportException
import com.aurora.music.data.ImportFailure
import com.aurora.music.data.ImportProgress
import com.aurora.music.data.ImportResult
import com.aurora.music.data.ImportSource
import com.aurora.music.data.PlaylistImporter
import com.aurora.music.desktop.ui.FilePickers
import com.aurora.music.localization.appString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ImportPlaylistDialog(importer: PlaylistImporter, onOpen: (String) -> Unit, onImported: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    var link by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<ImportProgress?>(null) }
    var result by remember { mutableStateOf<ImportResult?>(null) }
    var failure by remember { mutableStateOf<ImportFailure?>(null) }
    val busy = progress != null
    val fallback = appString(R.string.text_imported_playlist_37a186)
    val pickTitle = appString(R.string.import_file)

    fun run(block: suspend ((ImportProgress) -> Unit) -> ImportResult) {
        failure = null
        result = null
        progress = ImportProgress.Reading
        scope.launch {
            try {
                result = block { progress = it }
                onImported()
            } catch (e: CancellationException) { throw e }
            catch (e: ImportException) { failure = e.failure }
            catch (_: Exception) { failure = ImportFailure.UNREADABLE }
            finally { progress = null }
        }
    }
    val start = { if (link.isNotBlank() && !busy) run { report -> importer.fromLink(link, fallback, report) } }
    val chooseFile = {
        FilePickers.openFile(pickTitle, listOf("m3u", "m3u8", "csv", "tsv", "txt", "xspf"))?.let { file ->
            run { report ->
                val bytes = withContext(Dispatchers.IO) { runCatching { file.readBytes() }.getOrNull() } ?: throw ImportException(ImportFailure.UNREADABLE)
                importer.fromFile(file.name, bytes, fallback, report)
            }
        }
        Unit
    }
    val done = result

    AlertDialog(containerColor = com.aurora.music.ui.theme.solidColors().surfaceContainerHigh,
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(done?.name ?: appString(R.string.import_title), fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.width(460.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                if (done == null) {
                    Text(appString(R.string.import_hint), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    OutlinedTextField(
                        value = link, onValueChange = { link = it; failure = null },
                        label = { Text(appString(R.string.import_link)) },
                        placeholder = { Text("https://open.spotify.com/playlist/…", maxLines = 1) },
                        singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { start() }),
                    )
                    val step = progress
                    if (step != null) {
                        val (text, fraction) = when (step) {
                            ImportProgress.Reading -> appString(R.string.import_reading) to null
                            is ImportProgress.Matching -> appString(R.string.import_matching, step.done, step.total) to step.done.toFloat() / step.total.coerceAtLeast(1)
                            ImportProgress.Saving -> appString(R.string.import_saving) to null
                        }
                        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                        if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    }
                    failure?.let { f ->
                        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.ErrorOutline, null, tint = colors.error, modifier = Modifier.size(18.dp))
                            Text(failureText(f), color = colors.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    TextButton(onClick = chooseFile, enabled = !busy, modifier = Modifier.padding(top = 8.dp)) { Text(appString(R.string.import_file)) }
                } else {
                    Text(appString(R.string.import_count, done.matched, done.total), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                    Column(Modifier.padding(top = 12.dp)) {
                        ImportSource.entries.forEach { source ->
                            val count = done.found[source] ?: 0
                            if (count > 0) SourceRow(sourceIcon(source), sourceLabel(source), count)
                        }
                        if (done.missing.isNotEmpty()) SourceRow(Icons.Outlined.SearchOff, appString(R.string.import_not_found), done.missing.size)
                    }
                    done.missing.take(40).forEach { t ->
                        Text(listOf(t.artist, t.title).filter { it.isNotBlank() }.joinToString(" – "), style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 32.dp, top = 2.dp))
                    }
                    if (done.ownSourceOnly) Note(appString(R.string.import_own_only))
                    if (done.limited) Note(appString(R.string.import_limited))
                }
            }
        },
        confirmButton = {
            if (done == null) TextButton(onClick = start, enabled = link.isNotBlank() && !busy) { Text(appString(R.string.import_start)) }
            else TextButton(onClick = { onDismiss(); onOpen(done.playlistId) }) { Text(appString(R.string.import_open)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(appString(if (done == null) R.string.text_cancel_77dfd2 else R.string.import_done)) }
        },
    )
}

@Composable
private fun SourceRow(icon: ImageVector, label: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 12.dp))
        Text(count.toString(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp).paperClip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(10.dp))
}

private fun sourceIcon(source: ImportSource): ImageVector = when (source) {
    ImportSource.LIBRARY, ImportSource.OTHER -> Icons.Outlined.LibraryMusic
    ImportSource.YOUTUBE_MUSIC -> Icons.Outlined.SmartDisplay
    ImportSource.SPOTIFY, ImportSource.SOUNDCLOUD -> Icons.Outlined.CloudQueue
}

@Composable
private fun sourceLabel(source: ImportSource): String = when (source) {
    ImportSource.LIBRARY, ImportSource.OTHER -> appString(R.string.import_from_library)
    ImportSource.YOUTUBE_MUSIC -> "YouTube Music"
    ImportSource.SPOTIFY -> "Spotify"
    ImportSource.SOUNDCLOUD -> "SoundCloud"
}

@Composable
private fun failureText(f: ImportFailure): String = appString(when (f) {
    ImportFailure.UNSUPPORTED -> R.string.import_failed_unsupported
    ImportFailure.UNREADABLE -> R.string.import_failed_unreadable
    ImportFailure.EMPTY -> R.string.import_failed_empty
    ImportFailure.OFFLINE -> R.string.import_failed_offline
    ImportFailure.NOT_SAVED -> R.string.import_failed_save
})
