package com.aurora.music.ui.screens.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.aurora.music.R
import com.aurora.music.data.HomeData
import com.aurora.music.data.HomeFeedItem
import com.aurora.music.data.HomeFeedSection
import com.aurora.music.data.HomeSection
import com.aurora.music.data.ThemeStyle
import com.aurora.music.data.starredQueue
import com.aurora.music.foryou.GeneratedMix
import com.aurora.music.foryou.MIX_KIND
import com.aurora.music.foryou.MixKind
import com.aurora.music.localization.appPlural
import com.aurora.music.localization.appString
import com.aurora.music.localization.asPlaylist
import com.aurora.music.model.Album
import com.aurora.music.model.Song
import com.aurora.music.model.accent
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.Eyebrow
import com.aurora.music.ui.components.LocalHiddenSongs
import com.aurora.music.ui.components.LottieLoader
import com.aurora.music.ui.components.StatusBarScrim
import com.aurora.music.ui.components.TrackGrid
import com.aurora.music.ui.layout.LocalWindowLayout
import com.aurora.music.ui.screens.stats.RecapInboxButton
import com.aurora.music.ui.theme.LocalUiPrefs
import com.aurora.music.ui.theme.Motion
import com.aurora.music.ui.theme.PaperTokens
import com.aurora.music.ui.theme.isGlassTheme
import com.aurora.music.ui.theme.isPaperTheme
import com.aurora.music.ui.theme.paperClip
import com.aurora.music.ui.theme.paperFrame
import com.aurora.music.ui.theme.paperSquare
import com.aurora.music.ui.theme.pressClickable
import com.aurora.music.util.accentFor
import com.aurora.music.viewmodel.HomeUiState
import kotlinx.coroutines.launch
import kotlin.math.abs

internal val DriftGutter = 24.dp

internal enum class DriftForm { STACK, COVER, CIRCLE }

