package com.aurora.music.ui.screens.library

import android.net.Uri
import com.aurora.music.ui.theme.paperClip
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.PlaylistAddCheck
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.SmartDisplay
import com.aurora.music.ui.theme.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import com.aurora.music.ui.theme.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aurora.music.R
import com.aurora.music.data.ImportException
import com.aurora.music.data.ImportFailure
import com.aurora.music.data.ImportProgress
import com.aurora.music.data.ImportResult
import com.aurora.music.data.ImportSource
import com.aurora.music.data.PlaylistImporter
import com.aurora.music.localization.appString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImportPlaylistSheet(importer: PlaylistImporter, onOpen: (String) -> Unit, onImported: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    var link by rememberSaveable { mutableStateOf("") }
    var progress by remember { mutableStateOf<ImportProgress?>(null) }
    var result by remember { mutableStateOf<ImportResult?>(null) }
    var failure by remember { mutableStateOf<ImportFailure?>(null) }
    val busy = progress != null
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !busy || it != SheetValue.Hidden })
    val fallback = appString(R.string.text_imported_playlist_37a186)

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

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) run { report ->
            val (bytes, name) = withContext(Dispatchers.IO) {
                val data = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                val display = runCatching {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                }.getOrNull()
                data to display
            }
            importer.fromFile(name ?: "playlist", bytes ?: throw ImportException(ImportFailure.UNREADABLE), fallback, report)
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() }, sheetState = sheet,
        containerColor = colors.surface, tonalElevation = 0.dp,
        shape = com.aurora.music.ui.theme.paperSquare(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)),
    ) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            val done = result
            if (done == null) {
                Text(appString(R.string.import_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(appString(R.string.import_hint), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
                val start = { if (link.isNotBlank() && !busy) run { report -> importer.fromLink(link, fallback, report) } }
                OutlinedTextField(
                    value = link, onValueChange = { link = it; failure = null },
                    label = { Text(appString(R.string.import_link)) },
                    placeholder = { Text("https://open.spotify.com/playlist/…", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true, enabled = !busy, shape = com.aurora.music.ui.theme.paperSquare(RoundedCornerShape(16.dp)), modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { start() }),
                )
                val step = progress
                if (step != null) {
                    val (text, fraction) = when (step) {
                        ImportProgress.Reading -> appString(R.string.import_reading) to null
                        is ImportProgress.Matching -> appString(R.string.import_matching, step.done, step.total) to step.done.toFloat() / step.total.coerceAtLeast(1)
                        ImportProgress.Saving -> appString(R.string.import_saving) to null
                    }
                    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
                    if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                }
                failure?.let { f ->
                    Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.ErrorOutline, null, tint = colors.error, modifier = Modifier.size(18.dp))
                        Text(appString(failureText(f)), color = colors.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { pickFile.launch(arrayOf("*/*")) }, enabled = !busy, shape = com.aurora.music.ui.theme.paperSquare(RoundedCornerShape(18.dp)),
                        modifier = Modifier.weight(1f).height(54.dp)) {
                        Icon(Icons.Outlined.FileOpen, null, modifier = Modifier.size(18.dp))
                        Text(appString(R.string.import_file), modifier = Modifier.padding(start = 8.dp))
                    }
                    Button(onClick = start, enabled = link.isNotBlank() && !busy, shape = com.aurora.music.ui.theme.paperSquare(RoundedCornerShape(18.dp)),
                        modifier = Modifier.weight(1f).height(54.dp)) {
                        Text(appString(R.string.import_start), fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(56.dp).paperClip(RoundedCornerShape(16.dp)).background(colors.primary.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.PlaylistAddCheck, null, tint = colors.primary, modifier = Modifier.size(28.dp))
                    }
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(done.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(appString(R.string.import_count, done.matched, done.total), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                }
                Column(Modifier.padding(top = 18.dp)) {
                    ImportSource.entries.forEach { source ->
                        val count = done.found[source] ?: 0
                        if (count > 0) SourceRow(sourceIcon(source), sourceLabel(source), count)
                    }
                    if (done.missing.isNotEmpty()) SourceRow(Icons.Outlined.SearchOff, appString(R.string.import_not_found), done.missing.size)
                }
                done.missing.take(40).forEach { t ->
                    Text(listOf(t.artist, t.title).filter { it.isNotBlank() }.joinToString(" – "), style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 34.dp, top = 2.dp))
                }
                if (done.ownSourceOnly) Note(appString(R.string.import_own_only))
                if (done.limited) Note(appString(R.string.import_limited))
                Row(Modifier.fillMaxWidth().padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onDismiss, shape = com.aurora.music.ui.theme.paperSquare(RoundedCornerShape(18.dp)), modifier = Modifier.weight(1f).height(54.dp)) {
                        Text(appString(R.string.import_done))
                    }
                    Button(onClick = { onDismiss(); onOpen(done.playlistId) }, shape = com.aurora.music.ui.theme.paperSquare(RoundedCornerShape(18.dp)), modifier = Modifier.weight(1f).height(54.dp)) {
                        Text(appString(R.string.import_open), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceRow(icon: ImageVector, label: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 12.dp))
        Text(count.toString(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 14.dp).paperClip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp))
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

private fun failureText(f: ImportFailure): Int = when (f) {
    ImportFailure.UNSUPPORTED -> R.string.import_failed_unsupported
    ImportFailure.UNREADABLE -> R.string.import_failed_unreadable
    ImportFailure.EMPTY -> R.string.import_failed_empty
    ImportFailure.OFFLINE -> R.string.import_failed_offline
    ImportFailure.NOT_SAVED -> R.string.import_failed_save
}
