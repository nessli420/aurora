package com.aurora.music.ui.screens.search

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.aurora.music.R
import com.aurora.music.data.SearchResults
import com.aurora.music.localization.appString
import com.aurora.music.model.Song
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.Eyebrow
import com.aurora.music.ui.components.LocalStartRadio
import com.aurora.music.ui.components.SongListHeader
import com.aurora.music.ui.components.coverHover
import com.aurora.music.ui.layout.LocalPageGutter
import com.aurora.music.ui.screens.home.ConsoleButton
import com.aurora.music.ui.screens.home.ConsoleLabel
import com.aurora.music.ui.screens.home.ConsoleModule
import com.aurora.music.ui.screens.home.ConsoleSegments
import com.aurora.music.ui.screens.home.QuickModule
import com.aurora.music.ui.screens.home.TileGrid
import com.aurora.music.ui.screens.home.consoleInset
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
import java.util.Locale

private val SideWidth = 244.dp
private val InspectWidth = 300.dp
private val PaneGap = 14.dp
// narrower than the first and the page is one column; from the second the top result has a pane of its own
private val CompactWidth = 720.dp
private val RoomyWidth = 1000.dp
private val TableWidth = 640.dp
private val CellMinWidth = 190.dp
private val QuickCover = 104.dp
private const val TopSongs = 8
private const val TopCells = 8

@Composable
internal fun SearchConsole(p: SearchParts) {
    val gutter = LocalPageGutter.current
    val found = p.held?.state?.results
    val filters = remember(found) { found?.let(::filtersFor).orEmpty() }
    val chosen = found?.let(p::filterFor)
    val sources = p.state.sources
    val recents = p.pane.recents
    val clock = rememberEntrance()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val side = maxWidth >= CompactWidth
        val three = maxWidth >= RoomyWidth
        val bottom = p.contentPadding.calculateBottomPadding() + 12.dp
        val body: @Composable (Modifier) -> Unit = { pane ->
            SearchPanes(p.pane, pane) { shown ->
                val idle = shown.kind == Pane.HINT || shown.kind == Pane.RECENTS
                when {
                    shown.kind == Pane.RESULTS -> Results(p, shown, three)
                    idle && (p.jumpBackIn.isNotEmpty() || !side && shown.recents.isNotEmpty()) -> Column(verticalArrangement = Arrangement.spacedBy(PaneGap)) {
                        if (!side && shown.recents.isNotEmpty()) Recents(p, shown.recents, Modifier.weight(1f, fill = false))
                        if (p.jumpBackIn.isNotEmpty()) BoxWithConstraints {
                            QuickModule(p.jumpBackIn, ((maxWidth - 36.dp + PaneGap) / (QuickCover + PaneGap)).toInt().coerceIn(2, 8), p.onJumpBack)
                        }
                    }
                    else -> ConsoleModule(Modifier.fillMaxSize(), lift = false) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { SearchNotice(shown) }
                    }
                }
            }
        }
        if (side) Row(
            Modifier.fillMaxSize().padding(start = gutter, end = gutter, top = 20.dp, bottom = bottom),
            horizontalArrangement = Arrangement.spacedBy(PaneGap),
        ) {
            Column(Modifier.width(SideWidth).fillMaxHeight().rise(clock, 0), verticalArrangement = Arrangement.spacedBy(PaneGap)) {
                Field(p)
                if (found != null) Module(appString(R.string.text_show_d97d1e)) {
                    filters.forEach { filter -> PaneRow(null, filter.label, filter.count(found).toString(), filter == chosen) { p.onFilter(filter) } }
                }
                if (sources.size > 1) Module(appString(R.string.text_source_6da13a)) {
                    sources.forEach { source -> PaneRow(sourceIcon(source.id), source.label, "", source.id == p.state.selectedSource) { p.onSelectSource(source.id) } }
                }
                if (recents.isNotEmpty()) Recents(p, recents, Modifier.weight(1f, fill = false))
            }
            body(Modifier.weight(1f).fillMaxHeight().rise(clock, 1))
        } else Column(
            Modifier.fillMaxSize().padding(start = gutter, end = gutter, top = 20.dp, bottom = bottom),
            verticalArrangement = Arrangement.spacedBy(PaneGap),
        ) {
            Field(p)
            if (sources.size > 1) ConsoleSegments(sources.map { it.label }, sources.indexOfFirst { it.id == p.state.selectedSource }) { p.onSelectSource(sources[it].id) }
            if (found != null) ConsoleSegments(filters.map { "${it.label} ${it.count(found)}" }, filters.indexOf(chosen)) { p.onFilter(filters[it]) }
            body(Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun Field(p: SearchParts) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    val recess = consoleInset()
    val edge by animateColorAsState(if (p.state.query.isEmpty() || isPaperTheme()) Color.Transparent else colors.primary, Motion.state(), label = "consoleField")
    Row(
        Modifier.fillMaxWidth().height(56.dp).border(1.dp, edge, shape).spotSurface(shape, outlined = true).padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(6.dp))
        SearchInput(p, MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), Modifier.weight(1f))
        if (p.state.query.isNotEmpty()) Icon(
            Icons.Filled.Close, appString(R.string.text_clear_719ea3), tint = colors.onSurface,
            modifier = Modifier.size(40.dp).paperFrame(MaterialTheme.shapes.small, { background(recess) }, fill = PaperTokens.plate)
                .clickable { p.onQuery("") }.pointerHoverIcon(PointerIcon.Hand).padding(10.dp),
        )
    }
}

