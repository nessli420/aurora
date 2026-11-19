package com.aurora.music.ui.screens.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.aurora.music.data.TabletSetting
import com.aurora.music.data.ThemeStyle
import com.aurora.music.data.label
import com.aurora.music.data.starredQueue
import com.aurora.music.desktop.ui.LocalDesktopContainer
import com.aurora.music.desktop.ui.LocalPlayer
import com.aurora.music.foryou.GeneratedMix
import com.aurora.music.foryou.MIX_KIND
import com.aurora.music.foryou.MixKind
import com.aurora.music.localization.AppStrings
import com.aurora.music.localization.appPlural
import com.aurora.music.localization.appString
import com.aurora.music.localization.asPlaylist
import com.aurora.music.localization.localizedSignalLabel
import com.aurora.music.model.Album
import com.aurora.music.model.Song
import com.aurora.music.model.accent
import com.aurora.music.ui.components.AdaptiveShelf
import com.aurora.music.ui.components.AlbumCard
import com.aurora.music.ui.components.ArtistCircle
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.Eyebrow
import com.aurora.music.ui.components.FeedTrackCard
import com.aurora.music.ui.components.LocalHiddenSongs
import com.aurora.music.ui.components.LottieLoader
import com.aurora.music.ui.components.PageHeader
import com.aurora.music.ui.components.PlaylistCard
import com.aurora.music.ui.components.TrackGrid
import com.aurora.music.ui.components.coverHover
import com.aurora.music.ui.layout.LocalPageGutter
import com.aurora.music.ui.layout.pagePadding
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

private val Gap = 14.dp
private val Inset = 18.dp
private val TopHeight = 228.dp
private val TileHeight = 200.dp
private val QuickCover = 104.dp
// a shelf stretches its covers until a whole number fits, which from here lands them around 128
private val CoverWidth = 116.dp
// the shelf beside the chart draws larger covers, so the two end near the same height
private val LeadCoverWidth = 160.dp
private val ChartBasis = 440.dp
private val ShelfBasis = 520.dp
private val Solo = 100_000.dp

// a module's share of a wrapping row, as css flex-grow and flex-basis; lazy marks one that cannot say how tall it will be before it is measured
private class Cell(val key: String, val grow: Float, val basis: Dp, val lazy: Boolean = false, val content: @Composable (width: Dp) -> Unit)

private class Featured(val label: String, val title: String, val subtitle: String, val art: String, val accent: Color, val mix: String, val item: HomeFeedItem?)

private class Week(val millis: Long, val streak: Int, val days: FloatArray, val today: Int)

