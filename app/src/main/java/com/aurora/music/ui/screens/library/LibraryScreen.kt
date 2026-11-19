package com.aurora.music.ui.screens.library

import androidx.compose.foundation.combinedClickable
import com.aurora.music.ui.theme.paperClip
import com.aurora.music.ui.theme.paperFrame
import com.aurora.music.localization.localizedMediaType

import com.aurora.music.localization.appPlural

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
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
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import com.aurora.music.ui.theme.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.music.model.LibraryFilter
import com.aurora.music.model.LibraryLayout
import com.aurora.music.model.LibrarySort
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.SongRow
import com.aurora.music.util.accentFor
import com.aurora.music.viewmodel.LibraryUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.aurora.music.data.LIKE_BUCKET
import com.aurora.music.data.accent
import com.aurora.music.data.likeWindow
import com.aurora.music.model.accent
import com.aurora.music.model.label
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import com.aurora.music.ui.components.EmptyState
import com.aurora.music.ui.components.SectionHeader
import com.aurora.music.ui.theme.Motion
import com.aurora.music.ui.theme.PressScaleIndication
import com.aurora.music.ui.theme.pressClickable
import com.aurora.music.ui.theme.screenEnter
import com.aurora.music.ui.components.glass.GlassAction
import com.aurora.music.ui.components.glass.GlassIconButton
import com.aurora.music.ui.theme.glassCard
import com.aurora.music.ui.theme.glassChip
import com.aurora.music.ui.theme.isGlassTheme

internal val LocalLibrarySelection = androidx.compose.runtime.compositionLocalOf<String?> { null }

@Composable
private fun selectionTint(key: String): Color =
    if (key.isNotEmpty() && LocalLibrarySelection.current == key) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent

internal data class LibRow(
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
    // drawn instead of artwork, as for a playlist folder
    val icon: ImageVector? = null,
)