@Composable
internal fun HomeDrift(
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
) {
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val hiddenSongs = LocalHiddenSongs.current
    val data = remember(state.data, hiddenSongs) { state.data.copy(starred = hiddenSongs.visible(state.data.starred)) }
    val feed = remember(data.sections, hiddenSongs) {
        if (hiddenSongs.songs.isEmpty()) data.sections
        else data.sections.map { section -> section.copy(items = section.items.filterNot { it is HomeFeedItem.Track && hiddenSongs.hides(it.song) }) }
    }
    val ui = LocalUiPrefs.current
    val hidden = ui.hiddenHomeSections
    val tablet = LocalWindowLayout.current.useNavigationRail
    val loading = state.loading && data == HomeData()
    val heroes = remember(data.newReleases, data.sections, hidden) { if (HomeSection.HERO in hidden) emptyList() else heroesOf(data) }
    val chart = remember(data.mostPlayed, hidden) { if (HomeSection.MOST in hidden) emptyList() else data.mostPlayed.take(5) }
    val featured = data.starred.firstOrNull().takeIf { HomeSection.FAVOURITE !in hidden }
    val (forYou, stations) = remember(mixes, state.selectedFeed) {
        if (state.selectedFeed == "library") mixes.partition { it.kind != MixKind.STATION } else emptyList<GeneratedMix>() to emptyList<GeneratedMix>()
    }
    val clock = rememberEntrance()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = maxWidth
        val wide = width >= 960.dp
        val cover = (if (tablet) 320.dp * ui.tabletHeroScale else 240.dp).coerceAtMost(width * 0.62f)
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(top = topInset + 8.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(34.dp),
        ) {
            // wide pages set sections two to a row; a section waits here for its neighbour
            var pending: Pair<String, @Composable () -> Unit>? = null
            fun flush() {
                pending?.let { held -> item(key = held.first) { held.second() } }
                pending = null
            }
            fun section(key: String, content: @Composable () -> Unit) {
                val first = pending
                if (!wide) item(key = key) { content() }
                else if (first == null) pending = key to content
                else {
                    pending = null
                    item(key = first.first) {
                        Row {
                            Box(Modifier.weight(1f)) { first.second() }
                            Box(Modifier.weight(1f)) { content() }
                        }
                    }
                }
            }

            item(key = "top", contentType = "top") {
                DriftTop(state, username, avatarUrl, sourceLabel, heroes, tablet, width, cover, clock, onOpenDrawer, onOpenSettings, onOpenNotifications, onSelectFeed,
                    onOpen = { onOpenDetail(it.kind, it.id) },
                    onPlay = { if (it.kind == "album") onPlayAlbum(it.id) else onPlayCollection(it.kind, it.id, false) })
            }

            if (data.recentlyPlayed.isNotEmpty() && HomeSection.RECENT !in hidden) item(key = "recent") {
                Column(Modifier.rise(clock, 2)) {
                    DriftHeading(appString(R.string.text_jump_back_in_1d9181), Modifier.padding(horizontal = DriftGutter))
                    LazyRow(contentPadding = PaddingValues(start = DriftGutter - 4.dp, end = DriftGutter - 4.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(data.recentlyPlayed.size) { i ->
                            val album = data.recentlyPlayed[i]
                            RecentDisc(album, i == 0, if (tablet) 112.dp else 80.dp) { onOpenDetail("album", album.id) }
                        }
                    }
                }
            }

            listOf(R.string.foryou_title to forYou, R.string.foryou_stations to stations).forEach { (title, row) ->
                if (row.isNotEmpty()) section("mixes:$title") {
                    val tiles = remember(row) { row.map { it.asPlaylist() } }
                    DriftShelf(appString(title)) {
                        items(tiles.size, key = { tiles[it].id }) { i ->
                            val mix = tiles[i]
                            // mix names run long in some languages and have no artwork to tell them apart
                            DriftTile(mix.title, mix.subtitle, mix.coverUrl, mix.accent, DriftForm.STACK, clock, titleLines = 2) { onOpenDetail(MIX_KIND, mix.id) }
                        }
                    }
                }
            }

            if (loading) {
                flush()
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) { LottieLoader(modifier = Modifier.size(80.dp)) }
                }
                return@LazyColumn
            }
            if (state.nothingToShow(data)) {
                flush()
                item(key = "empty") { HomeEmptyState(onAddSource) }
                return@LazyColumn
            }

            if (chart.isNotEmpty()) section("most") { DriftChart(chart, onOpenDetail) }
            if (featured != null) section("favourite") {
                DriftFavourite(featured, if (tablet) ui.tabletFavouriteScale else 1f) {
                    starredQueue(state.allStarred, data.starred, featured).let { (songs, at) -> onPlayAll(songs, at) }
                }
            }
            feed.forEach { shelf ->
                if (shelf.items.isNotEmpty()) section("feed:${shelf.id}") { DriftFeed(shelf, clock, onOpenDetail, onPlayAll) }
            }
            flush()
            if (data.continuation != null || state.error != null) {
                item(key = "feed-more") { HomeFeedMore(state, onLoadMore, onRetry, DriftGutter) }
            }

            if (data.playlists.isNotEmpty() && HomeSection.PLAYLISTS !in hidden) section("playlists") {
                DriftShelf(appString(R.string.text_your_playlists_df03eb)) {
                    items(data.playlists.size) { i ->
                        val playlist = data.playlists[i]
                        DriftTile(playlist.title, playlist.subtitle, playlist.coverUrl, playlist.accent, DriftForm.STACK, clock) { onOpenDetail("playlist", playlist.id) }
                    }
                }
            }
            listOf(
                Triple(HomeSection.RECOMMENDED, R.string.text_recommended_albums_36271e, data.random),
                Triple(HomeSection.NEW, R.string.text_new_releases_3cdd02, data.newReleases),
            ).forEach { (key, title, albums) ->
                if (albums.isNotEmpty() && key !in hidden) section(key) {
                    DriftShelf(appString(title)) {
                        items(albums.size) { i ->
                            val album = albums[i]
                            DriftTile(album.title, album.artist, album.artworkUrl, accentFor(album.id), DriftForm.COVER, clock) { onOpenDetail("album", album.id) }
                        }
                    }
                }
            }
            if (data.artists.isNotEmpty() && HomeSection.ARTISTS !in hidden) section("artists") {
                DriftShelf(appString(R.string.text_artists_1528d8)) {
                    items(data.artists.size) { i ->
                        val artist = data.artists[i]
                        DriftTile(artist.name, "", artist.imageUrl, accentFor(artist.id), DriftForm.CIRCLE, clock) { onOpenDetail("artist", artist.id) }
                    }
                }
            }
            flush()
        }
        StatusBarScrim(Modifier.align(Alignment.TopCenter))
    }
}

