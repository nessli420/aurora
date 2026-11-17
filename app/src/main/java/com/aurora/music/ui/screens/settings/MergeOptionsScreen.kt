package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aurora.music.AuroraApplication
import com.aurora.music.data.MergeFeatures
import com.aurora.music.data.ServerType
import kotlinx.coroutines.launch

private val SOURCE_ORDER = listOf(ServerType.LOCAL, ServerType.SUBSONIC, ServerType.JELLYFIN, ServerType.PLEX,
    ServerType.YOUTUBE_MUSIC, ServerType.SPOTIFY, ServerType.SOUNDCLOUD, ServerType.APPLE_MUSIC)

private fun sourceLabel(type: ServerType) = when (type) {
    ServerType.LOCAL -> appString(R.string.text_on_this_device_a7f962)
    ServerType.SUBSONIC -> "Navidrome / Subsonic"
    ServerType.JELLYFIN -> "Jellyfin"
    ServerType.PLEX -> "Plex"
    ServerType.YOUTUBE_MUSIC -> "YouTube Music"
    ServerType.SPOTIFY -> "Spotify"
    ServerType.SOUNDCLOUD -> "SoundCloud"
    ServerType.APPLE_MUSIC -> "Apple Music"
    ServerType.EXTENSION -> appString(R.string.text_extension_659087)
}

@Composable
fun MergeOptionsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { (ctx.applicationContext as AuroraApplication).container.settingsStore }
    val scope = rememberCoroutineScope()
    val features by store.mergeFeatures.collectAsState(initial = MergeFeatures())
    val saved by store.savedSessions.collectAsState(initial = emptyList())
    // only the kinds of source this device has, and each feature only once its service is connected
    val types = remember(saved) { (saved.map { it.type } + ServerType.LOCAL).distinct().filter { it in SOURCE_ORDER }.sortedBy(SOURCE_ORDER::indexOf) }
    fun save(f: MergeFeatures) { scope.launch { store.setMergeFeatures(f) } }
    Column(Modifier.fillMaxSize()) {
        SettingsTopBar(appString(R.string.merge_options), onBack)
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {
            if (ServerType.SPOTIFY in types) feature(Icons.Filled.Movie, R.string.merge_canvas, R.string.merge_canvas_summary,
                features.canvas, features.canvasOff, types, { save(features.copy(canvas = it)) }) { save(features.copy(canvasOff = it)) }
            feature(Icons.Filled.VideoLibrary, R.string.merge_videos, R.string.merge_videos_summary,
                features.videos, features.videosOff, types, { save(features.copy(videos = it)) }) { save(features.copy(videosOff = it)) }
            if (ServerType.SPOTIFY in types) feature(Icons.Filled.Lyrics, R.string.merge_lyrics, R.string.merge_lyrics_summary,
                features.lyrics, features.lyricsOff, types, { save(features.copy(lyrics = it)) }) { save(features.copy(lyricsOff = it)) }
            if (ServerType.APPLE_MUSIC in types) feature(Icons.Filled.Lyrics, R.string.merge_apple_lyrics, R.string.merge_apple_lyrics_summary,
                features.appleLyrics, features.appleLyricsOff, types, { save(features.copy(appleLyrics = it)) }) { save(features.copy(appleLyricsOff = it)) }
            if (ServerType.SOUNDCLOUD in types) feature(Icons.Filled.ChatBubble, R.string.merge_comments, R.string.merge_comments_summary,
                features.comments, features.commentsOff, types, { save(features.copy(comments = it)) }) { save(features.copy(commentsOff = it)) }
            if (ServerType.YOUTUBE_MUSIC in types) feature(Icons.Filled.ChatBubble, R.string.merge_youtube_comments, R.string.merge_youtube_comments_summary,
                features.youtubeComments, features.youtubeCommentsOff, types, { save(features.copy(youtubeComments = it)) }) { save(features.copy(youtubeCommentsOff = it)) }
        }
    }
}

// one overall switch, then one per source the songs come from while it is on
private fun LazyListScope.feature(icon: ImageVector, title: Int, summary: Int, on: Boolean, off: Set<String>, types: List<ServerType>,
    onToggle: (Boolean) -> Unit, onSources: (Set<String>) -> Unit) {
    item { SettingsSectionTitle(appString(title)) }
    item {
        SettingsGroup {
            SettingsSwitchRow(icon, appString(title), appString(summary), on, onToggle)
            SettingsReveal(on) {
                SettingsRowDivider()
                Text(appString(R.string.merge_on_songs_from), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, top = 12.dp, end = 20.dp))
                types.forEach { type ->
                    SettingsSwitchRow(null, sourceLabel(type), null, type.name !in off) { show ->
                        onSources(if (show) off - type.name else off + type.name)
                    }
                }
            }
        }
    }
}
