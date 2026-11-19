package com.aurora.music.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aurora.music.R
import com.aurora.music.data.HomeData
import com.aurora.music.data.HomeFeedItem
import com.aurora.music.data.HomeSection
import com.aurora.music.data.TabletSetting
import com.aurora.music.data.ThemeStyle
import com.aurora.music.data.starredQueue
import com.aurora.music.foryou.GeneratedMix
import com.aurora.music.foryou.MIX_COVER_PREFIX
import com.aurora.music.foryou.MIX_KIND
import com.aurora.music.foryou.MixKind
import com.aurora.music.localization.appPlural
import com.aurora.music.localization.appString
import com.aurora.music.localization.asPlaylist
import com.aurora.music.model.Album
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import com.aurora.music.model.accent
import com.aurora.music.ui.components.AdaptiveShelf
import com.aurora.music.ui.components.ArtistCircle
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.Eyebrow
import com.aurora.music.ui.components.FeedSection
import com.aurora.music.ui.components.LocalHiddenSongs
import com.aurora.music.ui.components.LottieLoader
import com.aurora.music.ui.components.PageHeader
import com.aurora.music.ui.components.PageSection
import com.aurora.music.ui.components.PlaylistCard
import com.aurora.music.ui.components.SectionHeader
import com.aurora.music.ui.components.coverHover
import com.aurora.music.ui.layout.LocalPageGutter
import com.aurora.music.ui.layout.PageMetrics
import com.aurora.music.ui.layout.pagePadding
import com.aurora.music.ui.screens.stats.RecapInboxButton
import com.aurora.music.ui.theme.LocalUiPrefs
import com.aurora.music.ui.theme.Motion
import com.aurora.music.ui.theme.PaperTokens
import com.aurora.music.ui.theme.auroraPanel
import com.aurora.music.ui.theme.isPaperTheme
import com.aurora.music.ui.theme.paperClip
import com.aurora.music.ui.theme.paperFrame
import com.aurora.music.ui.theme.pressClickable
import com.aurora.music.util.MotionTokens
import com.aurora.music.util.accentFor
import com.aurora.music.util.rememberDominantColor
import com.aurora.music.viewmodel.HomeUiState
import kotlinx.coroutines.launch
import kotlin.random.Random

private const val RISE_GAP_MS = 70
private const val ENTRANCE_MS = MotionTokens.EMPHASIZED + 3 * RISE_GAP_MS

private val BlockGap = 40.dp
// narrower than this and the two-thirds and one-third blocks stack
private val SplitMinWidth = 860.dp
private val TileHeight = 72.dp
private val TileGap = 12.dp
private val TileMinWidth = 230.dp

internal class Hero(val kind: String, val id: String, val label: String, val title: String, val subtitle: String, val meta: String, val art: String, val accent: Color)

