package com.aurora.music.ui.screens.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.R
import com.aurora.music.data.HomeData
import com.aurora.music.data.HomeFeedItem
import com.aurora.music.data.HomeFeedSection
import com.aurora.music.data.HomeSection
import com.aurora.music.data.ListeningRecaps
import com.aurora.music.data.PlayHistoryStore
import com.aurora.music.data.Preservation
import com.aurora.music.data.RecapPeriod
import com.aurora.music.data.RecapWindow
import com.aurora.music.data.SignalPath
import com.aurora.music.data.ThemeStyle
import com.aurora.music.data.label
import com.aurora.music.data.starredQueue
import com.aurora.music.foryou.GeneratedMix
import com.aurora.music.foryou.MIX_KIND
import com.aurora.music.foryou.MixKind
import com.aurora.music.localization.AppStrings
import com.aurora.music.localization.appPlural
import com.aurora.music.localization.appString
import com.aurora.music.localization.asPlaylist
import com.aurora.music.localization.localizedMediaType
import com.aurora.music.localization.localizedSignalLabel
import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.Song
import com.aurora.music.model.accent
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.Eyebrow
import com.aurora.music.ui.components.LocalHiddenSongs
import com.aurora.music.ui.components.LottieLoader
import com.aurora.music.ui.components.StatusBarScrim
import com.aurora.music.ui.components.TrackGrid
import com.aurora.music.ui.layout.LocalWindowLayout
import com.aurora.music.ui.screens.library.ShelfCard
import com.aurora.music.ui.screens.stats.RecapInboxButton
import com.aurora.music.ui.screens.stats.listeningDuration
import com.aurora.music.ui.theme.LocalUiPrefs
import com.aurora.music.ui.theme.Motion
import com.aurora.music.ui.theme.PaperTokens
import com.aurora.music.ui.theme.cornerDp
import com.aurora.music.ui.theme.isPaperTheme
import com.aurora.music.ui.theme.paperClip
import com.aurora.music.ui.theme.paperFrame
import com.aurora.music.ui.theme.pressClickable
import com.aurora.music.util.accentFor
import com.aurora.music.viewmodel.HomeUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale

private val WideWidth = 960.dp
private val Gap = 10.dp
private val Inset = 14.dp
private val CardWidth = 112.dp

private class Featured(val label: String, val title: String, val subtitle: String, val art: String, val accent: Color, val mix: String, val item: HomeFeedItem?)

private class Week(val millis: Long, val streak: Int, val days: FloatArray, val today: Int)

