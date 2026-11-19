package com.aurora.music.ui.screens.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aurora.music.R
import com.aurora.music.data.SearchResults
import com.aurora.music.localization.appString
import com.aurora.music.ui.components.AdaptiveShelf
import com.aurora.music.ui.components.AlbumCard
import com.aurora.music.ui.components.ArtistCircle
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.Eyebrow
import com.aurora.music.ui.components.PageSection
import com.aurora.music.ui.components.PlaylistCard
import com.aurora.music.ui.components.SectionHeader
import com.aurora.music.ui.components.coverHover
import com.aurora.music.ui.components.shelfColumns
import com.aurora.music.ui.layout.LocalPageGutter
import com.aurora.music.ui.layout.PageMetrics
import com.aurora.music.ui.layout.pagePadding
import com.aurora.music.ui.screens.home.SpotlightPill
import com.aurora.music.ui.screens.home.SpotlightTile
import com.aurora.music.ui.screens.home.TileGrid
import com.aurora.music.ui.screens.home.TwoThirds
import com.aurora.music.ui.screens.home.rememberEntrance
import com.aurora.music.ui.screens.home.rise
import com.aurora.music.ui.screens.home.spotSurface
import com.aurora.music.ui.screens.home.spotlightTint
import com.aurora.music.ui.theme.Motion
import com.aurora.music.ui.theme.PaperTokens
import com.aurora.music.ui.theme.isPaperTheme
import com.aurora.music.ui.theme.paperClip
import com.aurora.music.ui.theme.paperFrame
import com.aurora.music.ui.theme.pressClickable
import com.aurora.music.util.MotionTokens
import com.aurora.music.util.accentFor
import java.util.Locale

// narrower than this and the top result and the songs stack, as home's blocks do
private val SplitMinWidth = 860.dp
private val CompactWidth = 720.dp
private val BlockGap = 40.dp
private val TileMinWidth = 132.dp
private val CircleMinWidth = 104.dp
private val ListTableWidth = 560.dp
private const val TopSongs = 5

@Composable
internal fun SearchSpotlight(p: SearchParts) {
    val gutter = LocalPageGutter.current
    val hit = remember(p.held) { p.held?.let { topHit(it.state.query, it.state.results) } }
    val tint by animateColorAsState(spotlightTint(hit?.art.orEmpty(), hit?.accent ?: Color.Unspecified), Motion.state(MotionTokens.STANDARD), label = "searchBand")
    val clock = rememberEntrance()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth - gutter * 2
        val compact = maxWidth < CompactWidth
        Column(Modifier.fillMaxSize()) {
            Band(p, p.held?.state?.results, tint, width, compact, Modifier.rise(clock, 0))
            SearchBody(p, Modifier.weight(1f).rise(clock, 1), idle = { Idle(p, it) }) { Results(p, it, width) }
        }
    }
}