@Composable
internal fun HomeSpotlight(
    contentPadding: PaddingValues,
    state: HomeUiState,
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
) {
    val hiddenSongs = LocalHiddenSongs.current
    val data = remember(state.data, hiddenSongs) { state.data.copy(starred = hiddenSongs.visible(state.data.starred)) }
    val ui = LocalUiPrefs.current
    val hidden = ui.hiddenHomeSections
    val gutter = LocalPageGutter.current
    val colors = MaterialTheme.colorScheme
    val heroes = remember(data.newReleases, data.sections, hidden) { if (HomeSection.HERO in hidden) emptyList() else heroesOf(data) }
    val heroAlbums = if (HomeSection.HERO in hidden) 0 else HeroCount
    val recent = if (HomeSection.RECENT in hidden) emptyList() else data.recentlyPlayed
    val chart = if (HomeSection.MOST in hidden) emptyList() else data.mostPlayed.take(5)
    val starred = if (HomeSection.FAVOURITE in hidden) emptyList() else data.starred
    val (forYou, stations) = remember(mixes, state.selectedFeed) {
        (if (state.selectedFeed == "library") mixes else emptyList()).partition { it.kind != MixKind.STATION }
            .let { (own, artists) -> own.map { it.asPlaylist() } to artists.map { it.asPlaylist() } }
    }
    // one set of mixes rides beside the tiles; the stations follow in a row of their own when both exist
    val (sideTitle, side) = if (forYou.isNotEmpty()) R.string.foryou_title to forYou else R.string.foryou_stations to stations
    val clock = rememberEntrance()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth - gutter * 2
        val split = width >= SplitMinWidth
        val tileColumns = (((width - BlockGap) * 2 / 3 + TileGap) / (TileMinWidth + TileGap)).toInt().coerceAtLeast(1)
        // a column much longer than the grid would leave the tiles' side of the row empty, so it takes the page width instead
        val mixesBeside = split && recent.isNotEmpty() && side.size <= (recent.size + tileColumns - 1) / tileColumns + 2
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = pagePadding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(PageMetrics.SectionGap),
        ) {
            item(key = "header") {
                PageHeader(greeting()) {
                    if (state.feeds.size > 1) Row(
                        Modifier.widthIn(max = (width - 320.dp).coerceAtLeast(0.dp)).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        state.feeds.forEach { feed -> SpotlightPill(feed.label, feed.id == state.selectedFeed) { onSelectFeed(feed.id) } }
                    }
                    RecapInboxButton(onOpenNotifications)
                }
            }

            if (heroes.isNotEmpty()) item(key = "hero") {
                HeroBlock(heroes, width, ui.tabletHeroScale / TabletSetting.HERO.default, Modifier.rise(clock, 0),
                    onOpen = { onOpenDetail(it.kind, it.id) },
                    onPlay = { hero, shuffle -> if (hero.kind == "album" && !shuffle) onPlayAlbum(hero.id) else onPlayCollection(hero.kind, hero.id, shuffle) })
            }

            if (recent.isNotEmpty() || side.isNotEmpty()) item(key = "recent+mixes") {
                TwoThirds(mixesBeside, Modifier.rise(clock, 1),
                    main = if (recent.isEmpty()) null else ({
                        Section(appString(R.string.text_jump_back_in_1d9181)) {
                            TileGrid {
                                recent.forEach { album ->
                                    SpotlightTile(album.artworkUrl, accentFor(album.id), album.title, album.artist, Modifier.spotSurface(MaterialTheme.shapes.medium),
                                        colors.onSurfaceVariant) { onOpenDetail("album", album.id) }
                                }
                            }
                        }
                    }),
                    side = if (side.isEmpty()) null else ({ Mixes(appString(sideTitle), side) { onOpenDetail(MIX_KIND, it) } }),
                )
            }
            if (forYou.isNotEmpty() && stations.isNotEmpty()) item(key = "stations") {
                Box(Modifier.rise(clock, 2)) { Mixes(appString(R.string.foryou_stations), stations) { onOpenDetail(MIX_KIND, it) } }
            }

            if (state.loading && data == HomeData()) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) { LottieLoader(modifier = Modifier.size(80.dp)) }
                }
                return@LazyColumn
            }
            if (state.nothingToShow(data)) {
                item(key = "empty") { HomeEmptyState(onAddSource) }
                return@LazyColumn
            }

            if (chart.isNotEmpty() || starred.isNotEmpty()) item(key = "most+favourites") {
                val beside = split && chart.isNotEmpty() && starred.isNotEmpty()
                TwoThirds(beside, Modifier.rise(clock, 3),
                    main = if (chart.isEmpty()) null else ({
                        Section(appString(R.string.text_most_played_14202e)) {
                            chart.forEachIndexed { i, album -> ChartRow(i + 1, album) { onOpenDetail("album", album.id) } }
                        }
                    }),
                    side = if (starred.isEmpty()) null else ({
                        Section(appString(R.string.text_from_your_favourites_d39722), appString(R.string.text_see_all_2941c5), { onOpenDetail("liked", "liked") }) {
                            Favourites(starred, ui.tabletFavouriteScale, third = beside, stacked = beside || !split) { tapped ->
                                starredQueue(state.allStarred, data.starred, tapped).let { (songs, at) -> onPlayAll(songs, at) }
                            }
                        }
                    }),
                )
            }

            data.sections.forEach { section ->
                item(key = "feed:${section.id}") { FeedSection(section, gutter, onOpenDetail, onPlayAll) }
            }
            if (data.continuation != null || state.error != null) {
                item(key = "feed-more") { HomeFeedMore(state, onLoadMore, onRetry) }
            }

            if (data.playlists.isNotEmpty() && HomeSection.PLAYLISTS !in hidden) {
                item(key = "playlists") {
                    PageSection(appString(R.string.text_your_playlists_df03eb)) {
                        AdaptiveShelf(data.playlists, bleed = gutter) { playlist, card ->
                            PlaylistCard(playlist, onClick = { onOpenDetail("playlist", playlist.id) }, width = card)
                        }
                    }
                }
            }
            albumShelf("recommended", appString(R.string.text_recommended_albums_36271e), data.random.takeIf { HomeSection.RECOMMENDED !in hidden }, gutter, onOpenDetail)
            albumShelf("new", appString(R.string.text_new_releases_3cdd02), data.newReleases.drop(heroAlbums).takeIf { HomeSection.NEW !in hidden }, gutter, onOpenDetail)
            if (data.artists.isNotEmpty() && HomeSection.ARTISTS !in hidden) {
                item(key = "artists") {
                    PageSection(appString(R.string.text_artists_1528d8)) {
                        AdaptiveShelf(data.artists, bleed = gutter) { artist, card ->
                            ArtistCircle(artist, onClick = { onOpenDetail("artist", artist.id) }, width = card)
                        }
                    }
                }
            }
        }
    }
}

