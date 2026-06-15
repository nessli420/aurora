package com.aurora.music.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aurora.music.model.LibraryFilter
import com.aurora.music.model.LibraryLayout
import com.aurora.music.model.LibrarySort
import com.aurora.music.model.Song
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.SongRow
import com.aurora.music.util.accentFor
import com.aurora.music.viewmodel.LibraryUiState

private data class LibRow(val title: String, val subtitle: String, val art: String, val accent: androidx.compose.ui.graphics.Color, val id: String, val kind: String, val circle: Boolean = false, val menu: Boolean = true)

/** Context-menu actions for a library collection row (album / playlist / artist / smart). */
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
    onCreatePlaylist: (String) -> Unit,
    onCreateSmart: () -> Unit,
    onEditSmart: (String) -> Unit,
    onDeleteSmart: (String) -> Unit,
    onImportM3u: () -> Unit,
    onExportPlaylist: (String, String, String) -> Unit,
    onOpenFolders: () -> Unit,
    onOpenRadio: () -> Unit = {},
    onOpenPodcasts: () -> Unit = {},
    onPlayCollection: (String, String) -> Unit,
    onShuffleCollection: (String, String) -> Unit,
    onQueueCollection: (String, String) -> Unit,
    onToggleLikeKind: (String, String) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    canDownload: Boolean = true,
    pins: List<com.aurora.music.data.Pin> = emptyList(),
    onEditTags: ((Song) -> Unit)? = null,
    serverTagEditing: Boolean = false,
) {
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var showCreate by remember { mutableStateOf(false) }
    val filter = state.filter
    val sort = state.sort
    val layout = state.layout
    val libColumns = com.aurora.music.ui.theme.LocalUiPrefs.current.libraryColumns.coerceIn(2, 4)
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

    Column(Modifier.fillMaxWidth().padding(top = topInset)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)))
                    .clickable(onClick = onOpenDrawer),
                contentAlignment = Alignment.Center,
            ) { Text(username.take(2).uppercase().ifBlank { "ME" }, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onPrimary) }
            Spacer(Modifier.width(12.dp))
            Text("Your Library", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.Search, "Search", modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onOpenSearch).padding(8.dp))
            Icon(Icons.Filled.Add, "Create playlist", modifier = Modifier.size(40.dp).clip(CircleShape).clickable { showCreate = true }.padding(8.dp))
        }

        if (showCreate) {
            CreatePlaylistDialog(
                onCreate = { name -> onCreatePlaylist(name); showCreate = false },
                onCreateSmart = { showCreate = false; onCreateSmart() },
                onImportM3u = { showCreate = false; onImportM3u() },
                onDismiss = { showCreate = false },
            )
        }

        Spacer(Modifier.height(12.dp))

        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (filter != LibraryFilter.ALL) {
                item {
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable { onFilter(LibraryFilter.ALL) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.Close, "Clear", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) }
                }
            }
            // In local mode there are no downloads — drop that filter.
            val visible = LibraryFilter.entries.filter { canDownload || it != LibraryFilter.DOWNLOADED }
            items(visible.size) { i ->
                val f = visible[i]
                if (f == LibraryFilter.ALL && filter != LibraryFilter.ALL) return@items
                FilterChip(f.label, selected = f == filter) { onFilter(f) }
            }
        }

        Spacer(Modifier.height(12.dp))

        var sortMenu by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                Row(
                    Modifier.clip(RoundedCornerShape(50)).clickable { sortMenu = true }.padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.SwapVert, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(sort.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
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
            Icon(
                imageVector = if (layout == LibraryLayout.LIST) Icons.Filled.GridView else Icons.AutoMirrored.Filled.List,
                contentDescription = "Toggle layout",
                modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onToggleLayout).padding(8.dp),
            )
        }

        Spacer(Modifier.height(4.dp))

        val bottom = contentPadding.calculateBottomPadding() + 24.dp

        if (state.loading && state.albums.isEmpty() && state.playlists.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                com.aurora.music.ui.components.LottieLoader(modifier = Modifier.size(72.dp))
            }
            return@Column
        }

        if (filter == LibraryFilter.SONGS) {
            val songs = sortedSongs(state.songs, sort)
            LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 8.dp), contentPadding = PaddingValues(bottom = bottom)) {
                items(songs.size) { i ->
                    val s = songs[i]
                    SongRow(
                        s, isPlaying = s.id == currentSongId && isPlaying, isLiked = likedIds.contains(s.id),
                        onClick = { onPlayAll(songs, i) }, onToggleLike = { onToggleLike(s.id) },
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
            }
            return@Column
        }

        if (filter == LibraryFilter.DOWNLOADED) {
            val dlRows = state.downloadedRows.map { LibRow(it.title, "${it.kind.replaceFirstChar { c -> c.uppercase() }} • Downloaded", it.coverUrl, it.accent, it.id, it.kind) }
            if (dlRows.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No downloads yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                return@Column
            }
            if (layout == LibraryLayout.LIST) {
                LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 8.dp), contentPadding = PaddingValues(bottom = bottom)) {
                    items(dlRows.size) { i -> LibListItem(dlRows[i], actions) { onOpenDetail(dlRows[i].kind, dlRows[i].id) } }
                }
            } else {
                LazyVerticalGrid(columns = GridCells.Fixed(libColumns), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), contentPadding = PaddingValues(bottom = bottom), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(dlRows.size) { i -> LibGridItem(dlRows[i], actions) { onOpenDetail(dlRows[i].kind, dlRows[i].id) } }
                }
            }
            return@Column
        }

        val rows = buildRows(state, filter, sort, pins)
        val openRow: (LibRow) -> Unit = { r ->
            when (r.kind) {
                "folders" -> onOpenFolders()
                "radio" -> onOpenRadio()
                "podcasts" -> onOpenPodcasts()
                else -> onOpenDetail(r.kind, r.id)
            }
        }
        if (layout == LibraryLayout.LIST) {
            LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 8.dp), contentPadding = PaddingValues(bottom = bottom)) {
                items(rows.size) { i -> LibListItem(rows[i], actions) { openRow(rows[i]) } }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(libColumns),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                contentPadding = PaddingValues(bottom = bottom),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(rows.size) { i -> LibGridItem(rows[i], actions) { openRow(rows[i]) } }
            }
        }
    }
}

