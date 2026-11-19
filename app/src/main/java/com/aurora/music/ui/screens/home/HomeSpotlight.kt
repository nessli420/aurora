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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aurora.music.R
import com.aurora.music.data.HomeData
import com.aurora.music.data.HomeFeedItem
import com.aurora.music.data.HomeSection
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
import com.aurora.music.ui.components.AlbumCard
import com.aurora.music.ui.components.ArtistCircle
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.Eyebrow
import com.aurora.music.ui.components.FeedSection
import com.aurora.music.ui.components.LocalHiddenSongs
import com.aurora.music.ui.components.LottieLoader
import com.aurora.music.ui.components.PlaylistCard
import com.aurora.music.ui.components.SectionHeader
import com.aurora.music.ui.components.StatusBarScrim
import com.aurora.music.ui.layout.LocalWindowLayout
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
import com.aurora.music.util.rememberDominantTarget
import com.aurora.music.viewmodel.HomeUiState
import kotlin.random.Random

private const val RISE_GAP_MS = 70
private const val ENTRANCE_MS = MotionTokens.EMPHASIZED + 2 * RISE_GAP_MS

private val ShelfWidths = listOf(168.dp, 144.dp)

internal class Hero(val kind: String, val id: String, val label: String, val title: String, val subtitle: String, val meta: String, val art: String, val accent: Color)