internal fun heroesOf(data: HomeData): List<Hero> {
    if (data.newReleases.isNotEmpty()) {
        val label = appString(R.string.text_new_release_f0ec22)
        return data.newReleases.take(HeroCount).map { it.hero(label) }
    }
    val section = data.sections.firstOrNull() ?: return emptyList()
    val label = section.title.uppercase(java.util.Locale.getDefault())
    return listOfNotNull(section.items.firstNotNullOfOrNull { item ->
        when (item) {
            is HomeFeedItem.Record -> item.album.hero(label)
            is HomeFeedItem.Collection -> item.playlist.let {
                Hero("playlist", it.id, label, it.title, it.subtitle, it.songCount.takeIf { n -> n > 0 }?.let { n -> appPlural(R.plurals.track_count, n) }.orEmpty(), it.coverUrl, it.accent)
            }
            else -> null
        }
    })
}

private fun Album.hero(label: String) = Hero("album", id, label, title, artist, meta(), artworkUrl, accentFor(id))

private fun Album.meta(): String =
    listOfNotNull(year.takeIf { it > 0 }?.toString(), songCount.takeIf { it > 0 }?.let { appPlural(R.plurals.track_count, it) }).joinToString(" · ")

// one clock for a page's entrance; blocks that scroll away and come back find it finished
@Composable
internal fun rememberEntrance(): Animatable<Float, AnimationVector1D> {
    val clock = remember { Animatable(0f) }
    LaunchedEffect(clock) { clock.animateTo(1f, tween(ENTRANCE_MS, easing = LinearEasing)) }
    return clock
}

internal fun Modifier.rise(clock: Animatable<Float, AnimationVector1D>, step: Int): Modifier = graphicsLayer {
    val p = Motion.EaseOut.transform(((clock.value * ENTRANCE_MS - step * RISE_GAP_MS) / MotionTokens.EMPHASIZED).coerceIn(0f, 1f))
    alpha = p
    translationY = (1f - p) * MotionTokens.ENTER_RISE_DP.dp.toPx()
}

// the cover's own colour pulled toward the page, so the type on it keeps its contrast in every theme
@Composable
internal fun spotlightTint(artUrl: String, accent: Color = Color.Unspecified): Color {
    val colors = MaterialTheme.colorScheme
    // a generated cover has no picture to sample; transparent stands for no colour yet
    val cover = if (artUrl.isBlank() || artUrl.startsWith(MIX_COVER_PREFIX)) accent.takeOrElse { Color.Transparent } else rememberDominantColor(artUrl, Color.Transparent).value
    val tinted = lerp(colors.background, cover.copy(alpha = 1f), if (colors.background.luminance() < 0.5f) 0.38f else 0.22f)
    return lerp(colors.surfaceContainerHigh, tinted, cover.alpha)
}

