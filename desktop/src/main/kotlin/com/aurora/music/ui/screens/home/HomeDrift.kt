package com.aurora.music.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
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
import androidx.compose.ui.zIndex
import com.aurora.music.R
import com.aurora.music.data.HomeData
import com.aurora.music.data.HomeFeedItem
import com.aurora.music.data.HomeFeedSection
import com.aurora.music.data.HomeSection
import com.aurora.music.data.TabletSetting
import com.aurora.music.data.ThemeStyle
import com.aurora.music.data.starredQueue
import com.aurora.music.desktop.ui.LocalDesktopContainer
import com.aurora.music.foryou.GeneratedMix
import com.aurora.music.foryou.MIX_COVER_PREFIX
import com.aurora.music.foryou.MIX_KIND
import com.aurora.music.foryou.MixKind
import com.aurora.music.localization.appPlural
import com.aurora.music.localization.appString
import com.aurora.music.localization.asPlaylist
import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import com.aurora.music.model.accent
import com.aurora.music.ui.components.AdaptiveShelf
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.Eyebrow
import com.aurora.music.ui.components.LocalHiddenSongs
import com.aurora.music.ui.components.LottieLoader
import com.aurora.music.ui.components.TrackGrid
import com.aurora.music.ui.components.coverHover
import com.aurora.music.ui.layout.LocalPageGutter
import com.aurora.music.ui.layout.PageMetrics
import com.aurora.music.ui.layout.pagePadding
import com.aurora.music.ui.screens.stats.RecapInboxButton
import com.aurora.music.ui.theme.LocalUiPrefs
import com.aurora.music.ui.theme.Motion
import com.aurora.music.ui.theme.PaperTokens
import com.aurora.music.ui.theme.cornerDp
import com.aurora.music.ui.theme.isPaperTheme
import com.aurora.music.ui.theme.paperClip
import com.aurora.music.ui.theme.paperFrame
import com.aurora.music.ui.theme.paperSquare
import com.aurora.music.ui.theme.pressClickable
import com.aurora.music.ui.theme.themedCorner
import com.aurora.music.util.MotionTokens
import com.aurora.music.util.accentFor
import com.aurora.music.util.rememberDominantColor
import com.aurora.music.viewmodel.HomeUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.sign
import kotlin.random.Random

private val SectionGap = 52.dp
private val PairGap = 56.dp
private val PairMinWidth = 520.dp
private val ShelfGap = 22.dp
private val FanOverhang = 14.dp
// room above a shelf's covers for the fanned cards and the hover lift, which a lazy row would clip
private val Slack = 10.dp
private const val SHELF = 0
private const val TALL = 1
private const val SOLO = 2

// a carousel cover's size and opacity by how many places it sits from the centre
private val CoverScale = floatArrayOf(1f, 0.758f, 0.576f, 0.576f)
private val CoverAlpha = floatArrayOf(1f, 0.65f, 0.35f, 0f)

private class Block(val key: String, val kind: Int, val content: @Composable (paired: Boolean, width: Dp) -> Unit)

