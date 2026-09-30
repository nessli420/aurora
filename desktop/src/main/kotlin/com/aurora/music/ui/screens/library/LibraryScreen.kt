package com.aurora.music.ui.screens.library

import com.aurora.music.localization.localizedMediaType

import com.aurora.music.localization.appPlural

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.sp
import com.aurora.music.data.ThemeStyle
import com.aurora.music.model.LibraryFilter
import com.aurora.music.model.LibraryLayout
import com.aurora.music.model.LibrarySort
import com.aurora.music.model.Song
import com.aurora.music.ui.components.AdaptiveShelf
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.PageHeader
import com.aurora.music.ui.components.PageSection
import com.aurora.music.ui.components.SongListHeader
import com.aurora.music.ui.components.SongRow
import com.aurora.music.ui.layout.LocalPageGutter
import com.aurora.music.ui.layout.PageMetrics
import com.aurora.music.ui.theme.LocalUiPrefs
import com.aurora.music.ui.theme.auroraPanel
import com.aurora.music.util.accentFor
import com.aurora.music.viewmodel.LibraryUiState
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import com.aurora.music.data.accent
import com.aurora.music.model.accent
import com.aurora.music.model.label

internal val LocalLibrarySelection = androidx.compose.runtime.compositionLocalOf<String?> { null }

private data class LibInsets(val start: Dp, val end: Dp)

private val LocalLibInsets = staticCompositionLocalOf { LibInsets(24.dp, 24.dp) }

private val RowInset = 8.dp
private val RailReserve = 40.dp
private val ListArt = 44.dp
private val CellGap = 12.dp
private val ColumnGap = 16.dp
private val ActionSize = 36.dp
private val TileGap = 12.dp
private val TileMinWidth = 260.dp
private val TileMaxWidth = 320.dp
private val CompactWidth = 720.dp
private val RailLetter = 12.dp

@Composable
internal fun PaneScrollbar(adapter: ScrollbarAdapter, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    VerticalScrollbar(
        adapter, modifier,
        style = ScrollbarStyle(
            minimalHeight = 32.dp, thickness = 8.dp, shape = RoundedCornerShape(4.dp), hoverDurationMillis = 300,
            unhoverColor = ink.copy(alpha = 0.18f), hoverColor = ink.copy(alpha = 0.45f),
        ),
    )
}

internal fun Modifier.onSecondaryPress(onPress: (Offset) -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                event.changes.forEach { it.consume() }
                onPress(event.changes.first().position)
            }
        }
    }
}

@Composable
private fun isSelected(row: LibRow): Boolean = LocalLibrarySelection.current == "${row.kind}:${row.id}"

private data class LibRow(
    val title: String,
    val subtitle: String,
    val art: String,
    val accent: Color,
    val id: String,
    val kind: String,
    val circle: Boolean = false,
    val menu: Boolean = true,
    val badge: String = "",
    val sortPlayCount: Int = 0,
    val sortRecencySec: Long = 0,
    val detail: String = subtitle,
    val cells: List<String> = emptyList(),
)

private class TableColumn(val label: String, val weight: Float = 0f, val width: Dp = 0.dp)

private class LibActions(
    val isLiked: (String) -> Boolean,
    val onPlay: (LibRow) -> Unit,
    val onShuffle: (LibRow) -> Unit,
    val onQueue: (LibRow) -> Unit,
    val onToggleLike: (LibRow) -> Unit,
    val onDelete: (LibRow) -> Unit,
    val onEditSmart: (LibRow) -> Unit,
    val onDeleteSmart: (LibRow) -> Unit,
    val onExport: (LibRow) -> Unit,
)

