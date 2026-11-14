package com.aurora.music.ui.screens.home

import com.aurora.music.localization.appString
import com.aurora.music.ui.theme.paperClip
import com.aurora.music.ui.theme.paperFrame
import com.aurora.music.ui.theme.isPaperTheme
import com.aurora.music.ui.theme.fade
import com.aurora.music.R

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.PlayCircle
import com.aurora.music.ui.theme.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.aurora.music.ui.theme.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aurora.music.data.HomeData
import com.aurora.music.data.HomeFeedItem
import com.aurora.music.data.HomeSection
import com.aurora.music.data.starredQueue
import com.aurora.music.data.TabletSetting
import com.aurora.music.data.ThemeStyle
import com.aurora.music.model.Album
import com.aurora.music.model.Song
import com.aurora.music.model.accent
import com.aurora.music.ui.components.AdaptiveShelf
import com.aurora.music.ui.components.AlbumCard
import com.aurora.music.ui.components.ArtistCircle
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.coverHover
import com.aurora.music.ui.components.Eyebrow
import com.aurora.music.ui.components.LottieLoader
import com.aurora.music.ui.components.PageHeader
import com.aurora.music.ui.components.PageSection
import com.aurora.music.ui.components.PlaylistCard
import com.aurora.music.ui.components.SectionHeader
import com.aurora.music.ui.components.Waveform
import com.aurora.music.ui.components.formatTime
import com.aurora.music.ui.layout.LocalPageGutter
import com.aurora.music.ui.layout.LocalWindowLayout
import com.aurora.music.ui.layout.PageMetrics
import com.aurora.music.ui.layout.pagePadding
import com.aurora.music.ui.screens.stats.RecapInboxButton
import com.aurora.music.ui.theme.LocalUiPrefs
import com.aurora.music.ui.theme.auroraPanel
import com.aurora.music.util.accentFor
import com.aurora.music.viewmodel.HomeUiState
import kotlinx.coroutines.launch
import com.aurora.music.ui.theme.themedCorner