@Composable
private fun Band(p: SearchParts, found: SearchResults?, tint: Color, width: Dp, compact: Boolean, modifier: Modifier) {
    val gutter = LocalPageGutter.current
    val sources = p.state.sources
    val pills = remember(found) { found?.let(::filtersFor).orEmpty() }
    val chosen = found?.let(p::filterFor)
    val picker: @Composable (Modifier) -> Unit = { row ->
        Row(row.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            sources.forEach { source -> SpotlightPill(source.label, source.id == p.state.selectedSource) { p.onSelectSource(source.id) } }
        }
    }
    Column(
        modifier.fillMaxWidth()
            .paperFrame(RectangleShape, { background(Brush.verticalGradient(0f to tint, 0.62f to tint.copy(alpha = tint.alpha * 0.6f), 1f to tint.copy(alpha = 0f))) }, fill = tint)
            .padding(start = gutter, end = gutter, top = 28.dp, bottom = 22.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Field(p, Modifier.weight(1f, fill = false).widthIn(max = PageMetrics.SearchFieldMaxWidth).fillMaxWidth())
            if (sources.size > 1 && !compact) picker(Modifier.padding(start = 16.dp).widthIn(max = (width - 320.dp).coerceAtLeast(0.dp)))
        }
        if (sources.size > 1 && compact) picker(Modifier.padding(top = 12.dp))
        AnimatedVisibility(
            pills.isNotEmpty(),
            enter = fadeIn(Motion.enter()) + expandVertically(Motion.enter()),
            exit = fadeOut(Motion.exit()) + shrinkVertically(Motion.exit()),
        ) {
            Row(Modifier.padding(top = 18.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pills.forEach { filter -> SpotlightPill(filter.label, filter == chosen) { p.onFilter(filter) } }
            }
        }
    }
}

@Composable
private fun Field(p: SearchParts, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val wash = colors.onSurface.copy(alpha = 0.12f)
    Row(
        modifier.height(60.dp).paperFrame(CircleShape, { background(wash) }).padding(start = 22.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, null, tint = colors.onSurface, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        SearchInput(p, MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), Modifier.weight(1f), hintColor = colors.onSurface.copy(alpha = 0.6f))
        if (p.state.query.isNotEmpty()) Icon(
            Icons.Filled.Close, appString(R.string.text_clear_719ea3), tint = colors.onSurface,
            modifier = Modifier.size(44.dp).paperClip(CircleShape).background(wash).clickable { p.onQuery("") }.pointerHoverIcon(PointerIcon.Hand).padding(12.dp),
        )
    }
}

@Composable
private fun Results(p: SearchParts, shown: SearchPane, width: Dp) {
    val gutter = LocalPageGutter.current
    val found = shown.state.results
    val filter = p.filterFor(found)
    val songs = found.songs
    val hit = remember(shown) { topHit(shown.state.query, found) }
    val split = width >= SplitMinWidth
    val gap = when (filter) {
        SearchFilter.ALL -> 28.dp
        SearchFilter.SONGS -> 0.dp
        else -> 24.dp
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding(p.contentPadding, top = bandGap()), verticalArrangement = Arrangement.spacedBy(gap)) {
        if (filter != SearchFilter.ALL) {
            oneKind(filter, found, width, p, numbered = true)
            return@LazyColumn
        }
        val list: @Composable () -> Unit = {
            Column {
                SectionHeader(appString(R.string.text_songs_e1404b),
                    action = appString(R.string.text_see_all_2941c5).takeIf { songs.size > TopSongs }, onAction = { p.onFilter(SearchFilter.SONGS) })
                Spacer(Modifier.height(PageMetrics.HeaderToContent))
                repeat(songs.size.coerceAtMost(TopSongs)) { p.Track(songs, it, index = it + 1, tableMinWidth = ListTableWidth) }
            }
        }
        if (hit != null) item(key = "top") {
            val beside = split && songs.isNotEmpty()
            val card: @Composable () -> Unit = { Hero(hit, flat = !beside, onOpen = { p.open(hit, songs) }, onPlay = p.play(hit, songs)) }
            if (beside) TopRow(card, list)
            else Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                Box(Modifier.fillMaxWidth().height(176.dp)) { card() }
                if (songs.isNotEmpty()) list()
            }
        }
        if (found.albums.isNotEmpty() || found.artists.isNotEmpty()) item(key = "albums+artists") {
            val beside = split && found.albums.isNotEmpty() && found.artists.isNotEmpty()
            // a shelf beside another may only bleed to the middle of the gap between them
            val bleed = if (beside) BlockGap / 2 else gutter
            TwoThirds(beside, Modifier,
                main = if (found.albums.isEmpty()) null else ({
                    Shelf(SearchFilter.ALBUMS, found.albums, if (beside) (width - BlockGap) * 2 / 3 else width, TileMinWidth, bleed, p) { album, w ->
                        AlbumCard(album, onClick = { p.onOpenDetail("album", album.id) }, width = w)
                    }
                }),
                side = if (found.artists.isEmpty()) null else ({
                    Shelf(SearchFilter.ARTISTS, found.artists, if (beside) (width - BlockGap) / 3 else width, CircleMinWidth, bleed, p) { artist, w ->
                        ArtistCircle(artist, onClick = { p.onOpenDetail("artist", artist.id) }, width = w)
                    }
                }),
            )
        }
        if (found.playlists.isNotEmpty()) item(key = "playlists") {
            Shelf(SearchFilter.PLAYLISTS, found.playlists, width, TileMinWidth, gutter, p) { playlist, w ->
                PlaylistCard(playlist, onClick = { p.onOpenDetail("playlist", playlist.id) }, width = w)
            }
        }
    }
}

@Composable
private fun <T> Shelf(kind: SearchFilter, items: List<T>, room: Dp, min: Dp, bleed: Dp, p: SearchParts, card: @Composable (T, Dp) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        // headers that sit side by side share a height, whether or not one carries an action
        SectionHeader(kind.label, Modifier.heightIn(min = 32.dp), appString(R.string.text_see_all_2941c5).takeIf { items.size > shelfColumns(room, min) }, onAction = { p.onFilter(kind) })
        Spacer(Modifier.height(PageMetrics.HeaderToContent))
        AdaptiveShelf(items, minItemWidth = min, bleed = bleed, itemContent = card)
    }
}

