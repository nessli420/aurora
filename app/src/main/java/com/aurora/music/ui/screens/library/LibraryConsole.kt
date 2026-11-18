package com.aurora.music.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.music.R
import com.aurora.music.data.Pin
import com.aurora.music.localization.appPlural
import com.aurora.music.localization.appString
import com.aurora.music.model.LibraryFilter
import com.aurora.music.model.LibrarySort
import com.aurora.music.model.label
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.layout.LocalWindowLayout
import com.aurora.music.ui.screens.home.ConsoleLabel
import com.aurora.music.ui.screens.home.ConsoleModule
import com.aurora.music.ui.screens.home.ConsoleSegments
import com.aurora.music.ui.screens.home.ConsoleTitle
import com.aurora.music.ui.screens.home.IconPill
import com.aurora.music.ui.screens.home.rememberEntrance
import com.aurora.music.ui.screens.home.rise
import com.aurora.music.ui.screens.home.spotSurface
import com.aurora.music.ui.screens.home.spotlightTint
import com.aurora.music.ui.theme.PaperTokens
import com.aurora.music.ui.theme.PressScaleIndication
import com.aurora.music.ui.theme.isPaperTheme
import com.aurora.music.ui.theme.paperClip
import com.aurora.music.ui.theme.paperFrame
import com.aurora.music.ui.theme.paperSquare
import com.aurora.music.ui.theme.pressClickable
import com.aurora.music.util.accentFor
import com.aurora.music.viewmodel.LibraryUiState

private val Side = 16.dp
private const val PREVIEW_ROWS = 6

@Composable
internal fun LibraryConsoleHeader(
    state: LibraryUiState,
    username: String,
    source: String,
    filter: LibraryFilter,
    onOpenDrawer: () -> Unit,
    onOpenSearch: () -> Unit,
    onCreate: () -> Unit,
    onCycleView: () -> Unit,
    onFilter: (LibraryFilter) -> Unit,
    onCustomizeTabs: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.padding(start = Side, end = Side, top = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ConsoleTitle(state.views.firstOrNull { it.id == state.view }?.label ?: source, appString(R.string.text_library_b8100f), Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            if (state.signedIn) {
                IconPill(Icons.Filled.Add, appString(R.string.text_create_playlist_62c988), onCreate)
                Spacer(Modifier.width(8.dp))
            }
            LibraryAvatar(username, onOpenDrawer)
        }
        if (state.views.size > 1) {
            val at = state.views.indexOfFirst { it.id == state.view }.coerceAtLeast(0)
            // shortcut: the view model can only step to the next view, so a far segment starts loads it cancels; call a select-by-id once it has one
            ConsoleSegments(state.views.size, at, { state.views[it].label }, Modifier.widthIn(max = 560.dp)) { i -> repeat((i - at).mod(state.views.size)) { onCycleView() } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.weight(1f).height(46.dp).spotSurface(MaterialTheme.shapes.medium, colors.surface, outlined = true)
                    .clickable(role = Role.Button, onClick = onOpenSearch).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(appString(R.string.text_search_your_library_d9fa3f), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Filled.Tune, appString(R.string.library_tabs_customize), tint = colors.onSurface,
                modifier = Modifier.size(46.dp).spotSurface(MaterialTheme.shapes.medium, colors.surface, outlined = true).clickable(onClick = onCustomizeTabs).padding(12.dp))
        }
        if (filter != LibraryFilter.ALL) Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.heightIn(min = 40.dp).paperClip(CircleShape).clickable(role = Role.Button) { onFilter(LibraryFilter.ALL) }.padding(start = 6.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, appString(R.string.text_back_b52b36), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(LibraryFilter.ALL.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, maxLines = 1)
            }
            Spacer(Modifier.width(8.dp))
            Text(filter.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(8.dp))
            Text(tabCount(state, filter), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant, maxLines = 1)
        }
    }
}

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