@Composable
internal fun HomeDrift(
    contentPadding: PaddingValues,
    state: HomeUiState,
    sourceLabel: String,
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
    val sections = remember(data.sections, hiddenSongs) {
        if (hiddenSongs.songs.isEmpty()) data.sections
        else data.sections.map { section -> section.copy(items = section.items.filterNot { it is HomeFeedItem.Track && hiddenSongs.hides(it.song) }) }
    }
    val ui = LocalUiPrefs.current
    val hidden = ui.hiddenHomeSections
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val gutter = LocalPageGutter.current
    val heroes = remember(data.newReleases, data.sections, hidden) { if (HomeSection.HERO in hidden) emptyList() else heroesOf(data) }
    val heroAlbums = if (HomeSection.HERO in hidden) 0 else HeroCount
    val recent = if (HomeSection.RECENT in hidden) emptyList() else data.recentlyPlayed
    val (forYou, stations) = remember(mixes, state.selectedFeed) {
        (if (state.selectedFeed == "library") mixes else emptyList()).partition { it.kind != MixKind.STATION }
            .let { (own, artists) -> own.map { it.asPlaylist() } to artists.map { it.asPlaylist() } }
    }
    val history = LocalDesktopContainer.current.playHistory
    val streak by produceState(0, history) { value = withContext(Dispatchers.Default) { history.streak().first } }
    val line = listOfNotNull(sourceLabel.takeIf { it.isNotBlank() }, streak.takeIf { it > 0 }?.let { appString(R.string.text_day_streak_661711, it) }).joinToString(" · ")
    val clock = rememberEntrance()
    val fan = { Motion.EaseOut.transform(clock.value) }
    val playStarred: (Song) -> Unit = { tapped -> starredQueue(state.allStarred, data.starred, tapped).let { (songs, at) -> onPlayAll(songs, at) } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth - gutter * 2
        val two = width >= PairMinWidth * 2 + PairGap
        fun stacks(key: String, title: Int, kind: String, items: List<Playlist>) = if (items.isEmpty()) null else Block(key, SHELF) { paired, _ ->
            Section(appString(title)) { Shelf(items, paired, { it.id }) { item, slot -> StackTile(item, slot, fan) { onOpenDetail(kind, item.id) } } }
        }
        fun covers(key: String, title: Int, items: List<Album>) = if (items.isEmpty()) null else Block(key, SHELF) { paired, _ ->
            Section(appString(title)) { Shelf(items, paired, { it.id }) { album, slot -> AlbumTile(album, slot) { onOpenDetail("album", album.id) } } }
        }
        val mixRows = listOfNotNull(stacks("mixes", R.string.foryou_title, MIX_KIND, forYou), stacks("stations", R.string.foryou_stations, MIX_KIND, stations))
        val lead = listOfNotNull(
            data.mostPlayed.take(5).takeIf { it.isNotEmpty() && HomeSection.MOST !in hidden }?.let { chart ->
                Block("most", TALL) { _, _ -> Section(appString(R.string.text_most_played_14202e)) { Chart(chart) { onOpenDetail("album", it.id) } } }
            },
            data.starred.takeIf { it.isNotEmpty() && HomeSection.FAVOURITE !in hidden }?.let { starred ->
                Block("favourites", TALL) { _, room ->
                    Section(appString(R.string.text_from_your_favourites_d39722), onOpen = { onOpenDetail("liked", "liked") }) { Favourites(starred, room, playStarred) }
                }
            },
        )
        val feed = sections.mapNotNull { feedBlock(it, fan, onOpenDetail, onPlayAll) }
        val library = listOfNotNull(
            stacks("playlists", R.string.text_your_playlists_df03eb, "playlist", data.playlists.takeIf { HomeSection.PLAYLISTS !in hidden }.orEmpty()),
            covers("recommended", R.string.text_recommended_albums_36271e, data.random.takeIf { HomeSection.RECOMMENDED !in hidden }.orEmpty()),
            covers("new", R.string.text_new_releases_3cdd02, data.newReleases.drop(heroAlbums).takeIf { HomeSection.NEW !in hidden }.orEmpty()),
            data.artists.takeIf { it.isNotEmpty() && HomeSection.ARTISTS !in hidden }?.let { artists ->
                Block("artists", SHELF) { paired, _ ->
                    Section(appString(R.string.text_artists_1528d8)) {
                        Shelf(artists, paired, { it.id }, min = 124.dp) { artist, slot -> ArtistTile(artist, slot) { onOpenDetail("artist", artist.id) } }
                    }
                }
            },
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = pagePadding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(SectionGap),
        ) {
            item(key = "header") {
                Column(Modifier.padding(top = PageMetrics.HeaderTop)) {
                    Row(Modifier.rise(clock, 0)) {
                        DriftTitle(greeting(), Modifier.weight(1f), maxLines = 2)
                        Spacer(Modifier.width(16.dp))
                        RecapInboxButton(onOpenNotifications)
                    }
                    if (line.isNotBlank() || state.feeds.size > 1) FlowRow(
                        Modifier.padding(top = 10.dp).rise(clock, 1),
                        horizontalArrangement = Arrangement.spacedBy(30.dp),
                    ) {
                        if (line.isNotBlank()) Text(line, style = type.titleLarge, fontWeight = FontWeight.Normal, color = colors.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.CenterVertically))
                        if (state.feeds.size > 1) DriftTabs(state.feeds.map { it.label }, state.feeds.indexOfFirst { it.id == state.selectedFeed }, type.titleLarge) {
                            onSelectFeed(state.feeds[it].id)
                        }
                    }
                }
            }

            if (heroes.isNotEmpty()) item(key = "hero") {
                Carousel(heroes, width, ui.tabletHeroScale / TabletSetting.HERO.default, clock, onOpen = { onOpenDetail(it.kind, it.id) },
                    onPlay = { if (it.kind == "album") onPlayAlbum(it.id) else onPlayCollection(it.kind, it.id, false) })
            }

            if (recent.isNotEmpty()) item(key = "recent") {
                Column(Modifier.rise(clock, 2)) {
                    DriftHeading(appString(R.string.text_jump_back_in_1d9181))
                    Spacer(Modifier.height(22.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        recent.forEachIndexed { i, album -> RecentDisc(album, i == 0) { onOpenDetail("album", album.id) } }
                    }
                }
            }

            lines(pair(mixRows, two), width)

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

            lines(pair(lead, two), width)
            lines(pair(feed, two), width)
            if (data.continuation != null || state.error != null) {
                item(key = "feed-more") { HomeFeedMore(state, onLoadMore, onRetry) }
            }
            lines(pair(library, two), width)
        }
    }
}