// drift's heavy type: a theme style scaled up, so font scale and typeface carry over; tight except on retro's monospace
@Composable
internal fun driftType(base: TextStyle, scale: Float = 1f): TextStyle {
    val mono = LocalUiPrefs.current.themeStyle == ThemeStyle.RETRO
    return remember(base, scale, mono) {
        base.copy(fontSize = base.fontSize * scale, lineHeight = 1.06.em, fontWeight = FontWeight.ExtraBold, letterSpacing = if (mono) 0.em else (-0.03).em)
    }
}

// a page's big line: two lines at most, shrunk until no word has to break
@Composable
internal fun DriftTitle(text: String, scale: Float, modifier: Modifier = Modifier, clock: Animatable<Float, AnimationVector1D>? = null) {
    val measurer = rememberTextMeasurer()
    val base = driftType(MaterialTheme.typography.displayLarge, scale)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width = constraints.maxWidth
        val style = remember(text, base, width, measurer) {
            var fit = base
            // shortcut: a script written without spaces shrinks toward one line before it wraps, break on its own line breaks once a long cjk title shows up
            repeat(6) {
                val layout = measurer.measure(text, fit, maxLines = 2, constraints = Constraints(maxWidth = width))
                val split = (0 until layout.lineCount - 1).any { text[layout.getLineEnd(it) - 1].isLetterOrDigit() }
                if (!layout.hasVisualOverflow && !split) return@remember fit
                fit = fit.copy(fontSize = fit.fontSize * 0.9f)
            }
            fit
        }
        Column {
            text.lines().forEachIndexed { i, line ->
                Text(line, style = style, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = (if (clock != null) Modifier.rise(clock, i.coerceAtMost(2)) else Modifier).semantics { heading() })
            }
        }
    }
}

@Composable
internal fun DriftHeading(title: String, modifier: Modifier = Modifier, note: String = "", onClick: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clip(MaterialTheme.shapes.small).clickable(role = Role.Button, onClick = onClick).heightIn(min = 44.dp) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = driftType(MaterialTheme.typography.headlineMedium, 1.1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).alignByBaseline().semantics { heading() })
        if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 10.dp).widthIn(max = 160.dp).alignByBaseline())
        if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, appString(R.string.text_see_all_2941c5), tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

// a text tab: the selected one is heavy with a primary rule under it
@Composable
internal fun DriftTab(label: String, selected: Boolean, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val on by animateFloatAsState(if (selected) 1f else 0f, Motion.state(), label = "driftTab")
    Box(
        Modifier.heightIn(min = 44.dp)
            .drawBehind { drawRect(colors.primary, Offset(0f, size.height - 3.dp.toPx()), Size(size.width, 3.dp.toPx()), alpha = on) }
            .clip(MaterialTheme.shapes.extraSmall)
            .semantics { this.selected = selected }
            .combinedClickable(role = Role.Tab, onLongClick = onLongClick, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleLarge, fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
            color = lerp(colors.onSurfaceVariant, colors.onSurface, on), maxLines = 1)
    }
}

// a cover lifted off the page; paper frames it instead
@Composable
internal fun DriftCover(art: String, accent: Color, shape: Shape, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit = {}) {
    Box(modifier.then(if (isPaperTheme()) Modifier.border(1.dp, PaperTokens.ink) else Modifier.shadow(6.dp, shape))) {
        Artwork(art, accent, Modifier.matchParentSize(), corner = 0.dp)
        content()
    }
}

// a collection's cover in front of two tilted cards in its colour; the fan opens on the page's entrance clock, so only once a visit
@Composable
internal fun FanStack(art: String, accent: Color, size: Dp, clock: Animatable<Float, AnimationVector1D>, modifier: Modifier = Modifier, mark: ImageVector? = null) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    val tint = spotlightTint(art, accent)
    Box(modifier.size(size * 1.1f, size * 1.2f)) {
        Box(Modifier.offset(size / 6, size / 15).size(size * 0.87f).graphicsLayer { rotationZ = 9f * entrance(clock.value, 2) }
            .paperFrame(shape, { background(tint.copy(alpha = 0.55f)) }, fill = tint))
        Box(Modifier.offset(size / 20, size / 10).size(size * 0.9f).graphicsLayer { rotationZ = -6f * entrance(clock.value, 2) }
            .paperFrame(shape, { background(tint) }, fill = tint))
        DriftCover(art, accent, shape, Modifier.offset(y = size / 5).size(size)) {
            if (mark != null) Icon(mark, null, tint = colors.onSurface,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).size(30.dp).paperClip(MaterialTheme.shapes.extraSmall)
                    .background(colors.background.copy(alpha = 0.78f)).padding(6.dp))
        }
    }
}