@Composable
internal fun HomeConsole(
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
    onOpenSignalPath: (() -> Unit)?,
    onOpenStats: (() -> Unit)?,
) {
    val hiddenSongs = LocalHiddenSongs.current
    val data = remember(state.data, hiddenSongs) { state.data.copy(starred = hiddenSongs.visible(state.data.starred)) }
    val sections = remember(data.sections, hiddenSongs) {
        if (hiddenSongs.songs.isEmpty()) data.sections
        else data.sections.map { section -> section.copy(items = section.items.filterNot { it is HomeFeedItem.Track && hiddenSongs.hides(it.song) }) }
    }
    val ui = LocalUiPrefs.current
    val hidden = ui.hiddenHomeSections
    val gutter = LocalPageGutter.current
    val container = LocalDesktopContainer.current
    val loading = state.loading && data == HomeData()
    val blank = loading || state.nothingToShow(data)
    val hero = remember(data.newReleases, data.sections, hidden) { if (HomeSection.HERO in hidden) null else heroesOf(data).firstOrNull() }
    val chart = remember(data.mostPlayed, hidden) { if (HomeSection.MOST in hidden) emptyList() else data.mostPlayed.take(5) }
    val recent = if (HomeSection.RECENT in hidden) emptyList() else data.recentlyPlayed
    val starred = if (HomeSection.FAVOURITE in hidden) emptyList() else data.starred
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
    val hasHistory = remember { container.playHistory.history.value.isNotEmpty() }
    val week by produceState<Week?>(null) { value = withContext(Dispatchers.Default) { weekOf(container.playHistory) } }
    val showWeek = hasHistory || week != null
    val clock = rememberEntrance()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth - gutter * 2
        val status = buildList {
            if (hero != null) add(Cell("hero", 5f, 480.dp) { w ->
                HeroModule(hero, minOf(192.dp * (ui.tabletHeroScale / TabletSetting.HERO.default), w * 0.38f), onOpen = { onOpenDetail(hero.kind, hero.id) }) { shuffle ->
                    if (hero.kind == "album" && !shuffle) onPlayAlbum(hero.id) else onPlayCollection(hero.kind, hero.id, shuffle)
                }
            })
            add(Cell("signal", 4f, 320.dp) { SignalModule(onOpenSignalPath) })
            if (showWeek) add(Cell("week", 3f, 240.dp) { WeekModule(week, clock, onOpenStats) })
        }
        val tiles = buildList {
            if (recent.isNotEmpty()) add(Cell("quick", 7f, 560.dp) { w ->
                QuickModule(recent, ((w - Inset * 2 + Gap) / (QuickCover + Gap)).toInt().coerceIn(2, 8), onOpenDetail)
            })
            if (showLiked) add(Cell("liked", 2f, 170.dp) { LikedModule(state.allStarred.size) { onOpenDetail("liked", likedId) } })
            if (featured != null) add(Cell("featured", 3f, 220.dp) {
                FeaturedModule(featured) {
                    val item = featured.item
                    if (item == null) onOpenDetail(MIX_KIND, featured.mix)
                    else sections.firstOrNull()?.let { openFeedItem(item, it, onOpenDetail, onPlayAll) }
                }
            })
        }
        val lead = !blank && chart.isNotEmpty() && ChartBasis + Gap + ShelfBasis <= width
        val shelves = buildList<Cell> {
            shelfMixes.forEach { (title, row) ->
                shelf("mixes:$title", appString(title), row, lead, itemKey = { it.id }) { mix, w -> PlaylistCard(mix, { onOpenDetail(MIX_KIND, mix.id) }, width = w) }
            }
            if (blank) return@buildList
            if (HomeSection.PLAYLISTS !in hidden) shelf("playlists", appString(R.string.text_your_playlists_df03eb), data.playlists, lead) { playlist, w ->
                PlaylistCard(playlist, { onOpenDetail("playlist", playlist.id) }, width = w)
            }
            listOf(
                Triple(HomeSection.RECOMMENDED, R.string.text_recommended_albums_36271e, data.random),
                Triple(HomeSection.NEW, R.string.text_new_releases_3cdd02, data.newReleases),
            ).forEach { (section, title, albums) ->
                if (section !in hidden) shelf(section, appString(title), albums, lead) { album, w -> AlbumCard(album, { onOpenDetail("album", album.id) }, width = w) }
            }
            if (HomeSection.ARTISTS !in hidden) shelf("artists", appString(R.string.text_artists_1528d8), data.artists, lead) { artist, w ->
                ArtistCircle(artist, { onOpenDetail("artist", artist.id) }, width = w)
            }
            shelf("favourites", appString(R.string.text_from_your_favourites_d39722), starred, lead, onSeeAll = { onOpenDetail("liked", "liked") }, itemKey = { it.id }) { song, w ->
                FeedTrackCard(song, w) { starredQueue(state.allStarred, data.starred, song).let { (songs, at) -> onPlayAll(songs, at) } }
            }
            sections.forEach { feed(it, lead, onOpenDetail, onPlayAll) }
        }
        val lines = buildList {
            if (!blank) {
                // without jump back in, its two tiles would stretch across a line of their own
                if (recent.isEmpty()) addAll(wrap(status + tiles, width, fromEnd = true))
                else {
                    addAll(wrap(status, width, fromEnd = true))
                    addAll(wrap(tiles, width, fromEnd = true))
                }
            }
            if (!blank && chart.isNotEmpty()) {
                addAll(wrap(listOf(Cell("most", 5f, ChartBasis) { ChartModule(chart, clock, onOpenDetail) }) + shelves.take(1), width, fromEnd = true))
                addAll(wrap(shelves.drop(1), width))
            } else addAll(wrap(shelves, width))
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = pagePadding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(Gap),
        ) {
            item(key = "header", contentType = "header") {
                PageHeader(greeting(), eyebrow = state.feeds.firstOrNull { it.id == state.selectedFeed }?.label ?: sourceLabel) {
                    if (state.feeds.size > 1) ConsoleSegments(state.feeds.map { it.label }, state.feeds.indexOfFirst { it.id == state.selectedFeed },
                        Modifier.widthIn(max = (width - 320.dp).coerceAtLeast(0.dp))) { onSelectFeed(state.feeds[it].id) }
                    RecapInboxButton(onOpenNotifications)
                }
            }
            lines.forEachIndexed { i, line ->
                item(key = line.first().key, contentType = "modules") { ModuleRow(line, width, if (i < 4) Modifier.rise(clock, i) else Modifier) }
            }
            if (loading) item(key = "loading") {
                Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) { LottieLoader(modifier = Modifier.size(80.dp)) }
            } else if (blank) item(key = "empty") { HomeEmptyState(onAddSource) }
            else if (data.continuation != null || state.error != null) item(key = "feed-more") { HomeFeedMore(state, onLoadMore, onRetry) }
        }
    }
}