private const val HeroCount = 5
private val FavouritesHeight = 204.dp
private val LikedRowHeight = 48.dp

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
    onOpenNotifications: () -> Unit = {},
    onAddSource: () -> Unit = {},
) {
    val hiddenSongs = com.aurora.music.ui.components.LocalHiddenSongs.current
    val data = androidx.compose.runtime.remember(state.data, hiddenSongs) { state.data.copy(starred = hiddenSongs.visible(state.data.starred)) }
    val ui = LocalUiPrefs.current
    val hidden = ui.hiddenHomeSections
    val gutter = LocalPageGutter.current
    val nothingToShow = !state.loading && state.error == null && data.continuation == null &&
        data.sections.isEmpty() && data.newReleases.isEmpty() && data.recentlyPlayed.isEmpty() && data.playlists.isEmpty() &&
        data.starred.isEmpty() && data.mostPlayed.isEmpty() && data.random.isEmpty() && data.artists.isEmpty()
    val heroItems = if (HomeSection.HERO in hidden) emptyList() else data.newReleases.take(HeroCount)
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = pagePadding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(PageMetrics.SectionGap),
    ) {
        item(key = "header") {
            PageHeader(greeting()) {
                RecapInboxButton(onOpenNotifications)
            }
        }
        if (state.feeds.size > 1) {
            item(key = "feeds") {
                com.aurora.music.ui.components.ChoiceTabs(state.feeds.map { Triple(it.id, it.label, com.aurora.music.ui.components.feedIcon(it.id)) },
                    state.selectedFeed, onSelectFeed)
            }
        }

        if (state.loading && data == HomeData()) {
            item(key = "loading") {
                Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
                    LottieLoader(modifier = Modifier.size(80.dp))
                }
            }
            return@LazyColumn
        }

        if (nothingToShow) {
            item(key = "empty") {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 96.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(88.dp).paperClip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                            .clickable(onClickLabel = appString(R.string.home_add_source), onClick = onAddSource)
                            .pointerHoverIcon(PointerIcon.Hand),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Add, appString(R.string.home_add_source), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                    }
                    Spacer(Modifier.height(20.dp))
                    Text(appString(R.string.home_empty_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(appString(R.string.home_empty_body), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 420.dp))
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = onAddSource) {
                        Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(appString(R.string.home_add_source))
                    }
                }
            }
            return@LazyColumn
        }


        data.sections.forEach { section ->
            item(key = "feed:${section.id}") {
                com.aurora.music.ui.components.FeedSection(section, gutter, onOpenDetail, onPlayAll)
            }
        }
        if (data.continuation != null || state.error != null) {
            item(key = "feed-more") {
                LaunchedEffect(data.continuation) {
                    if (data.continuation != null && state.error == null) onLoadMore()
                }
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (state.loadingMore) CircularProgressIndicator(Modifier.size(28.dp))
                    else OutlinedButton(onClick = if (data.continuation != null) onLoadMore else onRetry) {
                        Text(if (state.error != null) appString(R.string.text_try_again_042c86) else appString(R.string.text_more_recommendations_5fd054))
                    }
                }
            }
        }

        if (heroItems.isNotEmpty()) {
            item(key = "hero") {
                HeroPager(heroItems, ui.tabletHeroScale / TabletSetting.HERO.default, onOpenDetail, onPlayAlbum)
            }
        }

        if (data.recentlyPlayed.isNotEmpty() && HomeSection.RECENT !in hidden) {
            item(key = "recent") {
                PageSection(appString(R.string.text_jump_back_in_1d9181)) {
                    AdaptiveShelf(data.recentlyPlayed, bleed = gutter) { album, width ->
                        OverlayTile(album, width) { onOpenDetail("album", album.id) }
                    }
                }
            }
        }

        if (data.playlists.isNotEmpty() && HomeSection.PLAYLISTS !in hidden) {
            item(key = "playlists") {
                PageSection(appString(R.string.text_your_playlists_df03eb)) {
                    AdaptiveShelf(data.playlists, bleed = gutter) { playlist, width ->
                        PlaylistCard(playlist, onClick = { onOpenDetail("playlist", playlist.id) }, width = width)
                    }
                }
            }
        }

        if (data.starred.isNotEmpty() && HomeSection.FAVOURITE !in hidden) {
            item(key = "favourites") {
                PageSection(
                    appString(R.string.text_from_your_favourites_d39722),
                    action = appString(R.string.text_see_all_2941c5),
                    onAction = { onOpenDetail("liked", "liked") },
                ) {
                    Favourites(data.starred, ui.tabletFavouriteScale) { tapped -> starredQueue(state.allStarred, data.starred, tapped).let { (songs, at) -> onPlayAll(songs, at) } }
                }
            }
        }

        albumShelf("most", appString(R.string.text_most_played_14202e), data.mostPlayed.takeIf { HomeSection.MOST !in hidden }, gutter, onOpenDetail)
        albumShelf("recommended", appString(R.string.text_recommended_albums_36271e), data.random.takeIf { HomeSection.RECOMMENDED !in hidden }, gutter, onOpenDetail)

        if (data.artists.isNotEmpty() && HomeSection.ARTISTS !in hidden) {
            item(key = "artists") {
                PageSection(appString(R.string.text_artists_1528d8)) {
                    AdaptiveShelf(data.artists, bleed = gutter) { artist, width ->
                        ArtistCircle(artist, onClick = { onOpenDetail("artist", artist.id) }, width = width)
                    }
                }
            }
        }

        albumShelf("new", appString(R.string.text_new_releases_3cdd02),
            data.newReleases.drop(heroItems.size).takeIf { HomeSection.NEW !in hidden }, gutter, onOpenDetail)
    }
    }
}

private fun LazyListScope.albumShelf(
    key: String,
    title: String,
    albums: List<Album>?,
    gutter: Dp,
    onOpenDetail: (String, String) -> Unit,
) {
    if (albums.isNullOrEmpty()) return
    item(key = key) {
        PageSection(title) {
            AdaptiveShelf(albums, bleed = gutter) { album, width ->
                AlbumCard(album, onClick = { onOpenDetail("album", album.id) }, width = width)
            }
        }
    }
}