// a neutral card: flat on aurora, every other style brings its own panel
@Composable
internal fun Modifier.spotSurface(shape: Shape, outlined: Boolean = false): Modifier {
    val colors = MaterialTheme.colorScheme
    return if (LocalUiPrefs.current.themeStyle != ThemeStyle.AURORA) auroraPanel(shape)
    else clip(shape).background(colors.surface).then(if (outlined) Modifier.border(1.dp, colors.surfaceContainerHigh, shape) else Modifier)
}

@Composable
internal fun SpotlightPill(label: String, selected: Boolean, modifier: Modifier = Modifier, accent: Boolean = false, icon: ImageVector? = null, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val on by animateFloatAsState(if (selected) 1f else 0f, Motion.state(), label = "pill")
    val fill = if (accent) colors.primary else colors.inverseSurface
    val rest = if (accent) fill.copy(alpha = 0f) else colors.surface
    val ink = if (isPaperTheme()) PaperTokens.ink else lerp(colors.onSurface, if (accent) colors.onPrimary else colors.inverseOnSurface, on)
    Box(
        modifier.height(44.dp)
            .paperFrame(CircleShape, {
                background(lerp(rest, fill, on)).then(if (accent) Modifier.border(1.dp, lerp(colors.outlineVariant, fill, on), CircleShape) else Modifier)
            }, fill = if (selected) colors.primary else PaperTokens.paper)
            .semantics { this.selected = selected }
            .clickable(onClickLabel = label, role = Role.Tab, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = if (icon != null) 13.dp else 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Icon(icon, label, tint = ink, modifier = Modifier.size(18.dp))
        else Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, color = ink, maxLines = 1)
    }
}

@Composable
internal fun TwoThirds(beside: Boolean, modifier: Modifier, main: (@Composable () -> Unit)?, side: (@Composable () -> Unit)?) {
    if (beside && main != null && side != null) Row(modifier, horizontalArrangement = Arrangement.spacedBy(BlockGap)) {
        Box(Modifier.weight(2f)) { main() }
        Box(Modifier.weight(1f)) { side() }
    } else Column(modifier, verticalArrangement = Arrangement.spacedBy(PageMetrics.SectionGap)) {
        main?.invoke()
        side?.invoke()
    }
}

// headers that sit side by side share a height, whether or not one carries an action
@Composable
private fun Section(title: String, action: String? = null, onAction: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(title, Modifier.heightIn(min = 32.dp), action, onAction)
        Spacer(Modifier.height(PageMetrics.HeaderToContent))
        content()
    }
}

@Composable
private fun Mixes(title: String, mixes: List<Playlist>, onOpen: (String) -> Unit) {
    Section(title) {
        TileGrid {
            mixes.forEach { mix ->
                val tint = spotlightTint(mix.coverUrl, mix.accent)
                SpotlightTile(mix.coverUrl, mix.accent, mix.title, mix.subtitle, Modifier.paperFrame(MaterialTheme.shapes.medium, { background(tint) }, fill = tint),
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)) { onOpen(mix.id) }
            }
        }
    }
}

// an adaptive grid for a handful of same-height tiles; a plain layout, so it needs no lazy grid inside the page's list
@Composable
internal fun TileGrid(modifier: Modifier = Modifier, minWidth: Dp = TileMinWidth, stretch: Boolean = false, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val gap = TileGap.roundToPx()
        val columns = ((constraints.maxWidth + gap) / (minWidth.roundToPx() + gap)).coerceIn(1, if (stretch) measurables.size.coerceAtLeast(1) else Int.MAX_VALUE)
        val cell = (constraints.maxWidth - gap * (columns - 1)) / columns
        val placeables = measurables.map { it.measure(Constraints.fixedWidth(cell)) }
        val row = placeables.maxOfOrNull { it.height } ?: 0
        val rows = (placeables.size + columns - 1) / columns
        layout(constraints.maxWidth, rows * row + (rows - 1).coerceAtLeast(0) * gap) {
            placeables.forEachIndexed { i, tile -> tile.placeRelative(i % columns * (cell + gap), i / columns * (row + gap)) }
        }
    }
}

