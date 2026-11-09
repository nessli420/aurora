package com.aurora.music.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import com.aurora.music.ui.theme.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.R
import com.aurora.music.data.MatchChoice
import com.aurora.music.data.TrackChoices
import com.aurora.music.desktop.ui.LocalDesktopContainer
import com.aurora.music.desktop.youtube.YoutubeResolver
import com.aurora.music.localization.appString
import com.aurora.music.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// opens the picker for a track that plays a youtube match; the dialog lives at the app level so closing a menu keeps it open
val LocalMatchPicker = staticCompositionLocalOf<((Song) -> Unit)?> { null }

@Composable
fun MatchMenuItem(song: Song, close: () -> Unit) {
    val open = LocalMatchPicker.current ?: return
    if (TrackChoices.matchKey(song.streamUrl) == null) return
    DropdownMenuItem(
        text = { Text(appString(R.string.match_change)) },
        onClick = { close(); open(song) },
        leadingIcon = { Icon(Icons.Filled.SwapHoriz, null) },
    )
}

@Composable
fun MatchPickerDialog(song: Song, notify: (String) -> Unit, onChanged: (Song) -> Unit = {}, onDismiss: () -> Unit) {
    val container = LocalDesktopContainer.current
    val key = TrackChoices.matchKey(song.streamUrl) ?: return
    val matches by container.trackChoices.matches.collectAsStateWithLifecycle()
    val chosen = matches[key]
    val scope = rememberCoroutineScope()
    var query by remember(song.id) { mutableStateOf(listOf(song.artist, song.title).filter { it.isNotBlank() }.joinToString(" ")) }
    var results by remember(song.id) { mutableStateOf<List<YoutubeResolver.SearchResult>?>(null) }
    var failed by remember(song.id) { mutableStateOf(false) }
    var searching by remember(song.id) { mutableStateOf(false) }

    fun search() {
        val text = query.trim().ifEmpty { return }
        searching = true; failed = false
        scope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { container.youtubeResolver.searchSongs(text) } }
            results = found.getOrNull(); failed = found.isFailure; searching = false
        }
    }
    fun choose(choice: MatchChoice?) {
        container.trackChoices.setMatch(key, choice)
        // the old audio has to be out of the cache before the song plays again, or it comes straight back
        // (chained on the cache's own job: the dialog, and its scope, close straight after this)
        container.audioCache.forget(song.id).invokeOnCompletion { cause -> if (cause == null) onChanged(song) }
        notify(if (choice == null) appString(R.string.match_reset_done) else appString(R.string.match_saved, choice.title))
        onDismiss()
    }
    LaunchedEffect(song.id) { search() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(appString(R.string.match_change), fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.width(520.dp)) {
                Text(appString(R.string.match_hint, song.title), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    placeholder = { Text(appString(R.string.match_search)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                )
                Box(Modifier.fillMaxWidth().height(380.dp)) {
                    when {
                        searching -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        failed -> Note(appString(R.string.match_failed))
                        results?.isEmpty() == true -> Note(appString(R.string.match_none))
                        else -> LazyColumn(Modifier.fillMaxWidth()) {
                            items(results.orEmpty(), key = { it.videoId }) { result ->
                                val picked = chosen?.videoId == result.videoId
                                Row(
                                    Modifier.fillMaxWidth().clickable { choose(MatchChoice(result.videoId, result.title, result.artist, result.durationSec)) }
                                        .pointerHoverIcon(PointerIcon.Hand).padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Artwork(result.thumbnailUrl, MaterialTheme.colorScheme.primary, Modifier.size(48.dp), corner = 8.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(result.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium,
                                            color = if (picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${result.artist} · ${formatTime(result.durationSec)}", style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (picked) Icon(Icons.Filled.CheckCircle, appString(R.string.match_chosen), tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(appString(R.string.text_cancel_77dfd2)) } },
        dismissButton = if (chosen != null) ({ TextButton(onClick = { choose(null) }) { Text(appString(R.string.match_reset)) } }) else null,
    )
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
}