@Composable
private fun Hero(hit: TopHit, flat: Boolean, onOpen: () -> Unit, onPlay: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    val tint = spotlightTint(hit.art, hit.accent)
    val soft = colors.onSurface.copy(alpha = 0.85f)
    val play = appString(R.string.text_play_5d12bd)
    val frame = Modifier.fillMaxSize().paperFrame(MaterialTheme.shapes.extraLarge, { background(tint) }, fill = tint)
        .pressClickable(onClick = onOpen).pointerHoverIcon(PointerIcon.Hand).coverHover(hit.art).padding(26.dp)
    val cover: @Composable () -> Unit = { Artwork(hit.art, hit.accent, Modifier.size(124.dp), corner = if (hit.round) 124.dp else 18.dp) }
    val words: @Composable (Modifier) -> Unit = { column ->
        Column(column) {
            Eyebrow("${appString(R.string.search_top_result)} · ${hit.type}".uppercase(Locale.getDefault()), soft, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Text(hit.title, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (hit.line.isNotBlank()) Text(hit.line, style = MaterialTheme.typography.bodyMedium, color = soft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp))
        }
    }
    val button: @Composable () -> Unit = {
        if (onPlay != null) Box(
            Modifier.padding(start = 12.dp).size(58.dp).paperFrame(CircleShape, { background(colors.inverseSurface) }, fill = PaperTokens.rose)
                .pressClickable(onClickLabel = play, role = Role.Button, onClick = onPlay).pointerHoverIcon(PointerIcon.Hand),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.PlayArrow, play, tint = if (isPaperTheme()) PaperTokens.ink else colors.inverseOnSurface, modifier = Modifier.size(26.dp)) }
    }
    if (flat) Row(frame, verticalAlignment = Alignment.CenterVertically) {
        cover()
        words(Modifier.weight(1f).padding(start = 24.dp))
        button()
    } else Column(frame) {
        cover()
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.Bottom) {
            words(Modifier.weight(1f))
            button()
        }
    }
}

// paper frames the band, so what follows keeps clear of its rule
@Composable
private fun bandGap(): Dp = if (isPaperTheme()) 20.dp else 6.dp

@Composable
private fun Idle(p: SearchParts, shown: SearchPane) {
    if (shown.recents.isEmpty() && p.jumpBackIn.isEmpty()) return SearchNotice(shown, Modifier.fillMaxSize())
    val colors = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding(p.contentPadding, top = bandGap()), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        if (shown.recents.isNotEmpty()) item(key = "recent") {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionHeader(appString(R.string.text_recent_76eec7), Modifier.weight(1f))
                    Text(appString(R.string.text_clear_719ea3), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant, maxLines = 1,
                        modifier = Modifier.paperClip(CircleShape).clickable(role = Role.Button, onClick = p.onClearRecents).pointerHoverIcon(PointerIcon.Hand)
                            .padding(horizontal = 10.dp, vertical = 6.dp))
                }
                Spacer(Modifier.height(PageMetrics.HeaderToContent))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    shown.recents.forEach { query -> RecentPill(query, onClick = { p.onRecentClick(query) }, onRemove = { p.onRemoveRecent(query) }) }
                }
            }
        }
        if (p.jumpBackIn.isNotEmpty()) item(key = "jump") {
            PageSection(appString(R.string.text_jump_back_in_1d9181)) {
                TileGrid {
                    p.jumpBackIn.forEach { album ->
                        SpotlightTile(album.artworkUrl, accentFor(album.id), album.title, album.artist, Modifier.spotSurface(MaterialTheme.shapes.medium),
                            colors.onSurfaceVariant) { p.onJumpBack("album", album.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentPill(query: String, onClick: () -> Unit, onRemove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.height(44.dp).widthIn(max = 320.dp).paperFrame(CircleShape, { background(colors.surface) }).clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand).padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.History, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(query, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false))
        Icon(Icons.Filled.Close, appString(R.string.text_remove_e96390), tint = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp).size(32.dp).paperClip(CircleShape).clickable(onClick = onRemove).pointerHoverIcon(PointerIcon.Hand).padding(8.dp))
    }
}