internal class LibActions(
    val onOpenAllLiked: (() -> Unit)?,
    val likedPicker: @Composable () -> Unit,
    val isLiked: (String) -> Boolean,
    val onPlay: (LibRow) -> Unit,
    val onShuffle: (LibRow) -> Unit,
    val onQueue: (LibRow) -> Unit,
    val onToggleLike: (LibRow) -> Unit,
    val onDelete: (LibRow) -> Unit,
    val onEditSmart: (LibRow) -> Unit,
    val onDeleteSmart: (LibRow) -> Unit,
    val onExport: (LibRow) -> Unit,
    val prompt: (FolderPrompt) -> Unit = {},
    // the folder a playlist sits in ("" at the top), null where it can't be filed
    val folderOf: (String) -> String? = { null },
)

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun LibraryScreen(
    contentPadding: PaddingValues,
    state: LibraryUiState,
    username: String,
    likedIds: Set<String>,
    currentSongId: String,
    isPlaying: Boolean,
    onFilter: (LibraryFilter) -> Unit,
    onTabs: (com.aurora.music.data.LibraryTabs) -> Unit = {},
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
    onOpenMix: () -> Unit = {},
    onCreatePlaylist: suspend (name: String, description: String, target: String?) -> Boolean,
    playlistTargets: List<com.aurora.music.data.PlaylistTarget> = emptyList(),
    playlistHome: String = "",
    onCreateSmart: () -> Unit,
    onEditSmart: (String) -> Unit,
    onDeleteSmart: (String) -> Unit,
    onImportM3u: () -> Unit,
    onCreateWithAi: (() -> Unit)? = null,
    onAiDj: (() -> Unit)? = null,
    aiDjDetail: String = "",
    onExportPlaylist: (String, String, String) -> Unit,
    onOpenFolders: () -> Unit,
    onOpenRadio: () -> Unit = {},
    onOpenPodcasts: () -> Unit = {},
    onOpenYouTubeDownload: () -> Unit = {},
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
    // the songs near the scroll position, for hearts liked on other accounts
    onVisibleSongs: (List<Song>) -> Unit = {},
    selectedItem: String? = null,
    onRefresh: () -> Unit = {},
    onCycleView: () -> Unit = {},
    folderEdits: FolderEdits? = null,
    onSignIn: () -> Unit = {},
) {
    androidx.compose.runtime.CompositionLocalProvider(LocalLibrarySelection provides selectedItem) {
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var showCreate by remember { mutableStateOf(false) }
    var showTabs by remember { mutableStateOf(false) }
    val tabPrefs = com.aurora.music.ui.theme.LocalUiPrefs.current.libraryTabs
    val visibleTabs = tabPrefs.visible.filter { canDownload || it != LibraryFilter.DOWNLOADED }.ifEmpty { listOf(LibraryFilter.ALL) }
    val pageLayout = com.aurora.music.ui.theme.LocalUiPrefs.current.pageLayout
    val console = pageLayout == com.aurora.music.data.PageLayout.CONSOLE
    // a hidden tab never stays on screen; console's hub is the all tab, so that one always can
    val filter = state.filter.takeIf { it in visibleTabs || console && it == LibraryFilter.ALL } ?: if (console) LibraryFilter.ALL else visibleTabs.first()
    val sort = state.sort
    val layout = state.layout
    val libColumns = com.aurora.music.ui.theme.LocalUiPrefs.current.libraryColumns.coerceIn(2, 4)
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val multiLibrary = state.views.isNotEmpty()
    // holding liked songs picks its sources, or opens every source when only one has likes; a tap stays on the library on screen
    val likeMenu = com.aurora.music.ui.components.LocalLikeMenu.current
    var pickLiked by remember { mutableStateOf<List<com.aurora.music.data.LikedSource>?>(null) }
    val openAllLiked: (() -> Unit)? = if (!multiLibrary) null else ({
        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        val sources = likeMenu?.likedSources?.invoke().orEmpty()
        if (sources.size > 1) pickLiked = sources else onOpenDetail("liked", com.aurora.music.data.ALL_LIKED_ID)
    })
    val likedPicker: @Composable () -> Unit = {
        pickLiked?.let { sources ->
            com.aurora.music.ui.components.LikedSourcesMenu(sources, onDismiss = { pickLiked = null }) { id -> pickLiked = null; onOpenDetail("liked", id) }
        }
    }
    val tree = state.playlistFolders.takeIf { folderEdits != null }
    var openFolder by androidx.compose.runtime.saveable.rememberSaveable(state.view) { mutableStateOf<String?>(null) }
    // a folder deleted or moved away meanwhile drops back to the top
    val folder = openFolder?.let { tree?.find(it) }
    var prompt by remember { mutableStateOf<FolderPrompt?>(null) }
    // registered before the folder handler, so back leaves an open folder first
    androidx.activity.compose.BackHandler(enabled = console && filter != LibraryFilter.ALL && selectedItem == null) { onFilter(LibraryFilter.ALL) }
    androidx.activity.compose.BackHandler(enabled = folder != null && filter == LibraryFilter.PLAYLISTS) {
        openFolder = tree?.parentOf(folder?.id.orEmpty())?.id?.ifEmpty { null }
    }
    val actions = remember(likedIds, onPlayCollection, onShuffleCollection, onQueueCollection, onToggleLikeKind,
        onDeletePlaylist, onEditSmart, onDeleteSmart, onExportPlaylist, multiLibrary, tree) {
        LibActions(
            onOpenAllLiked = openAllLiked,
            likedPicker = likedPicker,
            isLiked = { id -> likedIds.contains(id) },
            onPlay = { r -> onPlayCollection(r.id, r.kind) },
            onShuffle = { r -> onShuffleCollection(r.id, r.kind) },
            onQueue = { r -> onQueueCollection(r.id, r.kind) },
            onToggleLike = { r -> onToggleLikeKind(r.id, r.kind) },
            onDelete = { r -> onDeletePlaylist(r.id) },
            onEditSmart = { r -> onEditSmart(r.id) },
            onDeleteSmart = { r -> onDeleteSmart(r.id) },
            onExport = { r -> onExportPlaylist(r.id, r.kind, r.title) },
            prompt = { prompt = it },
            folderOf = { id -> tree?.folderOf(id)?.id },
        )
    }
    if (folderEdits != null) prompt?.let { FolderPromptDialog(it, tree, folderEdits) { prompt = null } }

    Column(Modifier.fillMaxWidth().padding(top = topInset)) {
        // the stats run under the icons on their own line, so they fit without wrapping mid-phrase
        val stats = buildList {
            state.views.firstOrNull { it.id == state.view }?.let { add(it.label) }
            if (state.playlists.isNotEmpty() || state.smartPlaylists.isNotEmpty()) add(appPlural(R.plurals.playlist_count, (state.playlists.size + state.smartPlaylists.size)))
            if (state.albums.isNotEmpty()) add(appPlural(R.plurals.album_count, (state.albums.size)))
            if (state.artists.isNotEmpty()) add(appPlural(R.plurals.artist_count, (state.artists.size)))
        }.joinToString("  ·  ")
        // each page layout brings its own header and tab control
        when (pageLayout) {
        com.aurora.music.data.PageLayout.SPOTLIGHT -> LibrarySpotlightHeader(state, username, stats, visibleTabs, filter, onOpenDrawer, onOpenSearch,
            onCreate = { showCreate = true }, onCycleView = onCycleView, onFilter = onFilter, onCustomizeTabs = { showTabs = true })
        com.aurora.music.data.PageLayout.CONSOLE -> LibraryConsoleHeader(state, username, playlistHome, filter, onOpenDrawer, onOpenSearch,
            onCreate = { showCreate = true }, onCycleView = onCycleView, onFilter = onFilter, onCustomizeTabs = { showTabs = true })
        com.aurora.music.data.PageLayout.DRIFT -> LibraryDriftHeader(state, username, playlistHome, stats, visibleTabs, filter, onOpenDrawer, onOpenSearch,
            onCreate = { showCreate = true }, onCycleView = onCycleView, onFilter = onFilter, onCustomizeTabs = { showTabs = true })
        else -> {
        // ---- header: identity + stats ----
        val glass = isGlassTheme()
        val headerIcon: @Composable (ImageVector, String, () -> Unit) -> Unit = { icon, label, action ->
            if (glass) GlassIconButton(icon, label, action, Modifier.padding(start = 8.dp))
            else Icon(icon, label, modifier = Modifier.size(40.dp).paperClip(CircleShape).clickable(onClick = action).padding(8.dp))
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = if (glass) 16.dp else 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            LibraryAvatar(username, onOpenDrawer)
            Spacer(Modifier.width(12.dp))
            Text(appString(R.string.text_library_b8100f), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            if (state.views.size > 1) {
                val next = state.views[(state.views.indexOfFirst { it.id == state.view } + 1).mod(state.views.size)]
                headerIcon(com.aurora.music.ui.components.sourceViewIcon(state.view), appString(R.string.library_switch_view, next.label), onCycleView)
            }
            headerIcon(Icons.Filled.Search, appString(R.string.text_search_bce064), onOpenSearch)
            if (state.signedIn) headerIcon(Icons.Filled.Add, appString(R.string.text_create_playlist_62c988)) { showCreate = true }
        }
        if (stats.isNotBlank()) {
            Text(stats, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 66.dp, end = 16.dp))
        }

        Spacer(Modifier.height(10.dp))

        // ---- tab rail: icon + label with accent underline, not pills ----
        TextButton(onClick = onOpenMix, modifier = Modifier.padding(horizontal = 12.dp)) {
            Icon(Icons.Filled.Apps, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(appString(R.string.text_mix_studio_668363))
        }
        LazyRow(contentPadding = PaddingValues(horizontal = if (glass) 16.dp else 12.dp), horizontalArrangement = Arrangement.spacedBy(if (glass) 8.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = if (glass) 8.dp else 0.dp)) {
            items(visibleTabs.size) { i ->
                val f = visibleTabs[i]
                LibTab(label = f.label, icon = tabIcon(f), selected = f == filter, onLongClick = {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    showTabs = true
                }) { onFilter(f) }
            }
            item {
                Icon(Icons.Filled.Tune, appString(R.string.library_tabs_customize), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(36.dp).paperClip(CircleShape).clickable { showTabs = true }.padding(9.dp))
            }
        }
        }
        }
        if (showCreate) {
            CreatePlaylistSheet(
                destination = state.views.firstOrNull { it.id == state.view }?.label ?: playlistHome,
                targets = playlistTargets,
                onCreate = onCreatePlaylist,
                onCreateSmart = { showCreate = false; onCreateSmart() },
                onImportM3u = { showCreate = false; onImportM3u() },
                onDismiss = { showCreate = false },
                onCreateWithAi = onCreateWithAi?.let { open -> { showCreate = false; open() } },
                onNewFolder = if (tree == null) null else ({ showCreate = false; prompt = FolderPrompt.Create(folder?.id.orEmpty()) }),
            )
        }
        if (showTabs) LibraryTabsSheet(tabPrefs, canDownload, onChange = onTabs, onDismiss = { showTabs = false })

        Spacer(Modifier.height(6.dp))

        // ---- contextual tool row ----
        if (filter != LibraryFilter.ALL) {
            var sortMenu by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    Row(
                        (if (isGlassTheme()) Modifier.glassChip().clickable { sortMenu = true }.padding(horizontal = 12.dp, vertical = 8.dp)
                            else Modifier.paperClip(RoundedCornerShape(50)).clickable { sortMenu = true }.padding(6.dp)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.SwapVert, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(sort.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        LibrarySort.entries.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(s.label) },
                                onClick = { onSort(s); sortMenu = false },
                                trailingIcon = { if (s == sort) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary) },
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (filter != LibraryFilter.SONGS) {
                    val toggle = if (layout == LibraryLayout.LIST) Icons.Filled.GridView else Icons.AutoMirrored.Filled.List
                    if (isGlassTheme()) GlassIconButton(toggle, appString(R.string.text_toggle_layout_6169e7), onToggleLayout)
                    else Icon(
                        imageVector = toggle,
                        contentDescription = appString(R.string.text_toggle_layout_6169e7),
                        modifier = Modifier.size(40.dp).paperClip(CircleShape).clickable(onClick = onToggleLayout).padding(8.dp),
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
        }

        val bottom = contentPadding.calculateBottomPadding() + 24.dp

        if (state.loading && state.albums.isEmpty() && state.playlists.isEmpty() && state.songs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                com.aurora.music.ui.components.LottieLoader(modifier = Modifier.size(72.dp))
            }
            return@Column
        }

        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize(),
        ) {
        Column(Modifier.fillMaxSize()) {
        when (filter) {
            LibraryFilter.ALL -> when (pageLayout) {
                com.aurora.music.data.PageLayout.SPOTLIGHT -> LibrarySpotlightAll(
                    state = state, pins = pins, bottom = bottom, columns = libColumns, tree = tree, actions = actions, onOpenDetail = onOpenDetail,
                    tiles = collectionTiles(state, canDownload, onFilter, onOpenFolders, onOpenRadio, onOpenPodcasts, onOpenYouTubeDownload, onAiDj, aiDjDetail),
                    onOpenMix = onOpenMix, onSignIn = onSignIn,
                    // a folder opens where the playlists tab shows it
                    onOpenFolder = if (LibraryFilter.PLAYLISTS in visibleTabs) ({ id -> openFolder = id; onFilter(LibraryFilter.PLAYLISTS) }) else null,
                )
                com.aurora.music.data.PageLayout.CONSOLE -> LibraryConsoleAll(
                    state = state, pins = pins, bottom = bottom, tabs = visibleTabs, actions = actions, onFilter = onFilter, onOpenDetail = onOpenDetail,
                    tiles = collectionTiles(state, canDownload, onFilter, onOpenFolders, onOpenRadio, onOpenPodcasts, onOpenYouTubeDownload, onAiDj, aiDjDetail),
                    onOpenMix = onOpenMix, onCustomizeTabs = { showTabs = true }, onSignIn = onSignIn,
                )
                com.aurora.music.data.PageLayout.DRIFT -> LibraryDriftAll(
                    state = state, pins = pins, bottom = bottom, tabs = visibleTabs, tree = tree, actions = actions, onFilter = onFilter, onOpenDetail = onOpenDetail,
                    tiles = collectionTiles(state, canDownload, onFilter, onOpenFolders, onOpenRadio, onOpenPodcasts, onOpenYouTubeDownload, onAiDj, aiDjDetail),
                    onOpenMix = onOpenMix, onSignIn = onSignIn,
                    onOpenFolder = if (LibraryFilter.PLAYLISTS in visibleTabs) ({ id -> openFolder = id; onFilter(LibraryFilter.PLAYLISTS) }) else null,
                )
                else -> AllOverview(
                state = state, pins = pins, canDownload = canDownload, bottom = bottom,
                onFilter = onFilter, onOpenDetail = onOpenDetail,
                onOpenFolders = onOpenFolders, onOpenRadio = onOpenRadio, onOpenPodcasts = onOpenPodcasts,
                onOpenYouTubeDownload = onOpenYouTubeDownload, onOpenAllLiked = openAllLiked, likedPicker = likedPicker, onAiDj = onAiDj, aiDjDetail = aiDjDetail,
            )
            }
            LibraryFilter.SONGS -> SongsTab(
                state = state, likedIds = likedIds, currentSongId = currentSongId, isPlaying = isPlaying,
                bottom = bottom, canDownload = canDownload, downloadedIds = downloadedIds,
                onAddToQueue = onAddToQueue, onPlayNext = onPlayNext, onToggleLike = onToggleLike,
                onOpenDetail = onOpenDetail, onDownload = onDownload, onRemoveDownload = onRemoveDownload,
                onEditTags = onEditTags, serverTagEditing = serverTagEditing, onLoadMoreSongs = onLoadMoreSongs,
                onPlayAllSongs = onPlayAllSongs, onPlaySong = onPlaySong, onVisibleSongs = onVisibleSongs,
            )
            LibraryFilter.DOWNLOADED -> {
                val dlRows = remember(state.downloadedRows) {
                    state.downloadedRows.map { LibRow(it.title, appString(R.string.downloaded_collection), it.coverUrl, it.accent, it.id, it.kind) }
                        .distinctBy { "${it.kind}:${it.id}" }
                }
                if (dlRows.isEmpty()) {
                    EmptyHint(Icons.Filled.Download, appString(R.string.text_no_downloads_yet_9647c1), appString(R.string.text_albums_and_playlists_you_download_live_here_e4c7ba))
                } else {
                    RowsContent(dlRows, layout, libColumns, sort, bottom, actions) { r -> onOpenDetail(r.kind, r.id) }
                }
            }
            else -> {
                // large merged libraries build rows off the main thread; small ones stay synchronous so a tab tap never flashes empty
                val heavy = when (filter) {
                    LibraryFilter.ALBUMS -> state.albums.size
                    LibraryFilter.ARTISTS -> state.artists.size + state.songs.size
                    else -> state.playlists.size
                } > 400
                // artists aggregate over the whole song list so only rebuild when an input really changed
                val shownFolder = folder.takeIf { filter == LibraryFilter.PLAYLISTS }
                if (shownFolder != null) FolderHeader(shownFolder, actions) { openFolder = tree?.parentOf(shownFolder.id)?.id?.ifEmpty { null } }
                val inputs = arrayOf<Any?>(sort, pins, state.playlists, state.smartPlaylists, state.albums, state.artists,
                    state.likedSongCount, state.likedCover, tree, shownFolder,
                    if (filter == LibraryFilter.ARTISTS) state.songs else null,
                    if (filter == LibraryFilter.ARTISTS) state.localPlayCounts else null)
                // a heavy rebuild keeps the last rows on screen so the list and its scroll survive
                val built = remember(filter) { mutableStateOf<List<LibRow>?>(null) }
                val synced = if (heavy) null else remember(filter, *inputs) { buildRows(state, filter, sort, pins, tree, shownFolder) }
                SideEffect { if (synced != null) built.value = synced }
                if (heavy) LaunchedEffect(filter, *inputs) {
                    built.value = withContext(Dispatchers.Default) { buildRows(state, filter, sort, pins, tree, shownFolder) }
                }
                val rows = synced ?: built.value
                if (rows != null && rows.isEmpty() && shownFolder != null) {
                    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                        EmptyState(Icons.Filled.Folder, appString(R.string.text_nothing_in_this_folder_d2a003))
                    }
                } else if (rows != null && rows.isEmpty()) {
                    EmptyHint(tabIcon(filter), appString(R.string.text_nothing_here_yet_e89225), appString(R.string.text_your_will_show_up_once_the_server_has_some_ced31f, (filter.label.lowercase())))
                } else if (rows != null) {
                    Box(Modifier.fillMaxSize().screenEnter(shownFolder?.id)) { androidx.compose.runtime.key(shownFolder?.id) {
                    RowsContent(rows, layout, libColumns, sort, bottom, actions) { r ->
                        when (r.kind) {
                            "folder" -> openFolder = r.id
                            "folders" -> onOpenFolders()
                            "radio" -> onOpenRadio()
                            "podcasts" -> onOpenPodcasts()
                            else -> onOpenDetail(r.kind, r.id)
                        }
                    }
                    } }
                }
            }
        }
        }
        }
    }
    }
}

internal fun tabIcon(f: LibraryFilter): ImageVector = when (f) {
    LibraryFilter.ALL -> Icons.Filled.Apps
    LibraryFilter.PLAYLISTS -> Icons.AutoMirrored.Filled.QueueMusic
    LibraryFilter.ALBUMS -> Icons.Filled.Album
    LibraryFilter.ARTISTS -> Icons.Filled.Person
    LibraryFilter.SONGS -> Icons.Filled.MusicNote
    LibraryFilter.DOWNLOADED -> Icons.Filled.Download
}

@Composable
private fun LibTab(label: String, icon: ImageVector, selected: Boolean, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    if (isGlassTheme()) {
        val ink = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        Row(Modifier.glassChip(selected).combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = ink, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = ink)
        }
        return
    }
    val on by animateFloatAsState(if (selected) 1f else 0f, Motion.state(), label = "libTab")
    val tint = lerp(MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.primary, on)
    Column(
        Modifier.paperClip(RoundedCornerShape(12.dp)).combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Black else FontWeight.Medium,
                color = lerp(MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.onSurface, on),
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.height(3.dp).width(26.dp).graphicsLayer { alpha = on }.paperClip(RoundedCornerShape(50)).background(
                Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))
            )
        )
    }
}

