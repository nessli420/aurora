package com.aurora.music.ui.screens.podcasts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurora.music.data.Podcast
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.LottieLoader
import com.aurora.music.util.accentFor
import com.aurora.music.viewmodel.PodcastViewModel

private val SEED_TERMS = listOf(
    "News", "Comedy", "Technology", "Science", "True Crime",
    "History", "Business", "Sports", "Health", "Music",
)

@Composable
fun PodcastsScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenPodcast: (Podcast) -> Unit,
) {
    val vm: PodcastViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val subs by vm.subscriptions.collectAsStateWithLifecycle()
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // Seed from the VM (which survives navigating into a show & back) so the field and the shown
    // results stay in sync after popping back from a podcast detail page.
    var searchOpen by remember { mutableStateOf(state.query.isNotBlank()) }
    var query by remember { mutableStateOf(state.query) }

    Column(Modifier.fillMaxSize().padding(top = topInset)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, "Back",
                modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onBack).padding(8.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("Podcasts", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Icon(
                if (searchOpen) Icons.Filled.Close else Icons.Filled.Search,
                if (searchOpen) "Close search" else "Search podcasts",
                modifier = Modifier.size(40.dp).clip(CircleShape).clickable {
                    searchOpen = !searchOpen
                    if (!searchOpen) { query = ""; vm.clearSearch() }
                }.padding(8.dp),
            )
        }

        AnimatedVisibility(visible = searchOpen) {
            TextField(
                value = query,
                onValueChange = { query = it; vm.search(it) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                placeholder = { Text("Search shows & topics") },
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.primary) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
        }

        val bottom = contentPadding.calculateBottomPadding() + 24.dp
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = bottom)) {
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(SEED_TERMS.size) { i ->
                        val term = SEED_TERMS[i]
                        Text(
                            term,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable { searchOpen = true; query = term; vm.search(term) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            if (state.query.isBlank() && subs.isNotEmpty()) {
                item { SectionLabel("Subscribed") }
                items(subs.size) { i ->
                    val p = subs[i]
                    PodcastRow(p, onClick = { onOpenPodcast(p) })
                }
            }

            if (state.query.isNotBlank()) {
                item { SectionLabel("Results") }
                if (state.loading) {
                    item { Box(Modifier.fillMaxWidth().padding(vertical = 36.dp), contentAlignment = Alignment.Center) { LottieLoader(modifier = Modifier.size(64.dp)) } }
                } else if (state.results.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 36.dp), contentAlignment = Alignment.Center) {
                            Text(if (state.failed) "Couldn't reach the directory" else "No shows found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    items(state.results.size) { i ->
                        val p = state.results[i]
                        PodcastRow(p, onClick = { onOpenPodcast(p) })
                    }
                }
            } else if (subs.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(top = 60.dp, start = 32.dp, end = 32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "Search for a show or pick a topic above to get started.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 6.dp),
    )
}

@Composable
private fun PodcastRow(podcast: Podcast, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            Artwork(podcast.imageUrl.orEmpty(), accentFor(podcast.feedUrl), Modifier.size(56.dp), corner = 12.dp)
            if (podcast.imageUrl.isNullOrBlank()) Icon(Icons.Filled.Podcasts, null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(podcast.displayTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            podcast.author?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