@Composable
fun LibraryScreen(
    contentPadding: PaddingValues,
    state: LibraryUiState,
    username: String,
    likedIds: Set<String>,
    currentSongId: String,
    isPlaying: Boolean,
    onFilter: (LibraryFilter) -> Unit,
    onSort: (LibrarySort) -> Unit,
    onToggleLayout: () -> Unit,
    onOpenDrawer: () -> Unit,
    onPlayAll: (List<Song>, Int) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onToggleLike: (String) -> Unit,
    onOpenDetail: (String, String) -> Unit,
    downloadedIds: Set<String>,
    onDownload: (Song) -> Unit,
    onRemoveDownload: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onCreatePlaylist: suspend (String) -> Boolean,
    onCreateSmart: () -> Unit,
    onEditSmart: (String) -> Unit,
    onDeleteSmart: (String) -> Unit,
    onImportM3u: () -> Unit,
    onExportPlaylist: (String, String, String) -> Unit,
    onOpenFolders: () -> Unit,
    onOpenRadio: (() -> Unit)? = null,
    onOpenPodcasts: (() -> Unit)? = null,
    onPlayCollection: (String, String) -> Unit,
    onShuffleCollection: (String, String) -> Unit,
    onQueueCollection: (String, String) -> Unit,
    onToggleLikeKind: (String, String) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    canDownload: Boolean = true,
    pins: List<com.aurora.music.data.Pin> = emptyList(),
    onEditTags: ((Song) -> Unit)? = null,
    serverTagEditing: Boolean = false,
    onLoadMoreSongs: () -> Unit = {},
    // songs-tab playback covers the whole library, not just the scrolled-in rows
    onPlayAllSongs: (shuffle: Boolean) -> Unit = {},
    onPlaySong: (Song) -> Unit = {},
    selectedItem: String? = null,
) {
    val gutter = LocalPageGutter.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val compact = maxWidth < CompactWidth
    val insets = LibInsets(gutter, if (compact && selectedItem != null) 16.dp else gutter)
    CompositionLocalProvider(LocalLibrarySelection provides selectedItem, LocalLibInsets provides insets) {
    var showCreate by remember { mutableStateOf(false) }
    val filter = state.filter
    val sort = state.sort
    val layout = state.layout
    val cardMin = cardMinWidth(LocalUiPrefs.current.libraryColumns, compact)
    val actions = LibActions(
        isLiked = { id -> likedIds.contains(id) },
        onPlay = { r -> onPlayCollection(r.id, r.kind) },
        onShuffle = { r -> onShuffleCollection(r.id, r.kind) },
        onQueue = { r -> onQueueCollection(r.id, r.kind) },
        onToggleLike = { r -> onToggleLikeKind(r.id, r.kind) },
        onDelete = { r -> onDeletePlaylist(r.id) },
        onEditSmart = { r -> onEditSmart(r.id) },
        onDeleteSmart = { r -> onDeleteSmart(r.id) },
        onExport = { r -> onExportPlaylist(r.id, r.kind, r.title) },
    )
    val open: (LibRow) -> Unit = { r ->
        when (r.kind) {
            "folders" -> onOpenFolders()
            "radio" -> onOpenRadio?.invoke()
            "podcasts" -> onOpenPodcasts?.invoke()
            else -> onOpenDetail(r.kind, r.id)
        }
    }

    Column(Modifier.fillMaxSize()) {
        val stats = if (compact) null else buildList {
            if (state.playlists.isNotEmpty() || state.smartPlaylists.isNotEmpty()) add(appPlural(R.plurals.playlist_count, (state.playlists.size + state.smartPlaylists.size)))
            if (state.albums.isNotEmpty()) add(appPlural(R.plurals.album_count, (state.albums.size)))
            if (state.artists.isNotEmpty()) add(appPlural(R.plurals.artist_count, (state.artists.size)))
        }.joinToString("  ·  ")
        PageHeader(
            appString(R.string.text_library_b8100f),
            Modifier.padding(start = insets.start, end = (insets.end - RowInset).coerceAtLeast(0.dp)),
            subtitle = stats,
        ) {
            HeaderAction(Icons.Filled.Search, appString(R.string.text_search_bce064), onOpenSearch)
            HeaderAction(Icons.Filled.Add, appString(R.string.text_create_playlist_62c988)) { showCreate = true }
        }

        if (showCreate) {
            CreatePlaylistDialog(
                onCreate = onCreatePlaylist,
                onCreateSmart = { showCreate = false; onCreateSmart() },
                onImportM3u = { showCreate = false; onImportM3u() },
                onDismiss = { showCreate = false },
            )
        }

        LibraryToolbar(filter, sort, layout, canDownload, compact, onFilter, onSort, onToggleLayout)

        val bottom = contentPadding.calculateBottomPadding() + 24.dp

        if (state.loading && state.albums.isEmpty() && state.playlists.isEmpty() && state.songs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                com.aurora.music.ui.components.LottieLoader(modifier = Modifier.size(72.dp))
            }
            return@Column
        }

        when (filter) {
            LibraryFilter.ALL -> AllOverview(
                state = state, pins = pins, canDownload = canDownload, bottom = bottom, cardMin = cardMin, actions = actions,
                onFilter = onFilter, onOpen = open, onOpenDetail = onOpenDetail,
                onOpenFolders = onOpenFolders, onOpenRadio = onOpenRadio, onOpenPodcasts = onOpenPodcasts,
            )
            LibraryFilter.SONGS -> SongsTab(
                state = state, sort = sort, likedIds = likedIds, currentSongId = currentSongId, isPlaying = isPlaying,
                bottom = bottom, canDownload = canDownload, downloadedIds = downloadedIds,
                onAddToQueue = onAddToQueue, onPlayNext = onPlayNext, onToggleLike = onToggleLike,
                onOpenDetail = onOpenDetail, onDownload = onDownload, onRemoveDownload = onRemoveDownload,
                onEditTags = onEditTags, serverTagEditing = serverTagEditing, onLoadMoreSongs = onLoadMoreSongs,
                onPlayAllSongs = onPlayAllSongs, onPlaySong = onPlaySong,
            )
            LibraryFilter.DOWNLOADED -> {
                val dlRows = state.downloadedRows.map { LibRow(it.title, appString(R.string.downloaded_collection), it.coverUrl, it.accent, it.id, it.kind) }
                if (dlRows.isEmpty()) {
                    EmptyHint(appString(R.string.text_no_downloads_yet_9647c1), appString(R.string.text_albums_and_playlists_you_download_live_here_e4c7ba))
                } else {
                    RowsContent(dlRows, emptyList(), layout, cardMin, sort, bottom, actions, open)
                }
            }
            else -> {
                val rows = buildRows(state, filter, sort, pins)
                if (rows.isEmpty()) {
                    EmptyHint(appString(R.string.text_nothing_here_yet_e89225), appString(R.string.text_your_will_show_up_once_the_server_has_some_ced31f, (filter.label.lowercase())))
                } else {
                    key(filter) { RowsContent(rows, tableColumns(filter), layout, cardMin, sort, bottom, actions, open) }
                }
            }
        }
    }
    }
    }
}

private fun cardMinWidth(columns: Int, compact: Boolean): Dp = when (columns.coerceIn(2, 4)) {
    3 -> if (compact) 96.dp else 148.dp
    4 -> if (compact) 80.dp else 128.dp
    else -> if (compact) 112.dp else PageMetrics.ShelfMinItemWidth
}

@Composable
private fun tableColumns(filter: LibraryFilter): List<TableColumn> = when (filter) {
    LibraryFilter.ALBUMS -> listOf(
        TableColumn(appString(R.string.text_artist_6c3f3d), weight = 0.33f),
        TableColumn(appString(R.string.text_year_879e32), width = 72.dp),
        TableColumn(appString(R.string.text_tracks_3dd1a4), width = 72.dp),
    )
    LibraryFilter.ARTISTS -> listOf(TableColumn(appString(R.string.text_albums_4c45e7), width = 88.dp))
    LibraryFilter.PLAYLISTS -> listOf(TableColumn(appString(R.string.text_tracks_3dd1a4), width = 72.dp))
    else -> emptyList()
}

