package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.data.DEFAULT_LYRICS_SOURCES
import com.aurora.music.data.LyricsSync
import com.aurora.music.data.UiPrefs
import com.aurora.music.desktop.ui.LocalDesktopContainer
import kotlinx.coroutines.launch

private fun lyricsSourceLabel(id: String) = when (id) {
    "server" -> appString(R.string.lyrics_source_server)
    "spotify" -> "Spotify"
    "apple" -> "Apple Music"
    "lrclib" -> "LRCLIB"
    else -> id
}

private fun lyricsSourceDetail(id: String) = when (id) {
    "server" -> appString(R.string.lyrics_source_server_detail)
    "spotify" -> appString(R.string.lyrics_source_spotify_detail)
    "apple" -> appString(R.string.lyrics_source_apple_desc)
    "lrclib" -> appString(R.string.lyrics_source_lrclib_detail)
    else -> ""
}

@Composable
fun LyricsSettingsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val store = LocalDesktopContainer.current.settingsStore
    val prefs by store.uiPrefs.collectAsStateWithLifecycle(initialValue = com.aurora.music.ui.theme.LocalUiPrefs.current)
    val order by store.lyricsSources.collectAsStateWithLifecycle(initialValue = DEFAULT_LYRICS_SOURCES)
    val scope = rememberCoroutineScope()
    val line = prefs.lyricsSync == LyricsSync.LINE

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar(SettingsDestinations.lyrics.label, onBack)
        SettingsList(contentPadding) {
            item {
                SegmentedRow(appString(R.string.lyrics_sync_label), listOf(appString(R.string.lyrics_sync_line), appString(R.string.lyrics_sync_word)),
                    if (line) 0 else 1) { i ->
                    scope.launch { store.setLyricsSync(if (i == 0) LyricsSync.LINE else LyricsSync.WORD) }
                }
            }
            item {
                Text(appString(if (line) R.string.lyrics_sync_line_desc else R.string.lyrics_sync_word_desc),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
            item { SettingsSectionTitle(appString(R.string.lyrics_sources_title_desktop)) }
            item {
                Text(appString(R.string.lyrics_sources_hint),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            }
            itemsIndexed(order) { index, id ->
                PriorityRow(
                    label = lyricsSourceLabel(id),
                    subtitle = lyricsSourceDetail(id),
                    enabled = true,
                    canUp = index > 0,
                    canDown = index < order.lastIndex,
                    onUp = { scope.launch { store.setLyricsSources(order.swapped(index, index - 1)) } },
                    onDown = { scope.launch { store.setLyricsSources(order.swapped(index, index + 1)) } },
                )
            }
        }
    }
}