private fun feedBlock(section: HomeFeedSection, fan: () -> Float, onOpenDetail: (String, String) -> Unit, onPlayAll: (List<Song>, Int) -> Unit): Block? {
    if (section.items.isEmpty()) return null
    val tracks = section.items.filterIsInstance<HomeFeedItem.Track>().map { it.song }
    if (tracks.size == section.items.size && tracks.size >= 8) return Block("feed:${section.id}", SOLO) { _, _ ->
        Section(section.title, section.subtitle) { TrackGrid(tracks) { index -> onPlayAll(tracks, index) } }
    }
    return Block("feed:${section.id}", SHELF) { paired, _ ->
        Section(section.title, section.subtitle) {
            Shelf(section.items, paired, { it.key }) { entry, slot ->
                when (entry) {
                    is HomeFeedItem.Record -> AlbumTile(entry.album, slot) { onOpenDetail("album", entry.album.id) }
                    is HomeFeedItem.Collection -> StackTile(entry.playlist, slot, fan) { onOpenDetail("playlist", entry.playlist.id) }
                    is HomeFeedItem.Performer -> ArtistTile(entry.artist, slot) { onOpenDetail("artist", entry.artist.id) }
                    is HomeFeedItem.Track -> Tile(entry.song.artworkUrl, entry.song.title, entry.song.artist, slot,
                        onClick = { onPlayAll(tracks, tracks.indexOfFirst { it.id == entry.song.id }.coerceAtLeast(0)) }) {
                        FlatCover(entry.song.artworkUrl, entry.song.accent, slot)
                    }
                }
            }
        }
    }
}

// two sections share a line only when they are the same sort of thing, so neither leaves a hole beside the other
private fun pair(blocks: List<Block>, two: Boolean): List<List<Block>> {
    val lines = mutableListOf<List<Block>>()
    var i = 0
    while (i < blocks.size) {
        val next = blocks.getOrNull(i + 1)
        val together = two && next != null && blocks[i].kind != SOLO && next.kind == blocks[i].kind
        lines += if (together) listOf(blocks[i], next) else listOf(blocks[i])
        i += if (together) 2 else 1
    }
    return lines
}

private fun LazyListScope.lines(lines: List<List<Block>>, width: Dp) {
    lines.forEach { line ->
        item(key = line.first().key) {
            val room = (width - PairGap * (line.size - 1)) / line.size
            Row(horizontalArrangement = Arrangement.spacedBy(PairGap)) {
                line.forEach { block -> Box(Modifier.weight(1f)) { block.content(line.size > 1, room) } }
            }
        }
    }
}