@Composable
private fun HeaderAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
        Icon(icon, label)
    }
}

private fun tabIcon(f: LibraryFilter): ImageVector = when (f) {
    LibraryFilter.ALL -> Icons.Filled.Apps
    LibraryFilter.PLAYLISTS -> Icons.AutoMirrored.Filled.QueueMusic
    LibraryFilter.ALBUMS -> Icons.Filled.Album
    LibraryFilter.ARTISTS -> Icons.Filled.Person
    LibraryFilter.SONGS -> Icons.Filled.MusicNote
    LibraryFilter.DOWNLOADED -> Icons.Filled.Download
}

@Composable
private fun LibraryToolbar(
    filter: LibraryFilter,
    sort: LibrarySort,
    layout: LibraryLayout,
    canDownload: Boolean,
    compact: Boolean,
    onFilter: (LibraryFilter) -> Unit,
    onSort: (LibrarySort) -> Unit,
    onToggleLayout: () -> Unit,
) {
    val insets = LocalLibInsets.current
    val visibleTabs = LibraryFilter.entries.filter { canDownload || it != LibraryFilter.DOWNLOADED }
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(end = insets.end),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = (insets.start - 10.dp).coerceAtLeast(0.dp), end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(visibleTabs.size) { i ->
                val f = visibleTabs[i]
                LibTab(label = f.label, icon = tabIcon(f), selected = f == filter, iconOnly = compact) { onFilter(f) }
            }
        }
        if (filter != LibraryFilter.ALL) SortButton(sort, iconOnly = compact, onSort = onSort)
        if (filter != LibraryFilter.ALL && filter != LibraryFilter.SONGS) {
            Spacer(Modifier.width(6.dp))
            LayoutToggle(layout, onToggleLayout)
        }
    }
}

@Composable
private fun SortButton(sort: LibrarySort, iconOnly: Boolean, onSort: (LibrarySort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = appString(R.string.text_sort_by_a2a5bd)
    Box {
        Row(
            Modifier.height(36.dp).clip(RoundedCornerShape(50))
                .clickable(onClickLabel = label) { open = true }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = if (iconOnly) 9.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.SwapVert, if (iconOnly) sort.label else label, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            if (!iconOnly) {
                Spacer(Modifier.width(6.dp))
                Text(sort.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            LibrarySort.entries.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.label) },
                    onClick = { onSort(s); open = false },
                    trailingIcon = { if (s == sort) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary) },
                )
            }
        }
    }
}

@Composable
private fun LayoutToggle(layout: LibraryLayout, onToggle: () -> Unit) {
    val label = appString(R.string.text_toggle_layout_6169e7)
    val shape = if (LocalUiPrefs.current.themeStyle == ThemeStyle.AURORA) RoundedCornerShape(50) else MaterialTheme.shapes.small
    Row(Modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f)).padding(3.dp)) {
        listOf(LibraryLayout.LIST to Icons.AutoMirrored.Filled.List, LibraryLayout.GRID to Icons.Filled.GridView).forEach { (mode, icon) ->
            val on = mode == layout
            Box(
                Modifier.size(30.dp).clip(shape)
                    .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable(enabled = !on, onClickLabel = label, onClick = onToggle)
                    .pointerHoverIcon(PointerIcon.Hand),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, label, tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibTab(label: String, icon: ImageVector, selected: Boolean, iconOnly: Boolean, onClick: () -> Unit) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val bare = iconOnly && !selected
    val tab: @Composable () -> Unit = {
        Column(
            Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = label, onClick = onClick).pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.height(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, if (bare) label else null, tint = tint, modifier = Modifier.size(16.dp))
                if (!bare) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.Black else FontWeight.Medium,
                        color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier.height(3.dp).width(if (bare) 16.dp else 26.dp).clip(RoundedCornerShape(50)).background(
                    if (selected) Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))
                    else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                )
            )
        }
    }
    if (bare) TooltipArea(tooltip = { TabTooltip(label) }, delayMillis = 400) { tab() } else tab()
}

