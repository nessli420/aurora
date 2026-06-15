package com.aurora.music.ui.screens.podcasts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurora.music.data.Podcast
import com.aurora.music.data.PodcastEpisode
import com.aurora.music.data.toSong
import com.aurora.music.model.Song
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.LottieLoader
import com.aurora.music.util.accentFor
import com.aurora.music.viewmodel.PodcastViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PodcastDetailScreen(
    contentPadding: PaddingValues,
    feedUrl: String,
    title: String,
    imageUrl: String,
    author: String,
    onBack: () -> Unit,
    onPlay: (Song) -> Unit,
) {
    val vm: PodcastViewModel = viewModel()
    val episodesState by vm.episodes.collectAsStateWithLifecycle()
    val subs by vm.subscriptions.collectAsStateWithLifecycle()
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    LaunchedEffect(feedUrl) { vm.loadEpisodes(feedUrl, title, imageUrl) }

    val showTitle = episodesState.channelTitle.ifBlank { title }.ifBlank { "Podcast" }
    val showImage = episodesState.channelImage.ifBlank { imageUrl }
    val subscribed = subs.any { it.feedUrl == feedUrl }
    val accent = accentFor(feedUrl)

    Column(Modifier.fillMaxSize().padding(top = topInset)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, "Back",
                modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onBack).padding(8.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(showTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }

        val bottom = contentPadding.calculateBottomPadding() + 24.dp
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = bottom)) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(showImage, accent, Modifier.size(96.dp), corner = 16.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(showTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        author.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(
                            Modifier.clip(RoundedCornerShape(50))
                                .background(if (subscribed) MaterialTheme.colorScheme.surfaceContainerHighest else accent)
                                .clickable {
                                    vm.toggleSubscribe(Podcast(feedUrl = feedUrl, title = showTitle, author = author, imageUrl = showImage))
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (subscribed) Icons.Filled.Check else Icons.Filled.Add, null,
                                tint = if (subscribed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (subscribed) "Subscribed" else "Subscribe",
                                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                                color = if (subscribed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    "Episodes",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
                )
            }

            if (episodesState.loading) {
                item { Box(Modifier.fillMaxWidth().padding(vertical = 36.dp), contentAlignment = Alignment.Center) { LottieLoader(modifier = Modifier.size(64.dp)) } }
            } else if (episodesState.episodes.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 36.dp), contentAlignment = Alignment.Center) {
                        Text(if (episodesState.failed) "Couldn't load this feed" else "No episodes", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                items(episodesState.episodes.size) { i ->
                    val ep = episodesState.episodes[i]
                    EpisodeRow(ep, onPlay = { onPlay(ep.toSong(showImage)) })
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(episode: PodcastEpisode, onPlay: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp)).clickable(onClick = onPlay).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(episode.title.ifBlank { "Episode" }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(
                episode.pubDateMs.takeIf { it > 0 }?.let { formatDate(it) },
                episode.durationSec.takeIf { it > 0 }?.let { formatDuration(it) },
            ).joinToString(" • ")
            if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Filled.PlayArrow, "Play",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onPlay).padding(9.dp),
        )
    }
}

private fun formatDate(ms: Long): String = runCatching {
    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(ms))
}.getOrDefault("")

private fun formatDuration(sec: Int): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m} min"
        else -> "${sec}s"
    }
}