@Composable
internal fun HomeConsole(
    contentPadding: PaddingValues,
    state: HomeUiState,
    username: String,
    avatarUrl: String,
    sourceLabel: String,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDetail: (String, String) -> Unit,
    onPlayAlbum: (String) -> Unit,
    onPlayAll: (List<Song>, Int) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onSelectFeed: (String) -> Unit,
    onOpenNotifications: () -> Unit,
    onAddSource: () -> Unit,
    mixes: List<GeneratedMix>,
    onPlayCollection: (kind: String, id: String, shuffle: Boolean) -> Unit,
    onOpenSignalPath: (() -> Unit)?,
    onOpenStats: (() -> Unit)?,
) {
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val hiddenSongs = LocalHiddenSongs.current
    val data = remember(state.data, hiddenSongs) { state.data.copy(starred = hiddenSongs.visible(state.data.starred)) }
    val sections = remember(data.sections, hiddenSongs) {
        if (hiddenSongs.songs.isEmpty()) data.sections
        else data.sections.map { section -> section.copy(items = section.items.filterNot { it is HomeFeedItem.Track && hiddenSongs.hides(it.song) }) }
    }
    val ui = LocalUiPrefs.current
    val hidden = ui.hiddenHomeSections
    val tablet = LocalWindowLayout.current.useNavigationRail
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    val loading = state.loading && data == HomeData()
    val blank = loading || state.nothingToShow(data)
    val hero = remember(data.newReleases, data.sections, hidden) { if (HomeSection.HERO in hidden) null else heroesOf(data).firstOrNull() }
    val chart = remember(data.mostPlayed, hidden) { if (HomeSection.MOST in hidden) emptyList() else data.mostPlayed.take(4) }
    val favourite = data.starred.firstOrNull().takeIf { HomeSection.FAVOURITE !in hidden }
    val (forYou, stations) = remember(mixes, state.selectedFeed) {
        if (state.selectedFeed == "library") mixes.partition { it.kind != MixKind.STATION } else emptyList<GeneratedMix>() to emptyList<GeneratedMix>()
    }
    val featured = remember(forYou, stations, sections, blank) {
        if (blank) null else featuredOf(forYou.firstOrNull() ?: stations.firstOrNull(), sections.firstOrNull())
    }
    val shelfMixes = remember(forYou, stations, featured) {
        listOf(R.string.foryou_title to forYou, R.string.foryou_stations to stations)
            .map { (title, row) -> title to row.filterNot { it.id == featured?.mix }.map { it.asPlaylist() } }
    }
    // feeds and library views are the same choices in the same order; only their ids can differ
    val view = remember(state.feeds, state.selectedFeed) {
        container.repository.libraryViews.getOrNull(state.feeds.indexOfFirst { it.id == state.selectedFeed })?.id
    }
    val likedId = view ?: "liked"
    val showLiked = remember(view, state.data) { container.repository.signedIn(view) }
    val stream by container.streamFormat.collectAsStateWithLifecycle()
    // shortcut: read per track, as collecting it switches the service's spectrum taps on; an output change shows a track late, subscribe once those are gated to the inspector
    val path = remember(stream) { container.signalPath.value }
    val hasHistory = remember { container.playHistory.history.value.isNotEmpty() }
    val week by produceState<Week?>(null) { value = withContext(Dispatchers.Default) { weekOf(container.playHistory) } }
    val showWeek = hasHistory || week != null

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= WideWidth
        val gutter = if (tablet) 24.dp else 16.dp
        val recent = remember(data.recentlyPlayed, hidden, wide) {
            if (HomeSection.RECENT in hidden) emptyList() else data.recentlyPlayed.take(if (wide) 6 else 4)
        }
        val clock = rememberEntrance()
        val heroModule: @Composable (Modifier) -> Unit = { modifier ->
            if (hero != null) HeroModule(hero, wide, (if (wide) 168.dp else 122.dp) * (if (tablet) ui.tabletHeroScale else 1f), modifier,
                onOpen = { onOpenDetail(hero.kind, hero.id) },
                onPlay = { if (hero.kind == "album") onPlayAlbum(hero.id) else onPlayCollection(hero.kind, hero.id, false) })
        }
        val likedModule: @Composable (Modifier) -> Unit = { modifier ->
            LikedModule(state.allStarred.size, modifier, onOpen = { onOpenDetail("liked", likedId) }, onShuffle = { onPlayCollection("liked", likedId, true) })
        }
        val featuredModule: @Composable (Modifier) -> Unit = { modifier ->
            if (featured != null) FeaturedModule(featured, modifier) {
                val item = featured.item
                if (item == null) onOpenDetail(MIX_KIND, featured.mix)
                else sections.firstOrNull()?.let { openFeedItem(item, it, onOpenDetail, onPlayAll) }
            }
        }
        val quickModule: @Composable (Modifier) -> Unit = { modifier -> QuickModule(recent, if (wide) 6 else 4, modifier, onOpenDetail) }
        val signalModule: @Composable (Modifier) -> Unit = { modifier -> SignalModule(path, modifier, onOpenSignalPath) }
        val weekModule: @Composable (Modifier) -> Unit = { modifier -> WeekModule(week, clock, modifier, onOpenStats) }

        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = gutter, end = gutter, top = topInset + 8.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(Gap),
        ) {
            var block = 0
            fun entering(): Modifier = if (block < 3) Modifier.rise(clock, block++) else Modifier

            item(key = "top", contentType = "top") {
                Column(Modifier.padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(Inset)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ConsoleTitle(
                            listOf(state.feeds.firstOrNull { it.id == state.selectedFeed }?.label ?: sourceLabel, username).filter { it.isNotBlank() }.joinToString(" · "),
                            titleCase(greeting()), Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(12.dp))
                        RecapInboxButton(onOpenNotifications)
                        Spacer(Modifier.width(8.dp))
                        HomeAvatar(username, avatarUrl, onOpenDrawer)
                        Spacer(Modifier.width(8.dp))
                        IconPill(Icons.Outlined.Settings, appString(R.string.text_settings_c7f73b), onClick = onOpenSettings)
                    }
                    if (state.feeds.size > 1) ConsoleSegments(state.feeds.size, state.feeds.indexOfFirst { it.id == state.selectedFeed }, { state.feeds[it].label },
                        Modifier.widthIn(max = 560.dp)) { onSelectFeed(state.feeds[it].id) }
                }
            }

            if (!blank) {
                if (wide) {
                    val first = entering()
                    item(key = "r1") {
                        Cells(first) {
                            if (hero != null) heroModule(Modifier.weight(5f).fillMaxHeight())
                            signalModule(Modifier.weight(4f).fillMaxHeight())
                            if (showWeek) weekModule(Modifier.weight(3f).fillMaxHeight())
                        }
                    }
                    if (recent.isNotEmpty() || showLiked || featured != null) {
                        val second = entering()
                        item(key = "r2") {
                            Cells(second) {
                                if (recent.isNotEmpty()) quickModule(Modifier.weight(7f).fillMaxHeight())
                                if (showLiked) likedModule(Modifier.weight(2f).fillMaxHeight())
                                if (featured != null) featuredModule(Modifier.weight(3f).fillMaxHeight())
                            }
                        }
                    }
                } else {
                    if (hero != null) {
                        val enter = entering()
                        item(key = "hero") { heroModule(enter.fillMaxWidth()) }
                    }
                    if (showLiked || featured != null) {
                        val enter = entering()
                        item(key = "tiles") {
                            Cells(enter) {
                                if (showLiked) likedModule(Modifier.weight(1f).fillMaxHeight())
                                if (featured != null) featuredModule(Modifier.weight(1f).fillMaxHeight())
                            }
                        }
                    }
                    if (recent.isNotEmpty()) {
                        val enter = entering()
                        item(key = "quick") { quickModule(enter.fillMaxWidth()) }
                    }
                }
                if (chart.isNotEmpty()) {
                    val enter = entering()
                    item(key = "most") { ChartModule(chart, clock, enter.fillMaxWidth(), onOpenDetail) }
                }
                if (!wide) item(key = "status") {
                    Cells(Modifier) {
                        signalModule(Modifier.weight(1f).fillMaxHeight())
                        if (showWeek) weekModule(Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }

            shelfMixes.forEach { (title, tiles) ->
                if (tiles.isNotEmpty()) item(key = "mixes:$title") {
                    ShelfModule(appString(title)) {
                        items(tiles.size, key = { tiles[it].id }) { i ->
                            val mix = tiles[i]
                            ShelfCard(mix.title, mix.subtitle, mix.coverUrl, mix.accent, width = CardWidth, titleLines = 2) { onOpenDetail(MIX_KIND, mix.id) }
                        }
                    }
                }
            }

            if (loading) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) { LottieLoader(modifier = Modifier.size(80.dp)) }
                }
                return@LazyColumn
            }
            if (blank) {
                item(key = "empty") { HomeEmptyState(onAddSource) }
                return@LazyColumn
            }

            if (data.playlists.isNotEmpty() && HomeSection.PLAYLISTS !in hidden) item(key = "playlists") {
                ShelfModule(appString(R.string.text_your_playlists_df03eb)) {
                    items(data.playlists.size) { i ->
                        val playlist = data.playlists[i]
                        ShelfCard(playlist.title, playlist.subtitle, playlist.coverUrl, playlist.accent, width = CardWidth) { onOpenDetail("playlist", playlist.id) }
                    }
                }
            }
            listOf(
                Triple(HomeSection.RECOMMENDED, R.string.text_recommended_albums_36271e, data.random),
                Triple(HomeSection.NEW, R.string.text_new_releases_3cdd02, data.newReleases),
            ).forEach { (section, title, albums) ->
                if (albums.isNotEmpty() && section !in hidden) item(key = section) {
                    ShelfModule(appString(title)) { items(albums.size) { i -> AlbumTile(albums[i], onOpenDetail) } }
                }
            }
            if (data.artists.isNotEmpty() && HomeSection.ARTISTS !in hidden) item(key = "artists") {
                ShelfModule(appString(R.string.text_artists_1528d8)) { items(data.artists.size) { i -> ArtistTile(data.artists[i], onOpenDetail) } }
            }
            if (favourite != null) item(key = "favourite") {
                FavouriteModule(favourite, if (tablet) ui.tabletFavouriteScale else 1f) {
                    starredQueue(state.allStarred, data.starred, favourite).let { (songs, at) -> onPlayAll(songs, at) }
                }
            }
            sections.forEach { section ->
                if (section.items.isNotEmpty()) item(key = "feed:${section.id}") { FeedModule(section, onOpenDetail, onPlayAll) }
            }
            if (data.continuation != null || state.error != null) {
                item(key = "feed-more") { HomeFeedMore(state, onLoadMore, onRetry, 0.dp) }
            }
        }
        StatusBarScrim(Modifier.align(Alignment.TopCenter))
    }
}