@Composable
private fun TabTooltip(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.inverseOnSurface,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.inverseSurface).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun AllOverview(
    state: LibraryUiState,
    pins: List<com.aurora.music.data.Pin>,
    canDownload: Boolean,
    bottom: Dp,
    cardMin: Dp,
    actions: LibActions,
    onFilter: (LibraryFilter) -> Unit,
    onOpen: (LibRow) -> Unit,
    onOpenDetail: (String, String) -> Unit,
    onOpenFolders: () -> Unit,
    onOpenRadio: (() -> Unit)?,
    onOpenPodcasts: (() -> Unit)?,
) {
    val insets = LocalLibInsets.current
    val listState = rememberLazyListState()
    val seeAll = appString(R.string.text_see_all_2941c5)
    val pinRows = pins.map { p ->
        LibRow(p.title, p.subtitle.ifBlank { kindLabel(p.kind) }, p.coverUrl, accentFor(p.id), p.id, p.kind, circle = p.kind == "artist")
    }
    val playlistRows = state.smartPlaylists.map { sp ->
        LibRow(sp.name ?: appString(R.string.text_smart_playlist_f77ad7), appString(R.string.text_smart_playlist_f77ad7), "", accentFor(sp.id ?: "smart"), sp.id ?: "", "smart", badge = appString(R.string.text_auto_50c3f1))
    } + state.playlists.map { p -> LibRow(p.title, appPlural(R.plurals.track_count, (p.songCount)), p.coverUrl, p.accent, p.id, "playlist") }
    val albumRows = state.albums.map { albumRow(it) }
    val albumsByArtist = remember(state.albums) { state.albums.groupingBy { it.artist.lowercase() }.eachCount() }
    val artistRows = state.artists.map { artistRow(it, albumsByArtist[it.name.lowercase()] ?: 0) }
    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = insets.start, end = insets.end, top = 8.dp, bottom = bottom),
        verticalArrangement = Arrangement.spacedBy(PageMetrics.SectionGap),
    ) {
        item {
            val tiles = buildList {
                add(QuickTile(appString(R.string.text_liked_songs_58c3a9), appPlural(R.plurals.track_count, (state.likedSongCount)), Icons.Filled.Favorite, state.likedCover) { onOpenDetail("liked", "liked") })
                if (canDownload) add(QuickTile(appString(R.string.text_downloads_a862c2), appPlural(R.plurals.item_count, (state.downloadedRows.size)), Icons.Filled.Download, "") { onFilter(LibraryFilter.DOWNLOADED) })
                if (state.supportsFolders) add(QuickTile(appString(R.string.text_folders_19adc4), appString(R.string.text_browse_files_524932), Icons.Filled.Folder, "") { onOpenFolders() })
                if (onOpenRadio != null) add(QuickTile(appString(R.string.text_radio_b11bf1), appString(R.string.text_live_stations_f40694), Icons.Filled.Radio, "") { onOpenRadio() })
                if (onOpenPodcasts != null) add(QuickTile(appString(R.string.text_podcasts_fd52b4), appString(R.string.text_shows_episodes_526d46), Icons.Filled.Podcasts, "") { onOpenPodcasts() })
            }
            QuickTiles(tiles)
        }

        if (pinRows.isNotEmpty()) {
            item {
                PageSection(appString(R.string.text_pinned_f93121), count = pinRows.size) { LibShelf(pinRows, cardMin, actions, onOpen) }
            }
        }

        if (playlistRows.isNotEmpty()) {
            item {
                PageSection(appString(R.string.text_playlists_77b69f), action = seeAll, onAction = { onFilter(LibraryFilter.PLAYLISTS) }, count = playlistRows.size) {
                    LibShelf(playlistRows, cardMin, actions, onOpen)
                }
            }
        }

        if (albumRows.isNotEmpty()) {
            item {
                PageSection(appString(R.string.text_albums_4c45e7), action = seeAll, onAction = { onFilter(LibraryFilter.ALBUMS) }, count = albumRows.size) {
                    LibShelf(albumRows, cardMin, actions, onOpen)
                }
            }
        }

        if (artistRows.isNotEmpty()) {
            item {
                PageSection(appString(R.string.text_artists_1528d8), action = seeAll, onAction = { onFilter(LibraryFilter.ARTISTS) }, count = artistRows.size) {
                    LibShelf(artistRows, cardMin, actions, onOpen)
                }
            }
        }
    }
    PaneScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(bottom = bottom))
    }
}

@Composable
private fun LibShelf(rows: List<LibRow>, minWidth: Dp, actions: LibActions, onOpen: (LibRow) -> Unit) {
    val insets = LocalLibInsets.current
    AdaptiveShelf(rows, minItemWidth = minWidth, bleed = minOf(insets.start, insets.end)) { row, _ ->
        LibCard(row, actions) { onOpen(row) }
    }
}

private data class QuickTile(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val art: String,
    val onClick: () -> Unit,
)

@Composable
private fun QuickTiles(tiles: List<QuickTile>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = floor((maxWidth + TileGap) / (TileMinWidth + TileGap)).toInt().coerceAtLeast(1)
        val tileWidth = if (columns == 1) maxWidth else ((maxWidth - TileGap * (columns - 1)) / columns).coerceAtMost(TileMaxWidth)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(TileGap), verticalArrangement = Arrangement.spacedBy(TileGap)) {
            tiles.forEach { t -> QuickTileCard(t, Modifier.width(tileWidth)) }
        }
    }
}