private fun sortedSongs(songs: List<Song>, sort: LibrarySort): List<Song> = when (sort) {
    LibrarySort.ALPHABETICAL -> songs.sortedBy { it.title }
    LibrarySort.CREATOR -> songs.sortedBy { it.artist }
    else -> songs
}

private fun buildRows(state: LibraryUiState, filter: LibraryFilter, sort: LibrarySort, pins: List<com.aurora.music.data.Pin>): List<LibRow> {
    val smart = state.smartPlaylists.map {
        val n = it.rules.orEmpty().size
        LibRow(it.name ?: "Smart playlist", "Smart playlist • $n rule${if (n == 1) "" else "s"}", "", accentFor(it.id ?: "smart"), it.id ?: "", "smart")
    }
    val playlists = smart + state.playlists.map { LibRow(it.title, "Playlist • ${it.songCount} songs", it.coverUrl, it.accent, it.id, "playlist") }
    val albums = state.albums.map { LibRow(it.title, "Album • ${it.artist}", it.artworkUrl, accentFor(it.id), it.id, "album") }
    val artists = state.artists.map { LibRow(it.name, "Artist", it.imageUrl, accentFor(it.id), it.id, "artist", circle = true) }
    val base = when (filter) {
        LibraryFilter.ALL -> playlists + albums + artists
        LibraryFilter.PLAYLISTS -> playlists
        LibraryFilter.ALBUMS -> albums
        LibraryFilter.ARTISTS -> artists
        else -> emptyList()
    }
    val sorted = when (sort) {
        LibrarySort.ALPHABETICAL -> base.sortedBy { it.title }
        LibrarySort.CREATOR -> base.sortedBy { it.subtitle }
        else -> base
    }
    if (filter != LibraryFilter.ALL && filter != LibraryFilter.PLAYLISTS) return sorted

    // Pinned items sit above everything in All; de-dupe them out of the normal rows below.
    val pinRows = if (filter == LibraryFilter.ALL) pins.map {
        LibRow(it.title, "Pinned • ${it.kind.replaceFirstChar { c -> c.uppercase() }}", it.coverUrl, accentFor(it.id), it.id, it.kind, circle = it.kind == "artist")
    } else emptyList()
    val pinned = pins.map { it.kind to it.id }.toSet()
    val deduped = sorted.filterNot { (it.kind to it.id) in pinned }
    val liked = LibRow("Liked Songs", "Playlist • ${state.likedSongCount} songs", state.likedCover, accentFor("liked"), "liked", "liked")
    // Folder/file-tree entry: virtual row, only on backends that expose a tree.
    val folders = if (filter == LibraryFilter.ALL && state.supportsFolders)
        listOf(LibRow("Folders", "Browse by folder", "", accentFor("folders"), "", "folders", menu = false))
    else emptyList()
    // Internet radio + podcasts: app-level browse entries, always available in All.
    val streaming = if (filter == LibraryFilter.ALL) listOf(
        LibRow("Radio", "Live internet stations", "", accentFor("radio"), "", "radio", menu = false),
        LibRow("Podcasts", "Shows & episodes", "", accentFor("podcasts"), "", "podcasts", menu = false),
    ) else emptyList()
    return pinRows + listOf(liked) + folders + streaming + deduped
}