// ---- ALL tab: sectioned overview with quick tiles + shelves per type ----

@Composable
private fun AllOverview(
    state: LibraryUiState,
    pins: List<com.aurora.music.data.Pin>,
    canDownload: Boolean,
    bottom: androidx.compose.ui.unit.Dp,
    onFilter: (LibraryFilter) -> Unit,
    onOpenDetail: (String, String) -> Unit,
    onOpenFolders: () -> Unit,
    onOpenRadio: () -> Unit,
    onOpenPodcasts: () -> Unit,
    onOpenYouTubeDownload: () -> Unit,
    onOpenAllLiked: (() -> Unit)?,
    likedPicker: @Composable () -> Unit,
    onAiDj: (() -> Unit)?,
    aiDjDetail: String,
) {
    val glass = isGlassTheme()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom)) {
        fun shelf(header: @Composable () -> Unit, row: @Composable () -> Unit) {
            if (glass) item {
                Column(Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp).glassCard(MaterialTheme.shapes.large).padding(bottom = 14.dp)) { header(); row() }
            } else {
                item { header() }
                item { row() }
            }
        }
        // quick access tiles
        item {
            val tiles = buildList {
                // 0 doubles as not counted yet, so no number rather than a wrong 0
                if (state.signedIn) add(QuickTile(appString(R.string.text_liked_songs_58c3a9), if (state.likedSongCount > 0) appPlural(R.plurals.track_count, (state.likedSongCount)) else "", Icons.Filled.Favorite, state.likedCover,
                    onLongClick = onOpenAllLiked, menu = likedPicker) { onOpenDetail("liked", state.likedId) })
                addAll(collectionTiles(state, canDownload, onFilter, onOpenFolders, onOpenRadio, onOpenPodcasts, onOpenYouTubeDownload, onAiDj, aiDjDetail))
            }
            QuickTileGrid(tiles, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }

        if (pins.isNotEmpty()) {
            shelf({ ShelfHeader(appString(R.string.text_pinned_f93121), null) {} }) {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(pins.size) { i ->
                        val p = pins[i]
                        ShelfCard(
                            title = p.title, subtitle = p.kind.replaceFirstChar { it.uppercase() }, art = p.coverUrl,
                            accent = accentFor(p.id), circle = p.kind == "artist", badge = "",
                            width = 112.dp, selectionKey = "${p.kind}:${p.id}",
                        ) { onOpenDetail(p.kind, p.id) }
                    }
                }
            }
        }

        val playlistCount = state.smartPlaylists.size + state.playlists.size
        if (playlistCount > 0) {
            shelf({ ShelfHeader(appString(R.string.text_playlists_77b69f), playlistCount) { onFilter(LibraryFilter.PLAYLISTS) } }) {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.smartPlaylists.size) { i ->
                        val sp = state.smartPlaylists[i]
                        ShelfCard(sp.name ?: appString(R.string.text_smart_playlist_f77ad7), appString(R.string.text_smart_playlist_f77ad7), "", accentFor(sp.id ?: "smart"), badge = appString(R.string.text_auto_50c3f1)) {
                            onOpenDetail("smart", sp.id ?: "")
                        }
                    }
                    items(state.playlists.size) { i ->
                        val p = state.playlists[i]
                        ShelfCard(p.title, if (p.songCount >= 0) appPlural(R.plurals.track_count, (p.songCount)) else appString(R.string.text_playlist_cd95b4), p.coverUrl, p.accent, selectionKey = "playlist:${p.id}") { onOpenDetail("playlist", p.id) }
                    }
                }
            }
        }

        if (state.albums.isNotEmpty()) {
            shelf({ ShelfHeader(appString(R.string.text_albums_4c45e7), state.albums.size) { onFilter(LibraryFilter.ALBUMS) } }) {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.albums.size) { i ->
                        val a = state.albums[i]
                        val label = a.typeLabel.localizedMediaType()
                        ShelfCard(a.title, a.artist, a.artworkUrl, accentFor(a.id), badge = if (label == appString(R.string.text_album_dfb4c9)) "" else label.uppercase(java.util.Locale.getDefault()), selectionKey = "album:${a.id}") {
                            onOpenDetail("album", a.id)
                        }
                    }
                }
            }
        }

        if (state.artists.isNotEmpty()) {
            shelf({ ShelfHeader(appString(R.string.text_artists_1528d8), state.artists.size) { onFilter(LibraryFilter.ARTISTS) } }) {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(state.artists.size) { i ->
                        val ar = state.artists[i]
                        ShelfCard(ar.name, "", ar.imageUrl, accentFor(ar.id), circle = true, width = 96.dp, centered = true, selectionKey = "artist:${ar.id}") {
                            onOpenDetail("artist", ar.id)
                        }
                    }
                }
            }
        }
    }
}