@Composable
private fun QuickTileCard(tile: QuickTile, modifier: Modifier = Modifier) {
    val aurora = LocalUiPrefs.current.themeStyle == ThemeStyle.AURORA
    val shape = if (aurora) RoundedCornerShape(16.dp) else MaterialTheme.shapes.medium
    Row(
        modifier
            .height(64.dp)
            .then(if (aurora) Modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f)) else Modifier.auroraPanel(shape))
            .clickable(onClick = tile.onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tile.art.isNotBlank()) {
            Artwork(tile.art, MaterialTheme.colorScheme.primary, Modifier.size(40.dp), corner = 10.dp)
        } else {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                    .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f), MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f)))),
                contentAlignment = Alignment.Center,
            ) { Icon(tile.icon, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp)) }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(tile.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tile.subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SongsTab(
    state: LibraryUiState,
    sort: LibrarySort,
    likedIds: Set<String>,
    currentSongId: String,
    isPlaying: Boolean,
    bottom: Dp,
    canDownload: Boolean,
    downloadedIds: Set<String>,
    onAddToQueue: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onToggleLike: (String) -> Unit,
    onOpenDetail: (String, String) -> Unit,
    onDownload: (Song) -> Unit,
    onRemoveDownload: (String) -> Unit,
    onEditTags: ((Song) -> Unit)?,
    serverTagEditing: Boolean,
    onLoadMoreSongs: () -> Unit,
    onPlayAllSongs: (shuffle: Boolean) -> Unit,
    onPlaySong: (Song) -> Unit,
) {
    val songs = remember(state.songs, sort, state.localPlayCounts) { sortedSongs(state.songs, sort, state.localPlayCounts) }
    if (songs.isEmpty() && !state.loading) {
        EmptyHint(appString(R.string.text_no_songs_e6bbe2), appString(R.string.text_songs_from_your_server_appear_here_e4db53))
        return
    }
    val insets = LocalLibInsets.current
    val rail = sort == LibrarySort.ALPHABETICAL && songs.size > 30
    val rowStart = insets.start - RowInset
    val rowEnd = if (rail) maxOf(insets.end - RowInset, RailReserve) else (insets.end - RowInset).coerceAtLeast(0.dp)

    Column(Modifier.fillMaxSize()) {
    Row(
        Modifier.fillMaxWidth().padding(start = insets.start, end = insets.end, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val accent = MaterialTheme.colorScheme.primary
        val onAccent = if (accent.luminance() > 0.6f) Color.Black else Color.White
        Row(
            Modifier.clip(RoundedCornerShape(50))
                .background(Brush.horizontalGradient(listOf(accent, MaterialTheme.colorScheme.tertiary)))
                .clickable(enabled = songs.isNotEmpty()) { onPlayAllSongs(false) }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = 18.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.PlayArrow, appString(R.string.text_play_all_ebb2ff), tint = onAccent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(appString(R.string.text_play_5d12bd), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black, color = onAccent)
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Filled.Shuffle, appString(R.string.text_shuffle_all_7e388b),
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(38.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(enabled = songs.isNotEmpty()) { onPlayAllSongs(true) }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(9.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            if (state.canLoadMoreSongs) appString(R.string.track_count_more, (songs.size)) else appPlural(R.plurals.track_count, (songs.size)),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }

    SongListHeader(Modifier.padding(start = rowStart, end = rowEnd), showIndex = false, showArt = true, showAlbum = true, showDateAdded = true)

    val listState = rememberLazyListState()
    LaunchedEffect(listState, state.canLoadMoreSongs) {
        snapshotFlow {
            val li = listState.layoutInfo
            (li.visibleItemsInfo.lastOrNull()?.index ?: 0) to li.totalItemsCount
        }.collect { (last, count) ->
            if (state.canLoadMoreSongs && count > 0 && last >= count - 14) onLoadMoreSongs()
        }
    }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(start = rowStart, end = rowEnd, top = 4.dp, bottom = bottom),
        ) {
            items(songs.size) { i ->
                val s = songs[i]
                SongRow(
                    s, isPlaying = s.id == currentSongId && isPlaying, isLiked = likedIds.contains(s.id),
                    onClick = { onPlaySong(s) }, onToggleLike = { onToggleLike(s.id) },
                    onAddToQueue = { onAddToQueue(s) }, onPlayNext = { onPlayNext(s) },
                    onGoToAlbum = if (s.albumId.isNotBlank()) ({ onOpenDetail("album", s.albumId) }) else null,
                    onGoToArtist = if (s.artistId.isNotBlank()) ({ onOpenDetail("artist", s.artistId) }) else null,
                    isDownloaded = canDownload && downloadedIds.contains(s.id),
                    onDownload = if (canDownload) ({ onDownload(s) }) else null,
                    onRemoveDownload = if (canDownload) ({ onRemoveDownload(s.id) }) else null,
                    onEditTags = onEditTags?.let { cb -> { cb(s) } },
                    serverTagEditing = serverTagEditing,
                    showDateAdded = true,
                )
            }
            if (state.songsLoadingMore) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                        com.aurora.music.ui.components.LottieLoader(modifier = Modifier.size(36.dp))
                    }
                }
            }
        }
        PaneScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(bottom = bottom))
        if (rail) {
            AlphabetRail(
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 12.dp, bottom = bottom),
                onJump = { c ->
                    jumpIndex(songs.map { it.title }, c)?.let { idx -> scope.launch { listState.scrollToItem(idx) } }
                },
            )
        }
    }
    }
}

@Composable
private fun RowsContent(
    rows: List<LibRow>,
    columns: List<TableColumn>,
    layout: LibraryLayout,
    cardMin: Dp,
    sort: LibrarySort,
    bottom: Dp,
    actions: LibActions,
    onOpen: (LibRow) -> Unit,
) {
    val insets = LocalLibInsets.current
    if (layout == LibraryLayout.LIST) {
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val rail = sort == LibrarySort.ALPHABETICAL && rows.size > 30
        val rowStart = insets.start - RowInset
        val rowEnd = if (rail) maxOf(insets.end - RowInset, RailReserve) else (insets.end - RowInset).coerceAtLeast(0.dp)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val table = columns.isNotEmpty() && maxWidth - rowStart - rowEnd >= PageMetrics.SongTableMinWidth
            val shown = if (table) columns else emptyList()
            Column(Modifier.fillMaxSize()) {
                if (table) LibTableHeader(shown, Modifier.padding(start = rowStart, end = rowEnd))
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        state = listState,
                        contentPadding = PaddingValues(start = rowStart, end = rowEnd, top = 4.dp, bottom = bottom),
                    ) {
                        items(rows.size) { i -> LibListItem(rows[i], shown, actions) { onOpen(rows[i]) } }
                    }
                    PaneScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(bottom = bottom))
                    if (rail) {
                        AlphabetRail(
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 12.dp, bottom = bottom),
                            onJump = { c ->
                                jumpIndex(rows.map { it.title }, c)?.let { idx -> scope.launch { listState.scrollToItem(idx) } }
                            },
                        )
                    }
                }
            }
        }
    } else {
        val gridState = rememberLazyGridState()
        Box(Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(cardMin),
                modifier = Modifier.fillMaxSize(),
                state = gridState,
                contentPadding = PaddingValues(start = insets.start, end = insets.end, top = 8.dp, bottom = bottom),
                horizontalArrangement = Arrangement.spacedBy(PageMetrics.ShelfSpacing),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                items(rows.size) { i -> LibCard(rows[i], actions) { onOpen(rows[i]) } }
            }
            PaneScrollbar(rememberScrollbarAdapter(gridState), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(bottom = bottom))
        }
    }
}

private fun titleWeight(columns: List<TableColumn>): Float = if (columns.any { it.weight > 0f }) 0.42f else 1f

