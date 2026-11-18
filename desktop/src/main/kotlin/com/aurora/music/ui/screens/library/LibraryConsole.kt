package com.aurora.music.ui.screens.library

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import com.aurora.music.ui.components.EmptyState
import com.aurora.music.ui.components.PageHeader
import com.aurora.music.ui.components.coverHover
import com.aurora.music.ui.screens.home.ConsoleButton
import com.aurora.music.ui.screens.home.ConsoleLabel
import com.aurora.music.ui.screens.home.ConsoleModule
import com.aurora.music.ui.screens.home.ConsoleSegments
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
import com.aurora.music.ui.theme.paperSquare
import com.aurora.music.ui.theme.pressClickable
import com.aurora.music.util.accentFor
import com.aurora.music.viewmodel.LibraryUiState

private val SideWidth = 244.dp
private val PaneGap = 14.dp
private val PaneInset = 18.dp
private val ListMinWidth = 240.dp
private const val PREVIEW_ROWS = 6

// console's browser sets a pane of tabs beside the page; narrower than that, and in every other layout, the page stands alone
@Composable
internal fun LibraryConsoleFrame(browser: Boolean, contentPadding: PaddingValues, side: @Composable (Modifier) -> Unit, content: @Composable ColumnScope.() -> Unit) {
    if (!browser) {
        Column(Modifier.fillMaxSize(), content = content)
        return
    }
    val insets = LocalLibInsets.current
    val clock = rememberEntrance()
    Row(
        Modifier.fillMaxSize().padding(start = insets.start, end = insets.end, top = 20.dp, bottom = contentPadding.calculateBottomPadding() + 12.dp),
        horizontalArrangement = Arrangement.spacedBy(PaneGap),
    ) {
        side(Modifier.width(SideWidth).fillMaxHeight().rise(clock, 0))
        CompositionLocalProvider(LocalLibInsets provides LibInsets(PaneInset, PaneInset)) {
            ConsoleModule(Modifier.weight(1f).fillMaxHeight().rise(clock, 1), padding = PaddingValues(0.dp), lift = false, content = content)
        }
    }
}

private fun likedRow(state: LibraryUiState) = LibRow(appString(R.string.text_liked_songs_58c3a9),
    if (state.likedSongCount > 0) appPlural(R.plurals.track_count, state.likedSongCount) else "", state.likedCover, accentFor("liked"), state.likedId, "liked")

// what classic's all tab counts for each type; nothing rather than a zero that may only mean not loaded
private fun tabCount(state: LibraryUiState, tab: LibraryFilter): String {
    val count = when (tab) {
        LibraryFilter.ALL -> 0
        LibraryFilter.PLAYLISTS -> state.playlists.size + state.smartPlaylists.size
        LibraryFilter.ALBUMS -> state.albums.size
        LibraryFilter.ARTISTS -> state.artists.size
        LibraryFilter.SONGS -> state.songs.size
        LibraryFilter.DOWNLOADED -> state.downloadedRows.size
    }
    return if (count <= 0) "" else if (tab == LibraryFilter.SONGS && state.canLoadMoreSongs) "$count+" else count.toString()
}

// a tile's detail line leads with its number when it has one
private fun String.leadingCount(): String = takeWhile { it.isDigit() || it == '+' }

// classic's right-click menu for anything that stands for a library row
@Composable
private fun MenuBox(row: LibRow, actions: LibActions, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var at by remember { mutableStateOf<Offset?>(null) }
    Box(modifier.onSecondaryPress { at = it }) {
        content()
        at?.let { Box(Modifier.offset { it.round() }) { ContextMenu(row, actions, onDismiss = { at = null }) } }
    }
}