// drift's large corners follow the corner setting on aurora and every other style's own shapes elsewhere
@Composable
internal fun driftShape(base: Dp): Shape {
    val ui = LocalUiPrefs.current
    return if (ui.themeStyle != ThemeStyle.AURORA) MaterialTheme.shapes.large
    else remember(ui.cornerStyle, base) { RoundedCornerShape(cornerDp(ui.cornerStyle, base).coerceAtMost(base * 1.5f)) }
}

// the page title in the largest size its room holds: 84sp where there is room, less as the page narrows or the words run long
@Composable
internal fun DriftTitle(text: String, modifier: Modifier = Modifier, maxLines: Int = 1) {
    BoxWithConstraints(modifier) {
        Text(text, style = driftTitleStyle(text, constraints.maxWidth, maxWidth, maxLines), maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun driftTitleStyle(text: String, room: Int, width: Dp, maxLines: Int = 1, peak: Float = 2.1f): TextStyle {
    val base = MaterialTheme.typography.displayLarge
    val tight = LocalUiPrefs.current.themeStyle == ThemeStyle.AURORA
    val measurer = rememberTextMeasurer()
    return remember(text, room, base, maxLines, tight, peak) {
        fun sized(k: Float) = base.copy(fontSize = base.fontSize * k, lineHeight = base.fontSize * k, fontWeight = FontWeight.Black,
            letterSpacing = if (tight) (-0.04).em else base.letterSpacing * k)
        var k = peak * (width / 800.dp).coerceIn(0.5f, 1f)
        while (k > 0.7f && measurer.measure(text, sized(k), maxLines = maxLines, constraints = Constraints(maxWidth = room)).hasVisualOverflow) k *= 0.9f
        sized(k)
    }
}

@Composable
internal fun DriftTabs(labels: List<String>, selected: Int, style: TextStyle, modifier: Modifier = Modifier, gap: Dp = 22.dp, dot: Boolean = false, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val square = isPaperTheme()
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(gap)) {
        labels.forEachIndexed { i, label ->
            val active = i == selected
            val on by animateFloatAsState(if (active) 1f else 0f, Motion.state(), label = "driftTab")
            Text(
                label, style = style, fontWeight = if (active) FontWeight.Black else FontWeight.SemiBold, color = lerp(colors.onSurfaceVariant, colors.onSurface, on), maxLines = 1,
                modifier = Modifier.paperClip(MaterialTheme.shapes.extraSmall).semantics { this.selected = active }
                    .clickable(role = Role.Tab) { onSelect(i) }.pointerHoverIcon(PointerIcon.Hand)
                    .drawBehind {
                        val bar = if (dot) Size(6.dp.toPx(), 6.dp.toPx()) else Size(minOf(28.dp.toPx(), size.width), 3.dp.toPx())
                        val at = Offset(if (dot) (size.width - bar.width) / 2f else if (rtl) size.width - bar.width else 0f, size.height - bar.height)
                        if (dot && !square) drawOval(colors.primary, at, bar, alpha = on) else drawRect(colors.primary, at, bar, alpha = on)
                    }
                    .padding(vertical = 10.dp),
            )
        }
    }
}

@Composable
internal fun DriftHeading(title: String, modifier: Modifier = Modifier, note: String = "", spread: Boolean = false, onOpen: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.headlineLarge.let { it.copy(fontSize = it.fontSize * 1.15f, lineHeight = it.lineHeight * 1.15f) }
    Row(
        modifier.then(if (spread) Modifier.fillMaxWidth() else Modifier).paperClip(MaterialTheme.shapes.small)
            .then(if (onOpen != null) Modifier.clickable(role = Role.Button, onClick = onOpen).pointerHoverIcon(PointerIcon.Hand) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = style, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = spread).alignByBaseline())
        if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp).widthIn(max = 220.dp).alignByBaseline())
        if (onOpen != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, appString(R.string.text_see_all_2941c5), tint = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp).size(24.dp))
    }
}