@Composable
private fun Module(label: String, modifier: Modifier = Modifier, count: String = "", trailing: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    ConsoleModule(modifier.fillMaxWidth(), padding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp), lift = false) {
        Row(Modifier.heightIn(min = 40.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ConsoleLabel(label)
            Text(count, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 8.dp))
            trailing()
        }
        content()
    }
}

@Composable
private fun PaneRow(icon: ImageVector?, label: String, trail: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val on by animateFloatAsState(if (selected) 1f else 0f, Motion.state(), label = "paneRow")
    val recess = consoleInset()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val ink = if (isPaperTheme()) PaperTokens.ink else lerp(colors.onSurfaceVariant, colors.onSurface, on)
    Row(
        Modifier.fillMaxWidth().height(40.dp).paperClip(MaterialTheme.shapes.small)
            .drawBehind {
                val mark = 3.dp.toPx()
                drawRect(recess, alpha = on)
                drawRect(colors.primary, Offset(if (rtl) size.width - mark else 0f, 0f), Size(mark, size.height), alpha = on)
            }
            .selectable(selected, role = Role.Tab, onClick = onClick).pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, tint = ink, modifier = Modifier.padding(end = 10.dp).size(18.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (trail.isNotBlank()) Text(trail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun Recents(p: SearchParts, recents: List<String>, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Module(appString(R.string.text_recent_76eec7), modifier, trailing = {
        Text(appString(R.string.text_clear_719ea3), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1,
            modifier = Modifier.paperClip(MaterialTheme.shapes.small).clickable(role = Role.Button, onClick = p.onClearRecents).pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = 10.dp, vertical = 8.dp))
    }) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            recents.forEach { query ->
                Row(
                    Modifier.fillMaxWidth().height(40.dp).paperClip(MaterialTheme.shapes.small).clickable { p.onRecentClick(query) }.pointerHoverIcon(PointerIcon.Hand)
                        .padding(start = 12.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.History, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    Text(query, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                    Icon(Icons.Filled.Close, appString(R.string.text_remove_e96390), tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(32.dp).paperClip(CircleShape).clickable { p.onRemoveRecent(query) }.pointerHoverIcon(PointerIcon.Hand).padding(8.dp))
                }
            }
        }
    }
}

// the short form a chip has room for: the container, then depth over rate where the file says both, else its bitrate
private fun Song.format(): String = listOfNotNull(
    suffix.uppercase(Locale.ROOT).takeIf { it.isNotBlank() },
    if (bitDepth > 0 && sampleRateHz > 0) "$bitDepth/${sampleRateHz / 1000}" else bitrateKbps.takeIf { it > 0 }?.toString(),
).joinToString(" ")

@Composable
private fun Results(p: SearchParts, shown: SearchPane, three: Boolean) {
    val found = shown.state.results
    val filter = p.filterFor(found)
    val songs = found.songs
    val hit = remember(shown) { topHit(shown.state.query, found) }
    val table: @Composable (Modifier) -> Unit = { pane -> Table(p, found, filter, artists = !three, pane) }
    if (three) Row(horizontalArrangement = Arrangement.spacedBy(PaneGap)) {
        table(Modifier.weight(1f).fillMaxHeight())
        Column(Modifier.width(InspectWidth).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(PaneGap)) {
            if (hit != null) TopModule(p, hit, songs, stacked = true)
            if (filter != SearchFilter.ARTISTS && found.artists.isNotEmpty()) Module(SearchFilter.ARTISTS.label, Modifier.weight(1f, fill = false), found.artists.size.toString()) {
                LazyColumn {
                    items(found.artists.size) { i ->
                        val artist = found.artists[i].hit()
                        Entry(artist, Modifier.fillMaxWidth(), flat = true) { p.onOpenDetail(artist.kind, artist.id) }
                    }
                }
            }
        }
    } else Column(verticalArrangement = Arrangement.spacedBy(PaneGap)) {
        if (hit != null && filter == SearchFilter.ALL) TopModule(p, hit, songs, stacked = false)
        table(Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable
private fun Table(p: SearchParts, found: SearchResults, filter: SearchFilter, artists: Boolean, modifier: Modifier) {
    val songs = found.songs
    val all = filter == SearchFilter.ALL
    val formats = remember(songs) { songs.any { it.format().isNotBlank() } }
    val cells: @Composable (SearchFilter, List<TopHit>) -> Unit = { kind, entries ->
        if (all) Heading(kind, entries.size, if (entries.size > TopCells) ({ p.onFilter(kind) }) else null)
        // shortcut: a kind's cells are one list item, fine for a search's few dozen results; chunk them into lazy rows if a source returns hundreds
        TileGrid(Modifier.padding(horizontal = 6.dp), minWidth = CellMinWidth) {
            (if (all) entries.take(TopCells) else entries).forEach { entry -> Entry(entry, Modifier) { p.onOpenDetail(entry.kind, entry.id) } }
        }
    }
    ConsoleModule(modifier, padding = PaddingValues(start = 6.dp, end = 6.dp, top = 4.dp), lift = false) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
            if ((all || filter == SearchFilter.SONGS) && songs.isNotEmpty()) {
                item(key = "songs") {
                    Heading(SearchFilter.SONGS, songs.size, if (all && songs.size > TopSongs) ({ p.onFilter(SearchFilter.SONGS) }) else null)
                    SongListHeader(badge = appString(R.string.text_format_041a5d).takeIf { formats }, tableMinWidth = TableWidth)
                }
                items(if (all) songs.size.coerceAtMost(TopSongs) else songs.size) { i ->
                    p.Track(songs, i, index = i + 1, badge = songs[i].format().takeIf { formats }, tableMinWidth = TableWidth)
                }
            }
            if ((all || filter == SearchFilter.ALBUMS) && found.albums.isNotEmpty()) item(key = "albums") { cells(SearchFilter.ALBUMS, found.albums.map { it.hit() }) }
            if ((all && artists || filter == SearchFilter.ARTISTS) && found.artists.isNotEmpty()) item(key = "artists") { cells(SearchFilter.ARTISTS, found.artists.map { it.hit() }) }
            if ((all || filter == SearchFilter.PLAYLISTS) && found.playlists.isNotEmpty()) item(key = "playlists") { cells(SearchFilter.PLAYLISTS, found.playlists.map { it.hit() }) }
        }
    }
}

@Composable
private fun Heading(kind: SearchFilter, count: Int, onOpenAll: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().paperClip(MaterialTheme.shapes.small)
            .then(if (onOpenAll != null) Modifier.clickable(role = Role.Button, onClick = onOpenAll).pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .heightIn(min = 44.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConsoleLabel(kind.label)
        Spacer(Modifier.width(8.dp))
        Text(count.toString(), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1)
        Spacer(Modifier.weight(1f))
        if (onOpenAll != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, appString(R.string.text_see_all_2941c5), tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun Entry(entry: TopHit, modifier: Modifier, flat: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val recess = consoleInset()
    val shape = if (flat) MaterialTheme.shapes.small else MaterialTheme.shapes.medium
    Row(
        modifier.height(if (flat) 56.dp else 64.dp)
            .then(if (flat) Modifier.paperClip(shape).clickable(onClick = onClick) else Modifier.paperFrame(shape, { background(recess) }, fill = PaperTokens.plate).pressClickable(onClick = onClick))
            .pointerHoverIcon(PointerIcon.Hand).coverHover(entry.art).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(entry.art, entry.accent, Modifier.size(if (flat) 40.dp else 44.dp), corner = if (entry.round) 44.dp else 9.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(entry.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (entry.subtitle.isNotBlank()) Text(entry.subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (flat) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun TopModule(p: SearchParts, hit: TopHit, songs: List<Song>, stacked: Boolean) {
    val colors = MaterialTheme.colorScheme
    val soft = colors.onSurface.copy(alpha = 0.85f)
    val song = songs.getOrNull(hit.songIndex)
    val chips = listOfNotNull(hit.type, song?.format()?.takeIf { it.isNotBlank() }) + hit.counts
    val play = p.play(hit, songs)
    val shuffle = p.play(hit, songs, shuffle = true)
    val startRadio = LocalStartRadio.current
    val seed = when (hit.kind) {
        "song" -> song
        "artist" -> songs.firstOrNull { it.artistId == hit.id }
        "album" -> songs.firstOrNull { it.albumId == hit.id }
        else -> null
    }
    val words: @Composable () -> Unit = {
        Text(hit.title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (hit.subtitle.isNotBlank()) Text(hit.subtitle, style = MaterialTheme.typography.bodyMedium, color = soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            chips.forEach { chip ->
                Text(chip.uppercase(Locale.getDefault()), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = colors.onSurface, maxLines = 1,
                    modifier = Modifier.border(1.dp, colors.onSurface.copy(alpha = 0.3f), MaterialTheme.shapes.extraSmall).padding(horizontal = 7.dp, vertical = 3.dp))
            }
        }
    }
    val keys: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (play != null) ConsoleButton(Icons.Filled.PlayArrow, appString(R.string.text_play_5d12bd), onClick = play)
            if (shuffle != null) Key(Icons.Filled.Shuffle, appString(R.string.text_shuffle_5b772b), shuffle)
            if (startRadio != null && seed != null) Key(Icons.Filled.Radio, appString(R.string.discovery_start_radio)) { startRadio(seed) }
        }
    }
    ConsoleModule(Modifier.fillMaxWidth().coverHover(hit.art), tint = spotlightTint(hit.art, hit.accent), onClick = { p.open(hit, songs) }) {
        Eyebrow(appString(R.string.search_top_result).uppercase(Locale.getDefault()), soft, maxLines = 1)
        Spacer(Modifier.height(12.dp))
        if (stacked) {
            Artwork(hit.art, hit.accent, Modifier.fillMaxWidth().aspectRatio(1.5f), corner = 14.dp)
            Spacer(Modifier.height(14.dp))
            words()
            Spacer(Modifier.height(14.dp))
            keys()
        } else Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(hit.art, hit.accent, Modifier.size(96.dp), corner = 14.dp)
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) { words() }
            keys()
        }
    }
}

@Composable
private fun Key(icon: ImageVector, label: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.size(46.dp).paperFrame(MaterialTheme.shapes.small, { background(colors.onSurface.copy(alpha = 0.12f)) })
            .pressClickable(onClickLabel = label, role = Role.Button, onClick = onClick).pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = colors.onSurface, modifier = Modifier.size(20.dp)) }
}
