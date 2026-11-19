package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.DEFAULT_LYRICS_SOURCES
import com.aurora.music.data.LyricsSync
import com.aurora.music.data.ServerType
import com.aurora.music.data.UiPrefs
import kotlinx.coroutines.launch

@Composable
fun LyricsSettingsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { (ctx.applicationContext as AuroraApplication).container.settingsStore }
    val scope = rememberCoroutineScope()
    val prefs by store.uiPrefs.collectAsStateWithLifecycle(initialValue = com.aurora.music.ui.theme.LocalUiPrefs.current)
    val order by store.lyricsSources.collectAsStateWithLifecycle(initialValue = DEFAULT_LYRICS_SOURCES)
    val lrclib by store.lrclibEnabled.collectAsStateWithLifecycle(initialValue = true)
    val saved by store.savedSessions.collectAsStateWithLifecycle(initialValue = emptyList())
    // a catalogue only shows once an account can supply it; its place in the order is kept either way
    val shown = order.filter { (it != "spotify" || saved.any { s -> s.type == ServerType.SPOTIFY }) &&
        (it != "apple" || saved.any { s -> s.type == ServerType.APPLE_MUSIC && !s.isGuest }) }
    Column(Modifier.fillMaxSize()) {
        SettingsTopBar(appString(R.string.lyrics_settings_title), onBack)
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {
            item { SettingsSectionTitle(appString(R.string.lyrics_settings_title)) }
            item {
                SegmentedRow(appString(R.string.lyrics_sync_label), listOf(appString(R.string.lyrics_sync_line),
                    appString(R.string.lyrics_sync_word), appString(R.string.lyrics_sync_vocals)), prefs.lyricsSync) { i ->
                    scope.launch { store.setLyricsSync(i) }
                }
            }
            item {
                Text(appString(when (prefs.lyricsSync) {
                    LyricsSync.LINE -> R.string.lyrics_sync_line_desc
                    LyricsSync.VOCALS -> R.string.lyrics_sync_vocals_desc
                    else -> R.string.lyrics_sync_word_desc
                }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
            item {
                SettingsSwitchRow(title = appString(R.string.lyrics_translate), subtitle = appString(R.string.lyrics_translate_desc), checked = prefs.lyricsTranslate) { v ->
                    scope.launch { store.setLyricsTranslate(v) }
                }
            }

            item { SettingsSectionTitle(appString(R.string.lyrics_sources_title)) }
            item {
                Text(appString(R.string.lyrics_sources_desc), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            }
            itemsIndexed(shown) { idx, source ->
                PriorityRow(
                    label = when (source) {
                        "server" -> appString(R.string.lyrics_source_own)
                        "spotify" -> "Spotify"
                        "apple" -> "Apple Music"
                        else -> "LRCLIB"
                    },
                    subtitle = when (source) {
                        "server" -> appString(R.string.lyrics_source_own_desc)
                        "spotify" -> appString(R.string.lyrics_source_spotify_desc)
                        "apple" -> appString(R.string.lyrics_source_apple_desc)
                        else -> if (lrclib) appString(R.string.lyrics_source_lrclib_desc) else appString(R.string.text_off_e3de5a)
                    },
                    enabled = source != "lrclib" || lrclib,
                    canUp = idx > 0,
                    canDown = idx < shown.lastIndex,
                    // moves swap neighbours in the full order, so a hidden source keeps its place
                    onUp = { scope.launch { store.setLyricsSources(order.swapped(order.indexOf(source), order.indexOf(shown[idx - 1]))) } },
                    onDown = { scope.launch { store.setLyricsSources(order.swapped(order.indexOf(source), order.indexOf(shown[idx + 1]))) } },
                )
            }
        }
    }
}