@Composable
internal fun LibraryConsoleSide(
    state: LibraryUiState,
    source: String,
    filter: LibraryFilter,
    tabs: List<LibraryFilter>,
    tiles: List<QuickTile>,
    actions: LibActions,
    modifier: Modifier,
    onFilter: (LibraryFilter) -> Unit,
    onSelectView: (String) -> Unit,
    onOpen: (LibRow) -> Unit,
    onOpenMix: (() -> Unit)?,
) {
    val liked = likedRow(state)
    ConsoleModule(modifier, padding = PaddingValues(start = 10.dp, end = 10.dp, top = PaneInset, bottom = 10.dp), lift = false) {
        Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 12.dp)) {
            (state.views.firstOrNull { it.id == state.view }?.label ?: source).takeIf { it.isNotBlank() }?.let { ConsoleLabel(it) }
            Text(appString(R.string.text_library_b8100f), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (state.views.size > 1) ConsoleSegments(state.views.map { it.label }, state.views.indexOfFirst { it.id == state.view }, Modifier.padding(bottom = 12.dp)) {
            onSelectView(state.views[it].id)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            tabs.forEach { tab ->
                if (tab != LibraryFilter.ALL) SideRow(tabIcon(tab), tab.label, tabCount(state, tab), tab == filter) { onFilter(if (tab == filter) LibraryFilter.ALL else tab) }
            }
            HorizontalDivider(Modifier.padding(horizontal = 8.dp, vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
            if (state.signedIn) MenuBox(liked, actions) {
                SideRow(Icons.Filled.Favorite, liked.title, liked.subtitle.leadingCount(), isSelected(liked), accent = true) { onOpen(liked) }
            }
            tiles.forEach { tile -> SideRow(tile.icon, tile.title, tile.subtitle.leadingCount(), false, accent = true, onClick = tile.onClick) }
            if (onOpenMix != null) SideRow(Icons.Filled.GraphicEq, appString(R.string.text_mix_studio_668363), "", false, accent = true, onClick = onOpenMix)
        }
    }
}

@Composable
private fun SideRow(icon: ImageVector?, label: String, count: String, selected: Boolean, accent: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val on by animateFloatAsState(if (selected) 1f else 0f, Motion.state(), label = "sideRow")
    val ink = if (isPaperTheme()) PaperTokens.ink else lerp(colors.onSurface, colors.primary, on)
    Row(
        Modifier.fillMaxWidth().height(46.dp).paperClip(MaterialTheme.shapes.small).drawBehind { drawRect(colors.primary.copy(alpha = 0.16f * on)) }
            .semantics { this.selected = selected }.clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, tint = if (accent) colors.primary else ink, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (count.isNotBlank()) Text(count, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
internal fun LibraryConsoleHeader(
    state: LibraryUiState,
    source: String,
    browser: Boolean,
    roomy: Boolean,
    filter: LibraryFilter,
    sort: LibrarySort,
    layout: LibraryLayout,
    canDownload: Boolean,
    tabs: LibraryTabs,
    onFilter: (LibraryFilter) -> Unit,
    onSort: (LibrarySort) -> Unit,
    onToggleLayout: () -> Unit,
    onTabs: (LibraryTabs) -> Unit,
    onSelectView: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onCreate: () -> Unit,
) {
    val insets = LocalLibInsets.current
    val colors = MaterialTheme.colorScheme
    val field = consoleInset()
    val search: @Composable (Modifier) -> Unit = { modifier ->
        Row(
            modifier.height(46.dp).paperFrame(MaterialTheme.shapes.small, { background(field) }, fill = PaperTokens.plate)
                .clickable(role = Role.Button, onClick = onOpenSearch).pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(appString(R.string.text_search_your_library_d9fa3f), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (browser) {
        Row(
            Modifier.fillMaxWidth().padding(start = insets.start - 4.dp, end = insets.end - 4.dp, top = 14.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            search(Modifier.weight(1f))
            if (filter != LibraryFilter.ALL) SortButton(sort, iconOnly = !roomy, onSort = onSort)
            if (filter != LibraryFilter.ALL && filter != LibraryFilter.SONGS) LayoutToggle(layout, onToggleLayout)
            LibraryTabsMenu(tabs, canDownload, onTabs)
            if (state.signedIn) ConsoleButton(Icons.Filled.Add, appString(R.string.text_new_playlist_a5474a), iconOnly = !roomy, onClick = onCreate)
        }
        return
    }
    PageHeader(
        appString(R.string.text_library_b8100f),
        Modifier.padding(start = insets.start, end = (insets.end - 8.dp).coerceAtLeast(0.dp)),
        eyebrow = state.views.firstOrNull { it.id == state.view }?.label ?: source,
    ) {
        if (state.signedIn) HeaderAction(Icons.Filled.Add, appString(R.string.text_create_playlist_62c988), onCreate)
    }
    if (state.views.size > 1) ConsoleSegments(state.views.map { it.label }, state.views.indexOfFirst { it.id == state.view },
        Modifier.padding(start = insets.start, end = insets.end, bottom = 10.dp)) { onSelectView(state.views[it].id) }
    Row(Modifier.fillMaxWidth().padding(start = insets.start, end = insets.end), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        search(Modifier.weight(1f))
        LibraryTabsMenu(tabs, canDownload, onTabs)
    }
    if (filter == LibraryFilter.ALL) Spacer(Modifier.height(8.dp))
    else Row(Modifier.fillMaxWidth().height(52.dp).padding(start = (insets.start - 6.dp).coerceAtLeast(0.dp), end = insets.end), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.height(36.dp).paperClip(CircleShape).clickable(role = Role.Button) { onFilter(LibraryFilter.ALL) }.pointerHoverIcon(PointerIcon.Hand).padding(start = 6.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, appString(R.string.text_back_b52b36), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(LibraryFilter.ALL.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, maxLines = 1)
        }
        Row(Modifier.weight(1f).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(filter.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(8.dp))
            Text(tabCount(state, filter), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant, maxLines = 1)
        }
        SortButton(sort, iconOnly = true, onSort = onSort)
        if (filter != LibraryFilter.SONGS) {
            Spacer(Modifier.width(6.dp))
            LayoutToggle(layout, onToggleLayout)
        }
    }
}

@Composable
internal fun LibraryConsoleAll(
    state: LibraryUiState,
    pins: List<Pin>,
    browser: Boolean,
    bottom: Dp,
    tabs: List<LibraryFilter>,
    actions: LibActions,
    tree: PlaylistFolder?,
    tiles: List<QuickTile>,
    onFilter: (LibraryFilter) -> Unit,
    onOpen: (LibRow) -> Unit,
    onOpenMix: (() -> Unit)?,
    onSignIn: () -> Unit,
) {
    val insets = LocalLibInsets.current
    // the playlists tab's own top level, without the sort and the liked row it adds
    val playlists = remember(state.playlists, state.smartPlaylists, tree) {
        buildRows(state, LibraryFilter.PLAYLISTS, LibrarySort.RECENT, emptyList(), tree).filterNot { it.kind == "liked" }
    }
    val albums = remember(state.albums) { state.albums.map { albumRow(it) } }
    val artists = remember(state.albums, state.artists) {
        val counts = state.albums.groupingBy { it.artist.lowercase() }.eachCount()
        state.artists.map { artistRow(it, counts[it.name.lowercase()] ?: 0) }
    }
    val pinned = remember(pins) {
        pins.map { p -> LibRow(p.title, p.subtitle.ifBlank { kindLabel(p.kind) }, p.coverUrl, accentFor(p.id), p.id, p.kind, circle = p.kind == "artist") }
    }
    val lists = listOf(LibraryFilter.PLAYLISTS to playlists, LibraryFilter.ALBUMS to albums, LibraryFilter.ARTISTS to artists).filter { it.second.isNotEmpty() }
    val liked = likedRow(state)
    val cells: List<QuickTile> = if (browser) emptyList() else buildList {
        tabs.forEach { tab -> if (tab != LibraryFilter.ALL) add(QuickTile(tab.label, tabCount(state, tab), tabIcon(tab), "") { onFilter(tab) }) }
        if (state.signedIn) add(QuickTile(liked.title, liked.subtitle, Icons.Filled.Favorite, "", row = liked) { onOpen(liked) })
        addAll(tiles)
        if (onOpenMix != null) add(QuickTile(appString(R.string.text_mix_studio_668363), "", Icons.Filled.GraphicEq, "", onClick = onOpenMix))
    }
    val clock = rememberEntrance()
    val line: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // side by side each list scrolls by itself and shows everything it has
        if (browser && lists.isNotEmpty() && maxWidth >= ListMinWidth * lists.size) Column(
            Modifier.fillMaxSize().padding(start = insets.start - 4.dp, end = insets.end - 4.dp, bottom = bottom),
            verticalArrangement = Arrangement.spacedBy(PaneGap),
        ) {
            if (pinned.isNotEmpty()) PinChips(pinned, actions, onOpen)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(PaneGap)) {
                lists.forEach { (tab, rows) ->
                    HubList(tab.label, rows.size, Modifier.weight(1f).fillMaxHeight(), if (tab in tabs) ({ onFilter(tab) }) else null) {
                        LazyColumn(Modifier.weight(1f)) { items(rows.size) { i -> LibListItem(rows[i], emptyList(), actions) { onOpen(rows[i]) } } }
                    }
                }
            }
        } else LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = insets.start, end = insets.end, top = 6.dp, bottom = bottom),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(cells.size, contentType = { "cell" }) { i -> HubCell(cells[i], actions, Modifier.rise(clock, 0)) }
            if (pinned.isNotEmpty()) item(span = line, contentType = "pinned") { PinChips(pinned, actions, onOpen, Modifier.rise(clock, 1)) }
            if (lists.isEmpty() && state.songs.isEmpty()) item(span = line, contentType = "empty") {
                val guest = !state.signedIn
                ConsoleModule(Modifier.fillMaxWidth().rise(clock, 2)) {
                    EmptyState(Icons.Filled.LibraryMusic, appString(R.string.text_nothing_here_yet_e89225),
                        subtitle = appString(if (guest) R.string.guest_detail else R.string.home_empty_body),
                        action = appString(if (guest) R.string.guest_sign_in else R.string.home_add_source), onAction = onSignIn)
                }
            }
            lists.forEachIndexed { i, (tab, rows) ->
                item(key = tab.name, span = line, contentType = "list") {
                    // shortcut: a stacked list previews only its first rows and its header opens the whole tab, make the rows lazy items before showing more
                    HubList(tab.label, rows.size, if (i == 0) Modifier.rise(clock, 2) else Modifier, if (tab in tabs) ({ onFilter(tab) }) else null) {
                        rows.take(PREVIEW_ROWS).forEach { row -> LibListItem(row, emptyList(), actions) { onOpen(row) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun HubCell(tile: QuickTile, actions: LibActions, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val cell: @Composable () -> Unit = {
        Column(
            modifier.fillMaxWidth().height(76.dp).spotSurface(MaterialTheme.shapes.medium, outlined = true)
                .pressClickable(onClick = tile.onClick).pointerHoverIcon(PointerIcon.Hand).padding(10.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                tile.icon?.let { Icon(it, null, tint = colors.primary, modifier = Modifier.size(20.dp)) }
                Spacer(Modifier.weight(1f))
                Text(tile.subtitle.leadingCount(), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1)
            }
            Text(tile.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    if (tile.row != null) MenuBox(tile.row, actions) { cell() } else cell()
}

@Composable
private fun PinChips(pins: List<LibRow>, actions: LibActions, onOpen: (LibRow) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        pins.forEach { pin ->
            val tint = spotlightTint(pin.art, pin.accent)
            MenuBox(pin, actions) {
                Row(
                    Modifier.height(48.dp).widthIn(max = 240.dp).paperFrame(shape, { background(tint) }, fill = tint)
                        .then(if (isSelected(pin)) Modifier.border(2.dp, colors.primary, paperSquare(shape)) else Modifier)
                        .pressClickable { onOpen(pin) }.pointerHoverIcon(PointerIcon.Hand).coverHover(pin.art).padding(start = 6.dp, end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(pin.art, pin.accent, Modifier.size(36.dp), corner = if (pin.circle) 36.dp else 9.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(pin.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun HubList(title: String, count: Int, modifier: Modifier, onOpenAll: (() -> Unit)?, rows: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    ConsoleModule(modifier.fillMaxWidth(), padding = PaddingValues(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 6.dp), lift = false) {
        Row(
            Modifier.fillMaxWidth().paperClip(MaterialTheme.shapes.small)
                .then(if (onOpenAll != null) Modifier.clickable(role = Role.Button, onClick = onOpenAll).pointerHoverIcon(PointerIcon.Hand) else Modifier)
                .heightIn(min = 44.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConsoleLabel(title)
            Spacer(Modifier.width(8.dp))
            Text(count.toString(), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1)
            Spacer(Modifier.weight(1f))
            if (onOpenAll != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, appString(R.string.text_see_all_2941c5), tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        rows()
    }
}