@Composable
internal fun DriftButton(icon: ImageVector?, label: String, modifier: Modifier = Modifier, iconOnly: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val side = if (icon == null) 30.dp else 16.dp
    CompositionLocalProvider(LocalContentColor provides if (isPaperTheme()) PaperTokens.ink else colors.inverseOnSurface) {
        Row(
            modifier.height(52.dp).paperFrame(CircleShape, { background(colors.inverseSurface) }, fill = PaperTokens.rose)
                .pressClickable(onClickLabel = label, role = Role.Button, onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
                .padding(start = side, end = if (iconOnly) side else side + 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Icon(icon, if (iconOnly) label else null, Modifier.size(22.dp))
            if (!iconOnly) Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1,
                modifier = Modifier.padding(start = if (icon != null) 8.dp else 0.dp))
        }
    }
}

// a cover floats up a little under the pointer; for short rows only, as each one holds its own animation
@Composable
internal fun Modifier.driftLift(): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val lift = animateFloatAsState(if (hovered) 1f else 0f, Motion.state(MotionTokens.STANDARD), label = "driftLift")
    return hoverable(interaction).graphicsLayer { translationY = -6.dp.toPx() * lift.value }
}

// a cover in front of two tilted cards in its own colour; fan runs from 0 to 1 as the cards swing out
@Composable
internal fun FanStack(art: String, accent: Color, cover: Dp, fan: () -> Float, modifier: Modifier = Modifier, mark: @Composable BoxScope.() -> Unit = {}) {
    val shape = driftShape(22.dp)
    val page = MaterialTheme.colorScheme.background
    // a generated cover has no picture to sample, so its accent stands in
    val colour = if (art.isBlank() || art.startsWith(MIX_COVER_PREFIX)) accent else rememberDominantColor(art, accent).value
    val near = lerp(page, colour, 0.6f)
    val far = lerp(page, colour, 0.4f)
    Box(modifier.size(cover + FanOverhang, cover * 1.12f)) {
        Box(Modifier.offset(cover * 0.17f, 0.dp).size(cover * 0.86f).graphicsLayer { rotationZ = 9f * fan() }.paperFrame(shape, { background(far) }, fill = far))
        Box(Modifier.offset(cover * 0.06f, cover * 0.035f).size(cover * 0.9f).graphicsLayer { rotationZ = -6f * fan() }.paperFrame(shape, { background(near) }, fill = near))
        // a cover with no picture is a see-through wash, which must not show the cards behind it
        Box(Modifier.align(Alignment.BottomStart).size(cover).paperClip(shape).background(page)) {
            Artwork(art, accent, Modifier.matchParentSize(), corner = 0.dp)
            mark()
        }
    }
}

