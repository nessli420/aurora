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
import androidx.compose.material3.CircularProgressIndicator
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// opens the picker for the playing song; the sheet lives at the app level so closing a menu keeps it open
val LocalMatchPicker = staticCompositionLocalOf<((Song) -> Unit)?> { null }

private class Pick(val id: String, val title: String, val artist: String, val durationSec: Int, val artworkUrl: String, val song: Song?)

// a track without audio of its own picks its youtube video; any other picks the right song on its own source
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchPickerSheet(song: Song, onSwap: (Song) -> Unit = {}, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as AuroraApplication).container
    val key = TrackChoices.matchKey(song.streamUrl)
    val matches by container.trackChoices.matches.collectAsStateWithLifecycle()
    val chosen = key?.let { matches[it]?.videoId } ?: song.id.takeIf { key == null }
    val scope = rememberCoroutineScope()
    var query by remember(song.id) { mutableStateOf(listOf(song.artist, song.title).filter { it.isNotBlank() }.joinToString(" ")) }
    var results by remember(song.id) { mutableStateOf<List<Pick>?>(null) }
    var failed by remember(song.id) { mutableStateOf(false) }
    var searching by remember(song.id) { mutableStateOf(false) }

    fun search() {
        val text = query.trim().ifEmpty { return }
        searching = true; failed = false
        scope.launch {
            val found = runCatching {
                if (key != null) withContext(Dispatchers.IO) { container.youtubeResolver.searchSongs(text) }
                    .map { Pick(it.videoId, it.title, it.artist, it.durationSec, it.thumbnailUrl, null) }
                else container.repository.searchOwnSource(song, text).map { Pick(it.id, it.title, it.artist, it.durationSec, it.artworkUrl, it) }
            }
            results = found.getOrNull()?.distinctBy { it.id }; failed = found.isFailure; searching = false
        }
    }
    fun choose(pick: Pick?) {
        if (key != null) {
            container.trackChoices.setMatch(key, pick?.let { MatchChoice(it.id, it.title, it.artist, it.durationSec) })
            container.audioCache.forget(song.id)
            onSwap(song)
        } else pick?.song?.takeIf { it.id != song.id }?.let(onSwap)
        Toast.makeText(context, if (pick == null) appString(R.string.match_reset_done) else appString(R.string.match_saved, pick.title), Toast.LENGTH_SHORT).show()
        onDismiss()
    }
    LaunchedEffect(song.id) { search() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().padding(bottom = 16.dp)) {
            Text(appString(R.string.match_change), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp))
            Text(appString(if (key != null) R.string.match_hint else R.string.match_hint_source, song.title), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text(appString(if (key != null) R.string.match_search else R.string.match_search_source)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            )
            when {
                searching -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                failed -> Note(appString(if (key != null) R.string.match_failed else R.string.match_failed_source))
                results?.isEmpty() == true -> Note(appString(if (key != null) R.string.match_none else R.string.match_none_source))
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(if (searching) emptyList() else results.orEmpty(), key = { it.id }) { result ->
                    val picked = chosen == result.id
                    Row(
                        Modifier.fillMaxWidth().clickable { choose(result) }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(result.artworkUrl, MaterialTheme.colorScheme.primary, Modifier.size(52.dp), corner = 8.dp)
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
            if (key != null && matches[key] != null) TextButton(onClick = { choose(null) }, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
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