// one shelf item: a collection fans out as a stack, a release stays a flat cover, a person is round
@Composable
internal fun DriftTile(
    title: String,
    subtitle: String,
    art: String,
    accent: Color,
    form: DriftForm,
    clock: Animatable<Float, AnimationVector1D>,
    titleLines: Int = 1,
    onClick: () -> Unit,
) {
    val round = form == DriftForm.CIRCLE
    // the padding is the room a cover's shadow needs inside the clip
    Column(
        Modifier.width(if (round) 116.dp else 152.dp).paperClip(RoundedCornerShape(14.dp)).pressClickable(onClick = onClick).padding(8.dp),
        horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        when (form) {
            DriftForm.STACK -> FanStack(art, accent, 120.dp, clock)
            DriftForm.COVER -> DriftCover(art, accent, MaterialTheme.shapes.large, Modifier.padding(top = 8.dp).size(136.dp))
            DriftForm.CIRCLE -> Artwork(art, accent, Modifier.padding(top = 8.dp).size(100.dp), corner = 100.dp)
        }
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = titleLines, overflow = TextOverflow.Ellipsis,
            textAlign = if (round) TextAlign.Center else TextAlign.Start, modifier = Modifier.fillMaxWidth())
        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun DriftShelf(title: String, note: String = "", onOpenAll: (() -> Unit)? = null, content: LazyListScope.() -> Unit) {
    Column {
        DriftHeading(title, Modifier.padding(horizontal = DriftGutter), note, onOpenAll)
        LazyRow(contentPadding = PaddingValues(horizontal = DriftGutter - 8.dp), content = content)
    }
}

@Composable
private fun PlayDisc(size: Dp, onClick: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.size(size).paperFrame(CircleShape, { background(colors.primary) }, fill = colors.primary)
            .then(if (onClick != null) Modifier.pressClickable(role = Role.Button, onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Filled.PlayArrow, appString(R.string.text_play_5d12bd), tint = colors.onPrimary, modifier = Modifier.size(size * 0.45f)) }
}

@Composable
private fun DriftTop(
    state: HomeUiState,
    username: String,
    avatarUrl: String,
    sourceLabel: String,
    heroes: List<Hero>,
    tablet: Boolean,
    width: Dp,
    cover: Dp,
    clock: Animatable<Float, AnimationVector1D>,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNotifications: () -> Unit,
    onSelectFeed: (String) -> Unit,
    onOpen: (Hero) -> Unit,
    onPlay: (Hero) -> Unit,
) {
    // a phone stacks the greeting on two lines, as the design draws it
    val hello = titleCase(greeting()).let { if (tablet) it else it.replaceFirst(' ', '\n') }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = DriftGutter), verticalAlignment = Alignment.CenterVertically) {
            ConsoleLabel(listOf(state.feeds.firstOrNull { it.id == state.selectedFeed }?.label ?: sourceLabel, username).filter { it.isNotBlank() }.joinToString(" · "),
                Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            RecapInboxButton(onOpenNotifications)
            Spacer(Modifier.width(8.dp))
            HomeAvatar(username, avatarUrl, onOpenDrawer)
            Spacer(Modifier.width(8.dp))
            IconPill(Icons.Outlined.Settings, appString(R.string.text_settings_c7f73b), onClick = onOpenSettings)
        }
        DriftTitle(hello, if (tablet) 1.6f else 1.2f, Modifier.padding(start = DriftGutter, end = DriftGutter, top = 10.dp), clock)
        if (state.feeds.size > 1) {
            Row(Modifier.padding(top = 14.dp).rise(clock, 2).horizontalScroll(rememberScrollState()).padding(horizontal = DriftGutter), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                state.feeds.forEach { feed -> DriftTab(feed.label, feed.id == state.selectedFeed) { onSelectFeed(feed.id) } }
            }
        }
        if (heroes.isNotEmpty()) DriftCarousel(heroes, width, cover, if (tablet) 24.dp else 6.dp, clock, onOpen, onPlay)
    }
}

