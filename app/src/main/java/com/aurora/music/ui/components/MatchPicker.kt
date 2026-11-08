package com.aurora.music.ui.components

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import com.aurora.music.ui.theme.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.R
import com.aurora.music.data.MatchChoice
import com.aurora.music.data.TrackChoices
import com.aurora.music.localization.appString
import com.aurora.music.model.Song
import com.aurora.music.playback.YoutubeResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// opens the picker for a track that plays a youtube match; the sheet lives at the app level so closing a menu keeps it open
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchPickerSheet(song: Song, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as AuroraApplication).container
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
        container.audioCache.forget(song.id)
        Toast.makeText(context, if (choice == null) appString(R.string.match_reset_done) else appString(R.string.match_saved, choice.title), Toast.LENGTH_SHORT).show()
        onDismiss()
    }
    LaunchedEffect(song.id) { search() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().padding(bottom = 16.dp)) {
            Text(appString(R.string.match_change), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp))
            Text(appString(R.string.match_hint, song.title), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text(appString(R.string.match_search)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            )
            when {
                searching -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                failed -> Note(appString(R.string.match_failed))
                results?.isEmpty() == true -> Note(appString(R.string.match_none))
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(if (searching) emptyList() else results.orEmpty(), key = { it.videoId }) { result ->
                    val picked = chosen?.videoId == result.videoId
                    Row(
                        Modifier.fillMaxWidth().clickable { choose(MatchChoice(result.videoId, result.title, result.artist, result.durationSec)) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(result.thumbnailUrl, MaterialTheme.colorScheme.primary, Modifier.size(52.dp), corner = 8.dp)
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
            if (chosen != null) TextButton(onClick = { choose(null) }, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Text(appString(R.string.match_reset))
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
}