// the quick tiles besides liked songs, which each page layout places itself
internal fun collectionTiles(state: LibraryUiState, canDownload: Boolean, onFilter: (LibraryFilter) -> Unit, onOpenFolders: () -> Unit, onOpenRadio: () -> Unit,
    onOpenPodcasts: () -> Unit, onOpenYouTubeDownload: () -> Unit, onAiDj: (() -> Unit)?, aiDjDetail: String): List<QuickTile> = buildList {
    if (onAiDj != null) add(QuickTile(appString(R.string.ai_dj_title), aiDjDetail, Icons.Filled.AutoAwesome, "") { onAiDj() })
    if (canDownload) add(QuickTile(appString(R.string.text_downloads_a862c2), appPlural(R.plurals.item_count, (state.downloadedRows.size)), Icons.Filled.Download, "") { onFilter(LibraryFilter.DOWNLOADED) })
    if (state.supportsFolders) add(QuickTile(appString(R.string.text_folders_19adc4), appString(R.string.text_browse_files_524932), Icons.Filled.Folder, "") { onOpenFolders() })
    add(QuickTile(appString(R.string.text_radio_b11bf1), appString(R.string.text_live_stations_f40694), Icons.Filled.Radio, "") { onOpenRadio() })
    add(QuickTile(appString(R.string.text_podcasts_fd52b4), appString(R.string.text_shows_episodes_526d46), Icons.Filled.Podcasts, "") { onOpenPodcasts() })
    add(QuickTile(appString(R.string.youtube_download_title), appString(R.string.youtube_download_tile_detail), Icons.Filled.CloudDownload, "") { onOpenYouTubeDownload() })
}