internal fun titleCase(text: String): String {
    val locale = AppStrings.context.resources.configuration.locales[0]
    return text.split(' ').joinToString(" ") { word -> word.lowercase(locale).replaceFirstChar { it.titlecase(locale) } }
}

private fun featuredOf(mix: GeneratedMix?, section: HomeFeedSection?): Featured? {
    if (mix != null) return mix.asPlaylist().let {
        Featured(appString(if (mix.kind == MixKind.STATION) R.string.foryou_stations else R.string.foryou_title), it.title, it.subtitle, it.coverUrl, it.accent, it.id, null)
    }
    val label = section?.title ?: return null
    return when (val item = section.items.getOrNull(1)) {
        is HomeFeedItem.Record -> Featured(label, item.album.title, item.album.artist, item.album.artworkUrl, accentFor(item.album.id), "", item)
        is HomeFeedItem.Collection -> item.playlist.let { Featured(label, it.title, it.subtitle, it.coverUrl, it.accent, "", item) }
        is HomeFeedItem.Performer -> Featured(label, item.artist.name, appString(R.string.text_artist_6c3f3d), item.artist.imageUrl, accentFor(item.artist.id), "", item)
        is HomeFeedItem.Track -> Featured(label, item.song.title, item.song.artist, item.song.artworkUrl, item.song.accent, "", item)
        null -> null
    }
}

