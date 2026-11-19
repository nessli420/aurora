package com.aurora.music.ui.screens.search

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aurora.music.R
import com.aurora.music.localization.appString
import com.aurora.music.model.Album
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.coverHover
import com.aurora.music.ui.layout.LocalPageGutter
import com.aurora.music.ui.layout.PageMetrics
import com.aurora.music.ui.screens.home.ConsoleLabel
import com.aurora.music.ui.screens.home.DriftTabs
import com.aurora.music.ui.screens.home.driftLift
import com.aurora.music.ui.screens.home.driftTitleStyle
import com.aurora.music.ui.screens.home.rememberEntrance
import com.aurora.music.ui.screens.home.rise
import com.aurora.music.ui.theme.Motion
import com.aurora.music.ui.theme.paperClip
import com.aurora.music.ui.theme.paperFrame
import com.aurora.music.ui.theme.pressClickable
import com.aurora.music.util.accentFor

// narrower than this and the top result gives its column up, as library's header tightens
private val CompactWidth = 720.dp
private val ColumnGap = 56.dp
private val ListTableWidth = 520.dp
private val StripItemWidth = 210.dp
private val StripLabelWidth = 132.dp
private const val TopSongs = 6
private const val TopRows = 4

@Composable
internal fun SearchDrift(p: SearchParts) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val gutter = LocalPageGutter.current
    val found = p.held?.state?.results
    val filters = remember(found) { found?.let(::filtersFor).orEmpty() }
    val sources = p.state.sources
    val clock = rememberEntrance()
    val fan = { Motion.EaseOut.transform(clock.value) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth - gutter * 2
        val two = maxWidth >= CompactWidth
        Column(Modifier.fillMaxSize().padding(start = gutter, end = gutter, top = PageMetrics.HeaderTop, bottom = p.contentPadding.calculateBottomPadding())) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(ColumnGap)) {
                Column(Modifier.weight(0.58f).fillMaxHeight()) {
                    Column(Modifier.rise(clock, 0)) {
                        sources.firstOrNull { it.id == p.state.selectedSource }?.let { ConsoleLabel(appString(R.string.text_search_in_0dcb13, it.label), Modifier.padding(bottom = 4.dp)) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { Headline(p) }
                            // the room is held either way, so the first letter typed does not resize the line
                            Box(Modifier.size(40.dp)) {
                                if (p.state.query.isNotEmpty()) Icon(Icons.Filled.Close, appString(R.string.text_clear_719ea3), tint = colors.onSurface,
                                    modifier = Modifier.matchParentSize().paperClip(CircleShape).clickable { p.onQuery("") }.pointerHoverIcon(PointerIcon.Hand).padding(9.dp))
                            }
                        }
                        HorizontalDivider(Modifier.padding(top = 12.dp), color = colors.outlineVariant)
                    }
                    FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp).rise(clock, 1), horizontalArrangement = Arrangement.SpaceBetween) {
                        if (found != null) DriftTabs(filters.map { it.label }, filters.indexOf(p.filterFor(found)), type.titleMedium, Modifier.align(Alignment.CenterVertically),
                            gap = 28.dp, dot = true) { p.onFilter(filters[it]) }
                        if (sources.size > 1) Row(Modifier.align(Alignment.CenterVertically).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            sources.forEach { source -> Source(source.label, source.id == p.state.selectedSource) { p.onSelectSource(source.id) } }
                        }
                    }
                    SearchBody(p, Modifier.weight(1f).rise(clock, 2), idle = { Recents(p, it) }) { Found(p, it, leadWithTop = !two) }
                }
                if (two) SearchPanes(p.pane, Modifier.weight(0.42f).fillMaxHeight().rise(clock, 1)) { shown ->
                    if (shown.kind == Pane.RESULTS) Fan(p, shown, fan)
                }
            }
            SearchPanes(p.pane, Modifier.rise(clock, 3)) { shown ->
                val albums = shown.state.results.albums
                when {
                    shown.kind == Pane.RESULTS && albums.isNotEmpty() && p.filterFor(shown.state.results) == SearchFilter.ALL ->
                        Strip(SearchFilter.ALBUMS.label, albums.size.toString(), albums, width) { p.onOpenDetail("album", it.id) }
                    (shown.kind == Pane.HINT || shown.kind == Pane.RECENTS) && p.jumpBackIn.isNotEmpty() ->
                        Strip(appString(R.string.text_jump_back_in_1d9181), "", p.jumpBackIn, width) { p.onJumpBack("album", it.id) }
                }
            }
        }
    }
}