@Composable
internal fun LibraryAvatar(username: String, onOpenDrawer: () -> Unit) {
    Box(
        Modifier.size(38.dp).paperClip(CircleShape)
            .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)))
            .clickable(onClick = onOpenDrawer),
        contentAlignment = Alignment.Center,
    ) { Text(username.take(2).uppercase().ifBlank { appString(R.string.text_me_b4d362) }, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onPrimary) }
}

internal data class QuickTile(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val art: String,
    val onLongClick: (() -> Unit)? = null,
    val menu: (@Composable () -> Unit)? = null,
    val onClick: () -> Unit,
)

// two columns, every tile as tall as the tallest; an odd last tile takes the whole row so its text fits
@Composable
private fun QuickTileGrid(tiles: List<QuickTile>, modifier: Modifier = Modifier) {
    Layout(content = { tiles.forEach { QuickTileCard(it) } }, modifier = modifier) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        val half = ((constraints.maxWidth - gap) / 2).coerceAtLeast(0)
        val lone = if (measurables.size % 2 == 1) measurables.lastIndex else -1
        val widths = List(measurables.size) { if (it == lone) constraints.maxWidth else half }
        val height = measurables.indices.maxOfOrNull { measurables[it].maxIntrinsicHeight(widths[it]) } ?: 0
        val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixed(widths[i], height)) }
        val width = half
        val rows = (placeables.size + 1) / 2
        layout(constraints.maxWidth, (rows * (height + gap) - gap).coerceAtLeast(0)) {
            placeables.forEachIndexed { i, p -> p.placeRelative(i % 2 * (width + gap), i / 2 * (height + gap)) }
        }
    }
}