private fun RowScope.cell(column: TableColumn): Modifier =
    (if (column.weight > 0f) Modifier.weight(column.weight) else Modifier.width(column.width)).padding(start = ColumnGap)

@Composable
private fun LibTableHeader(columns: List<TableColumn>, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = RowInset), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(ListArt + CellGap))
            Text(appString(R.string.text_title_768e0c), style = MaterialTheme.typography.labelMedium, color = muted, maxLines = 1, modifier = Modifier.weight(titleWeight(columns)))
            columns.forEach { c ->
                Text(
                    c.label, style = MaterialTheme.typography.labelMedium, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = if (c.weight > 0f) TextAlign.Start else TextAlign.End, modifier = cell(c),
                )
            }
            Spacer(Modifier.width(RowInset + ActionSize))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    }
}

@Composable
private fun EmptyHint(title: String, message: String) {
    Box(Modifier.fillMaxSize().padding(horizontal = 40.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
internal fun AlphabetRail(onJump: (Char) -> Unit, modifier: Modifier = Modifier) {
    val letters = remember { ('A'..'Z').toList() + '#' }
    var railHeight by remember { mutableStateOf(0) }
    var active by remember { mutableStateOf<Char?>(null) }
    val currentOnJump by rememberUpdatedState(onJump)
    val slot = with(LocalDensity.current) { RailLetter.toPx() }
    val step = if (railHeight <= 0) 1 else ceil(letters.size * slot / railHeight).toInt().coerceAtLeast(1)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    Layout(
        content = {
            letters.forEachIndexed { i, c ->
                val isActive = active == c
                if (isActive || i % step == 0) Text(
                    c.toString(),
                    fontSize = if (isActive) 13.sp else 9.sp,
                    lineHeight = if (isActive) 14.sp else 11.sp,
                    fontWeight = if (isActive) FontWeight.Black else FontWeight.SemiBold,
                    color = if (isActive) MaterialTheme.colorScheme.primary else muted,
                ) else Box(Modifier)
            }
        },
        modifier = modifier
            .width(24.dp)
            .onSizeChanged { railHeight = it.height }
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(Unit) {
                awaitEachGesture {
                    fun pick(y: Float): Char? {
                        if (railHeight <= 0) return null
                        val idx = ((y / railHeight) * letters.size).toInt().coerceIn(0, letters.size - 1)
                        return letters[idx]
                    }
                    val down = awaitFirstDown()
                    pick(down.position.y)?.let { c -> if (c != active) { active = c; currentOnJump(c) } }
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        change.consume()
                        pick(change.position.y)?.let { c -> if (c != active) { active = c; currentOnJump(c) } }
                    }
                    active = null
                }
            },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else (slot * letters.size).roundToInt()
        val placeables = measurables.map { it.measure(Constraints()) }
        layout(width, height) {
            placeables.forEachIndexed { i, p ->
                p.place((width - p.width) / 2, ((i + 0.5f) * height / letters.size - p.height / 2f).roundToInt())
            }
        }
    }
}

private fun jumpIndex(titles: List<String>, c: Char): Int? {
    if (titles.isEmpty()) return null
    if (c == '#') return titles.indexOfFirst { it.trimStart().firstOrNull()?.isLetter() != true }.takeIf { it >= 0 }
    val exact = titles.indexOfFirst { it.trimStart().firstOrNull()?.uppercaseChar() == c }
    if (exact >= 0) return exact
    // no entries for that letter land on the next one that exists
    return titles.indexOfFirst { (it.trimStart().firstOrNull()?.uppercaseChar() ?: ' ') > c }.takeIf { it >= 0 }
}

private fun sortedSongs(songs: List<Song>, sort: LibrarySort, localPlayCounts: Map<String, Int>): List<Song> =
    com.aurora.music.viewmodel.sortLibrarySongs(songs, sort, localPlayCounts)

private fun kindLabel(kind: String): String = when (kind) {
    "album" -> appString(R.string.text_album_dfb4c9)
    "artist" -> appString(R.string.text_artist_6c3f3d)
    "playlist" -> appString(R.string.text_playlist_cd95b4)
    else -> kind.replaceFirstChar { it.uppercase() }
}

private fun albumRow(album: com.aurora.music.model.Album): LibRow {
    val label = album.typeLabel.localizedMediaType()
    val year = album.year.takeIf { it > 0 }?.toString().orEmpty()
    return LibRow(
        album.title, listOf(album.artist, year).filter { it.isNotBlank() }.joinToString(" • "), album.artworkUrl, accentFor(album.id), album.id, "album",
        badge = if (label == appString(R.string.text_album_dfb4c9)) "" else label.uppercase(),
        sortPlayCount = album.playCount, sortRecencySec = album.year.toLong(),
        detail = label, cells = listOf(album.artist, year, album.songCount.takeIf { it > 0 }?.toString().orEmpty()),
    )
}

private fun artistRow(artist: com.aurora.music.model.Artist, albums: Int, plays: Int = 0, recency: Long = 0): LibRow = LibRow(
    artist.name, if (albums > 0) appPlural(R.plurals.album_count, albums) else appString(R.string.text_artist_6c3f3d), artist.imageUrl,
    accentFor(artist.id), artist.id, "artist", circle = true, sortPlayCount = plays, sortRecencySec = recency,
    detail = appString(R.string.text_artist_6c3f3d), cells = listOf(albums.takeIf { it > 0 }?.toString().orEmpty()),
)

private fun buildRows(state: LibraryUiState, filter: LibraryFilter, sort: LibrarySort, pins: List<com.aurora.music.data.Pin>): List<LibRow> {
    val base = when (filter) {
        LibraryFilter.PLAYLISTS -> {
            val smart = state.smartPlaylists.map {
                val n = it.rules.orEmpty().size
                LibRow(it.name ?: appString(R.string.text_smart_playlist_f77ad7), appString(R.string.smart_rule_count, appPlural(R.plurals.rule_count, (n))), "", accentFor(it.id ?: "smart"), it.id ?: "", "smart", badge = appString(R.string.text_auto_50c3f1))
            }
            smart + state.playlists.map {
                LibRow(
                    it.title, appString(R.string.playlist_track_count, appPlural(R.plurals.track_count, (it.songCount))), it.coverUrl, it.accent, it.id, "playlist",
                    detail = appString(R.string.text_playlist_cd95b4), cells = listOf(it.songCount.toString()),
                )
            }
        }
        LibraryFilter.ALBUMS -> state.albums.map { albumRow(it) }
        LibraryFilter.ARTISTS -> {
            // artist sort aggregates the loaded song pages
            val tracksByArtist = state.songs.groupBy { it.artistId }
            val albumsByArtist = state.albums.groupingBy { it.artist.lowercase() }.eachCount()
            state.artists.map { ar ->
                val tracks = tracksByArtist[ar.id].orEmpty()
                val plays = tracks.sumOf { maxOf(it.playCount, state.localPlayCounts[it.id] ?: 0) }
                val recency = tracks.maxOfOrNull { it.dateAddedSec } ?: 0L
                artistRow(ar, albumsByArtist[ar.name.lowercase()] ?: 0, plays, recency)
            }
        }
        else -> emptyList()
    }
    val sorted = when (sort) {
        LibrarySort.ALPHABETICAL -> base.sortedBy { it.title.lowercase() }
        LibrarySort.CREATOR -> base.sortedBy { it.subtitle.lowercase() }
        // playlists carry neither signal so they keep their loaded (roughly alphabetical) order
        LibrarySort.MOST_PLAYED -> base.sortedByDescending { it.sortPlayCount }
        LibrarySort.RECENT -> base.sortedByDescending { it.sortRecencySec }
    }
    if (filter != LibraryFilter.PLAYLISTS) return sorted

    // playlists tab keeps liked songs on top pinned entries stay deduped
    val pinned = pins.map { it.kind to it.id }.toSet()
    val deduped = sorted.filterNot { (it.kind to it.id) in pinned }
    val liked = LibRow(
        appString(R.string.text_liked_songs_58c3a9), appString(R.string.playlist_track_count, appPlural(R.plurals.track_count, (state.likedSongCount))), state.likedCover, accentFor("liked"), "liked", "liked",
        detail = appString(R.string.text_playlist_cd95b4), cells = listOf(state.likedSongCount.toString()),
    )
    return listOf(liked) + deduped
}

@Composable
private fun CreatePlaylistDialog(onCreate: suspend (String) -> Boolean, onCreateSmart: () -> Unit, onImportM3u: () -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(appString(R.string.text_new_playlist_a5474a), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; failed = false },
                    label = { Text(appString(R.string.text_playlist_name_544f75)) },
                    singleLine = true,
                    enabled = !saving,
                )
                if (failed) {
                    Text(appString(R.string.playlist_create_failed), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
                TextButton(onClick = onCreateSmart, enabled = !saving, modifier = Modifier.padding(top = 6.dp)) {
                    Text(appString(R.string.text_create_a_smart_playlist_instead_f73978))
                }
                TextButton(onClick = onImportM3u, enabled = !saving) {
                    Text(appString(R.string.text_import_an_m3u_file_3ae427))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                saving = true
                failed = false
                scope.launch {
                    try {
                        if (onCreate(name.trim())) onDismiss() else failed = true
                    } finally {
                        saving = false
                    }
                }
            }, enabled = name.isNotBlank() && !saving) {
                Text(appString(if (saving) R.string.playlist_creating else R.string.text_create_6e157c))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text(appString(R.string.text_cancel_77dfd2)) } },
    )
}