private class HeroPages(private val count: Int) : PageSize {
    override fun Density.calculateMainAxisPageSize(availableSpace: Int, pageSpacing: Int): Int =
        (availableSpace - pageSpacing * (count - 1)) / count
}

@Composable
private fun HeroPager(albums: List<Album>, scale: Float, onOpenDetail: (String, String) -> Unit, onPlayAlbum: (String) -> Unit) {
    val spacing = PageMetrics.ShelfSpacing
    val windowHeight = LocalWindowLayout.current.heightDp.dp
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxWidth().hoverable(interaction)) {
        val perPage = when {
            maxWidth < 900.dp -> 2
            maxWidth < 1400.dp -> 3
            else -> 4
        }
        val pageWidth = (maxWidth - spacing * (perPage - 1)) / perPage
        val tallest = if (windowHeight > 0.dp) (windowHeight * 0.28f).coerceIn(170.dp, 240.dp) else 240.dp
        val height = (pageWidth * 0.52f).coerceIn(170.dp, tallest) * scale
        val pagerState = rememberPagerState(pageCount = { albums.size })
        val stops = (albums.size - perPage + 1).coerceAtLeast(1)
        val pages = remember(perPage) { HeroPages(perPage) }
        val arrowTop = (height / 2 - 18.dp).coerceAtLeast(0.dp)
        Column {
            HorizontalPager(
                state = pagerState,
                pageSize = pages,
                pageSpacing = spacing,
                modifier = Modifier.fillMaxWidth(),
            ) { page ->
                HeroCard(albums[page], height, onOpenDetail, onPlayAlbum)
            }
            if (stops > 1) {
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    val current = pagerState.currentPage.coerceAtMost(stops - 1)
                    repeat(stops) { i ->
                        val active = current == i
                        Box(
                            Modifier.padding(horizontal = 3.dp).height(6.dp)
                                .width(if (active) 22.dp else 6.dp)
                                .paperClip(RoundedCornerShape(50))
                                .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)),
                        )
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = hovered && pagerState.canScrollBackward,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = arrowTop),
        ) {
            HeroArrow(Icons.AutoMirrored.Filled.KeyboardArrowLeft, appString(R.string.text_previous_50f942)) {
                scope.launch { pagerState.animateScrollToPage((pagerState.currentPage - perPage).coerceAtLeast(0)) }
            }
        }
        AnimatedVisibility(
            visible = hovered && pagerState.canScrollForward,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 12.dp, top = arrowTop),
        ) {
            HeroArrow(Icons.AutoMirrored.Filled.KeyboardArrowRight, appString(R.string.text_next_bc9819)) {
                scope.launch { pagerState.animateScrollToPage((pagerState.currentPage + perPage).coerceAtMost(albums.size - 1)) }
            }
        }
    }
}

@Composable
private fun HeroArrow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).paperClip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.fade(0.9f))
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), com.aurora.music.ui.theme.paperSquare(CircleShape))
            .clickable(onClickLabel = label, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun HeroCard(album: Album, height: Dp, onOpenDetail: (String, String) -> Unit, onPlayAlbum: (String) -> Unit) {
    val accent = accentFor(album.id)
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .paperClip(RoundedCornerShape(themedCorner(24.dp)))
            .clickable { onOpenDetail("album", album.id) }
            .pointerHoverIcon(PointerIcon.Hand)
            .coverHover(album.artworkUrl),
    ) {
        Artwork(album.artworkUrl, accent, Modifier.matchParentSize(), corner = 24.dp)
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.30f), Color.Black.copy(alpha = 0.88f)))
            )
        )
        Eyebrow(
            appString(R.string.text_new_release_f0ec22), Color.White.copy(alpha = 0.92f),
            Modifier.align(Alignment.TopStart).padding(16.dp)
                .paperClip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(album.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, color = Color.White,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(album.artist, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(12.dp))
            Box(
                // paper: the site's primary button, rose with an ink rule
                Modifier.size(48.dp).paperFrame(CircleShape, { background(Color.White) }, fill = com.aurora.music.ui.theme.PaperTokens.rose)
                    .clickable(onClickLabel = appString(R.string.text_play_5d12bd)) { onPlayAlbum(album.id) }
                    .pointerHoverIcon(PointerIcon.Hand),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, appString(R.string.text_play_5d12bd), tint = if (isPaperTheme()) com.aurora.music.ui.theme.PaperTokens.ink else Color.Black, modifier = Modifier.size(28.dp))
            }
        }
        if (isPaperTheme()) Box(Modifier.matchParentSize().border(1.dp, com.aurora.music.ui.theme.PaperTokens.ink))
    }
}