// every cover is a pager page the size of the centre one; the layer shrinks, dims and pulls in its neighbours from the pager's own offset, so a swipe redraws without recomposing
@Composable
internal fun DriftCarousel(heroes: List<Hero>, width: Dp, cover: Dp, gap: Dp, clock: Animatable<Float, AnimationVector1D>, onOpen: (Hero) -> Unit, onPlay: (Hero) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pager = rememberPagerState(pageCount = { heroes.size })
    val scope = rememberCoroutineScope()
    val coverShape = MaterialTheme.shapes.extraLarge
    val round = paperSquare(CircleShape)
    val paper = isPaperTheme()
    val side = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    Box(Modifier.padding(top = 8.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
        // taller than the cover, so the pager's clip leaves its shadow whole
        HorizontalPager(
            pager,
            Modifier.fillMaxWidth().height(cover + 40.dp),
            contentPadding = PaddingValues(horizontal = (width - cover) / 2),
            pageSize = PageSize.Fixed(cover),
            beyondViewportPageCount = 2,
        ) { page ->
            val hero = heroes[page]
            Box(
                Modifier.size(cover)
                    .graphicsLayer {
                        val at = page - pager.currentPage - pager.currentPageOffsetFraction
                        val away = abs(at)
                        val p = entrance(clock.value, 1)
                        val scale = (1f - 0.25f * away).coerceAtLeast(0.5f) * (0.82f + 0.18f * p)
                        scaleX = scale
                        scaleY = scale
                        alpha = (1f - 0.38f * away).coerceAtLeast(0f) * p
                        translationX = side * (at * (gap.toPx() - 0.125f * away * size.width) + at.coerceIn(-1f, 1f) * (1f - p) * 60.dp.toPx())
                        shadowElevation = if (paper) 0f else 16.dp.toPx() * (1f - away).coerceAtLeast(0f)
                        shape = if (hero.kind == "artist") round else coverShape
                        clip = true
                    }
                    .then(if (paper) Modifier.border(1.dp, PaperTokens.ink) else Modifier)
                    .semantics { contentDescription = "${hero.title}, ${hero.subtitle}" }
                    .pressClickable { if (page == pager.currentPage) onOpen(hero) else scope.launch { pager.animateScrollToPage(page) } },
            ) { Artwork(hero.art, hero.accent, Modifier.matchParentSize(), corner = 0.dp) }
        }
        Box(
            Modifier.offset(cover / 2 - 20.dp, cover / 2 - 20.dp)
                .graphicsLayer {
                    val scale = entrance(clock.value, 2) * (1f - abs(pager.currentPageOffsetFraction))
                    scaleX = scale
                    scaleY = scale
                }
                // glass has no flat page colour to cut the ring from
                .then(if (isGlassTheme()) Modifier else Modifier.background(colors.background, paperSquare(CircleShape)))
                .padding(4.dp),
        ) { PlayDisc(56.dp) { heroes.getOrNull(pager.currentPage)?.let(onPlay) } }
    }
    HeroCaption(heroes, pager, Modifier.padding(top = 6.dp).rise(clock, 2))
}

@Composable
private fun HeroCaption(heroes: List<Hero>, pager: PagerState, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().padding(horizontal = DriftGutter), horizontalAlignment = Alignment.CenterHorizontally) {
        Crossfade(pager.currentPage, animationSpec = Motion.state(), label = "driftHero") { page ->
            heroes.getOrNull(page)?.let { hero ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Eyebrow(hero.label, colors.primary, maxLines = 1)
                    Text(hero.title, style = driftType(MaterialTheme.typography.headlineMedium, 1.1f), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp))
                    val line = listOf(hero.subtitle, hero.meta).filter { it.isNotBlank() }.joinToString(" · ")
                    if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, textAlign = TextAlign.Center,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        if (heroes.size > 1) Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(heroes.size) { i ->
                val tint by animateColorAsState(if (pager.currentPage == i) colors.onSurface else colors.outlineVariant, Motion.state(), label = "driftBar")
                Box(Modifier.size(22.dp, 4.dp).paperClip(CircleShape).background(tint))
            }
        }
    }
}