@Composable
private fun CreatePlaylistDialog(onCreate: (String) -> Unit, onCreateSmart: () -> Unit, onImportM3u: () -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New playlist", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Playlist name") },
                    singleLine = true,
                )
                TextButton(onClick = onCreateSmart, modifier = Modifier.padding(top = 6.dp)) {
                    Text("Create a smart playlist instead")
                }
                TextButton(onClick = onImportM3u) {
                    Text("Import an M3U file")
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onCreate(name.trim()) }, enabled = name.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected)
        Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))
    else
        Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.surfaceContainerHigh))
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .then(if (selected) Modifier else Modifier.border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(50)))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun LibListItem(row: LibRow, actions: LibActions, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.45f))
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(row.art, row.accent, Modifier.size(56.dp), corner = if (row.circle) 56.dp else 14.dp)
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
                    Icons.Filled.MoreVert, "More",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(34.dp).clip(CircleShape).clickable { menuOpen = true }.padding(6.dp),
                )
                CollectionMenu(row, actions, expanded = menuOpen, onDismiss = { menuOpen = false })
            }
        }
    }
}

@Composable
private fun LibGridItem(row: LibRow, actions: LibActions, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(6.dp)) {
        Artwork(row.art, row.accent, Modifier.fillMaxWidth().aspectRatio(1f), corner = if (row.circle) 200.dp else 12.dp)
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
                        Icons.Filled.MoreVert, "More",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp).clip(CircleShape).clickable { menuOpen = true }.padding(4.dp),
                    )
                    CollectionMenu(row, actions, expanded = menuOpen, onDismiss = { menuOpen = false })
                }
            }
        }
    }
}

@Composable
private fun CollectionMenu(row: LibRow, actions: LibActions, expanded: Boolean, onDismiss: () -> Unit) {
    // The auto "Liked Songs" row is virtual — no like/delete, just playback actions.
    val isVirtual = row.kind == "liked"
    val isSmart = row.kind == "smart"
    val isPlaylist = row.kind == "playlist"
    val liked = actions.isLiked(row.id)
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("Play") }, onClick = { onDismiss(); actions.onPlay(row) }, leadingIcon = { Icon(Icons.Filled.PlayArrow, null) })
        DropdownMenuItem(text = { Text("Shuffle") }, onClick = { onDismiss(); actions.onShuffle(row) }, leadingIcon = { Icon(Icons.Filled.Shuffle, null) })
        DropdownMenuItem(text = { Text("Add to queue") }, onClick = { onDismiss(); actions.onQueue(row) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) })
        if (isPlaylist || isSmart || isVirtual) {
            DropdownMenuItem(
                text = { Text("Export as M3U") },
                onClick = { onDismiss(); actions.onExport(row) },
                leadingIcon = { Icon(Icons.Filled.IosShare, null) },
            )
        }
        if (isSmart) {
            DropdownMenuItem(
                text = { Text("Edit rules") },
                onClick = { onDismiss(); actions.onEditSmart(row) },
                leadingIcon = { Icon(Icons.Filled.Edit, null) },
            )
            DropdownMenuItem(
                text = { Text("Delete") },
                onClick = { onDismiss(); actions.onDeleteSmart(row) },
                leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
            )
        }
        if (!isVirtual && !isSmart) {
            DropdownMenuItem(
                text = { Text(if (liked) "Unlike" else "Like") },
                onClick = { onDismiss(); actions.onToggleLike(row) },
                leadingIcon = { Icon(if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, null) },
            )
            if (isPlaylist) {
                DropdownMenuItem(
                    text = { Text("Delete playlist") },
                    onClick = { onDismiss(); actions.onDelete(row) },
                    leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                )
            }
        }
    }
}