private fun openFeedItem(item: HomeFeedItem, section: HomeFeedSection, onOpenDetail: (String, String) -> Unit, onPlayAll: (List<Song>, Int) -> Unit) {
    when (item) {
        is HomeFeedItem.Record -> onOpenDetail("album", item.album.id)
        is HomeFeedItem.Collection -> onOpenDetail("playlist", item.playlist.id)
        is HomeFeedItem.Performer -> onOpenDetail("artist", item.artist.id)
        is HomeFeedItem.Track -> {
            val tracks = section.items.filterIsInstance<HomeFeedItem.Track>().map { it.song }
            onPlayAll(tracks, tracks.indexOfFirst { it.id == item.song.id }.coerceAtLeast(0))
        }
    }
}

private fun weekOf(store: PlayHistoryStore): Week? {
    val history = store.snapshot()
    if (history.isEmpty()) return null
    val today = LocalDate.now()
    val window = RecapWindow.containing(RecapPeriod.WEEK, today)
    val recap = ListeningRecaps.build(history, window)
    val top = (recap.dailyMillis.values.maxOrNull() ?: 0L).coerceAtLeast(1L).toFloat()
    return Week(recap.millis, store.streak().first, FloatArray(7) { (recap.dailyMillis[window.start.plusDays(it.toLong())] ?: 0L) / top },
        (today.toEpochDay() - window.start.toEpochDay()).toInt())
}

@Composable
private fun moduleShape(): Shape {
    val ui = LocalUiPrefs.current
    return if (ui.themeStyle != ThemeStyle.AURORA) MaterialTheme.shapes.large
    else remember(ui.cornerStyle) { RoundedCornerShape(cornerDp(ui.cornerStyle, 22.dp).coerceAtMost(28.dp)) }
}

// the one container every console module sits in, so a theme style restyles them all here
@Composable
internal fun ConsoleModule(
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    padding: PaddingValues = PaddingValues(Inset),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = moduleShape()
    val surface = if (tint.isUnspecified) modifier.spotSurface(shape, MaterialTheme.colorScheme.surface, outlined = true)
        else modifier.paperFrame(shape, { background(tint) }, fill = tint)
    Column(surface.then(if (onClick != null) Modifier.pressClickable(onClick = onClick) else Modifier).padding(padding), content = content)
}

