package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.data.MergeFeatures
import com.aurora.music.data.ServerType
import com.aurora.music.desktop.ui.LocalDesktopContainer
import kotlinx.coroutines.launch

private fun sourceName(type: ServerType) = when (type) {
    ServerType.SUBSONIC -> "Navidrome"
    ServerType.JELLYFIN -> "Jellyfin"
    ServerType.PLEX -> "Plex"
    ServerType.LOCAL -> appString(R.string.text_on_this_device_a7f962)
    ServerType.YOUTUBE_MUSIC -> "YouTube Music"
    ServerType.SPOTIFY -> "Spotify"
    ServerType.SOUNDCLOUD -> "SoundCloud"
    ServerType.APPLE_MUSIC -> "Apple Music"
    ServerType.EXTENSION -> appString(R.string.text_extension_659087)
}

@Composable
fun MergeOptionsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val store = LocalDesktopContainer.current.settingsStore
    val features by store.mergeFeatures.collectAsStateWithLifecycle(initialValue = MergeFeatures())
    val saved by store.savedSessions.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    fun save(next: MergeFeatures) { scope.launch { store.setMergeFeatures(next) } }
    val types = remember(saved) { (listOf(ServerType.LOCAL) + saved.map { it.type }).distinct().filter { it != ServerType.EXTENSION } }

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar(SettingsDestinations.merge.label, onBack)
        SettingsList(contentPadding) {
            item {
                Text(appString(R.string.merge_options_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
            }
            item {
                FeatureGroup(Icons.Filled.ChatBubble, appString(R.string.merge_comments_desktop), appString(R.string.merge_comments_summary_desktop),
                    features.comments, features.commentsOff, types.filter { it != ServerType.SOUNDCLOUD },
                    onToggle = { save(features.copy(comments = it)) },
                    onSource = { type, on -> save(features.copy(commentsOff = features.commentsOff.toggled(type, on))) })
            }
            if (ServerType.YOUTUBE_MUSIC in types) {
                item { androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 10.dp)) }
                item {
                    FeatureGroup(Icons.Filled.ChatBubble, appString(R.string.merge_youtube_comments), appString(R.string.merge_youtube_comments_summary),
                        features.youtubeComments, features.youtubeCommentsOff, types.filter { it != ServerType.YOUTUBE_MUSIC },
                        onToggle = { save(features.copy(youtubeComments = it)) },
                        onSource = { type, on -> save(features.copy(youtubeCommentsOff = features.youtubeCommentsOff.toggled(type, on))) })
                }
            }
            item { androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 10.dp)) }
            item {
                FeatureGroup(Icons.Filled.Lyrics, appString(R.string.merge_lyrics_desktop), appString(R.string.merge_lyrics_summary_desktop),
                    features.lyrics, features.lyricsOff, types.filter { it != ServerType.SPOTIFY },
                    onToggle = { save(features.copy(lyrics = it)) },
                    onSource = { type, on -> save(features.copy(lyricsOff = features.lyricsOff.toggled(type, on))) })
            }
            if (ServerType.APPLE_MUSIC in types) {
                item { androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 10.dp)) }
                item {
                    FeatureGroup(Icons.Filled.Lyrics, appString(R.string.merge_apple_lyrics), appString(R.string.merge_apple_lyrics_summary),
                        features.appleLyrics, features.appleLyricsOff, types.filter { it != ServerType.APPLE_MUSIC },
                        onToggle = { save(features.copy(appleLyrics = it)) },
                        onSource = { type, on -> save(features.copy(appleLyricsOff = features.appleLyricsOff.toggled(type, on))) })
                }
            }
        }
    }
}

private fun Set<String>.toggled(type: ServerType, on: Boolean): Set<String> = if (on) this - type.name else this + type.name

@Composable
private fun FeatureGroup(
    icon: ImageVector, title: String, summary: String, enabled: Boolean, off: Set<String>, types: List<ServerType>,
    onToggle: (Boolean) -> Unit, onSource: (ServerType, Boolean) -> Unit,
) {
    SettingsGroup {
        SettingsSwitchRow(icon, title, summary, enabled, onToggle)
        SettingsReveal(enabled && types.isNotEmpty()) {
            SettingsRowDivider()
            Text(appString(R.string.merge_songs_from), style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 2.dp))
            types.forEach { type ->
                SettingsSwitchRow(title = sourceName(type), checked = type.name !in off) { on -> onSource(type, on) }
            }
        }
    }
}