@Composable
private fun rowShape(): Shape = if (LocalUiPrefs.current.themeStyle == ThemeStyle.AURORA) RoundedCornerShape(12.dp) else MaterialTheme.shapes.small

@Composable
private fun artShape(row: LibRow): Shape = if (row.circle) CircleShape else RoundedCornerShape(
    when (LocalUiPrefs.current.themeStyle) {
        ThemeStyle.RETRO -> 2.dp
        ThemeStyle.AERO -> 5.dp
        ThemeStyle.GLASS -> 18.dp
        else -> 14.dp
    }
)

@Composable
private fun Badge(text: String, modifier: Modifier = Modifier, small: Boolean = false) {
    Text(
        text,
        fontSize = if (small) 8.sp else 9.sp,
        fontWeight = FontWeight.Black,
        color = Color.White,
        modifier = modifier.padding(if (small) 3.dp else 8.dp)
            .clip(RoundedCornerShape(if (small) 5.dp else 6.dp)).background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = if (small) 4.dp else 6.dp, vertical = if (small) 1.dp else 2.dp),
    )
}

@Composable
private fun LibListItem(row: LibRow, columns: List<TableColumn>, actions: LibActions, onClick: () -> Unit) {
    var contextAt by remember { mutableStateOf<Offset?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val selected = isSelected(row)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.fillMaxWidth().onSecondaryPress { contextAt = it }) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = PageMetrics.SongRowHeight)
            .clip(rowShape())
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = RowInset, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Artwork(row.art, row.accent, Modifier.size(ListArt), corner = if (row.circle) ListArt else 10.dp)
            if (row.badge.isNotBlank()) Badge(row.badge, Modifier.align(Alignment.BottomStart), small = true)
        }
        Spacer(Modifier.width(CellGap))
        Column(Modifier.weight(titleWeight(columns))) {
            Text(
                row.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(if (columns.isEmpty()) row.subtitle else row.detail, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        columns.forEachIndexed { i, c ->
            Text(
                row.cells.getOrElse(i) { "" }, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = if (c.weight > 0f) TextAlign.Start else TextAlign.End, modifier = cell(c),
            )
        }
        Spacer(Modifier.width(RowInset))
        Box(Modifier.size(ActionSize)) {
            if (row.menu) {
                Box(
                    Modifier.size(ActionSize).clip(CircleShape)
                        .then(if (hovered || menuOpen) Modifier.clickable(onClickLabel = appString(R.string.text_more_4bab2d)) { menuOpen = true }.pointerHoverIcon(PointerIcon.Hand) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    if (hovered || menuOpen) Icon(Icons.Filled.MoreVert, appString(R.string.text_more_4bab2d), tint = muted, modifier = Modifier.size(20.dp))
                }
                CollectionMenu(row, actions, expanded = menuOpen, onDismiss = { menuOpen = false })
            }
        }
    }
    if (row.menu) contextAt?.let { at ->
        Box(Modifier.offset { at.round() }) { CollectionMenu(row, actions, expanded = true, onDismiss = { contextAt = null }) }
    }
    }
}

@Composable
private fun LibCard(row: LibRow, actions: LibActions, onClick: () -> Unit) {
    var contextAt by remember { mutableStateOf<Offset?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val aurora = LocalUiPrefs.current.themeStyle == ThemeStyle.AURORA
    val shape = if (aurora) RoundedCornerShape(16.dp) else MaterialTheme.shapes.medium
    val selected = isSelected(row)
    val showActions = row.menu && (hovered || menuOpen)
    val align = if (row.circle) TextAlign.Center else TextAlign.Start
    Box(Modifier.fillMaxWidth().onSecondaryPress { contextAt = it }) {
    Column(
        Modifier.fillMaxWidth()
            .clip(shape)
            .then(if (aurora) Modifier else Modifier.auroraPanel(shape))
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(if (aurora) 0.dp else 8.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            Artwork(row.art, row.accent, Modifier.fillMaxSize(), corner = if (row.circle) 400.dp else 14.dp)
            if (selected) Box(Modifier.matchParentSize().border(3.dp, MaterialTheme.colorScheme.primary, artShape(row)))
            if (row.badge.isNotBlank()) Badge(row.badge, Modifier.align(Alignment.TopStart))
            if (showActions) {
                Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                    Box(
                        Modifier.size(32.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f))
                            .clickable(onClickLabel = appString(R.string.text_more_4bab2d)) { menuOpen = true }
                            .pointerHoverIcon(PointerIcon.Hand),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.MoreVert, appString(R.string.text_more_4bab2d), tint = Color.White, modifier = Modifier.size(18.dp)) }
                }
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(8.dp).size(40.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(onClickLabel = appString(R.string.text_play_5d12bd)) { actions.onPlay(row) }
                        .pointerHoverIcon(PointerIcon.Hand),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.PlayArrow, appString(R.string.text_play_5d12bd), tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp)) }
            }
            if (row.menu) Box(Modifier.align(Alignment.TopEnd)) { CollectionMenu(row, actions, expanded = menuOpen, onDismiss = { menuOpen = false }) }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            row.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = align, modifier = Modifier.fillMaxWidth(),
        )
        if (row.subtitle.isNotBlank()) {
            Text(
                row.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = align, modifier = Modifier.fillMaxWidth(),
            )
        }
        if (aurora) Spacer(Modifier.height(6.dp))
    }
    if (row.menu) contextAt?.let { at ->
        Box(Modifier.offset { at.round() }) { CollectionMenu(row, actions, expanded = true, onDismiss = { contextAt = null }) }
    }
    }
}

