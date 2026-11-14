package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import com.aurora.music.ui.theme.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
fun HiddenSongsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val store = com.aurora.music.desktop.ui.LocalDesktopContainer.current.settingsStore
    val hidden by store.hiddenSongs.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar(appString(R.string.hidden_songs_title), onBack)
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {
            item {
                Text(appString(if (hidden.isEmpty()) R.string.hidden_songs_empty else R.string.hidden_songs_summary), modifier = Modifier.padding(20.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(hidden.asReversed()) { song ->
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(song.title.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOf(song.artist, song.album).filterNot { it.isNullOrBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { scope.launch { store.setSongHidden(song, false) } }) { Text(appString(R.string.hide_song_undo)) }
                }
            }
        }
    }
}
