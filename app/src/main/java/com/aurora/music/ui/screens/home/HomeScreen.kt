package com.aurora.music.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aurora.music.data.HomeSection
import com.aurora.music.model.Album
import com.aurora.music.model.Song
import com.aurora.music.ui.components.AlbumCard
import com.aurora.music.ui.components.ArtistCircle
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.Eyebrow
import com.aurora.music.ui.components.PlaylistCard
import com.aurora.music.ui.components.SectionHeader
import com.aurora.music.ui.components.Waveform
import com.aurora.music.ui.components.formatTime
import com.aurora.music.util.accentFor
import com.aurora.music.viewmodel.HomeUiState

@Composable
fun HomeScreen(
    contentPadding: PaddingValues,
    state: HomeUiState,
    username: String,
    avatarUrl: String = "",
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDetail: (String, String) -> Unit,
    onPlayAlbum: (String) -> Unit,
    onPlayAll: (List<Song>, Int) -> Unit,
    onLoadMore: () -> Unit = {},
    onRetry: () -> Unit = {},
    onSelectFeed: (String) -> Unit = {},
    onOpenRecap: (com.aurora.music.data.RecapWindow) -> Unit = {},
) {
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val data = state.data
    val hidden = com.aurora.music.ui.theme.LocalUiPrefs.current.hiddenHomeSections
    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            top = topInset + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(26.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape)
                        .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)))
                        .clickable(onClick = onOpenDrawer),
                    contentAlignment = Alignment.Center,
                ) {
                    if (avatarUrl.isNotBlank()) {
                        com.aurora.music.ui.components.Artwork(avatarUrl, MaterialTheme.colorScheme.primary, Modifier.matchParentSize(), corner = 22.dp)
                    } else {
                        Text(username.take(2).uppercase().ifBlank { "ME" }, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Eyebrow(greeting(), MaterialTheme.colorScheme.primary)
                    Text(username.ifBlank { "Listener" }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                }
                if (state.feeds.size > 1) {
                    val next = state.feeds.first { it.id != state.selectedFeed }
                    IconPill(if (state.selectedFeed == state.feeds.first().id) Icons.Outlined.LibraryMusic else Icons.Outlined.PlayCircle,
                        "Switch to ${next.label} home") { onSelectFeed(next.id) }
                    Spacer(Modifier.width(8.dp))
                }
                com.aurora.music.ui.screens.stats.RecapInboxButton(onOpenRecap)
                Spacer(Modifier.width(8.dp))
                IconPill(Icons.Outlined.Settings, "Settings", onClick = onOpenSettings)
            }
        }

        if (state.loading && data.newReleases.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
                    com.aurora.music.ui.components.LottieLoader(modifier = Modifier.size(80.dp))
                }
            }
            return@LazyColumn
        }

        data.sections.forEach { section ->
            item(key = "feed:${section.id}") {
                Column {
                    SectionHeader(section.title, Modifier.padding(horizontal = 16.dp))
                    if (section.subtitle.isNotBlank()) Text(section.subtitle,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    Spacer(Modifier.height(10.dp))
                    val tracks = section.items.filterIsInstance<com.aurora.music.data.HomeFeedItem.Track>().map { it.song }
                    LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
                        items(section.items.size, key = { section.items[it].key }) { index ->
                            when (val entry = section.items[index]) {
                                is com.aurora.music.data.HomeFeedItem.Record -> AlbumCard(entry.album, { onOpenDetail("album", entry.album.id) })
                                is com.aurora.music.data.HomeFeedItem.Collection -> PlaylistCard(entry.playlist, { onOpenDetail("playlist", entry.playlist.id) })
                                is com.aurora.music.data.HomeFeedItem.Performer -> ArtistCircle(entry.artist, { onOpenDetail("artist", entry.artist.id) })
                                is com.aurora.music.data.HomeFeedItem.Track -> HomeTrackCard(entry.song) {
                                    onPlayAll(tracks, tracks.indexOfFirst { it.id == entry.song.id }.coerceAtLeast(0))
                                }
                            }
                        }
                    }
                }
            }
        }
        if (data.continuation != null || state.error != null) {
            item(key = "feed-more") {
                androidx.compose.runtime.LaunchedEffect(data.continuation) {
                    if (data.continuation != null && state.error == null) onLoadMore()
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (state.loadingMore) androidx.compose.material3.CircularProgressIndicator(Modifier.size(28.dp))
                    else androidx.compose.material3.OutlinedButton(onClick = if (data.continuation != null) onLoadMore else onRetry) {
                        Text(if (state.error != null) "Try again" else "More recommendations")
                    }
                }
            }
        }

        if (data.newReleases.isNotEmpty() && HomeSection.HERO !in hidden) {
            item {
                val heroItems = data.newReleases.take(5)
                val pagerState = rememberPagerState(pageCount = { heroItems.size })
                Column {
                    HorizontalPager(
                        state = pagerState,
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        pageSpacing = 12.dp,
                        modifier = Modifier.fillMaxWidth(),
                    ) { page ->
                        HeroCard(heroItems[page], onOpenDetail, onPlayAlbum)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        repeat(heroItems.size) { i ->
                            val active = pagerState.currentPage == i
                            Box(
                                Modifier.padding(horizontal = 3.dp).height(6.dp)
                                    .width(if (active) 22.dp else 6.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)),
                            )
                        }
                    }
                }
            }
        }

        if (data.recentlyPlayed.isNotEmpty() && HomeSection.RECENT !in hidden) {
            item {
                SectionHeader("Jump back in", Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(12.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(data.recentlyPlayed.size) { i ->
                        val a = data.recentlyPlayed[i]
                        OverlayTile(a.title, a.artworkUrl, accentFor(a.id)) { onOpenDetail("album", a.id) }
                    }
                }
            }
        }

        if (data.playlists.isNotEmpty() && HomeSection.PLAYLISTS !in hidden) {
            item {
                SectionHeader("Your playlists", Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(10.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
                    items(data.playlists.size) { i ->
                        PlaylistCard(data.playlists[i], onClick = { onOpenDetail("playlist", data.playlists[i].id) })
                    }
                }
            }
        }

        val featured = data.starred.firstOrNull()
        if (featured != null && HomeSection.FAVOURITE !in hidden) {
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    SectionHeader("From your favourites")
                    Spacer(Modifier.height(12.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceContainerHigh, featured.accent.copy(alpha = 0.20f))))
                            .clickable { onPlayAll(data.starred, 0) }
                            .padding(16.dp),
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Artwork(featured.artworkUrl, featured.accent, Modifier.size(56.dp), corner = 14.dp)
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Eyebrow("STARRED", featured.accent)
                                    Spacer(Modifier.height(2.dp))
                                    Text(featured.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(featured.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                }
                                Box(Modifier.size(46.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.PlayArrow, "Play", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp))
                                }
                            }
                            Spacer(Modifier.height(14.dp))
                            Waveform(progress = 0.0f, accent = featured.accent, onSeek = {}, seed = featured.id.hashCode(), barCount = 56, height = 40.dp)
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("0:00", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(formatTime(featured.durationSec), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        if (data.mostPlayed.isNotEmpty() && HomeSection.MOST !in hidden) {
            item {
                SectionHeader("Most played", Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(10.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
                    items(data.mostPlayed.size) { i ->
                        AlbumCard(data.mostPlayed[i], onClick = { onOpenDetail("album", data.mostPlayed[i].id) })
                    }
                }
            }
        }

        if (data.random.isNotEmpty() && HomeSection.RECOMMENDED !in hidden) {
            item {
                SectionHeader("Recommended albums", Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(10.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
                    items(data.random.size) { i ->
                        AlbumCard(data.random[i], onClick = { onOpenDetail("album", data.random[i].id) })
                    }
                }
            }
        }

        if (data.artists.isNotEmpty() && HomeSection.ARTISTS !in hidden) {
            item {
                SectionHeader("Artists", Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(10.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
                    items(data.artists.size) { i ->
                        ArtistCircle(data.artists[i], onClick = { onOpenDetail("artist", data.artists[i].id) })
                    }
                }
            }
        }

        if (data.newReleases.isNotEmpty() && HomeSection.NEW !in hidden) {
            item {
                SectionHeader("New releases", Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(10.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
                    items(data.newReleases.size) { i ->
                        AlbumCard(data.newReleases[i], onClick = { onOpenDetail("album", data.newReleases[i].id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroCard(album: Album, onOpenDetail: (String, String) -> Unit, onPlayAlbum: (String) -> Unit) {
    val accent = accentFor(album.id)
    Box(
        Modifier
            .fillMaxWidth()
            .height(210.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable { onOpenDetail("album", album.id) },
    ) {
        Artwork(album.artworkUrl, accent, Modifier.matchParentSize(), corner = 24.dp)
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.30f), Color.Black.copy(alpha = 0.88f)))
            )
        )
        Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
            Eyebrow("NEW RELEASE", accent)
            Spacer(Modifier.height(6.dp))
            Text(album.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(album.artist, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Color.White).clickable { onPlayAlbum(album.id) }.padding(horizontal = 20.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Play", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black, color = Color.Black)
            }
        }
    }
}

@Composable
private fun OverlayTile(title: String, artUrl: String, accent: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(160.dp).clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick),
    ) {
        Artwork(artUrl, accent, Modifier.matchParentSize(), corner = 18.dp)
        Box(
            Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
        )
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Black,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
        )
    }
}

@Composable
internal fun IconPill(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp)) }
}

private fun greeting(): String = "GOOD EVENING"

@Composable
private fun HomeTrackCard(song: Song, onClick: () -> Unit) {
    Column(Modifier.width(172.dp).clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).padding(8.dp)) {
        Box {
            Artwork(song.artworkUrl, song.accent, Modifier.size(156.dp), corner = 14.dp)
            Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).size(34.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, "Play", tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(song.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(song.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