@Composable
private fun Favourites(songs: List<Song>, scale: Float, onPlay: (Song) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val rest = songs.drop(1).take(4)
        if (maxWidth >= 1000.dp && rest.isNotEmpty()) {
            val gap = 24.dp
            val cardWidth = ((maxWidth - gap) / 2).coerceAtMost(560.dp)
            Row(Modifier.fillMaxWidth().height(FavouritesHeight), horizontalArrangement = Arrangement.spacedBy(gap)) {
                FavouriteCard(songs, scale, Modifier.width(cardWidth).fillMaxHeight(), onPlay, wave = 64.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    rest.forEach { song -> LikedRow(song) { onPlay(song) } }
                }
            }
        } else {
            FavouriteCard(songs, scale, Modifier.fillMaxWidth(), onPlay)
        }
    }
}

@Composable
private fun FavouriteCard(songs: List<Song>, scale: Float, modifier: Modifier, onPlay: (Song) -> Unit, wave: Dp = 40.dp) {
    val featured = songs.first()
    BoxWithConstraints(
        modifier
            .paperFrame(RoundedCornerShape(themedCorner(20.dp)), {
                background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceContainerHigh, featured.accent.copy(alpha = 0.20f))))
            })
            .clickable { onPlay(featured) }
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(16.dp * scale),
    ) {
        val bars = (maxWidth / 7.dp).toInt().coerceIn(32, 220)
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Artwork(featured.artworkUrl, featured.accent, Modifier.size(56.dp * scale), corner = 14.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Eyebrow(appString(R.string.text_starred_5f1f99), featured.accent)
                    Spacer(Modifier.height(2.dp))
                    Text(featured.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(featured.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Box(Modifier.size(46.dp * scale).paperFrame(CircleShape, { this }, fill = MaterialTheme.colorScheme.primary)
                    .background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.PlayArrow, appString(R.string.text_play_5d12bd), tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp * scale))
                }
            }
            Spacer(Modifier.height(14.dp * scale))
            Spacer(Modifier.weight(1f))
            Waveform(progress = 0.0f, accent = featured.accent, onSeek = {}, seed = featured.id.hashCode(),
                barCount = bars, height = wave * scale, barWidth = 3.dp)
            Spacer(Modifier.height(8.dp * scale))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("0:00", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatTime(featured.durationSec), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun LikedRow(song: Song, onClick: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().height(LikedRowHeight).paperClip(RoundedCornerShape(themedCorner(12.dp)))
            .clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(song.artworkUrl, song.accent, Modifier.size(36.dp), corner = 8.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (song.album.isNotBlank()) {
            Text(song.album, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(0.7f).padding(horizontal = 16.dp))
        }
        Text(if (song.durationSec > 0) formatTime(song.durationSec) else "—", style = MaterialTheme.typography.labelMedium, color = muted)
    }
}

@Composable
private fun OverlayTile(album: Album, width: Dp, onClick: () -> Unit) {
    Box(
        Modifier.size(width).paperClip(RoundedCornerShape(themedCorner(18.dp))).clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
            .coverHover(album.artworkUrl),
    ) {
        Artwork(album.artworkUrl, accentFor(album.id), Modifier.matchParentSize(), corner = 18.dp)
        Box(
            Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
        )
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
            Text(album.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black, color = Color.White,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (album.artist.isNotBlank()) Text(album.artist, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun IconPill(icon: ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).paperFrame(CircleShape, { background(MaterialTheme.colorScheme.surfaceContainerHigh) }).clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp)) }
}

private fun greeting(): String = appString(R.string.text_good_evening_47f6d5).lowercase().replaceFirstChar { it.titlecase() }