@Composable
private fun QuickTileCard(tile: QuickTile, modifier: Modifier = Modifier) {
    Row(
        modifier
            .then(if (isGlassTheme()) Modifier.glassCard(MaterialTheme.shapes.small)
                else Modifier.paperClip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f)))
            .combinedClickable(interactionSource = null, indication = PressScaleIndication, onClick = tile.onClick, onLongClick = tile.onLongClick,
                onLongClickLabel = if (tile.onLongClick != null) appString(R.string.liked_sources_choose) else null)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tile.menu?.invoke()
        if (tile.art.isNotBlank()) {
            Artwork(tile.art, MaterialTheme.colorScheme.primary, Modifier.size(38.dp), corner = 10.dp)
        } else {
            Box(
                Modifier.size(38.dp).paperClip(RoundedCornerShape(10.dp))
                    .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f), MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f)))),
                contentAlignment = Alignment.Center,
            ) { Icon(tile.icon, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(19.dp)) }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(tile.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(tile.subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun ShelfHeader(title: String, count: Int?, onSeeAll: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 18.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        SectionHeader(title, Modifier.weight(1f, fill = false).width(IntrinsicSize.Max))
        if (count != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                "$count",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.paperClip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        }
        if (count != null) {
            Text(
                appString(R.string.text_see_all_2941c5),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.paperClip(RoundedCornerShape(50)).clickable(onClick = onSeeAll).padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
internal fun ShelfCard(
    title: String,
    subtitle: String,
    art: String,
    accent: Color,
    circle: Boolean = false,
    badge: String = "",
    width: androidx.compose.ui.unit.Dp = 132.dp,
    centered: Boolean = false,
    selectionKey: String = "",
    titleLines: Int = 1,
    onClick: () -> Unit,
) {
    Column(
        Modifier.width(width).paperClip(RoundedCornerShape(14.dp)).background(selectionTint(selectionKey)).pressClickable(onClick = onClick).padding(4.dp),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Box {
            Artwork(art, accent, Modifier.fillMaxWidth().aspectRatio(1f), corner = if (circle) 200.dp else 14.dp)
            if (badge.isNotBlank()) {
                Text(
                    badge,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                        .paperClip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
            maxLines = titleLines, overflow = TextOverflow.Ellipsis,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
        if (subtitle.isNotBlank()) {
            Text(
                subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = if (centered) TextAlign.Center else TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ---- SONGS tab: infinite paging + A-Z rail ----

@Composable
private fun SongsTab(
    state: LibraryUiState,
    likedIds: Set<String>,
    currentSongId: String,
    isPlaying: Boolean,
    bottom: androidx.compose.ui.unit.Dp,
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
    onVisibleSongs: (List<Song>) -> Unit,
) {
    val songs = state.sortedSongs
    // a sort still running must not flash the empty hint
    if (state.songs.isEmpty() && !state.loading) {
        EmptyHint(Icons.Filled.MusicNote, appString(R.string.text_no_songs_e6bbe2), appString(R.string.text_songs_from_your_server_appear_here_e4db53))
        return
    }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (state.canLoadMoreSongs) appString(R.string.track_count_more, (songs.size)) else appPlural(R.plurals.track_count, (songs.size)),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        val accent = MaterialTheme.colorScheme.primary
        val onAccent = if (com.aurora.music.ui.theme.isPaperTheme()) com.aurora.music.ui.theme.PaperTokens.ink else if (accent.luminance() > 0.6f) Color.Black else Color.White
        if (isGlassTheme()) {
            GlassAction(Icons.Filled.PlayArrow, appString(R.string.text_play_5d12bd), onClick = { onPlayAllSongs(false) }, enabled = songs.isNotEmpty())
            Spacer(Modifier.width(8.dp))
            GlassIconButton(Icons.Filled.Shuffle, appString(R.string.text_shuffle_all_7e388b), onClick = { onPlayAllSongs(true) },
                modifier = Modifier.size(44.dp), enabled = songs.isNotEmpty())
        } else {
        Row(
            Modifier.paperFrame(RoundedCornerShape(50), { background(Brush.horizontalGradient(listOf(accent, MaterialTheme.colorScheme.tertiary))) }, fill = accent)
                .clickable(enabled = songs.isNotEmpty()) { onPlayAllSongs(false) }
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
            modifier = Modifier.size(40.dp).paperFrame(CircleShape, { background(MaterialTheme.colorScheme.surfaceContainerHigh) })
                .clickable(enabled = songs.isNotEmpty()) { onPlayAllSongs(true) }
                .padding(10.dp),
        )
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(listState, state.canLoadMoreSongs) {
        snapshotFlow {
            val li = listState.layoutInfo
            (li.visibleItemsInfo.lastOrNull()?.index ?: 0) to li.totalItemsCount
        }.collect { (last, count) ->
            if (state.canLoadMoreSongs && count > 0 && last >= count - 14) onLoadMoreSongs()
        }
    }
    LaunchedEffect(listState, songs) {
        snapshotFlow { listState.firstVisibleItemIndex / LIKE_BUCKET }.collect { onVisibleSongs(likeWindow(songs, it)) }
    }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp), state = listState, contentPadding = PaddingValues(bottom = bottom)) {
            items(songs.size, contentType = { "song" }) { i ->
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
        // a pending sort leaves the old order under the new label, so the rail follows the order on screen
        if (state.sortedFor == LibrarySort.ALPHABETICAL && songs.size > 30) {
            AlphabetRail(
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(bottom = bottom),
                onJump = { c ->
                    jumpIndex(songs.map { it.title }, c)?.let { idx -> scope.launch { listState.scrollToItem(idx) } }
                },
            )
        }
    }
}

// ---- shared rows content for playlist/album/artist/download tabs ----

@Composable
private fun RowsContent(
    rows: List<LibRow>,
    layout: LibraryLayout,
    libColumns: Int,
    sort: LibrarySort,
    bottom: androidx.compose.ui.unit.Dp,
    actions: LibActions,
    onOpen: (LibRow) -> Unit,
) {
    if (layout == LibraryLayout.LIST) {
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        Box(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp), state = listState, contentPadding = PaddingValues(bottom = bottom)) {
                items(rows, key = { "${it.kind}:${it.id}" }, contentType = { "row" }) { r -> LibListItem(r, actions) { onOpen(r) } }
            }
            if (sort == LibrarySort.ALPHABETICAL && rows.size > 30) {
                AlphabetRail(
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(bottom = bottom),
                    onJump = { c ->
                        jumpIndex(rows.map { it.title }, c)?.let { idx -> scope.launch { listState.scrollToItem(idx) } }
                    },
                )
            }
        }
    } else {
        LazyVerticalGrid(
            columns = if (com.aurora.music.ui.layout.LocalWindowLayout.current.useNavigationRail)
                GridCells.Adaptive((360f / libColumns).coerceAtLeast(120f).dp) else GridCells.Fixed(libColumns),
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            contentPadding = PaddingValues(bottom = bottom),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(rows, key = { "${it.kind}:${it.id}" }, contentType = { "row" }) { r -> LibGridItem(r, actions) { onOpen(r) } }
        }
    }
}

// ---- A-Z fast scroller ----

@Composable
private fun AlphabetRail(onJump: (Char) -> Unit, modifier: Modifier = Modifier) {
    val letters = remember { ('A'..'Z').toList() + '#' }
    var railHeight by remember { mutableStateOf(0) }
    var active by remember { mutableStateOf<Char?>(null) }
    Column(
        modifier
            .width(24.dp)
            .onSizeChanged { railHeight = it.height }
            .pointerInput(Unit) {
                awaitEachGesture {
                    fun pick(y: Float): Char? {
                        if (railHeight <= 0) return null
                        val idx = ((y / railHeight) * letters.size).toInt().coerceIn(0, letters.size - 1)
                        return letters[idx]
                    }
                    val down = awaitFirstDown()
                    pick(down.position.y)?.let { c -> if (c != active) { active = c; onJump(c) } }
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        change.consume()
                        pick(change.position.y)?.let { c -> if (c != active) { active = c; onJump(c) } }
                    }
                    active = null
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        letters.forEach { c ->
            val isActive = active == c
            Text(
                "$c",
                fontSize = if (isActive) 13.sp else 9.sp,
                fontWeight = if (isActive) FontWeight.Black else FontWeight.SemiBold,
                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
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

@Composable
private fun EmptyHint(icon: ImageVector, title: String, message: String) {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
        EmptyState(icon, title, subtitle = message)
    }
}

// tree is the view's playlist folders; an open folder lists only what is filed in it, the top level leaves those out
internal fun buildRows(state: LibraryUiState, filter: LibraryFilter, sort: LibrarySort, pins: List<com.aurora.music.data.Pin>,
    tree: com.aurora.music.data.PlaylistFolder? = null, folder: com.aurora.music.data.PlaylistFolder? = null): List<LibRow> {
    val smart = if (filter != LibraryFilter.PLAYLISTS || folder != null) emptyList() else state.smartPlaylists.map {
        val n = it.rules.orEmpty().size
        LibRow(it.name ?: appString(R.string.text_smart_playlist_f77ad7), appString(R.string.smart_rule_count, appPlural(R.plurals.rule_count, (n))), "", accentFor(it.id ?: "smart"), it.id ?: "", "smart", badge = appString(R.string.text_auto_50c3f1))
    }
    fun playlistRow(it: Playlist) = LibRow(it.title, if (it.songCount < 0) appString(R.string.text_playlist_cd95b4) else appString(R.string.playlist_track_count, appPlural(R.plurals.track_count, (it.songCount))), it.coverUrl, it.accent, it.id, "playlist")
    fun folderRow(f: com.aurora.music.data.PlaylistFolder) = LibRow(f.name.ifBlank { appString(R.string.playlist_folder_untitled) },
        appPlural(R.plurals.playlist_count, f.playlistIds.size + f.nestedPlaylistIds().size), "", accentFor(f.id), f.id, "folder", icon = Icons.Filled.Folder)
    val byId = if (folder != null) state.playlists.associateBy { it.id } else emptyMap()
    val playlists = when {
        filter != LibraryFilter.PLAYLISTS -> emptyList()
        folder != null -> folder.entries.mapNotNull { entry ->
            when (entry) {
                is com.aurora.music.data.PlaylistFolderEntry.Folder -> folderRow(entry.folder)
                is com.aurora.music.data.PlaylistFolderEntry.Item -> byId[entry.playlistId]?.let(::playlistRow)
            }
        }
        else -> {
            val filed = tree?.nestedPlaylistIds().orEmpty()
            smart + tree?.folders.orEmpty().map(::folderRow) + state.playlists.filter { it.id !in filed }.map(::playlistRow)
        }
    }
    val albumLabel = appString(R.string.text_album_dfb4c9)
    val albums = if (filter != LibraryFilter.ALBUMS) emptyList() else state.albums.map {
        val label = it.typeLabel.localizedMediaType()
        LibRow(
            it.title, "$label • ${it.artist}", it.artworkUrl, accentFor(it.id), it.id, "album",
            badge = if (label == albumLabel) "" else label.uppercase(java.util.Locale.getDefault()),
            sortPlayCount = it.playCount, sortRecencySec = it.year.toLong(),
        )
    }
    // no per-artist play/added data from the server aggregate over whatever song pages are currently loaded
    val artists = if (filter != LibraryFilter.ARTISTS) emptyList() else {
        val plays = HashMap<String, Int>()
        val recency = HashMap<String, Long>()
        for (song in state.songs) {
            plays[song.artistId] = (plays[song.artistId] ?: 0) + maxOf(song.playCount, state.localPlayCounts[song.id] ?: 0)
            if (song.dateAddedSec > (recency[song.artistId] ?: 0L)) recency[song.artistId] = song.dateAddedSec
        }
        val artistLabel = appString(R.string.text_artist_6c3f3d)
        state.artists.map { ar ->
            LibRow(ar.name, artistLabel, ar.imageUrl, accentFor(ar.id), ar.id, "artist", circle = true,
                sortPlayCount = plays[ar.id] ?: 0, sortRecencySec = recency[ar.id] ?: 0L)
        }
    }
    val base = when (filter) {
        LibraryFilter.PLAYLISTS -> playlists
        LibraryFilter.ALBUMS -> albums
        LibraryFilter.ARTISTS -> artists
        else -> emptyList()
    }
    val sorted = when (sort) {
        LibrarySort.ALPHABETICAL -> base.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        LibrarySort.CREATOR -> base.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.subtitle })
        // playlists carry neither signal so they keep their loaded (roughly alphabetical) order
        LibrarySort.MOST_PLAYED -> base.sortedByDescending { it.sortPlayCount }
        LibrarySort.RECENT -> base.sortedByDescending { it.sortRecencySec }
    }
    if (filter != LibraryFilter.PLAYLISTS || folder != null) return sorted.distinctBy { "${it.kind}:${it.id}" }

    // playlists tab keeps liked songs on top pinned entries stay deduped
    val pinned = pins.map { it.kind to it.id }.toSet()
    val deduped = sorted.filterNot { (it.kind to it.id) in pinned }
    val liked = LibRow(appString(R.string.text_liked_songs_58c3a9), if (state.likedSongCount > 0) appString(R.string.playlist_track_count, appPlural(R.plurals.track_count, (state.likedSongCount))) else appString(R.string.text_playlist_cd95b4), state.likedCover, accentFor("liked"), state.likedId, "liked")
    return (listOf(liked) + deduped).distinctBy { "${it.kind}:${it.id}" }
}

@Composable
private fun LibListItem(row: LibRow, actions: LibActions, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .then(if (isGlassTheme()) Modifier.glassCard(MaterialTheme.shapes.small)
                else Modifier.paperClip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.45f)))
            .background(selectionTint("${row.kind}:${row.id}"))
            .likedLongPress(row, actions, onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.kind == "liked") actions.likedPicker()
        Box {
            if (row.icon != null) IconTile(row.icon, Modifier.size(56.dp))
            else Artwork(row.art, row.accent, Modifier.size(56.dp), corner = if (row.circle) 56.dp else 14.dp)
            if (row.badge.isNotBlank()) {
                Text(
                    row.badge,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomStart).padding(3.dp)
                        .paperClip(RoundedCornerShape(5.dp)).background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(row.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(row.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (row.menu) {
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                Icon(
                    Icons.Filled.MoreVert, appString(R.string.text_more_4bab2d),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp).paperClip(CircleShape).clickable { menuOpen = true }.padding(9.dp),
                )
                CollectionMenu(row, actions, expanded = menuOpen, onDismiss = { menuOpen = false })
            }
        }
    }
}

@Composable
internal fun LibGridItem(row: LibRow, actions: LibActions, onClick: () -> Unit) {
    Column(Modifier.then(if (isGlassTheme()) Modifier.glassCard(MaterialTheme.shapes.small) else Modifier.paperClip(RoundedCornerShape(14.dp)))
        .background(selectionTint("${row.kind}:${row.id}")).likedLongPress(row, actions, onClick, PressScaleIndication).padding(6.dp)) {
        if (row.kind == "liked") actions.likedPicker()
        Box {
            if (row.icon != null) IconTile(row.icon, Modifier.fillMaxWidth().aspectRatio(1f), big = true)
            else Artwork(row.art, row.accent, Modifier.fillMaxWidth().aspectRatio(1f), corner = if (row.circle) 200.dp else 12.dp)
            if (row.badge.isNotBlank()) {
                Text(
                    row.badge,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                        .paperClip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(row.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(row.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (row.menu) {
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    Icon(
                        Icons.Filled.MoreVert, appString(R.string.text_more_4bab2d),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp).paperClip(CircleShape).clickable { menuOpen = true }.padding(4.dp),
                    )
                    CollectionMenu(row, actions, expanded = menuOpen, onDismiss = { menuOpen = false })
                }
            }
        }
    }
}

@Composable
private fun Modifier.likedLongPress(row: LibRow, actions: LibActions, onClick: () -> Unit,
    indication: androidx.compose.foundation.Indication = androidx.compose.foundation.LocalIndication.current): Modifier {
    val longPress = actions.onOpenAllLiked?.takeIf { row.kind == "liked" }
    return combinedClickable(interactionSource = null, indication = indication, onClick = onClick, onLongClick = longPress,
        onLongClickLabel = if (longPress != null) appString(R.string.liked_sources_choose) else null)
}

@Composable
internal fun CollectionMenu(row: LibRow, actions: LibActions, expanded: Boolean, onDismiss: () -> Unit) {
    if (row.kind == "folder") return FolderMenu(row.id, row.title, actions, expanded, onDismiss)
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
            if (com.aurora.music.ui.components.LocalCanLike.current(row.id)) DropdownMenuItem(
                text = { Text(if (liked) appString(R.string.text_unlike_e4fc40) else appString(R.string.text_like_c7e02c)) },
                onClick = { onDismiss(); actions.onToggleLike(row) },
                leadingIcon = { Icon(if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, null) },
            )
            if (isPlaylist) actions.folderOf(row.id)?.let { from ->
                DropdownMenuItem(
                    text = { Text(appString(R.string.playlist_folder_move)) },
                    onClick = { onDismiss(); actions.prompt(FolderPrompt.Move(row.id, from)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, null) },
                )
            }
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

@Composable
private fun IconTile(icon: ImageVector, modifier: Modifier, big: Boolean = false) {
    Box(modifier.paperClip(RoundedCornerShape(if (big) 12.dp else 14.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxSize(0.42f))
    }
}

@Composable
private fun FolderMenu(id: String, name: String, actions: LibActions, expanded: Boolean, onDismiss: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text(appString(R.string.playlist_folder_new)) }, onClick = { onDismiss(); actions.prompt(FolderPrompt.Create(id)) },
            leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null) })
        DropdownMenuItem(text = { Text(appString(R.string.playlist_folder_rename)) }, onClick = { onDismiss(); actions.prompt(FolderPrompt.Rename(id, name)) },
            leadingIcon = { Icon(Icons.Filled.Edit, null) })
        DropdownMenuItem(text = { Text(appString(R.string.playlist_folder_delete)) }, onClick = { onDismiss(); actions.prompt(FolderPrompt.Delete(id, name)) },
            leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) })
    }
}

// the open folder's title bar inside the playlists tab; back goes up one level
@Composable
private fun FolderHeader(folder: com.aurora.music.data.PlaylistFolder, actions: LibActions, onBack: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, appString(R.string.text_back_b52b36),
            modifier = Modifier.size(40.dp).paperClip(CircleShape).clickable(onClick = onBack).padding(8.dp))
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(folderName(folder.name), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Box {
            Icon(Icons.Filled.MoreVert, appString(R.string.text_more_4bab2d), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp).paperClip(CircleShape).clickable { menuOpen = true }.padding(9.dp))
            FolderMenu(folder.id, folder.name, actions, menuOpen) { menuOpen = false }
        }
    }
}