@Composable
private fun Section(title: String, note: String = "", onOpen: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        DriftHeading(title, note = note, spread = true, onOpen = onOpen)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

// a shelf beside another may only bleed to the middle of the gap between them
@Composable
private fun <T> Shelf(items: List<T>, paired: Boolean, key: (T) -> Any, min: Dp = 150.dp, content: @Composable (T, Dp) -> Unit) {
    val gutter = LocalPageGutter.current
    AdaptiveShelf(items, minItemWidth = min, spacing = ShelfGap, bleed = if (paired) minOf(PairGap / 2, gutter) else gutter, key = key, itemContent = content)
}

// every kind of shelf tile keeps its art in a box of one height, so a mixed row lines its titles up
@Composable
private fun Tile(art: String, title: String, subtitle: String, width: Dp, centered: Boolean = false, onClick: () -> Unit, cover: @Composable () -> Unit) {
    val align = if (centered) TextAlign.Center else TextAlign.Start
    Column(
        Modifier.width(width).driftLift().paperClip(MaterialTheme.shapes.medium).pressClickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand).coverHover(art),
    ) {
        Box(Modifier.size(width, (width - FanOverhang) * 1.12f + Slack), contentAlignment = Alignment.BottomStart) { cover() }
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = align, modifier = Modifier.fillMaxWidth())
        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = align, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun FlatCover(art: String, accent: Color, size: Dp) {
    Box(Modifier.size(size).paperClip(driftShape(28.dp))) { Artwork(art, accent, Modifier.matchParentSize(), corner = 0.dp) }
}

@Composable
private fun StackTile(playlist: Playlist, width: Dp, fan: () -> Float, onClick: () -> Unit) {
    Tile(playlist.coverUrl, playlist.title, playlist.subtitle, width, onClick = onClick) { FanStack(playlist.coverUrl, playlist.accent, width - FanOverhang, fan) }
}

@Composable
private fun AlbumTile(album: Album, width: Dp, onClick: () -> Unit) {
    val line = listOf(album.artist, album.year.takeIf { it > 0 }?.toString().orEmpty()).filter { it.isNotBlank() }.joinToString(" • ")
    Tile(album.artworkUrl, album.title, line, width, onClick = onClick) { FlatCover(album.artworkUrl, accentFor(album.id), width) }
}

@Composable
private fun ArtistTile(artist: Artist, width: Dp, onClick: () -> Unit) {
    Tile(artist.imageUrl, artist.name, "", width, centered = true, onClick = onClick) {
        Artwork(artist.imageUrl, accentFor(artist.id), Modifier.size(width), corner = width)
    }
}

@Composable
private fun RecentDisc(album: Album, first: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val size = 124.dp
    Column(
        Modifier.width(size).driftLift().paperClip(MaterialTheme.shapes.medium).pressClickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand).coverHover(album.artworkUrl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(size).border(6.dp, if (first) colors.primary else colors.outlineVariant, paperSquare(RoundedCornerShape(themedCorner(size)))).padding(10.dp)) {
            Artwork(album.artworkUrl, accentFor(album.id), Modifier.matchParentSize(), corner = size)
        }
        Spacer(Modifier.height(12.dp))
        Text(album.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (album.artist.isNotBlank()) Text(album.artist, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun Chart(albums: List<Album>, onOpen: (Album) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    Column {
        albums.forEachIndexed { i, album ->
            Row(
                Modifier.fillMaxWidth().height(72.dp).paperClip(MaterialTheme.shapes.medium).clickable { onOpen(album) }.pointerHoverIcon(PointerIcon.Hand)
                    .coverHover(album.artworkUrl).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text((i + 1).toString(), style = type.displayMedium, fontWeight = FontWeight.Black, color = colors.onSurfaceVariant, maxLines = 1, modifier = Modifier.widthIn(min = 34.dp))
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(album.title, style = type.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(album.artist.takeIf { it.isNotBlank() }, album.playCount.takeIf { it > 0 }?.let { appPlural(R.plurals.play_count, it) }).joinToString(" · "),
                        style = type.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(18.dp))
                Artwork(album.artworkUrl, accentFor(album.id), Modifier.size(50.dp), corner = 14.dp)
            }
            HorizontalDivider(color = colors.onSurface.copy(alpha = 0.08f))
        }
    }
}

@Composable
private fun Favourites(songs: List<Song>, width: Dp, onPlay: (Song) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val featured = songs.first()
    val rest = songs.drop(1).take(4)
    val beside = rest.isNotEmpty() && width >= 840.dp
    val square = isPaperTheme()
    val bar = colors.onSurface.copy(alpha = 0.3f)
    val levels = remember(featured.id) { Random(featured.id.hashCode()).let { r -> FloatArray(30) { 0.3f + r.nextFloat() * 0.7f } } }
    val card: @Composable (Modifier) -> Unit = { modifier ->
        Row(
            modifier.paperClip(driftShape(26.dp)).pressClickable { onPlay(featured) }.pointerHoverIcon(PointerIcon.Hand).coverHover(featured.artworkUrl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(132.dp).paperClip(driftShape(26.dp))) { Artwork(featured.artworkUrl, featured.accent, Modifier.matchParentSize(), corner = 0.dp) }
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Text(featured.title, style = type.headlineMedium, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(featured.artist, style = type.bodyLarge, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(56.dp).paperFrame(CircleShape, { background(colors.primary) }, fill = colors.primary), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.PlayArrow, appString(R.string.text_play_5d12bd), tint = colors.onPrimary, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    // a still waveform: nothing on this page seeks
                    Spacer(Modifier.weight(1f).height(44.dp).drawBehind {
                        val step = size.width / levels.size
                        val radius = if (square) CornerRadius.Zero else CornerRadius(2.dp.toPx())
                        for (i in levels.indices) {
                            val h = size.height * levels[i]
                            drawRoundRect(bar, Offset(i * step, (size.height - h) / 2f), Size(step * 0.72f, h), radius)
                        }
                    })
                }
            }
        }
    }
    val rows: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) { rest.forEach { song -> LikedRow(song, showAlbum = !beside && width >= 640.dp) { onPlay(song) } } }
    }
    if (beside) Row(Modifier.padding(top = Slack), horizontalArrangement = Arrangement.spacedBy(PairGap), verticalAlignment = Alignment.CenterVertically) {
        card(Modifier.weight(1f))
        rows(Modifier.weight(1f))
    } else Column(Modifier.padding(top = Slack), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        card(Modifier.fillMaxWidth())
        if (rest.isNotEmpty()) rows(Modifier.fillMaxWidth())
    }
}

private fun steps(d: Float, table: FloatArray): Float {
    val a = abs(d).coerceAtMost(table.lastIndex.toFloat())
    val i = a.toInt().coerceAtMost(table.lastIndex - 1)
    return table[i] + (table[i + 1] - table[i]) * (a - i)
}

// how far a cover's centre sits from the carousel's, with each neighbour butting up to the one before it
private fun slide(d: Float, cover: Float, gap: Float): Float {
    val a = abs(d)
    val first = (1f + CoverScale[1]) / 2f * cover + gap
    val second = (CoverScale[1] + CoverScale[2]) / 2f * cover + gap
    return sign(d) * when {
        a <= 1f -> a * first
        a <= 2f -> first + (a - 1f) * second
        else -> first + second + (a - 2f) * (CoverScale[2] * cover + gap)
    }
}

@Composable
private fun BoxScope.Chevron(visible: Boolean, forward: Boolean, onClick: () -> Unit) {
    AnimatedVisibility(visible, Modifier.zIndex(2f).align(if (forward) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = 12.dp), enter = fadeIn(), exit = fadeOut()) {
        if (forward) HeroArrow(Icons.AutoMirrored.Filled.KeyboardArrowRight, appString(R.string.text_next_bc9819), onClick)
        else HeroArrow(Icons.AutoMirrored.Filled.KeyboardArrowLeft, appString(R.string.text_previous_50f942), onClick)
    }
}

@Composable
private fun Carousel(heroes: List<Hero>, width: Dp, scale: Float, clock: Animatable<Float, AnimationVector1D>, onOpen: (Hero) -> Unit, onPlay: (Hero) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val n = heroes.size
    // three or more go round in a ring, so the centre cover has a neighbour on both sides wherever it stands
    val ring = n > 2
    var at by rememberSaveable(n) { mutableIntStateOf(0) }
    val pos = animateFloatAsState(at.toFloat(), Motion.enter(MotionTokens.EMPHASIZED), label = "driftCarousel")
    val current = at.mod(n)
    val fit = (width / 1100.dp).coerceIn(0.6f, 1f)
    val cover = 330.dp * scale * fit
    val gap = 24.dp * fit
    val button = 84.dp * (0.4f + 0.6f * fit)
    val shape = driftShape(40.dp)
    val turn = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focus = remember { FocusRequester() }
    val play = appString(R.string.text_play_5d12bd)
    // paging by pointer leaves the keys on the carousel, so the arrows carry on from there
    fun go(step: Int, byPointer: Boolean = true) {
        at = if (ring) at + step else (at + step).coerceIn(0, n - 1)
        if (byPointer) focus.requestFocus()
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().height(cover + 44.dp).hoverable(interaction).clipToBounds()
                .onKeyEvent { event ->
                    val step = turn * when (event.key) { Key.DirectionLeft -> -1; Key.DirectionRight -> 1; else -> 0 }
                    if (step != 0 && event.type == KeyEventType.KeyDown) go(step, byPointer = false)
                    step != 0
                }
                .focusRequester(focus).focusable(),
        ) {
            heroes.forEachIndexed { i, hero ->
                val away = if (ring) (i - current + n + n / 2).mod(n) - n / 2 else i - current
                // with an even count the cover opposite the centre has no side to stand on, so it waits out of sight
                val shown = abs(away) <= 2 && (!ring || abs(away) * 2 < n)
                Box(
                    Modifier.zIndex(-abs(away).toFloat()).align(Alignment.TopCenter).padding(top = 10.dp).size(cover)
                        .graphicsLayer {
                            val d = if (ring) (i - pos.value + n / 2f).mod(n.toFloat()) - n / 2f else i - pos.value
                            val grown = steps(d, CoverScale) * (0.8f + 0.2f * Motion.EaseOut.transform(clock.value))
                            scaleX = grown
                            scaleY = grown
                            alpha = steps(d, CoverAlpha) * if (ring) ((n / 2f - abs(d)) * 2f).coerceIn(0f, 1f) else 1f
                            translationX = turn * slide(d, cover.toPx(), gap.toPx())
                        }
                        .paperClip(shape)
                        .then(if (!shown) Modifier else Modifier.pressClickable(onClickLabel = hero.title) { if (away == 0) onOpen(hero) else go(away) }
                            .pointerHoverIcon(PointerIcon.Hand).coverHover(hero.art)),
                ) { Artwork(hero.art, hero.accent, Modifier.matchParentSize(), corner = 0.dp) }
            }
            Box(
                Modifier.zIndex(1f).align(Alignment.TopCenter).offset(cover / 2 + 22.dp - button / 2, 32.dp + cover - button)
                    .graphicsLayer {
                        val p = Motion.EaseOut.transform(((clock.value - 0.5f) * 2f).coerceIn(0f, 1f))
                        scaleX = 0.6f + 0.4f * p
                        scaleY = scaleX
                        alpha = p
                    }
                    .size(button)
                    .paperFrame(CircleShape, { background(colors.background).padding(6.dp).clip(CircleShape).background(colors.primary) }, fill = colors.primary)
                    .pressClickable(onClickLabel = play, role = Role.Button) { onPlay(heroes[current]) }.pointerHoverIcon(PointerIcon.Hand),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.PlayArrow, play, tint = colors.onPrimary, modifier = Modifier.size(button * 0.38f)) }
            Chevron(hovered && (ring || at > 0), false) { go(-1) }
            Chevron(hovered && (ring || at < n - 1), true) { go(1) }
        }
        Column(Modifier.fillMaxWidth().rise(clock, 1), horizontalAlignment = Alignment.CenterHorizontally) {
            val k = 1.1f * fit.coerceAtLeast(0.75f)
            val title = type.displayLarge.let { it.copy(fontSize = it.fontSize * k, lineHeight = it.lineHeight * k) }
            Crossfade(heroes[current], animationSpec = Motion.state(), label = "driftHero") { hero ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Eyebrow(hero.label, colors.primary, maxLines = 1)
                    Spacer(Modifier.height(8.dp))
                    Text(hero.title, style = title, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    Text(listOf(hero.subtitle, hero.meta).filter { it.isNotBlank() }.joinToString(" · "), style = type.titleLarge, fontWeight = FontWeight.Normal,
                        color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (n > 1) Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(n) { i ->
                    val bar by animateDpAsState(if (i == current) 22.dp else 6.dp, Motion.state(), label = "driftBar")
                    val tint by animateColorAsState(if (i == current) colors.primary else colors.onSurface.copy(alpha = 0.25f), Motion.state(), label = "driftBarTint")
                    Box(Modifier.height(6.dp).width(bar).paperClip(CircleShape).background(tint))
                }
            }
        }
    }
}
