package com.aurora.music.ui.screens.library

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import com.aurora.music.R
import com.aurora.music.data.LibraryTabs
import com.aurora.music.data.Pin
import com.aurora.music.data.PlaylistFolder
import com.aurora.music.localization.appPlural
import com.aurora.music.localization.appString
import com.aurora.music.model.LibraryFilter
import com.aurora.music.model.LibraryLayout
import com.aurora.music.model.LibrarySort
import com.aurora.music.model.label
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.coverHover
import com.aurora.music.ui.screens.home.DriftButton
import com.aurora.music.ui.screens.home.DriftHeading
import com.aurora.music.ui.screens.home.DriftTabs
import com.aurora.music.ui.screens.home.DriftTitle
import com.aurora.music.ui.screens.home.FanStack
import com.aurora.music.ui.screens.home.SpotlightPill
import com.aurora.music.ui.screens.home.driftLift
import com.aurora.music.ui.screens.home.driftShape
import com.aurora.music.ui.screens.home.rememberEntrance
import com.aurora.music.ui.screens.home.rise
import com.aurora.music.ui.screens.home.spotSurface
import com.aurora.music.ui.theme.Motion
import com.aurora.music.ui.theme.PressScaleIndication
import com.aurora.music.ui.theme.paperClip
import com.aurora.music.ui.theme.paperSquare
import com.aurora.music.ui.theme.pressClickable
import com.aurora.music.ui.theme.themedCorner
import com.aurora.music.util.accentFor
import com.aurora.music.viewmodel.LibraryUiState

private val CrateGap = 36.dp
private val CrateMinWidth = 340.dp
private val WallGap = 12.dp
private val WallMinWidth = 146.dp
private val DiscSize = 128.dp
private val DiscGap = 30.dp
private val TileSize = 96.dp