@Composable
internal fun ConsoleLabel(text: String, modifier: Modifier = Modifier) =
    Eyebrow(text.uppercase(Locale.getDefault()), MaterialTheme.colorScheme.onSurfaceVariant, modifier, maxLines = 1)

@Composable
internal fun ConsoleTitle(eyebrow: String, title: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        if (eyebrow.isNotBlank()) ConsoleLabel(eyebrow)
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun ConsoleSegments(count: Int, selected: Int, label: (Int) -> String, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val paper = isPaperTheme()
    val on = when (LocalUiPrefs.current.themeStyle) {
        ThemeStyle.AURORA -> colors.surfaceContainerHigh
        ThemeStyle.PAPER -> colors.primary
        else -> colors.onSurface.copy(alpha = 0.12f)
    }
    Row(
        modifier.fillMaxWidth().spotSurface(MaterialTheme.shapes.medium, colors.surface, outlined = true).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(count) { i ->
            val active = i == selected
            val fill = animateColorAsState(if (active) on else on.copy(alpha = 0f), Motion.state(), label = "segment")
            Box(
                Modifier.weight(if (active) 1.4f else 1f).height(44.dp).paperClip(MaterialTheme.shapes.small).drawBehind { drawRect(fill.value) }
                    .semantics { this.selected = active }.clickable(role = Role.Tab) { onSelect(i) }.padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label(i), style = MaterialTheme.typography.labelLarge, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (paper) PaperTokens.ink else if (active) colors.onSurface else colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Cells(modifier: Modifier, content: @Composable RowScope.() -> Unit) =
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(Gap), content = content)

@Composable
private fun HeroModule(hero: Hero, wide: Boolean, cover: Dp, modifier: Modifier, onOpen: () -> Unit, onPlay: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val soft = colors.onSurface.copy(alpha = 0.85f)
    ConsoleModule(modifier, tint = spotlightTint(hero.art, hero.accent), onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(hero.art, hero.accent, Modifier.size(cover), corner = 16.dp)
            Spacer(Modifier.width(Inset))
            Column(Modifier.weight(1f).heightIn(min = cover), verticalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.padding(bottom = 8.dp)) {
                    Eyebrow(hero.label, soft, maxLines = 1)
                    Spacer(Modifier.height(4.dp))
                    Text(hero.title, style = if (wide) type.headlineMedium else type.titleLarge, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(hero.subtitle, style = type.bodyMedium, color = soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(44.dp).paperFrame(CircleShape, { background(colors.inverseSurface) }, fill = PaperTokens.rose)
                            .pressClickable(role = Role.Button, onClick = onPlay),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.PlayArrow, appString(R.string.text_play_5d12bd), tint = if (isPaperTheme()) PaperTokens.ink else colors.inverseOnSurface, modifier = Modifier.size(20.dp))
                    }
                    if (hero.meta.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(hero.meta, style = type.bodySmall, color = colors.onSurface.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun LikedModule(count: Int, modifier: Modifier, onOpen: () -> Unit, onShuffle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    ConsoleModule(modifier.heightIn(min = 150.dp), onClick = onOpen) {
        Row(Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Favorite, null, tint = colors.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(44.dp).paperFrame(CircleShape, { background(colors.onSurface.copy(alpha = 0.1f)) }).pressClickable(role = Role.Button, onClick = onShuffle),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Shuffle, appString(R.string.text_shuffle_5b772b), tint = colors.onSurface, modifier = Modifier.size(20.dp)) }
        }
        Spacer(Modifier.weight(1f))
        // 0 doubles as not counted yet, so no number rather than a wrong 0
        if (count > 0) Text(count.toString(), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black, maxLines = 1)
        Text(appString(R.string.text_liked_songs_58c3a9), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FeaturedModule(item: Featured, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val scrim = colors.background.copy(alpha = 0.8f)
    ConsoleModule(modifier.heightIn(min = 150.dp), padding = PaddingValues(0.dp), onClick = onClick) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Artwork(item.art, item.accent, Modifier.matchParentSize(), corner = 0.dp)
            Text(item.label.uppercase(Locale.getDefault()), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = colors.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp).paperClip(MaterialTheme.shapes.extraSmall).background(scrim).padding(horizontal = 8.dp, vertical = 3.dp))
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(scrim).padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(item.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (item.subtitle.isNotBlank()) Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun QuickModule(albums: List<Album>, columns: Int, modifier: Modifier, onOpenDetail: (String, String) -> Unit) {
    ConsoleModule(modifier) {
        ConsoleLabel(appString(R.string.text_jump_back_in_1d9181))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            albums.forEach { album ->
                Column(Modifier.weight(1f).paperClip(MaterialTheme.shapes.small).pressClickable { onOpenDetail("album", album.id) }) {
                    Artwork(album.artworkUrl, accentFor(album.id), Modifier.fillMaxWidth().aspectRatio(1f), corner = 12.dp)
                    Spacer(Modifier.height(7.dp))
                    Text(album.title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(album.artist, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            repeat(columns - albums.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun ChartModule(albums: List<Album>, clock: Animatable<Float, AnimationVector1D>, modifier: Modifier, onOpenDetail: (String, String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val square = isPaperTheme()
    val top = albums.maxOf { it.playCount }
    val track = colors.onSurface.copy(alpha = 0.1f)
    ConsoleModule(modifier, padding = PaddingValues(start = 6.dp, end = 6.dp, top = Inset, bottom = 6.dp)) {
        ConsoleLabel(appString(R.string.text_most_played_14202e), Modifier.padding(horizontal = 8.dp))
        Spacer(Modifier.height(4.dp))
        albums.forEach { album ->
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { onOpenDetail("album", album.id) }.heightIn(min = 54.dp).padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(album.artworkUrl, accentFor(album.id), Modifier.size(40.dp), corner = 9.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(album.title, style = type.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (album.playCount > 0) {
                            Spacer(Modifier.width(8.dp))
                            Text(appPlural(R.plurals.play_count, album.playCount), style = type.labelMedium, color = colors.onSurfaceVariant, maxLines = 1)
                        }
                    }
                    if (top <= 0) Text(album.artist, style = type.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    else {
                        val share = album.playCount / top.toFloat()
                        Spacer(Modifier.padding(top = 7.dp).fillMaxWidth().height(4.dp).drawBehind {
                            val radius = if (square) CornerRadius.Zero else CornerRadius(size.height / 2f)
                            val width = size.width * share * Motion.EaseOut.transform(clock.value)
                            drawRoundRect(track, cornerRadius = radius)
                            drawRoundRect(colors.primary, Offset(if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f, 0f), Size(width, size.height), radius)
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun SignalModule(path: SignalPath, modifier: Modifier, onClick: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    ConsoleModule(modifier.heightIn(min = 158.dp), onClick = onClick) {
        ConsoleLabel(appString(R.string.text_signal_path_c3e29b))
        if (!path.active) {
            Spacer(Modifier.weight(1f))
            Text(appString(R.string.text_nothing_playing_13ae37), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(appString(R.string.signal_start_playback), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            return@ConsoleModule
        }
        val format = remember(path.codec, path.sampleRateHz, path.bitDepth, path.source) {
            listOfNotNull(
                path.codec.takeIf { it.isNotBlank() },
                path.sampleRateHz.takeIf { it > 0 }?.let { appString(R.string.text_1f_khz_92ed69).format(it / 1000f) },
                path.bitDepth.takeIf { it > 0 }?.let { appString(R.string.text_bit_fd7850, it) },
            ).joinToString(" · ").ifBlank { path.source.detail }
        }
        PathNode(format, "Source".localizedSignalLabel())
        PathNode(appString(when (path.preservation) {
            Preservation.PRESERVED -> R.string.text_samples_preserved_091001
            Preservation.MODIFIED -> R.string.text_samples_modified_d04e9f
            Preservation.UNKNOWN -> R.string.text_sample_preservation_unknown_7de2de
        }), "Processing".localizedSignalLabel())
        PathNode(path.output.localizedSignalLabel().ifBlank { appString(R.string.text_output_unknown_ef4fdb) }, "Output".localizedSignalLabel())
    }
}

@Composable
private fun PathNode(title: String, label: String) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.padding(top = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).paperClip(CircleShape).background(colors.primary))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun WeekModule(week: Week?, clock: Animatable<Float, AnimationVector1D>, modifier: Modifier, onClick: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val square = isPaperTheme()
    val rest = colors.onSurface.copy(alpha = 0.22f)
    ConsoleModule(modifier.heightIn(min = 158.dp), onClick = onClick) {
        ConsoleLabel(RecapPeriod.WEEK.label)
        if (week == null) return@ConsoleModule
        Spacer(Modifier.height(6.dp))
        Text(listeningDuration(week.millis), style = type.titleLarge, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (week.streak > 0) appString(R.string.text_day_streak_661711, week.streak) else appString(R.string.text_no_active_streak_7f1a27),
            style = type.labelMedium, fontWeight = FontWeight.SemiBold, color = colors.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.padding(top = 8.dp).fillMaxWidth().weight(1f).heightIn(min = 40.dp).drawBehind {
            val gap = 6.dp.toPx()
            val bar = (size.width - gap * 6) / 7
            val radius = if (square) CornerRadius.Zero else CornerRadius(3.dp.toPx())
            val grown = Motion.EaseOut.transform(clock.value)
            for (day in 0 until 7) {
                val height = maxOf(4.dp.toPx(), size.height * week.days[day] * grown)
                drawRoundRect(if (day == week.today) colors.primary else rest, Offset(day * (bar + gap), size.height - height), Size(bar, height), radius)
            }
        })
    }
}

@Composable
private fun TitledModule(title: String, subtitle: String, body: @Composable ColumnScope.() -> Unit) {
    ConsoleModule(Modifier.fillMaxWidth(), padding = PaddingValues(vertical = Inset)) {
        ConsoleLabel(title, Modifier.padding(horizontal = Inset))
        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = Inset, end = Inset, top = 2.dp))
        Spacer(Modifier.height(8.dp))
        body()
    }
}

@Composable
private fun ShelfModule(title: String, subtitle: String = "", content: LazyListScope.() -> Unit) = TitledModule(title, subtitle) {
    LazyRow(contentPadding = PaddingValues(horizontal = Inset - 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), content = content)
}

@Composable
private fun AlbumTile(album: Album, onOpenDetail: (String, String) -> Unit) {
    val label = album.typeLabel.localizedMediaType()
    ShelfCard(album.title, album.artist, album.artworkUrl, accentFor(album.id),
        badge = if (label == appString(R.string.text_album_dfb4c9)) "" else label.uppercase(Locale.getDefault()), width = CardWidth) { onOpenDetail("album", album.id) }
}

@Composable
private fun ArtistTile(artist: Artist, onOpenDetail: (String, String) -> Unit) =
    ShelfCard(artist.name, appString(R.string.text_artist_6c3f3d), artist.imageUrl, accentFor(artist.id), circle = true, width = CardWidth, centered = true) {
        onOpenDetail("artist", artist.id)
    }

@Composable
private fun FeedModule(section: HomeFeedSection, onOpenDetail: (String, String) -> Unit, onPlayAll: (List<Song>, Int) -> Unit) {
    val tracks = remember(section) { section.items.filterIsInstance<HomeFeedItem.Track>().map { it.song } }
    if (tracks.size == section.items.size) TitledModule(section.title, section.subtitle) {
        TrackGrid(tracks, Inset) { index -> onPlayAll(tracks, index) }
    } else ShelfModule(section.title, section.subtitle) {
        items(section.items.size, key = { section.items[it].key }) { index ->
            when (val entry = section.items[index]) {
                is HomeFeedItem.Record -> AlbumTile(entry.album, onOpenDetail)
                is HomeFeedItem.Collection -> entry.playlist.let { ShelfCard(it.title, it.subtitle, it.coverUrl, it.accent, width = CardWidth) { onOpenDetail("playlist", it.id) } }
                is HomeFeedItem.Performer -> ArtistTile(entry.artist, onOpenDetail)
                is HomeFeedItem.Track -> entry.song.let {
                    ShelfCard(it.title, it.artist, it.artworkUrl, it.accent, width = CardWidth) { openFeedItem(entry, section, onOpenDetail, onPlayAll) }
                }
            }
        }
    }
}

@Composable
private fun FavouriteModule(song: Song, scale: Float, onPlay: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    ConsoleModule(Modifier.fillMaxWidth(), onClick = onPlay) {
        ConsoleLabel(appString(R.string.text_from_your_favourites_d39722))
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(song.artworkUrl, song.accent, Modifier.size(52.dp * scale), corner = 12.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(44.dp * scale).paperFrame(CircleShape, { background(colors.primary) }, fill = colors.primary), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, appString(R.string.text_play_5d12bd), tint = colors.onPrimary, modifier = Modifier.size(22.dp * scale))
            }
        }
    }
}