@Composable
private fun Headline(p: SearchParts) {
    val colors = MaterialTheme.colorScheme
    val hint = appString(R.string.text_search_bce064)
    val words = p.state.query.ifEmpty { hint }
    val measurer = rememberTextMeasurer()
    val caretRoom = with(LocalDensity.current) { 24.dp.roundToPx() }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // the caret stands past the last letter, so the words leave it room
        val room = (constraints.maxWidth - caretRoom).coerceAtLeast(0)
        val style = driftTitleStyle(words, room, maxWidth, peak = 3.6f)
        // a line longer than its room scrolls under the field's own caret, which a drawn one cannot follow
        val fits = remember(words, style, room) { !measurer.measure(words, style, maxLines = 1, constraints = Constraints(maxWidth = room)).hasVisualOverflow }
        SearchInput(p, style, Modifier.fillMaxWidth(), hint = hint, hintColor = colors.onSurface.copy(alpha = 0.25f), caret = fits)
    }
}

@Composable
private fun Source(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val ink by animateColorAsState(if (selected) colors.onSurface else colors.onSurfaceVariant, Motion.state(), label = "driftSource")
    val dot by animateColorAsState(if (selected) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.5f), Motion.state(), label = "driftSourceDot")
    Row(
        Modifier.paperClip(MaterialTheme.shapes.extraSmall).selectable(selected, role = Role.RadioButton, onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).paperClip(CircleShape).background(dot))
        Spacer(Modifier.width(7.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = ink, maxLines = 1)
    }
}

@Composable
private fun Found(p: SearchParts, shown: SearchPane, leadWithTop: Boolean) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val found = shown.state.results
    val filter = p.filterFor(found)
    val all = filter == SearchFilter.ALL
    val songs = found.songs
    val title = type.headlineMedium
    val hit = remember(shown, leadWithTop, all) { if (leadWithTop && all) topHit(shown.state.query, found) else null }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
        if (hit != null) item(key = "top") {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp).paperClip(MaterialTheme.shapes.medium).clickable { p.open(hit, songs) }.pointerHoverIcon(PointerIcon.Hand)
                    .coverHover(hit.art).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(hit.art, hit.accent, Modifier.size(84.dp), corner = if (hit.round) 84.dp else 18.dp)
                Column(Modifier.weight(1f).padding(horizontal = 18.dp)) {
                    ConsoleLabel("${appString(R.string.search_top_result)} · ${hit.type}")
                    Text(hit.title, style = type.headlineLarge, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (hit.line.isNotBlank()) Text(hit.line, style = type.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                p.play(hit, songs)?.let { AccentPlay(it) }
            }
        }
        if (all || filter == SearchFilter.SONGS) items(if (all) songs.size.coerceAtMost(TopSongs) else songs.size) { i ->
            val playing = songs[i].id == p.currentSongId && p.isPlaying
            p.Track(songs, i, showArt = false, titleStyle = title, tableMinWidth = ListTableWidth, lead = {
                Text("%02d".format(i + 1), style = type.displaySmall, fontWeight = FontWeight.Black, maxLines = 1,
                    color = if (playing) colors.primary else colors.onSurface.copy(alpha = 0.25f), modifier = Modifier.width(52.dp))
            })
        }
        if (filter == SearchFilter.ALBUMS) rows(null, found.albums.map { it.hit() }, p)
        if (all || filter == SearchFilter.ARTISTS) rows(SearchFilter.ARTISTS.takeIf { all }, found.artists.map { it.hit() }, p)
        if (all || filter == SearchFilter.PLAYLISTS) rows(SearchFilter.PLAYLISTS.takeIf { all }, found.playlists.map { it.hit() }, p)
    }
}

