package com.aurora.music.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
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
    var playlists by remember(editor) { mutableStateOf<List<Playlist>?>(null) }
    val membership = remember(editor) { mutableStateMapOf<String, Boolean>() }
    val failedReads = remember(editor) { mutableStateMapOf<String, Boolean>() }
    var loadFailed by remember(editor) { mutableStateOf(false) }
    var saveFailed by remember(editor) { mutableStateOf(false) }
    var busy by remember(editor) { mutableStateOf<String?>(null) }
    var retry by remember(editor) { mutableIntStateOf(0) }
    DisposableEffect(editor) { onDispose { editor?.publishChanges() } }
    LaunchedEffect(editor, retry) {
        if (editor == null) return@LaunchedEffect
        playlists = null
        membership.clear()
        failedReads.clear()
        loadFailed = false
        try { playlists = editor.playlists() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { loadFailed = true }
    }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { busy == null || it != SheetValue.Hidden })
    val colors = MaterialTheme.colorScheme
    val maxHeight = (LocalConfiguration.current.screenHeightDp * .86f).dp
    ModalBottomSheet(
        onDismissRequest = { if (busy == null) onDismiss() }, sheetState = sheet,
        containerColor = colors.surface, tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).padding(horizontal = 20.dp)) {
            Text(stringResource(R.string.track_swipe_playlists), style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.track_playlists_hint), style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 18.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .background(Brush.horizontalGradient(listOf(colors.primary.copy(alpha = .13f), colors.surfaceContainerHigh)))
                    .border(1.dp, colors.primary.copy(alpha = .12f), RoundedCornerShape(20.dp))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(song.artworkUrl, song.accent,
                    Modifier.size(60.dp).shadow(6.dp, RoundedCornerShape(12.dp)), corner = 12.dp)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(song.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
            when {
                editor == null -> Text(stringResource(R.string.track_playlists_unavailable), color = colors.onSurfaceVariant)
                loadFailed -> {
                    Text(stringResource(R.string.track_playlists_load_error), color = colors.onSurfaceVariant)
                    TextButton(onClick = { retry++ }) { Text(stringResource(R.string.track_swipe_retry)) }
                }
                playlists == null -> Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                }
                playlists!!.isEmpty() -> Text(stringResource(R.string.track_playlists_empty), color = colors.onSurfaceVariant)
                else -> {
                    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.text_your_playlists_df03eb), Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(playlists!!.size.toString(), style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant, modifier = Modifier.clip(CircleShape)
                                .background(colors.surfaceContainerHigh).padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                    if (saveFailed) Text(stringResource(R.string.track_playlists_save_error), color = colors.error,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 10.dp))
                    LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 380.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 4.dp)) {
                        items(playlists!!, key = { it.id }) { playlist ->
                            var rowRetry by remember { mutableIntStateOf(0) }
                            LaunchedEffect(editor, playlist.id, rowRetry) {
                                if (membership.containsKey(playlist.id)) return@LaunchedEffect
                                failedReads.remove(playlist.id)
                                try { membership[playlist.id] = requests.withPermit { editor.contains(playlist.id) } }
                                catch (e: CancellationException) { throw e }
                                catch (_: Exception) { failedReads[playlist.id] = true }
                            }
                            val included = membership[playlist.id]
                            val failed = failedReads[playlist.id] == true
                            val rowColor by animateColorAsState(
                                if (included == true) colors.primary.copy(alpha = .10f) else colors.surfaceContainerLow,
                                label = "playlistSelectionFill")
                            val borderColor by animateColorAsState(
                                if (included == true) colors.primary.copy(alpha = .45f) else colors.outlineVariant.copy(alpha = .3f),
                                label = "playlistSelectionBorder")
                            val rowShape = RoundedCornerShape(16.dp)
                            Row(Modifier.fillMaxWidth().clip(rowShape).background(rowColor).border(1.dp, borderColor, rowShape)
                                .toggleable(value = included == true,
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
                                }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Artwork(playlist.coverUrl, playlist.accent, Modifier.size(46.dp), corner = 10.dp)
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(playlist.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    if (included != null) Text(stringResource(if (included) R.string.track_playlists_in_playlist
                                        else R.string.track_playlists_add), style = MaterialTheme.typography.bodySmall,
                                        color = if (included) colors.primary else colors.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 3.dp))
                                }
                                when {
                                    failed -> TextButton(onClick = { rowRetry++ }) { Text(stringResource(R.string.track_swipe_retry)) }
                                    included == null || busy == playlist.id -> Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    }
                                    else -> Crossfade(included, label = "playlistSelectionIcon") { checked ->
                                        Box(Modifier.size(30.dp).clip(CircleShape)
                                            .background(if (checked) colors.primary else colors.surfaceContainerHigh),
                                            contentAlignment = Alignment.Center) {
                                            Icon(if (checked) Icons.Default.Check else Icons.Default.Add, null, Modifier.size(18.dp),
                                                tint = if (checked) colors.onPrimary else colors.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Button(onClick = onDismiss, enabled = busy == null, shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 16.dp).heightIn(min = 50.dp)) {
                Text(stringResource(R.string.track_swipe_done), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