@Composable
internal fun SpotlightTile(art: String, accent: Color, title: String, subtitle: String, surface: Modifier, muted: Color, onClick: () -> Unit) {
    Row(
        surface.height(TileHeight).pressClickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand).coverHover(art),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(art, accent, Modifier.size(TileHeight), corner = 0.dp)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HeroBlock(heroes: List<Hero>, width: Dp, scale: Float, modifier: Modifier, onOpen: (Hero) -> Unit, onPlay: (Hero, Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val pager = rememberPagerState(pageCount = { heroes.size })
    val current = heroes.getOrElse(pager.currentPage) { heroes.first() }
    val tint = spotlightTint(current.art, current.accent)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val scope = rememberCoroutineScope()
    val shrink = 1.3f * (width / 1000.dp).coerceIn(0.6f, 1f)
    val title = type.displayLarge.let { it.copy(fontSize = it.fontSize * shrink, lineHeight = it.fontSize * (shrink * 1.08f)) }
    val cover = minOf(236.dp * scale, width * 0.3f)
    val pad = 28.dp
    val button = 50.dp
    // every page is as tall as a two-line title needs, so paging never moves what sits below
    val text = with(LocalDensity.current) { (type.labelSmall.lineHeight.toPx() + title.lineHeight.toPx() * 2 + type.titleLarge.lineHeight.toPx()).toDp() } + 44.dp + button
    Box(modifier.fillMaxWidth().hoverable(interaction).paperFrame(MaterialTheme.shapes.extraLarge, { background(tint) }, fill = tint)) {
        HorizontalPager(pager, contentPadding = PaddingValues(pad), pageSpacing = pad) { page ->
            val hero = heroes[page]
            val soft = colors.onSurface.copy(alpha = 0.85f)
            Row(Modifier.height(maxOf(cover, text)), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(cover).paperClip(MaterialTheme.shapes.large).pressClickable { onOpen(hero) }.pointerHoverIcon(PointerIcon.Hand).coverHover(hero.art)) {
                    Artwork(hero.art, hero.accent, Modifier.matchParentSize(), corner = 0.dp)
                }
                Spacer(Modifier.width(36.dp))
                Column(Modifier.weight(1f)) {
                    Eyebrow(hero.label, soft)
                    Spacer(Modifier.height(10.dp))
                    Text(hero.title, style = title, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(10.dp))
                    Text(listOf(hero.subtitle, hero.meta).filter { it.isNotBlank() }.joinToString(" · "), style = type.titleLarge, fontWeight = FontWeight.Normal, color = soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        // the pill's own ink is what its hover shade is drawn in
                        CompositionLocalProvider(LocalContentColor provides if (isPaperTheme()) PaperTokens.ink else colors.inverseOnSurface) {
                            Row(
                                Modifier.height(button).paperFrame(CircleShape, { background(colors.inverseSurface) }, fill = PaperTokens.rose)
                                    .pressClickable(role = Role.Button) { onPlay(hero, false) }.pointerHoverIcon(PointerIcon.Hand).padding(start = 18.dp, end = 26.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.PlayArrow, null, Modifier.size(22.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(appString(R.string.text_play_5d12bd), style = type.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                        }
                        RoundButton(Icons.Filled.Shuffle, appString(R.string.text_shuffle_5b772b), button) { onPlay(hero, true) }
                        RoundButton(Icons.AutoMirrored.Filled.ArrowForward, appString(R.string.text_details_dc3dec), button) { onOpen(hero) }
                    }
                }
            }
        }
        if (heroes.size > 1) Row(Modifier.align(Alignment.BottomEnd).padding(pad), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(heroes.size) { i ->
                val active = pager.currentPage == i
                val dot by animateDpAsState(if (active) 18.dp else 6.dp, Motion.state(), label = "heroDot")
                val ink by animateColorAsState(colors.onSurface.copy(alpha = if (active) 1f else 0.4f), Motion.state(), label = "heroDotTint")
                Box(Modifier.height(6.dp).width(dot).paperClip(CircleShape).background(ink))
            }
        }
        AnimatedVisibility(hovered && pager.canScrollBackward, Modifier.align(Alignment.CenterStart).padding(start = 12.dp), enter = fadeIn(), exit = fadeOut()) {
            HeroArrow(Icons.AutoMirrored.Filled.KeyboardArrowLeft, appString(R.string.text_previous_50f942)) { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }
        }
        AnimatedVisibility(hovered && pager.canScrollForward, Modifier.align(Alignment.CenterEnd).padding(end = 12.dp), enter = fadeIn(), exit = fadeOut()) {
            HeroArrow(Icons.AutoMirrored.Filled.KeyboardArrowRight, appString(R.string.text_next_bc9819)) { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, size: Dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).paperFrame(CircleShape, { background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)) })
            .pressClickable(onClickLabel = label, role = Role.Button, onClick = onClick).pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun ChartRow(rank: Int, album: Album, onClick: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().height(64.dp).clip(MaterialTheme.shapes.small).clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(rank.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = muted, textAlign = TextAlign.Center, modifier = Modifier.width(26.dp))
        Spacer(Modifier.width(16.dp))
        Artwork(album.artworkUrl, accentFor(album.id), Modifier.size(48.dp), corner = 10.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(album.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(album.artist, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(16.dp))
        Text(album.meta(), style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1)
        if (album.playCount > 0) Text(appPlural(R.plurals.play_count, album.playCount), style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1,
            textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 78.dp).padding(start = 12.dp))
    }
}

@Composable
private fun Favourites(songs: List<Song>, scale: Float, third: Boolean, stacked: Boolean, onPlay: (Song) -> Unit) {
    val rest = songs.drop(1).take(4)
    val card: @Composable () -> Unit = { FavouriteCard(songs.first(), scale) { onPlay(songs.first()) } }
    val rows: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { rest.forEach { song -> LikedRow(song, showAlbum = !third) { onPlay(song) } } }
    }
    if (stacked || rest.isEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        card()
        rows()
    } else Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { card() }
        Box(Modifier.weight(2f)) { rows() }
    }
}

@Composable
private fun FavouriteCard(song: Song, scale: Float, onPlay: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tint = spotlightTint(song.artworkUrl, song.accent)
    val soft = colors.onSurface.copy(alpha = 0.85f)
    val square = isPaperTheme()
    val levels = remember(song.id) { Random(song.id.hashCode()).let { r -> FloatArray(30) { 0.3f + r.nextFloat() * 0.7f } } }
    val bar = colors.onSurface.copy(alpha = 0.3f)
    Column(
        Modifier.fillMaxWidth().paperFrame(MaterialTheme.shapes.large, { background(tint) }, fill = tint)
            .pressClickable(onClick = onPlay).pointerHoverIcon(PointerIcon.Hand).coverHover(song.artworkUrl).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(song.artworkUrl, song.accent, Modifier.size(72.dp * scale), corner = 14.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Eyebrow(appString(R.string.text_starred_5f1f99), soft)
                Spacer(Modifier.height(2.dp))
                Text(song.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(50.dp * scale).paperFrame(CircleShape, { background(colors.inverseSurface) }, fill = PaperTokens.rose), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, appString(R.string.text_play_5d12bd), tint = if (square) PaperTokens.ink else colors.inverseOnSurface, modifier = Modifier.size(24.dp * scale))
            }
        }
        // a still waveform: nothing on this card seeks
        Spacer(Modifier.fillMaxWidth().height(44.dp * scale).drawBehind {
            val step = size.width / levels.size
            val radius = if (square) CornerRadius.Zero else CornerRadius(2.dp.toPx())
            for (i in levels.indices) {
                val h = size.height * levels[i]
                drawRoundRect(bar, Offset(i * step, (size.height - h) / 2f), Size(step * 0.72f, h), radius)
            }
        })
    }
}