@Composable
private fun CollectionMenu(row: LibRow, actions: LibActions, expanded: Boolean, onDismiss: () -> Unit) {
    // liked row is virtual no like/delete just playback
    val isVirtual = row.kind == "liked"
    val isSmart = row.kind == "smart"
    val isPlaylist = row.kind == "playlist"
    val liked = actions.isLiked(row.id)
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text(appString(R.string.text_play_5d12bd)) }, onClick = { onDismiss(); actions.onPlay(row) }, leadingIcon = { Icon(Icons.Filled.PlayArrow, null) })
        DropdownMenuItem(text = { Text(appString(R.string.text_shuffle_5b772b)) }, onClick = { onDismiss(); actions.onShuffle(row) }, leadingIcon = { Icon(Icons.Filled.Shuffle, null) })
        DropdownMenuItem(text = { Text(appString(R.string.text_add_to_queue_69b498)) }, onClick = { onDismiss(); actions.onQueue(row) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) })
        if (isPlaylist || isSmart || isVirtual) {
            DropdownMenuItem(
                text = { Text(appString(R.string.text_export_as_m3u_5189ed)) },
                onClick = { onDismiss(); actions.onExport(row) },
                leadingIcon = { Icon(Icons.Filled.IosShare, null) },
            )
        }
        if (isSmart) {
            DropdownMenuItem(
                text = { Text(appString(R.string.text_edit_rules_83bf03)) },
                onClick = { onDismiss(); actions.onEditSmart(row) },
                leadingIcon = { Icon(Icons.Filled.Edit, null) },
            )
            DropdownMenuItem(
                text = { Text(appString(R.string.text_delete_f6fdbe)) },
                onClick = { onDismiss(); actions.onDeleteSmart(row) },
                leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
            )
        }
        if (!isVirtual && !isSmart) {
            DropdownMenuItem(
                text = { Text(if (liked) appString(R.string.text_unlike_e4fc40) else appString(R.string.text_like_c7e02c)) },
                onClick = { onDismiss(); actions.onToggleLike(row) },
                leadingIcon = { Icon(if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, null) },
            )
            if (isPlaylist) {
                DropdownMenuItem(
                    text = { Text(appString(R.string.text_delete_playlist_b55b18)) },
                    onClick = { onDismiss(); actions.onDelete(row) },
                    leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                )
            }
        }
    }
}