@Composable
internal fun LibraryDriftHeader(
    state: LibraryUiState,
    stats: String?,
    width: Dp,
    compact: Boolean,
    filter: LibraryFilter,
    sort: LibrarySort,
    layout: LibraryLayout,
    canDownload: Boolean,
    tabs: LibraryTabs,
    visibleTabs: List<LibraryFilter>,
    onFilter: (LibraryFilter) -> Unit,
    onSort: (LibrarySort) -> Unit,
    onToggleLayout: () -> Unit,
    onTabs: (LibraryTabs) -> Unit,
    onSelectView: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenMix: (() -> Unit)?,
    onCreate: () -> Unit,
) {
    val insets = LocalLibInsets.current
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val inner = width - insets.start - insets.end
    val clock = rememberEntrance()
    Row(
        Modifier.fillMaxWidth().padding(start = insets.start, end = insets.end, top = 24.dp).rise(clock, 0),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        DriftTitle(appString(R.string.text_library_b8100f), Modifier.weight(1f))
        HeaderAction(Icons.Filled.Search, appString(R.string.text_search_bce064), onOpenSearch)
        onOpenMix?.let { HeaderAction(Icons.Filled.GraphicEq, appString(R.string.text_mix_studio_668363), it) }
        if (state.signedIn) DriftButton(Icons.Filled.Add, appString(R.string.text_new_playlist_a5474a), iconOnly = compact, onClick = onCreate)
    }
    if (!stats.isNullOrBlank() || state.views.size > 1) Row(
        Modifier.fillMaxWidth().padding(start = insets.start, end = insets.end, top = 8.dp).rise(clock, 1),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!stats.isNullOrBlank()) Text(stats, style = type.titleLarge, fontWeight = FontWeight.Normal, color = colors.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (state.views.size > 1) Row(
            Modifier.widthIn(max = if (stats.isNullOrBlank()) inner else inner * 0.6f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            state.views.forEach { view -> SpotlightPill(view.label, view.id == state.view) { onSelectView(view.id) } }
        }
    }
    Row(Modifier.fillMaxWidth().padding(start = insets.start, end = insets.end, top = if (compact) 4.dp else 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        DriftTabs(visibleTabs.map { it.label }, visibleTabs.indexOf(filter), if (compact) type.titleMedium else type.headlineLarge, Modifier.weight(1f),
            gap = if (compact) 16.dp else 34.dp) { onFilter(visibleTabs[it]) }
        Spacer(Modifier.width(8.dp))
        LibraryTabsMenu(tabs, canDownload, onTabs)
        if (filter != LibraryFilter.ALL) SortButton(sort, iconOnly = compact, onSort = onSort)
        if (filter != LibraryFilter.ALL && filter != LibraryFilter.SONGS) {
            Spacer(Modifier.width(6.dp))
            LayoutToggle(layout, onToggleLayout)
        }
    }
}

@Composable
internal fun LibraryDriftAll(
    state: LibraryUiState,
    pins: List<Pin>,
    width: Dp,
    bottom: Dp,
    tabs: List<LibraryFilter>,
    actions: LibActions,
    tree: PlaylistFolder?,
    tiles: List<QuickTile>,
    onFilter: (LibraryFilter) -> Unit,
    onOpen: (LibRow) -> Unit,
    onSignIn: () -> Unit,
) {
    val insets = LocalLibInsets.current
    val type = MaterialTheme.typography
    val inner = width - insets.start - insets.end
    // the playlists tab's own top level, without the sort and the liked row it adds
    val playlists = remember(state.playlists, state.smartPlaylists, tree) {
        buildRows(state, LibraryFilter.PLAYLISTS, LibrarySort.RECENT, emptyList(), tree).filterNot { it.kind == "liked" }
    }
    val albums = remember(state.albums) { state.albums.map { albumRow(it) } }
    val artists = remember(state.artists) { state.artists.map { artistRow(it, 0) } }
    // 0 doubles as not counted yet, so no number rather than a wrong 0
    val liked = LibRow(appString(R.string.text_liked_songs_58c3a9),
        if (state.likedSongCount > 0) appPlural(R.plurals.track_count, state.likedSongCount) else "", state.likedCover, accentFor("liked"), state.likedId, "liked")
    val circles = buildList {
        if (state.signedIn) add(QuickTile(liked.title, liked.subtitle, Icons.Filled.Favorite, state.likedCover, row = liked) { onOpen(liked) })
        addAll(tiles)
        pins.forEach { p ->
            val row = LibRow(p.title, p.subtitle.ifBlank { kindLabel(p.kind) }, p.coverUrl, accentFor(p.id), p.id, p.kind, circle = p.kind == "artist")
            add(QuickTile(row.title, row.subtitle, null, row.art, row = row, pinned = true) { onOpen(row) })
        }
    }
    val crateColumns = ((inner + CrateGap) / (CrateMinWidth + CrateGap)).toInt().coerceAtLeast(1)
    val wallColumns = ((inner + WallGap) / (WallMinWidth + WallGap)).toInt().coerceAtLeast(2)
    val cell = (inner - WallGap * (wallColumns - 1)) / wallColumns
    val discColumns = ((inner + DiscGap) / (DiscSize + DiscGap)).toInt().coerceAtLeast(2)
    // the first album takes two cells each way and the next ones fill the two short lines beside it
    val beside = wallColumns - 2
    val crates = remember(playlists, crateColumns) { playlists.chunked(crateColumns) }
    val wall = remember(albums, wallColumns) { albums.drop(1 + beside * 2).chunked(wallColumns) }
    val discs = remember(artists, discColumns) { artists.chunked(discColumns) }
    val clock = rememberEntrance()
    val fan = { Motion.EaseOut.transform(clock.value) }
    val heading: @Composable (LibraryFilter, Int) -> Unit = { tab, count ->
        Box(Modifier.padding(top = 44.dp, bottom = 12.dp)) {
            DriftHeading(tab.label, note = count.toString(), onOpen = if (tab in tabs) ({ onFilter(tab) }) else null)
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = insets.start, end = insets.end, top = 20.dp, bottom = bottom)) {
        item(contentType = "collections") {
            FlowRow(Modifier.rise(clock, 1), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                circles.forEach { CollectionDisc(it, actions) }
            }
        }
        if (playlists.isEmpty() && albums.isEmpty() && artists.isEmpty() && state.songs.isEmpty()) item(contentType = "empty") {
            val guest = !state.signedIn
            Column(Modifier.padding(top = 56.dp).widthIn(max = 620.dp).rise(clock, 2)) {
                Text(appString(R.string.text_nothing_here_yet_e89225), style = type.displayLarge.let { it.copy(fontSize = it.fontSize * 1.3f, lineHeight = it.lineHeight * 1.3f) },
                    fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(appString(if (guest) R.string.guest_detail else R.string.home_empty_body), style = type.titleLarge, fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp, bottom = 24.dp))
                DriftButton(null, appString(if (guest) R.string.guest_sign_in else R.string.home_add_source), onClick = onSignIn)
            }
        }
        if (playlists.isNotEmpty()) {
            item(contentType = "heading") { heading(LibraryFilter.PLAYLISTS, playlists.size) }
            items(crates.size, contentType = { "crates" }) { line ->
                Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(CrateGap)) {
                    crates[line].forEach { row -> Crate(row, actions, fan, Modifier.weight(1f)) { onOpen(row) } }
                    repeat(crateColumns - crates[line].size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        if (albums.isNotEmpty()) {
            item(contentType = "heading") { heading(LibraryFilter.ALBUMS, albums.size) }
            item(contentType = "wallLead") {
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(WallGap)) {
                    WallCover(albums[0], cell * 2 + WallGap, true, actions) { onOpen(albums[0]) }
                    if (beside > 0) Column(verticalArrangement = Arrangement.spacedBy(WallGap)) {
                        albums.drop(1).take(beside * 2).chunked(beside).forEach { line ->
                            Row(horizontalArrangement = Arrangement.spacedBy(WallGap)) { line.forEach { row -> WallCover(row, cell, false, actions) { onOpen(row) } } }
                        }
                    }
                }
            }
            items(wall.size, contentType = { "wall" }) { line ->
                Row(Modifier.padding(top = WallGap), horizontalArrangement = Arrangement.spacedBy(WallGap)) {
                    wall[line].forEach { row -> WallCover(row, cell, false, actions) { onOpen(row) } }
                }
            }
        }
        if (artists.isNotEmpty()) {
            item(contentType = "heading") { heading(LibraryFilter.ARTISTS, artists.size) }
            items(discs.size, contentType = { "artists" }) { line ->
                Row(Modifier.padding(top = 10.dp, bottom = 12.dp)) {
                    discs[line].forEach { row -> ArtistDisc(row, actions, Modifier.weight(1f)) { onOpen(row) } }
                    repeat(discColumns - discs[line].size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

// classic's right-click menu for anything that stands for a library row
@Composable
private fun MenuBox(row: LibRow?, actions: LibActions, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var at by remember { mutableStateOf<Offset?>(null) }
    Box(modifier.then(if (row != null && row.menu) Modifier.onSecondaryPress { at = it } else Modifier)) {
        content()
        if (row != null) at?.let { Box(Modifier.offset { it.round() }) { ContextMenu(row, actions, onDismiss = { at = null }) } }
    }
}

@Composable
private fun CollectionDisc(tile: QuickTile, actions: LibActions) {
    val colors = MaterialTheme.colorScheme
    val row = tile.row
    val shape = paperSquare(RoundedCornerShape(themedCorner(TileSize)))
    MenuBox(row, actions) {
        Column(
            Modifier.width(TileSize + 12.dp).driftLift().paperClip(MaterialTheme.shapes.medium).pressClickable(onClick = tile.onClick)
                .pointerHoverIcon(PointerIcon.Hand).coverHover(tile.art),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(TileSize).then(if (row != null && isSelected(row)) Modifier.border(3.dp, colors.primary, shape) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                if (tile.art.isNotBlank() || tile.icon == null) Artwork(tile.art, row?.accent ?: colors.primary, Modifier.matchParentSize(), corner = TileSize)
                else Box(Modifier.matchParentSize().spotSurface(shape, outlined = true), contentAlignment = Alignment.Center) {
                    Icon(tile.icon, null, tint = colors.primary, modifier = Modifier.size(32.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(tile.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (tile.pinned) Icon(Icons.Filled.PushPin, appString(R.string.text_pinned_f93121), tint = colors.primary, modifier = Modifier.padding(end = 4.dp).size(11.dp))
                Text(tile.subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun Crate(row: LibRow, actions: LibActions, fan: () -> Float, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var menuOpen by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val selected = isSelected(row)
    val chip = colors.background.copy(alpha = 0.78f)
    val more = appString(R.string.text_more_4bab2d)
    MenuBox(row, actions, modifier) {
        Row(
            Modifier.fillMaxWidth().height(116.dp).clip(driftShape(22.dp))
                .background(if (selected) colors.primary.copy(alpha = 0.16f) else Color.Transparent)
                .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FanStack(row.art, row.accent, 92.dp, fan) {
                val mark = Modifier.align(Alignment.BottomStart).padding(8.dp).paperClip(RoundedCornerShape(10.dp)).background(chip)
                if (row.icon != null) Icon(row.icon, null, tint = colors.onSurface, modifier = mark.size(32.dp).padding(7.dp))
                else if (row.badge.isNotBlank()) Text(row.badge, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = colors.onSurface, maxLines = 1,
                    modifier = mark.padding(horizontal = 7.dp, vertical = 3.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(row.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, color = if (selected) colors.primary else colors.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(row.subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (row.menu) Box(Modifier.size(36.dp)) {
                if (hovered || menuOpen) Icon(Icons.Filled.MoreVert, more, tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(36.dp).paperClip(CircleShape).clickable(onClickLabel = more) { menuOpen = true }.pointerHoverIcon(PointerIcon.Hand).padding(8.dp))
                CollectionMenu(row, actions, expanded = menuOpen, onDismiss = { menuOpen = false })
            }
        }
    }
}

@Composable
private fun WallCover(row: LibRow, size: Dp, big: Boolean, actions: LibActions, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = driftShape(22.dp)
    var menuOpen by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val active = hovered || menuOpen
    val play = appString(R.string.text_play_5d12bd)
    val more = appString(R.string.text_more_4bab2d)
    MenuBox(row, actions) {
        Box(
            Modifier.size(size).clip(shape).clickable(interactionSource = interaction, indication = PressScaleIndication, onClickLabel = row.title, onClick = onClick)
                .pointerHoverIcon(PointerIcon.Hand).coverHover(row.art),
        ) {
            Artwork(row.art, row.accent, Modifier.matchParentSize(), corner = 0.dp)
            if (big || active) Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().background(colors.background.copy(alpha = 0.8f))
                    .padding(horizontal = if (big) 18.dp else 10.dp, vertical = if (big) 14.dp else 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(row.title, style = if (big) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.labelLarge,
                        fontWeight = if (big) FontWeight.Black else FontWeight.Bold, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (big && row.subtitle.isNotBlank()) Text(row.subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (active) Icon(Icons.Filled.PlayArrow, play, tint = colors.onPrimary,
                    modifier = Modifier.padding(start = 8.dp).size(if (big) 44.dp else 32.dp).paperClip(CircleShape).background(colors.primary)
                        .clickable(onClickLabel = play) { actions.onPlay(row) }.pointerHoverIcon(PointerIcon.Hand).padding(if (big) 10.dp else 6.dp))
            }
            Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                if (active) Icon(Icons.Filled.MoreVert, more, tint = Color.White,
                    modifier = Modifier.size(32.dp).paperClip(CircleShape).background(Color.Black.copy(alpha = 0.45f))
                        .clickable(onClickLabel = more) { menuOpen = true }.pointerHoverIcon(PointerIcon.Hand).padding(7.dp))
                CollectionMenu(row, actions, expanded = menuOpen, onDismiss = { menuOpen = false })
            }
            if (isSelected(row)) Box(Modifier.matchParentSize().border(3.dp, colors.primary, shape))
        }
    }
}

@Composable
private fun ArtistDisc(row: LibRow, actions: LibActions, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    MenuBox(row, actions, modifier) {
        Column(
            Modifier.width(DiscSize).paperClip(MaterialTheme.shapes.medium).pressClickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand).coverHover(row.art),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(DiscSize).then(if (isSelected(row)) Modifier.border(3.dp, colors.primary, paperSquare(RoundedCornerShape(themedCorner(DiscSize)))) else Modifier)) {
                Artwork(row.art, row.accent, Modifier.matchParentSize(), corner = DiscSize)
            }
            Spacer(Modifier.height(12.dp))
            Text(row.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if (isSelected(row)) colors.primary else colors.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
        }
    }
}