@Composable
internal fun LibraryConsoleAll(
    state: LibraryUiState,
    pins: List<Pin>,
    bottom: Dp,
    tabs: List<LibraryFilter>,
    actions: LibActions,
    tiles: List<QuickTile>,
    onFilter: (LibraryFilter) -> Unit,
    onOpenDetail: (String, String) -> Unit,
    onOpenMix: () -> Unit,
    onCustomizeTabs: () -> Unit,
    onSignIn: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val albumLabel = appString(R.string.text_album_dfb4c9)
    val artistLabel = appString(R.string.text_artist_6c3f3d)
    // the playlists tab's own flat list, without the sort and the liked row it adds
    val playlists = remember(state.playlists, state.smartPlaylists) {
        buildRows(state, LibraryFilter.PLAYLISTS, LibrarySort.RECENT, emptyList()).filterNot { it.kind == "liked" }
    }
    // shortcut: each list previews its first rows and its header opens the whole tab; make the rows lazy items before showing more
    val albums = remember(state.albums, albumLabel) { state.albums.take(PREVIEW_ROWS).map { albumRow(it, albumLabel) } }
    val artists = remember(state.artists, artistLabel) { state.artists.take(PREVIEW_ROWS).map { artistRow(it, artistLabel) } }
    val drillIns = tabs.filter { it != LibraryFilter.ALL }
    val customize = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onCustomizeTabs()
    }
    val cells = buildList {
        drillIns.forEach { tab -> add(QuickTile(tab.label, tabCount(state, tab), tabIcon(tab), "", onLongClick = customize) { onFilter(tab) }) }
        // 0 doubles as not counted yet, so no number rather than a wrong 0
        if (state.signedIn) add(QuickTile(appString(R.string.text_liked_songs_58c3a9), if (state.likedSongCount > 0) appPlural(R.plurals.track_count, state.likedSongCount) else "",
            Icons.Filled.Favorite, state.likedCover, onLongClick = actions.onOpenAllLiked, menu = actions.likedPicker) { onOpenDetail("liked", state.likedId) })
        addAll(tiles)
        add(QuickTile(appString(R.string.text_mix_studio_668363), "", Icons.Filled.Apps, "", onClick = onOpenMix))
    }
    val customizeLabel = appString(R.string.library_tabs_customize)
    val likedLabel = appString(R.string.liked_sources_choose)
    val clock = rememberEntrance()
    LazyVerticalGrid(
        columns = if (LocalWindowLayout.current.useNavigationRail) GridCells.Adaptive(116.dp) else GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Side, end = Side, top = 6.dp, bottom = bottom),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(cells.size, contentType = { "cell" }) { i -> HubCell(cells[i], if (i < drillIns.size) customizeLabel else likedLabel, Modifier.rise(clock, 0)) }
        if (pins.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, contentType = "pinned") {
            LazyRow(Modifier.bleed(Side).rise(clock, 1), contentPadding = PaddingValues(horizontal = Side), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(pins.size) { i -> PinChip(pins[i]) { onOpenDetail(pins[i].kind, pins[i].id) } }
            }
        }
        if (playlists.isEmpty() && state.albums.isEmpty() && state.artists.isEmpty() && state.songs.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }, contentType = "empty") {
            val guest = !state.signedIn
            ConsoleModule(Modifier.fillMaxWidth().rise(clock, 2), padding = PaddingValues(horizontal = 20.dp, vertical = 24.dp)) {
                Text(appString(R.string.text_nothing_here_yet_e89225), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(8.dp))
                Text(appString(if (guest) R.string.guest_detail else R.string.home_empty_body), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier.height(46.dp).paperFrame(MaterialTheme.shapes.medium, { background(colors.inverseSurface) }, fill = PaperTokens.rose)
                        .pressClickable(role = Role.Button, onClick = onSignIn).padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(appString(if (guest) R.string.guest_sign_in else R.string.home_add_source), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                        color = if (isPaperTheme()) PaperTokens.ink else colors.inverseOnSurface, maxLines = 1)
                }
            }
        }
        listOf(
            Triple(LibraryFilter.PLAYLISTS, playlists.size, playlists.take(PREVIEW_ROWS)),
            Triple(LibraryFilter.ALBUMS, state.albums.size, albums),
            Triple(LibraryFilter.ARTISTS, state.artists.size, artists),
        ).forEachIndexed { i, (tab, count, rows) ->
            if (rows.isNotEmpty()) item(key = tab.name, span = { GridItemSpan(maxLineSpan) }, contentType = "list") {
                HubList(tab.label, count, rows, if (i == 0) Modifier.rise(clock, 2) else Modifier, onOpenAll = if (tab in tabs) ({ onFilter(tab) }) else null) { row ->
                    onOpenDetail(row.kind, row.id)
                }
            }
        }
    }
}

@Composable
private fun HubCell(tile: QuickTile, longClickLabel: String, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier.height(76.dp).spotSurface(MaterialTheme.shapes.medium, colors.surface, outlined = true)
            .combinedClickable(interactionSource = null, indication = PressScaleIndication, onClick = tile.onClick, onLongClick = tile.onLongClick,
                onLongClickLabel = if (tile.onLongClick != null) longClickLabel else null)
            .padding(10.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            tile.menu?.invoke()
            Icon(tile.icon, null, tint = colors.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.weight(1f))
            // a tile's detail line leads with its number when it has one
            Text(tile.subtitle.takeWhile { it.isDigit() || it == '+' }, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1)
        }
        Text(tile.title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PinChip(pin: Pin, onClick: () -> Unit) {
    val accent = accentFor(pin.id)
    val tint = spotlightTint(pin.coverUrl, accent)
    Row(
        Modifier.height(48.dp).widthIn(max = 220.dp).paperFrame(MaterialTheme.shapes.medium, { background(tint) }, fill = tint)
            .pressClickable(onClick = onClick).padding(start = 6.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(pin.coverUrl, accent, Modifier.size(36.dp), corner = if (pin.kind == "artist") 36.dp else 9.dp)
        Spacer(Modifier.width(10.dp))
        Text(pin.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun HubList(title: String, count: Int, rows: List<LibRow>, modifier: Modifier, onOpenAll: (() -> Unit)?, onOpen: (LibRow) -> Unit) {
    val colors = MaterialTheme.colorScheme
    ConsoleModule(modifier.fillMaxWidth(), padding = PaddingValues(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 6.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).then(if (onOpenAll != null) Modifier.clickable(role = Role.Button, onClick = onOpenAll) else Modifier)
                .heightIn(min = 44.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConsoleLabel(title, Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(8.dp))
            Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1)
            Spacer(Modifier.weight(1f))
            if (onOpenAll != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, appString(R.string.text_see_all_2941c5), tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        rows.forEach { row ->
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { onOpen(row) }.heightIn(min = 62.dp).padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(row.art, row.accent, Modifier.size(46.dp), corner = if (row.circle) 46.dp else 10.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(row.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(row.subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (row.badge.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(row.badge, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = colors.onSurfaceVariant, maxLines = 1,
                        modifier = Modifier.border(1.dp, colors.outlineVariant, paperSquare(MaterialTheme.shapes.extraSmall)).padding(horizontal = 7.dp, vertical = 3.dp))
                }
            }
        }
    }
}