@Composable
internal fun HomeSpotlight(
    contentPadding: PaddingValues,
    state: HomeUiState,
    username: String,
    avatarUrl: String,
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
) {
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val hiddenSongs = LocalHiddenSongs.current
    val data = remember(state.data, hiddenSongs) { state.data.copy(starred = hiddenSongs.visible(state.data.starred)) }
    val ui = LocalUiPrefs.current
    val hidden = ui.hiddenHomeSections
    val tablet = LocalWindowLayout.current.useNavigationRail
    val gutter = if (tablet) 24.dp else 20.dp
    val loading = state.loading && data == HomeData()
    val heroes = remember(data.newReleases, data.sections, hidden) { if (HomeSection.HERO in hidden) emptyList() else heroesOf(data) }
    val recent = remember(data.recentlyPlayed, hidden) { if (HomeSection.RECENT in hidden) emptyList() else data.recentlyPlayed.take(6) }
    val chart = remember(data.mostPlayed, hidden) { if (HomeSection.MOST in hidden) emptyList() else data.mostPlayed.take(5) }
    val featured = data.starred.firstOrNull().takeIf { HomeSection.FAVOURITE !in hidden }
    val (forYou, stations) = remember(mixes, state.selectedFeed) {
        if (state.selectedFeed == "library") mixes.partition { it.kind != MixKind.STATION } else emptyList<GeneratedMix>() to emptyList<GeneratedMix>()
    }
    val beside = tablet && recent.isNotEmpty() && forYou.isNotEmpty()
    val clock = rememberEntrance()
    val listState = rememberLazyListState()
    Box {
        LazyColumn(
            Modifier.fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            var block = 0
            fun entering(): Modifier = if (block < 2) Modifier.rise(clock, ++block) else Modifier

            item(key = "top", contentType = "top") {
                SpotlightTop(state, username, avatarUrl, heroes, tablet, gutter, topInset, if (tablet) ui.tabletHeroScale else 1f, clock,
                    onOpenDrawer, onOpenSettings, onOpenNotifications, onSelectFeed,
                    onOpen = { onOpenDetail(it.kind, it.id) },
                    onPlay = { hero, shuffle -> if (hero.kind == "album" && !shuffle) onPlayAlbum(hero.id) else onPlayCollection(hero.kind, hero.id, shuffle) })
            }

            if (beside) {
                val enter = entering()
                item(key = "recent+mixes") {
                    val tiles = remember(forYou) { forYou.map { it.asPlaylist() } }
                    Row(enter.padding(horizontal = gutter), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(Modifier.weight(2f)) {
                            SectionHeader(appString(R.string.text_jump_back_in_1d9181))
                            Spacer(Modifier.height(14.dp))
                            QuickGrid(recent, 2, 72.dp, onOpenDetail)
                        }
                        Column(Modifier.weight(1f)) {
                            SectionHeader(appString(R.string.foryou_title))
                            Spacer(Modifier.height(14.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                tiles.forEach { mix -> MixCard(mix, Modifier.fillMaxWidth()) { onOpenDetail(MIX_KIND, mix.id) } }
                            }
                        }
                    }
                }
            }
            listOf(R.string.foryou_title to forYou.takeIf { !beside }.orEmpty(), R.string.foryou_stations to stations).forEach { (title, row) ->
                if (row.isNotEmpty()) {
                    val enter = entering()
                    item(key = "mixes:$title") {
                        val tiles = remember(row) { row.map { it.asPlaylist() } }
                        Column(enter) {
                            SectionHeader(appString(title), Modifier.padding(horizontal = gutter))
                            Spacer(Modifier.height(14.dp))
                            LazyRow(contentPadding = PaddingValues(horizontal = gutter), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(tiles.size, key = { tiles[it].id }) { i -> MixCard(tiles[i], Modifier.width(216.dp)) { onOpenDetail(MIX_KIND, tiles[i].id) } }
                            }
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
            if (state.nothingToShow(data)) {
                item(key = "empty") { HomeEmptyState(onAddSource) }
                return@LazyColumn
            }

            if (recent.isNotEmpty() && !beside) {
                val enter = entering()
                item(key = "recent") {
                    Column(enter.padding(horizontal = gutter)) {
                        SectionHeader(appString(R.string.text_jump_back_in_1d9181))
                        Spacer(Modifier.height(14.dp))
                        QuickGrid(recent, if (tablet) 3 else 2, if (tablet) 72.dp else 58.dp, onOpenDetail)
                    }
                }
            }

            val mostPlayed: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier) {
                    SectionHeader(appString(R.string.text_most_played_14202e), Modifier.padding(horizontal = 8.dp))
                    Spacer(Modifier.height(8.dp))
                    chart.forEachIndexed { i, album -> ChartRow(i + 1, album) { onOpenDetail("album", album.id) } }
                }
            }
            val favourite: @Composable (Modifier, Song) -> Unit = { modifier, song ->
                Column(modifier) {
                    SectionHeader(appString(R.string.text_from_your_favourites_d39722))
                    Spacer(Modifier.height(14.dp))
                    FavouriteCard(song, if (tablet) ui.tabletFavouriteScale else 1f) {
                        starredQueue(state.allStarred, data.starred, song).let { (songs, at) -> onPlayAll(songs, at) }
                    }
                }
            }
            if (tablet && chart.isNotEmpty() && featured != null) {
                val enter = entering()
                item(key = "most+favourite") {
                    Row(enter.padding(start = gutter - 8.dp, end = gutter), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        mostPlayed(Modifier.weight(2f))
                        favourite(Modifier.weight(1f), featured)
                    }
                }
            } else {
                if (chart.isNotEmpty()) {
                    val enter = entering()
                    item(key = "most") { mostPlayed(enter.padding(horizontal = gutter - 8.dp)) }
                }
                if (featured != null) {
                    val enter = entering()
                    item(key = "favourite") { favourite(enter.padding(horizontal = gutter), featured) }
                }
            }

            data.sections.forEach { section ->
                item(key = "feed:${section.id}") { FeedSection(section, onOpenDetail, onPlayAll, gutter) }
            }
            if (data.continuation != null || state.error != null) {
                item(key = "feed-more") { HomeFeedMore(state, onLoadMore, onRetry, gutter) }
            }

            var shelf = 0
            if (data.playlists.isNotEmpty() && HomeSection.PLAYLISTS !in hidden) {
                val width = ShelfWidths[shelf++ % 2]
                item(key = "playlists") {
                    Shelf(appString(R.string.text_your_playlists_df03eb), gutter) {
                        items(data.playlists.size) { i -> PlaylistCard(data.playlists[i], onClick = { onOpenDetail("playlist", data.playlists[i].id) }, width = width) }
                    }
                }
            }
            listOf(
                Triple(HomeSection.RECOMMENDED, R.string.text_recommended_albums_36271e, data.random),
                Triple(HomeSection.NEW, R.string.text_new_releases_3cdd02, data.newReleases),
            ).forEach { (section, title, albums) ->
                if (albums.isNotEmpty() && section !in hidden) {
                    val width = ShelfWidths[shelf++ % 2]
                    item(key = section) {
                        Shelf(appString(title), gutter) {
                            items(albums.size) { i -> AlbumCard(albums[i], onClick = { onOpenDetail("album", albums[i].id) }, width = width) }
                        }
                    }
                }
            }
            if (data.artists.isNotEmpty() && HomeSection.ARTISTS !in hidden) {
                item(key = "artists") {
                    Shelf(appString(R.string.text_artists_1528d8), gutter) {
                        items(data.artists.size) { i -> ArtistCircle(data.artists[i], onClick = { onOpenDetail("artist", data.artists[i].id) }) }
                    }
                }
            }
        }
        TopScrim(always = tablet || heroes.isEmpty(), listState)
    }
}

// the tinted block is backdrop enough for the status bar; the scrim returns once it has scrolled away
@Composable
private fun BoxScope.TopScrim(always: Boolean, list: LazyListState) {
    val pastTop by remember(list) { derivedStateOf { list.firstVisibleItemIndex > 0 } }
    AnimatedVisibility(always || pastTop, Modifier.align(Alignment.TopCenter), enter = fadeIn(Motion.enter()), exit = fadeOut(Motion.exit())) {
        StatusBarScrim()
    }
}

internal fun heroesOf(data: HomeData): List<Hero> {
    if (data.newReleases.isNotEmpty()) {
        val label = appString(R.string.text_new_release_f0ec22)
        return data.newReleases.take(5).map { it.hero(label) }
    }
    val section = data.sections.firstOrNull() ?: return emptyList()
    val label = section.title.uppercase(java.util.Locale.getDefault())
    return listOfNotNull(section.items.firstNotNullOfOrNull { item ->
        when (item) {
            is HomeFeedItem.Record -> item.album.hero(label)
            is HomeFeedItem.Collection -> item.playlist.let {
                Hero("playlist", it.id, label, it.title, it.subtitle, if (it.songCount > 0) appPlural(R.plurals.track_count, it.songCount) else "", it.coverUrl, it.accent)
            }
            else -> null
        }
    })
}

private fun Album.hero(label: String) = Hero("album", id, label, title, artist,
    listOfNotNull(year.takeIf { it > 0 }?.toString(), songCount.takeIf { it > 0 }?.let { appPlural(R.plurals.track_count, it) }).joinToString(" · "),
    artworkUrl, accentFor(id))

// one clock for a page's entrance; blocks that scroll away and come back find it finished
@Composable
internal fun rememberEntrance(): Animatable<Float, AnimationVector1D> {
    val clock = remember { Animatable(0f) }
    LaunchedEffect(clock) { clock.animateTo(1f, tween(ENTRANCE_MS, easing = LinearEasing)) }
    return clock
}

internal fun entrance(clock: Float, step: Int): Float =
    Motion.EaseOut.transform(((clock * ENTRANCE_MS - step * RISE_GAP_MS) / MotionTokens.EMPHASIZED).coerceIn(0f, 1f))

internal fun Modifier.rise(clock: Animatable<Float, AnimationVector1D>, step: Int): Modifier = graphicsLayer {
    val p = entrance(clock.value, step)
    alpha = p
    translationY = (1f - p) * 16.dp.toPx()
}

// the cover's own colour pulled toward the page, so the type on it keeps its contrast in every theme
@Composable
internal fun spotlightTint(artUrl: String, accent: Color = Color.Unspecified): Color {
    val colors = MaterialTheme.colorScheme
    // a generated cover has no picture to sample
    val cover = if (artUrl.isBlank() || artUrl.startsWith(MIX_COVER_PREFIX)) accent else rememberDominantTarget(artUrl, Color.Unspecified).value
    return if (cover.isUnspecified) colors.surfaceContainerHigh
    else lerp(colors.background, cover, if (colors.background.luminance() < 0.5f) 0.38f else 0.22f)
}

@Composable
internal fun Modifier.spotSurface(shape: Shape, fill: Color, outlined: Boolean = false): Modifier =
    if (LocalUiPrefs.current.themeStyle != ThemeStyle.AURORA) auroraPanel(shape)
    else clip(shape).background(fill).then(if (outlined) Modifier.border(1.dp, MaterialTheme.colorScheme.surfaceContainerHigh, shape) else Modifier)

@Composable
internal fun SpotlightPill(label: String, selected: Boolean, modifier: Modifier = Modifier, accent: Boolean = false, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val paper = isPaperTheme()
    val on by animateFloatAsState(if (selected) 1f else 0f, Motion.state(), label = "pill")
    val fill = if (accent) colors.primary else colors.inverseSurface
    val rest = if (accent) fill.copy(alpha = 0f) else colors.onSurface.copy(alpha = 0.1f)
    Box(
        modifier.height(44.dp)
            .paperFrame(CircleShape, {
                background(lerp(rest, fill, on)).then(if (accent) Modifier.border(1.dp, lerp(colors.outlineVariant, fill, on), CircleShape) else Modifier)
            }, fill = if (selected) colors.primary else PaperTokens.paper)
            .semantics { this.selected = selected }
            .combinedClickable(role = Role.Tab, onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1,
            color = if (paper) PaperTokens.ink else lerp(colors.onSurface, if (accent) colors.onPrimary else colors.inverseOnSurface, on))
    }
}

@Composable
private fun SpotlightTop(
    state: HomeUiState,
    username: String,
    avatarUrl: String,
    heroes: List<Hero>,
    tablet: Boolean,
    gutter: Dp,
    topInset: Dp,
    heroScale: Float,
    clock: Animatable<Float, AnimationVector1D>,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNotifications: () -> Unit,
    onSelectFeed: (String) -> Unit,
    onOpen: (Hero) -> Unit,
    onPlay: (Hero, Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pager = rememberPagerState(pageCount = { heroes.size })
    val current = heroes.getOrNull(pager.currentPage) ?: heroes.firstOrNull()
    val tint = animateColorAsState(if (current == null) colors.background else spotlightTint(current.art, current.accent), Motion.state(MotionTokens.SLOW), label = "heroTint")
    val paper = isPaperTheme()
    // read while drawing, so a page change repaints the block without recomposing it
    fun Modifier.tinted(shape: Shape) = clip(shape).drawBehind { drawRect(tint.value) }
    val bleeds = current != null && !tablet
    Column(
        Modifier.fillMaxWidth()
            .then(if (bleeds) Modifier.tinted(MaterialTheme.shapes.extraLarge.copy(topStart = ZeroCornerSize, topEnd = ZeroCornerSize)) else Modifier)
            .padding(top = topInset + 8.dp, bottom = if (bleeds) 20.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = gutter), verticalAlignment = Alignment.CenterVertically) {
            HomeAvatar(username, avatarUrl, onOpenDrawer)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Eyebrow(greeting(), if (bleeds) colors.onSurface.copy(alpha = 0.85f) else colors.primary)
                Text(username.ifBlank { appString(R.string.text_listener_37ea46) }, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            RecapInboxButton(onOpenNotifications)
            Spacer(Modifier.width(8.dp))
            IconPill(Icons.Outlined.Settings, appString(R.string.text_settings_c7f73b), onClick = onOpenSettings)
        }
        if (state.feeds.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = gutter), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.feeds.forEach { feed -> SpotlightPill(feed.label, feed.id == state.selectedFeed) { onSelectFeed(feed.id) } }
            }
        }
        if (current != null) {
            val pad = if (tablet) 28.dp else gutter
            val button = if (tablet) 50.dp else 48.dp
            Box(
                if (tablet) Modifier.padding(horizontal = gutter).tinted(MaterialTheme.shapes.extraLarge)
                    .then(if (paper) Modifier.border(1.dp, PaperTokens.ink) else Modifier).padding(vertical = pad)
                else Modifier,
            ) {
                HorizontalPager(pager, contentPadding = PaddingValues(horizontal = pad), pageSpacing = pad, verticalAlignment = Alignment.Bottom) { page ->
                    val hero = heroes[page]
                    HeroPage(hero, tablet, (if (tablet) 236.dp else 132.dp) * heroScale, button, clock, onOpen = { onOpen(hero) }, onPlay = { onPlay(hero, it) })
                }
                if (heroes.size > 1) Row(
                    Modifier.align(Alignment.BottomEnd).padding(end = pad).height(button),
                    horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(heroes.size) { i ->
                        val active = pager.currentPage == i
                        val width by animateDpAsState(if (active) 18.dp else 5.dp, Motion.state(), label = "heroDot")
                        val dot by animateColorAsState(colors.onSurface.copy(alpha = if (active) 1f else 0.4f), Motion.state(), label = "heroDotTint")
                        Box(Modifier.height(5.dp).width(width).paperClip(CircleShape).background(dot))
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroPage(hero: Hero, wide: Boolean, cover: Dp, button: Dp, clock: Animatable<Float, AnimationVector1D>, onOpen: () -> Unit, onPlay: (shuffle: Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val soft = colors.onSurface.copy(alpha = 0.85f)
    val art: @Composable () -> Unit = {
        Box(
            Modifier.size(cover)
                .graphicsLayer { val p = entrance(clock.value, 0); scaleX = 0.92f + 0.08f * p; scaleY = 0.92f + 0.08f * p }
                .paperClip(MaterialTheme.shapes.large).pressClickable(onClick = onOpen),
        ) { Artwork(hero.art, hero.accent, Modifier.matchParentSize(), corner = 0.dp) }
    }
    val text: @Composable () -> Unit = {
        Eyebrow(hero.label, soft)
        Spacer(Modifier.height(6.dp))
        Text(hero.title, style = if (wide) type.displayLarge else type.headlineLarge, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        Text(hero.subtitle, style = if (wide) type.titleLarge else type.bodyMedium, color = soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (hero.meta.isNotBlank()) Text(hero.meta, style = if (wide) type.bodyMedium else type.bodySmall, color = colors.onSurface.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    val buttons: @Composable () -> Unit = {
        val ink = if (isPaperTheme()) PaperTokens.ink else colors.inverseOnSurface
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.height(button).paperFrame(CircleShape, { background(colors.inverseSurface) }, fill = PaperTokens.rose)
                    .pressClickable(role = Role.Button) { onPlay(false) }.padding(start = 16.dp, end = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.PlayArrow, null, tint = ink, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(appString(R.string.text_play_5d12bd), style = type.labelLarge, fontWeight = FontWeight.Bold, color = ink, maxLines = 1)
            }
            RoundButton(Icons.Filled.Shuffle, appString(R.string.text_shuffle_5b772b), button) { onPlay(true) }
            RoundButton(Icons.AutoMirrored.Filled.ArrowForward, appString(R.string.text_details_dc3dec), button, onOpen)
        }
    }
    if (wide) Row(Modifier.rise(clock, 0), verticalAlignment = Alignment.Bottom) {
        art()
        Spacer(Modifier.width(36.dp))
        Column(Modifier.weight(1f)) {
            text()
            Spacer(Modifier.height(24.dp))
            buttons()
        }
    } else Column(Modifier.rise(clock, 0)) {
        Row(verticalAlignment = Alignment.Bottom) {
            art()
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) { text() }
        }
        Spacer(Modifier.height(18.dp))
        buttons()
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, size: Dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).paperFrame(CircleShape, { background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)) })
            .pressClickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun MixCard(mix: Playlist, modifier: Modifier, onClick: () -> Unit) {
    val tint = spotlightTint(mix.coverUrl, mix.accent)
    Row(
        modifier.height(76.dp).paperFrame(MaterialTheme.shapes.medium, { background(tint) }, fill = tint).pressClickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(mix.coverUrl, mix.accent, Modifier.size(76.dp), corner = 0.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            // mix names run long in some languages and have no artwork to tell them apart
            Text(mix.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(mix.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// six tiles at most, so plain rows do and no nested lazy grid is needed
@Composable
internal fun QuickGrid(albums: List<Album>, columns: Int, height: Dp, onOpenDetail: (String, String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (start in albums.indices step columns) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (i in start until start + columns) {
                val album = albums.getOrNull(i)
                if (album == null) Spacer(Modifier.weight(1f))
                else Row(
                    Modifier.weight(1f).height(height).spotSurface(MaterialTheme.shapes.small, MaterialTheme.colorScheme.surfaceContainer)
                        .pressClickable { onOpenDetail("album", album.id) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(album.artworkUrl, accentFor(album.id), Modifier.size(height), corner = 0.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(album.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(album.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChartRow(rank: Int, album: Album, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onClick).height(62.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(rank.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = colors.onSurfaceVariant.copy(alpha = 0.8f),
            textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 22.dp))
        Spacer(Modifier.width(12.dp))
        Artwork(album.artworkUrl, accentFor(album.id), Modifier.size(46.dp), corner = 10.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(album.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(album.artist, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (album.playCount > 0) {
            Spacer(Modifier.width(12.dp))
            Text(appPlural(R.plurals.play_count, album.playCount), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun FavouriteCard(song: Song, scale: Float, onPlay: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().spotSurface(MaterialTheme.shapes.large, colors.surface, outlined = true).pressClickable(onClick = onPlay).padding(14.dp * scale),
        verticalArrangement = Arrangement.spacedBy(12.dp * scale),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(song.artworkUrl, song.accent, Modifier.size(52.dp * scale), corner = 12.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Eyebrow(appString(R.string.text_starred_5f1f99), colors.primary)
                Spacer(Modifier.height(2.dp))
                Text(song.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(46.dp * scale).paperFrame(CircleShape, { background(colors.primary) }, fill = colors.primary), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, appString(R.string.text_play_5d12bd), tint = colors.onPrimary, modifier = Modifier.size(22.dp * scale))
            }
        }
        StillBars(song.id, Modifier.fillMaxWidth().height(34.dp * scale))
    }
}

@Composable
internal fun StillBars(seed: String, modifier: Modifier) {
    val square = isPaperTheme()
    val levels = remember(seed) { Random(seed.hashCode()).let { r -> FloatArray(30) { 0.3f + r.nextFloat() * 0.7f } } }
    val bar = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
    Spacer(modifier.drawBehind {
        val step = size.width / levels.size
        val radius = if (square) CornerRadius.Zero else CornerRadius(2.dp.toPx())
        for (i in levels.indices) {
            val h = size.height * levels[i]
            drawRoundRect(bar, Offset(i * step, (size.height - h) / 2f), Size(step * 0.72f, h), radius)
        }
    })
}

@Composable
private fun Shelf(title: String, gutter: Dp, content: LazyListScope.() -> Unit) {
    SectionHeader(title, Modifier.padding(horizontal = gutter))
    Spacer(Modifier.height(10.dp))
    LazyRow(contentPadding = PaddingValues(horizontal = gutter - 8.dp), content = content)
}
