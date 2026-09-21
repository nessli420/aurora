package com.aurora.music.ui.components

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aurora.music.AuroraApplication
import com.aurora.music.R
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrackPlaylistsSheet(song: Song, onDismiss: () -> Unit) {
    val repository = (LocalContext.current.applicationContext as AuroraApplication).container.repository
    val editor = remember(song.id, song.playbackSource?.providerId) { repository.playlistEditor(song) }
    val scope = rememberCoroutineScope()
    val requests = remember { Semaphore(4) }
    var playlists by remember { mutableStateOf<List<Playlist>?>(null) }
    val membership = remember { mutableStateMapOf<String, Boolean>() }
    val failedReads = remember { mutableStateMapOf<String, Boolean>() }
    var loadFailed by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    DisposableEffect(editor) { onDispose { editor?.publishChanges() } }
    LaunchedEffect(editor, retry) {
        if (editor == null) return@LaunchedEffect
        loadFailed = false
        try { playlists = editor.playlists() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { loadFailed = true }
    }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { busy == null || it != SheetValue.Hidden })
    ModalBottomSheet(onDismissRequest = { if (busy == null) onDismiss() }, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.track_playlists_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
            when {
                editor == null -> Text(stringResource(R.string.track_playlists_unavailable))
                loadFailed -> {
                    Text(stringResource(R.string.track_playlists_load_error))
                    TextButton(onClick = { retry++ }) { Text(stringResource(R.string.track_swipe_retry)) }
                }
                playlists == null -> Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                }
                playlists!!.isEmpty() -> Text(stringResource(R.string.track_playlists_empty))
                else -> {
                    Text(stringResource(R.string.track_playlists_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (saveFailed) Text(stringResource(R.string.track_playlists_save_error), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp).padding(top = 8.dp)) {
                        items(playlists!!, key = { it.id }) { playlist ->
                            var rowRetry by remember { mutableIntStateOf(0) }
                            LaunchedEffect(playlist.id, rowRetry) {
                                if (membership.containsKey(playlist.id)) return@LaunchedEffect
                                failedReads.remove(playlist.id)
                                try { membership[playlist.id] = requests.withPermit { editor.contains(playlist.id) } }
                                catch (e: CancellationException) { throw e }
                                catch (_: Exception) { failedReads[playlist.id] = true }
                            }
                            val included = membership[playlist.id]
                            val failed = failedReads[playlist.id] == true
                            Row(Modifier.fillMaxWidth().toggleable(value = included == true,
                                enabled = included != null && busy == null, role = Role.Checkbox) { checked ->
                                busy = playlist.id
                                saveFailed = false
                                scope.launch {
                                    try {
                                        if (editor.setIncluded(playlist.id, checked)) membership[playlist.id] = checked
                                        else saveFailed = true
                                    } catch (e: CancellationException) { throw e }
                                    catch (_: Exception) { saveFailed = true }
                                    finally { busy = null }
                                }
                            }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Artwork(playlist.coverUrl, playlist.accent, Modifier.size(42.dp), corner = 8.dp)
                                Text(playlist.title, Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 2,
                                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                when {
                                    failed -> TextButton(onClick = { rowRetry++ }) { Text(stringResource(R.string.track_swipe_retry)) }
                                    included == null || busy == playlist.id -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                    else -> Checkbox(checked = included, onCheckedChange = null, enabled = busy == null)
                                }
                            }
                        }
                    }
                }
            }
            TextButton(onClick = onDismiss, enabled = busy == null, modifier = Modifier.align(Alignment.End).padding(top = 8.dp)) {
                Text(stringResource(R.string.track_swipe_done))
            }
        }
    }
}