// css flex-wrap; filling from the end leaves a group's lead module the line to itself
private fun wrap(cells: List<Cell>, width: Dp, fromEnd: Boolean = false): List<List<Cell>> {
    if (fromEnd) return wrap(cells.asReversed(), width).asReversed().map { it.asReversed() }
    val lines = mutableListOf<MutableList<Cell>>()
    var used = 0.dp
    cells.forEach { cell ->
        if (lines.isEmpty() || used + Gap + cell.basis > width) {
            lines += mutableListOf(cell)
            used = cell.basis
        } else {
            lines.last() += cell
            used += Gap + cell.basis
        }
    }
    return lines
}

// a line's modules share its height, which a row cannot ask of the lazy ones
@Composable
private fun ModuleRow(cells: List<Cell>, width: Dp, modifier: Modifier = Modifier) {
    val spare = cells.fold(width - Gap * (cells.size - 1)) { left, cell -> left - cell.basis }
    val grow = cells.fold(0f) { sum, cell -> sum + cell.grow }
    val widths = cells.map { (it.basis + spare * (it.grow / grow)).coerceAtLeast(1.dp) }
    Layout({ cells.forEachIndexed { i, cell -> Box(propagateMinConstraints = true) { cell.content(widths[i]) } } }, modifier.fillMaxWidth()) { measurables, constraints ->
        val gap = Gap.roundToPx()
        val room = constraints.maxWidth - gap * (cells.size - 1)
        val total = widths.fold(0f) { sum, w -> sum + w.value }
        var left = room
        val px = IntArray(cells.size) { i -> (if (i == cells.lastIndex) left else (room * widths[i].value / total).roundToInt()).also { left -= it } }
        var height = cells.indices.filterNot { cells[it].lazy }.maxOfOrNull { measurables[it].maxIntrinsicHeight(px[it]) } ?: 0
        val placeables = arrayOfNulls<Placeable>(cells.size)
        cells.indices.filter { cells[it].lazy }.forEach { i ->
            placeables[i] = measurables[i].measure(Constraints(px[i], px[i], height)).also { height = maxOf(height, it.height) }
        }
        cells.indices.filterNot { cells[it].lazy }.forEach { i -> placeables[i] = measurables[i].measure(Constraints.fixed(px[i], height)) }
        layout(constraints.maxWidth, height) {
            var x = 0
            placeables.forEachIndexed { i, placeable ->
                placeable?.placeRelative(x, 0)
                x += px[i] + gap
            }
        }
    }
}

private fun <T> MutableList<Cell>.shelf(
    key: String,
    title: String,
    items: List<T>,
    lead: Boolean,
    subtitle: String = "",
    onSeeAll: (() -> Unit)? = null,
    itemKey: ((T) -> Any)? = null,
    card: @Composable (T, Dp) -> Unit,
) {
    if (items.isEmpty()) return
    val cover = if (isEmpty() && lead) LeadCoverWidth else CoverWidth
    add(Cell(key, 7f, ShelfBasis, lazy = true) {
        TitledModule(title, subtitle, onSeeAll) {
            Box(Modifier.padding(horizontal = Inset)) { AdaptiveShelf(items, minItemWidth = cover, spacing = Gap, bleed = Inset, key = itemKey, itemContent = card) }
        }
    })
}

