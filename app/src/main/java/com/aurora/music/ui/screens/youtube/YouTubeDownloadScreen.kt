package com.aurora.music.ui.screens.youtube

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.R
import com.aurora.music.data.YouTubeDownloader
import com.aurora.music.localization.appString
import com.aurora.music.playback.YoutubeResolver
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.screens.settings.SettingsTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun YouTubeDownloadScreen(contentPadding: PaddingValues, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    val states by container.youTubeDownloader.states.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<YoutubeResolver.SearchResult>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun search() {
        val text = query.trim()
        if (text.isEmpty()) return
        keyboard?.hide()
        job?.cancel()
        searching = true
        failed = false
        job = scope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { container.youtubeResolver.searchSongs(text) } }
            results = found.getOrNull()
            failed = found.isFailure
            searching = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth()) {
            SettingsTopBar(appString(R.string.youtube_download_title), onBack)
            Icon(
                Icons.Filled.Settings, appString(R.string.youtube_download_settings),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 6.dp)
                    .size(40.dp).clip(CircleShape).clickable(onClick = onOpenSettings).padding(8.dp),
            )
        }
        TextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            placeholder = { Text(appString(R.string.youtube_download_search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.primary) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )
        val message = when {
            searching -> null
            failed -> appString(R.string.youtube_download_search_failed)
            results == null -> appString(R.string.youtube_download_intro)
            results.orEmpty().isEmpty() -> appString(R.string.youtube_download_no_results)
            else -> null
        }
        if (searching) Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        if (message != null) Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {
            items(if (searching) emptyList() else results.orEmpty(), key = { it.videoId }) { result ->
                ResultRow(result, states[result.videoId]) { container.youTubeDownloader.download(result.videoId) }
            }
        }
    }
}

@Composable
private fun ResultRow(result: YoutubeResolver.SearchResult, state: YouTubeDownloader.State?, onDownload: () -> Unit) {
    val busy = state == YouTubeDownloader.State.Queued || state == YouTubeDownloader.State.Saving || state is YouTubeDownloader.State.Downloading
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !busy && state != YouTubeDownloader.State.Done, onClick = onDownload)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(result.thumbnailUrl, MaterialTheme.colorScheme.primary, Modifier.size(52.dp), corner = 8.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(result.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = when (state) {
                YouTubeDownloader.State.Done -> appString(R.string.youtube_download_saved)
                YouTubeDownloader.State.Failed -> appString(R.string.youtube_download_failed)
                YouTubeDownloader.State.Saving -> appString(R.string.youtube_download_saving)
                else -> "${result.artist} · ${result.durationSec / 60}:${"%02d".format(result.durationSec % 60)}"
            }
            Text(detail, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (state == YouTubeDownloader.State.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            when (state) {
                is YouTubeDownloader.State.Downloading -> CircularProgressIndicator(progress = { state.progress }, modifier = Modifier.size(26.dp), strokeWidth = 3.dp)
                YouTubeDownloader.State.Queued, YouTubeDownloader.State.Saving -> CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp)
                YouTubeDownloader.State.Done -> Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                YouTubeDownloader.State.Failed -> Icon(Icons.Filled.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                null -> Icon(Icons.Filled.Download, appString(R.string.youtube_download_action), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