private fun LazyListScope.rows(label: SearchFilter?, entries: List<TopHit>, p: SearchParts) {
    if (entries.isEmpty()) return
    if (label != null) item(key = label.name) {
        ConsoleLabel(label.label, Modifier.padding(top = 20.dp).paperClip(MaterialTheme.shapes.extraSmall).clickable(role = Role.Button) { p.onFilter(label) }
            .pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 8.dp, vertical = 8.dp))
    }
    items(if (label != null) entries.size.coerceAtMost(TopRows) else entries.size) { i ->
        val entry = entries[i]
        Row(
            Modifier.fillMaxWidth().height(72.dp).paperClip(MaterialTheme.shapes.medium).clickable { p.onOpenDetail(entry.kind, entry.id) }.pointerHoverIcon(PointerIcon.Hand)
                .coverHover(entry.art).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(entry.art, entry.accent, Modifier.size(52.dp), corner = if (entry.round) 52.dp else 14.dp)
            Column(Modifier.weight(1f).padding(start = 18.dp)) {
                Text(entry.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(entry.subtitle.ifBlank { entry.type }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Fan(p: SearchParts, shown: SearchPane, fan: () -> Float) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val found = shown.state.results
    val hit = remember(shown) { topHit(shown.state.query, found) } ?: return
    val behind = remember(shown) { found.albums.filter { it.id != hit.id }.take(2) }
    val play = p.play(hit, found.songs)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cover = minOf(360.dp, maxWidth * 0.72f, maxHeight * 0.54f)
        Column(Modifier.padding(top = 20.dp)) {
            Box(Modifier.size(cover * 1.56f, cover)) {
                behind.getOrNull(1)?.let { album ->
                    Artwork(album.artworkUrl, accentFor(album.id), corner = 40.dp,
                        modifier = Modifier.offset(cover * 0.72f, cover * 0.17f).size(cover * 0.83f).graphicsLayer { rotationZ = 10f * fan(); alpha = 0.35f })
                }
                behind.getOrNull(0)?.let { album ->
                    Artwork(album.artworkUrl, accentFor(album.id), corner = 40.dp,
                        modifier = Modifier.offset(cover * 0.44f, cover * 0.08f).size(cover * 0.89f).graphicsLayer { rotationZ = 5f * fan(); alpha = 0.6f })
                }
                Box(Modifier.size(cover).paperClip(if (hit.round) CircleShape else MaterialTheme.shapes.extraLarge).background(colors.background)
                    .pressClickable(onClickLabel = hit.title) { p.open(hit, found.songs) }.pointerHoverIcon(PointerIcon.Hand).coverHover(hit.art)) {
                    Artwork(hit.art, hit.accent, Modifier.matchParentSize(), corner = 0.dp)
                }
            }
            ConsoleLabel("${appString(R.string.search_top_result)} · ${hit.type}", Modifier.padding(top = 28.dp))
            Row(Modifier.padding(top = 4.dp).widthIn(max = cover * 1.3f), verticalAlignment = Alignment.CenterVertically) {
                Text(hit.title, style = type.displayLarge, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (play != null) AccentPlay(play, Modifier.padding(start = 18.dp))
            }
            if (hit.line.isNotBlank()) Text(hit.line, style = type.bodyLarge, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun AccentPlay(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val label = appString(R.string.text_play_5d12bd)
    Icon(Icons.Filled.PlayArrow, label, tint = colors.onPrimary,
        modifier = modifier.size(56.dp).paperFrame(CircleShape, { background(colors.primary) }, fill = colors.primary)
            .pressClickable(onClickLabel = label, role = Role.Button, onClick = onClick).pointerHoverIcon(PointerIcon.Hand).padding(16.dp))
}

@Composable
private fun Recents(p: SearchParts, shown: SearchPane) {
    if (shown.recents.isEmpty()) {
        if (p.jumpBackIn.isEmpty()) SearchNotice(shown, Modifier.fillMaxSize())
        return
    }
    val colors = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.headlineLarge.let { it.copy(fontSize = it.fontSize * 1.15f, lineHeight = it.lineHeight * 1.15f) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
        item(key = "label") {
            Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ConsoleLabel(appString(R.string.text_recent_76eec7), Modifier.weight(1f))
                Text(appString(R.string.text_clear_719ea3), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant, maxLines = 1,
                    modifier = Modifier.paperClip(CircleShape).clickable(role = Role.Button, onClick = p.onClearRecents).pointerHoverIcon(PointerIcon.Hand)
                        .padding(horizontal = 10.dp, vertical = 8.dp))
            }
        }
        items(shown.recents.size) { i ->
            val query = shown.recents[i]
            val ink = when {
                i == 0 -> colors.onSurface
                i < 3 -> colors.onSurfaceVariant
                else -> colors.onSurfaceVariant.copy(alpha = 0.6f)
            }
            Row(
                Modifier.fillMaxWidth().paperClip(MaterialTheme.shapes.medium).clickable { p.onRecentClick(query) }.pointerHoverIcon(PointerIcon.Hand)
                    .padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(query, style = style, fontWeight = FontWeight.Black, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.Close, appString(R.string.text_remove_e96390), tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(36.dp).paperClip(CircleShape).clickable { p.onRemoveRecent(query) }.pointerHoverIcon(PointerIcon.Hand).padding(9.dp))
            }
        }
    }
}

@Composable
private fun Strip(title: String, note: String, albums: List<Album>, width: Dp, onOpen: (Album) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val fit = ((width - StripLabelWidth) / StripItemWidth).toInt().coerceIn(1, 6)
    Column {
        HorizontalDivider(color = colors.outlineVariant)
        Row(Modifier.padding(top = 20.dp, bottom = 22.dp), horizontalArrangement = Arrangement.spacedBy(26.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(StripLabelWidth - 26.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1)
            }
            albums.take(fit).forEach { album ->
                Row(
                    Modifier.weight(1f).driftLift().paperClip(MaterialTheme.shapes.medium).pressClickable { onOpen(album) }.pointerHoverIcon(PointerIcon.Hand)
                        .coverHover(album.artworkUrl),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(album.artworkUrl, accentFor(album.id), Modifier.size(64.dp), corner = 18.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(album.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (album.artist.isNotBlank()) Text(album.artist, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            repeat(fit - albums.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}