private fun MutableList<Cell>.feed(section: HomeFeedSection, lead: Boolean, onOpenDetail: (String, String) -> Unit, onPlayAll: (List<Song>, Int) -> Unit) {
    val tracks = section.items.filterIsInstance<HomeFeedItem.Track>().map { it.song }
    if (tracks.isNotEmpty() && tracks.size == section.items.size) add(Cell("feed:${section.id}", 1f, Solo, lazy = true) {
        TitledModule(section.title, section.subtitle) {
            Box(Modifier.padding(horizontal = Inset - 6.dp)) { TrackGrid(tracks) { index -> onPlayAll(tracks, index) } }
        }
    }) else shelf("feed:${section.id}", section.title, section.items, lead, section.subtitle, itemKey = { it.key }) { entry, w ->
        when (entry) {
            is HomeFeedItem.Record -> AlbumCard(entry.album, { onOpenDetail("album", entry.album.id) }, width = w)
            is HomeFeedItem.Collection -> PlaylistCard(entry.playlist, { onOpenDetail("playlist", entry.playlist.id) }, width = w)
            is HomeFeedItem.Performer -> ArtistCircle(entry.artist, { onOpenDetail("artist", entry.artist.id) }, width = w)
            is HomeFeedItem.Track -> FeedTrackCard(entry.song, w) { openFeedItem(entry, section, onOpenDetail, onPlayAll) }
        }
    }
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

// a recess inside a module: the next surface step on aurora, a wash of the ink in the other styles
@Composable
internal fun consoleInset(): Color = MaterialTheme.colorScheme.let {
    if (LocalUiPrefs.current.themeStyle == ThemeStyle.AURORA) it.surfaceContainer else it.onSurface.copy(alpha = 0.07f)
}

// the one container every console module sits in, so a theme style restyles them all here
@Composable
internal fun ConsoleModule(
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    padding: PaddingValues = PaddingValues(Inset),
    lift: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = moduleShape()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val raised = animateFloatAsState(if (hovered && lift) 1f else 0f, Motion.state(), label = "module")
    val edge = lerp(colors.surfaceContainerHigh, colors.onSurfaceVariant, 0.34f)
    val hairline = LocalUiPrefs.current.themeStyle == ThemeStyle.AURORA
    val surface = if (tint.isUnspecified) Modifier.spotSurface(shape, outlined = true) else Modifier.paperFrame(shape, { background(tint) }, fill = tint)
    Column(
        modifier.hoverable(interaction).graphicsLayer { translationY = -2.dp.toPx() * raised.value }.then(surface)
            .drawWithContent {
                drawContent()
                // half of the stroke falls outside the clip, which leaves a hairline
                if (hairline && raised.value > 0f) drawOutline(shape.createOutline(size, layoutDirection, this), edge, raised.value, Stroke(2.dp.toPx()))
            }
            .then(if (onClick != null) Modifier.pressClickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .padding(padding),
        content = content,
    )
}

@Composable
internal fun ConsoleLabel(text: String, modifier: Modifier = Modifier) =
    Eyebrow(text.uppercase(Locale.getDefault()), MaterialTheme.colorScheme.onSurfaceVariant, modifier, maxLines = 1)

@Composable
internal fun ConsoleSegments(labels: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val paper = isPaperTheme()
    val on = when (LocalUiPrefs.current.themeStyle) {
        ThemeStyle.AURORA -> colors.surfaceContainerHigh
        ThemeStyle.PAPER -> colors.primary
        else -> colors.onSurface.copy(alpha = 0.12f)
    }
    FlowRow(
        modifier.spotSurface(MaterialTheme.shapes.medium, outlined = true).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val active = i == selected
            val fill = animateColorAsState(if (active) on else on.copy(alpha = 0f), Motion.state(), label = "segment")
            Box(
                Modifier.height(44.dp).paperClip(MaterialTheme.shapes.small).drawBehind { drawRect(fill.value) }
                    .semantics { this.selected = active }.clickable(role = Role.Tab) { onSelect(i) }.pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (paper) PaperTokens.ink else if (active) colors.onSurface else colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun ConsoleButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, iconOnly: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    CompositionLocalProvider(LocalContentColor provides if (isPaperTheme()) PaperTokens.ink else colors.inverseOnSurface) {
        Row(
            modifier.height(46.dp).paperFrame(MaterialTheme.shapes.small, { background(colors.inverseSurface) }, fill = PaperTokens.rose)
                .pressClickable(onClickLabel = label, role = Role.Button, onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
                .padding(start = if (iconOnly) 13.dp else 16.dp, end = if (iconOnly) 13.dp else 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, if (iconOnly) label else null, Modifier.size(20.dp))
            if (!iconOnly) {
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

@Composable
private fun HeroModule(hero: Hero, cover: Dp, onOpen: () -> Unit, onPlay: (shuffle: Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val soft = colors.onSurface.copy(alpha = 0.85f)
    val title = type.displayLarge.let { it.copy(fontSize = it.fontSize * 0.8f, lineHeight = it.lineHeight * 0.8f) }
    val shuffle = appString(R.string.text_shuffle_5b772b)
    ConsoleModule(Modifier.heightIn(min = TopHeight).coverHover(hero.art), tint = spotlightTint(hero.art, hero.accent), onClick = onOpen) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Artwork(hero.art, hero.accent, Modifier.size(cover), corner = 18.dp)
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f).heightIn(min = cover)) {
                Eyebrow(hero.label, soft, maxLines = 1)
                Spacer(Modifier.height(8.dp))
                Text(hero.title, style = title, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Text(hero.subtitle, style = type.bodyLarge, color = soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (hero.meta.isNotBlank()) Text(hero.meta, style = type.bodySmall, color = colors.onSurface.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ConsoleButton(Icons.Filled.PlayArrow, appString(R.string.text_play_5d12bd)) { onPlay(false) }
                    Box(
                        Modifier.size(46.dp).paperFrame(MaterialTheme.shapes.small, { background(colors.onSurface.copy(alpha = 0.12f)) })
                            .pressClickable(onClickLabel = shuffle, role = Role.Button) { onPlay(true) }.pointerHoverIcon(PointerIcon.Hand),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.Shuffle, shuffle, tint = colors.onSurface, modifier = Modifier.size(20.dp)) }
                }
            }
        }
    }
}

@Composable
internal fun SignalModule(onClick: (() -> Unit)?, modifier: Modifier = Modifier.heightIn(min = TopHeight), stacked: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val player = LocalPlayer.current
    // the flow polls twice a second and its meters differ every time, so they are dropped before anything here reads it
    val path by remember(player) { player.signalPath.map { it.copy(measurements = null, nodeMeters = emptyList()) }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(player.signalPath.value.copy(measurements = null, nodeMeters = emptyList()))
    val playing by remember(player) { player.state.map { it.current }.distinctUntilChanged() }.collectAsStateWithLifecycle(player.state.value.current)
    ConsoleModule(modifier, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ConsoleLabel(appString(R.string.text_signal_path_c3e29b))
            if (path.active) Box(Modifier.weight(1f).padding(start = 10.dp), contentAlignment = Alignment.CenterEnd) {
                Text(
                    appString(when (path.preservation) {
                        Preservation.PRESERVED -> R.string.text_samples_preserved_091001
                        Preservation.MODIFIED -> R.string.text_samples_modified_d04e9f
                        Preservation.UNKNOWN -> R.string.text_sample_preservation_unknown_7de2de
                    }),
                    style = type.labelMedium, fontWeight = FontWeight.Bold, color = if (isPaperTheme()) PaperTokens.ink else colors.primary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.paperFrame(MaterialTheme.shapes.extraSmall, { background(colors.primary.copy(alpha = 0.18f)) }, fill = colors.primary)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        if (!path.active) {
            Spacer(Modifier.weight(1f))
            Text(appString(R.string.text_nothing_playing_13ae37), style = type.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(appString(R.string.signal_start_playback), style = type.bodySmall, color = colors.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            return@ConsoleModule
        }
        val format = listOfNotNull(
            path.codec.takeIf { it.isNotBlank() },
            path.sampleRateHz.takeIf { it > 0 }?.let { appString(R.string.text_1f_khz_92ed69, it / 1000f) },
            path.bitDepth.takeIf { it > 0 }?.let { appString(R.string.text_bit_fd7850, it) },
        ).joinToString(" · ").ifBlank { path.source.detail }.ifBlank { appString(R.string.text_unknown_bc7819) }
        Spacer(Modifier.height(Gap))
        val nodes: @Composable (Modifier) -> Unit = { node ->
            PathNode(Icons.Filled.GraphicEq, format, "Source".localizedSignalLabel(), node, stacked)
            // shortcut: the engine's width is written out, as it is fixed at binary64; read it from the decoder stage if that ever varies
            PathNode(Icons.Filled.Memory, appString(R.string.text_64_bit_float_eaae87), "Processing".localizedSignalLabel(), node, stacked)
            PathNode(Icons.AutoMirrored.Filled.VolumeUp, path.output.localizedSignalLabel().ifBlank { appString(R.string.text_output_unknown_ef4fdb) },
                "Output".localizedSignalLabel(), node, stacked)
        }
        if (stacked) Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { nodes(Modifier.weight(1f).fillMaxWidth()) }
        else Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) { nodes(Modifier.weight(1f).fillMaxHeight()) }
        if (playing.id.isNotEmpty() && !stacked) Text(
            listOf(appString(R.string.text_now_playing_586fa7).lowercase().replaceFirstChar { it.titlecase() }, playing.title, playing.artist)
                .filter { it.isNotBlank() }.joinToString(" · "),
            style = type.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Gap),
        )
    }
}

@Composable
private fun PathNode(icon: ImageVector, title: String, label: String, modifier: Modifier, stacked: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val fill = consoleInset()
    val frame = modifier.paperFrame(MaterialTheme.shapes.medium, { background(fill) }, fill = PaperTokens.plate)
    if (stacked) Row(frame.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = colors.primary, modifier = Modifier.size(24.dp))
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    else Column(frame.padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Icon(icon, null, tint = colors.primary, modifier = Modifier.size(24.dp))
        Column(Modifier.padding(top = 12.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(label, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun WeekModule(week: Week?, clock: Animatable<Float, AnimationVector1D>, onClick: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val square = isPaperTheme()
    val rest = colors.onSurface.copy(alpha = 0.22f)
    ConsoleModule(Modifier.heightIn(min = TopHeight), onClick = onClick) {
        ConsoleLabel(RecapPeriod.WEEK.label)
        if (week == null) return@ConsoleModule
        Spacer(Modifier.height(8.dp))
        Text(listeningDuration(week.millis), style = type.headlineLarge, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (week.streak > 0) appString(R.string.text_day_streak_661711, week.streak) else appString(R.string.text_no_active_streak_7f1a27),
            style = type.labelLarge, fontWeight = FontWeight.SemiBold, color = colors.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.padding(top = 12.dp).fillMaxWidth().weight(1f).heightIn(min = 70.dp).drawBehind {
            val gap = 8.dp.toPx()
            val bar = (size.width - gap * 6) / 7
            val radius = if (square) CornerRadius.Zero else CornerRadius(4.dp.toPx())
            val grown = Motion.EaseOut.transform(clock.value)
            for (day in 0 until 7) {
                val height = maxOf(4.dp.toPx(), size.height * week.days[day] * grown)
                val at = if (layoutDirection == LayoutDirection.Rtl) 6 - day else day
                drawRoundRect(if (day == week.today) colors.primary else rest, Offset(at * (bar + gap), size.height - height), Size(bar, height), radius)
            }
        })
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(7) { day ->
                Text(DayOfWeek.MONDAY.plus(day.toLong()).getDisplayName(java.time.format.TextStyle.NARROW, AppStrings.locale), style = type.bodySmall,
                    color = if (day == week.today) colors.onSurface else colors.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun QuickModule(albums: List<Album>, columns: Int, onOpenDetail: (String, String) -> Unit) {
    ConsoleModule {
        ConsoleLabel(appString(R.string.text_jump_back_in_1d9181))
        Spacer(Modifier.height(Gap))
        Row(horizontalArrangement = Arrangement.spacedBy(Gap)) {
            albums.take(columns).forEach { album ->
                Column(
                    Modifier.weight(1f).paperClip(MaterialTheme.shapes.small).pressClickable { onOpenDetail("album", album.id) }
                        .pointerHoverIcon(PointerIcon.Hand).coverHover(album.artworkUrl),
                ) {
                    Artwork(album.artworkUrl, accentFor(album.id), Modifier.fillMaxWidth().aspectRatio(1f), corner = 14.dp)
                    Spacer(Modifier.height(8.dp))
                    Text(album.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(album.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            repeat(columns - albums.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun LikedModule(count: Int, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    ConsoleModule(Modifier.heightIn(min = TileHeight), onClick = onOpen) {
        Icon(Icons.Filled.Favorite, null, tint = colors.primary, modifier = Modifier.size(26.dp))
        Spacer(Modifier.weight(1f))
        // 0 doubles as not counted yet, so no number rather than a wrong 0
        if (count > 0) Text(count.toString(), style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Black, maxLines = 1)
        Text(appString(R.string.text_liked_songs_58c3a9), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FeaturedModule(item: Featured, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val scrim = colors.background.copy(alpha = 0.8f)
    ConsoleModule(Modifier.heightIn(min = TileHeight).coverHover(item.art), padding = PaddingValues(0.dp), onClick = onClick) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Artwork(item.art, item.accent, Modifier.matchParentSize(), corner = 0.dp)
            Text(item.label.uppercase(Locale.getDefault()), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = colors.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp).paperClip(MaterialTheme.shapes.extraSmall).background(scrim).padding(horizontal = 9.dp, vertical = 4.dp))
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(scrim).padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (item.subtitle.isNotBlank()) Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ChartModule(albums: List<Album>, clock: Animatable<Float, AnimationVector1D>, onOpenDetail: (String, String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val square = isPaperTheme()
    val top = albums.maxOf { it.playCount }
    val track = colors.onSurface.copy(alpha = 0.1f)
    ConsoleModule(padding = PaddingValues(start = 10.dp, end = 10.dp, top = Inset, bottom = 8.dp)) {
        ConsoleLabel(appString(R.string.text_most_played_14202e), Modifier.padding(horizontal = 8.dp))
        Spacer(Modifier.height(6.dp))
        albums.forEach { album ->
            Row(
                Modifier.fillMaxWidth().weight(1f).heightIn(min = 48.dp).paperClip(MaterialTheme.shapes.small).clickable { onOpenDetail("album", album.id) }
                    .pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(album.artworkUrl, accentFor(album.id), Modifier.size(40.dp), corner = 9.dp)
                Spacer(Modifier.width(Gap))
                Column(Modifier.weight(1f)) {
                    Row {
                        Text(album.title, style = type.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).alignByBaseline())
                        if (album.playCount > 0) Text(appPlural(R.plurals.play_count, album.playCount), style = type.bodySmall, color = colors.onSurfaceVariant, maxLines = 1,
                            modifier = Modifier.padding(start = 10.dp).alignByBaseline())
                    }
                    if (top <= 0) Text(album.artist, style = type.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    else {
                        val share = album.playCount / top.toFloat()
                        Spacer(Modifier.padding(top = 8.dp).fillMaxWidth().height(4.dp).drawBehind {
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
private fun TitledModule(title: String, subtitle: String, onSeeAll: (() -> Unit)? = null, body: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    ConsoleModule(padding = PaddingValues(top = Inset - 6.dp, bottom = Inset)) {
        Row(Modifier.heightIn(min = 30.dp).padding(start = Inset, end = Inset - 10.dp), verticalAlignment = Alignment.CenterVertically) {
            ConsoleLabel(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
            if (onSeeAll != null) Text(appString(R.string.text_see_all_2941c5), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant, maxLines = 1,
                modifier = Modifier.paperClip(CircleShape).clickable(role = Role.Button, onClick = onSeeAll).pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 10.dp, vertical = 6.dp))
        }
        Spacer(Modifier.height(8.dp))
        body()
    }
}