@Composable
internal fun RecentDisc(album: Album, first: Boolean, size: Dp, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.width(size + 8.dp).paperClip(RoundedCornerShape(14.dp)).pressClickable(onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(size).border(if (first) 3.dp else 1.dp, if (first) colors.primary else colors.outlineVariant, paperSquare(CircleShape)), contentAlignment = Alignment.Center) {
            Artwork(album.artworkUrl, accentFor(album.id), Modifier.size(size - 14.dp), corner = size)
        }
        Spacer(Modifier.height(8.dp))
        Text(album.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun DriftChart(albums: List<Album>, onOpenDetail: (String, String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = DriftGutter)) {
        DriftHeading(appString(R.string.text_most_played_14202e))
        Spacer(Modifier.height(6.dp))
        albums.forEachIndexed { i, album ->
            val line = remember(album) {
                listOfNotNull(album.artist.takeIf { it.isNotBlank() }, album.playCount.takeIf { it > 0 }?.let { appPlural(R.plurals.play_count, it) }).joinToString(" · ")
            }
            Row(
                Modifier.fillMaxWidth()
                    .drawBehind { drawLine(colors.outlineVariant, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
                    .clip(MaterialTheme.shapes.small).clickable { onOpenDetail("album", album.id) }.heightIn(min = 68.dp).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text((i + 1).toString(), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.ExtraBold, color = colors.onSurfaceVariant, maxLines = 1,
                    modifier = Modifier.widthIn(min = 30.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(album.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(14.dp))
                Artwork(album.artworkUrl, accentFor(album.id), Modifier.size(46.dp), corner = 14.dp)
            }
        }
    }
}

@Composable
private fun DriftFavourite(song: Song, scale: Float, onPlay: () -> Unit) {
    Column {
        DriftHeading(appString(R.string.text_from_your_favourites_d39722), Modifier.padding(horizontal = DriftGutter), titleCase(appString(R.string.text_starred_5f1f99)))
        Row(
            Modifier.padding(start = DriftGutter - 8.dp, end = DriftGutter - 8.dp, top = 8.dp).fillMaxWidth()
                .paperClip(MaterialTheme.shapes.medium).pressClickable(onClick = onPlay).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayDisc(56.dp * scale)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                StillBars(song.id, Modifier.padding(top = 6.dp).fillMaxWidth().height(26.dp * scale))
            }
        }
    }
}

@Composable
private fun DriftFeed(shelf: HomeFeedSection, clock: Animatable<Float, AnimationVector1D>, onOpenDetail: (String, String) -> Unit, onPlayAll: (List<Song>, Int) -> Unit) {
    val tracks = remember(shelf) { shelf.items.filterIsInstance<HomeFeedItem.Track>().map { it.song } }
    if (tracks.size == shelf.items.size) Column {
        DriftHeading(shelf.title, Modifier.padding(horizontal = DriftGutter), shelf.subtitle)
        Spacer(Modifier.height(12.dp))
        TrackGrid(tracks, DriftGutter) { index -> onPlayAll(tracks, index) }
    } else DriftShelf(shelf.title, shelf.subtitle) {
        items(shelf.items.size, key = { shelf.items[it].key }) { index ->
            when (val entry = shelf.items[index]) {
                is HomeFeedItem.Record -> entry.album.let { DriftTile(it.title, it.artist, it.artworkUrl, accentFor(it.id), DriftForm.COVER, clock) { onOpenDetail("album", it.id) } }
                is HomeFeedItem.Collection -> entry.playlist.let { DriftTile(it.title, it.subtitle, it.coverUrl, it.accent, DriftForm.STACK, clock) { onOpenDetail("playlist", it.id) } }
                is HomeFeedItem.Performer -> entry.artist.let { DriftTile(it.name, "", it.imageUrl, accentFor(it.id), DriftForm.CIRCLE, clock) { onOpenDetail("artist", it.id) } }
                is HomeFeedItem.Track -> entry.song.let {
                    DriftTile(it.title, it.artist, it.artworkUrl, it.accent, DriftForm.COVER, clock) { onPlayAll(tracks, tracks.indexOfFirst { track -> track.id == it.id }.coerceAtLeast(0)) }
                }
            }
        }
    }
}
