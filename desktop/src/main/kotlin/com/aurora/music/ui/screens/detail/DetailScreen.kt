package com.aurora.music.ui.screens.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aurora.music.R
import com.aurora.music.data.ThemeStyle
import com.aurora.music.data.remote.ArtistInfo
import com.aurora.music.localization.appPlural
import com.aurora.music.localization.appString
import com.aurora.music.localization.localizedMediaType
import com.aurora.music.model.Album
import com.aurora.music.model.Song
import com.aurora.music.model.accent
import com.aurora.music.ui.components.AdaptiveShelf
import com.aurora.music.ui.components.AlbumCard
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.Eyebrow
import com.aurora.music.ui.components.LottieLoader
import com.aurora.music.ui.components.PageSection
import com.aurora.music.ui.components.SectionHeader
import com.aurora.music.ui.components.SongListHeader
import com.aurora.music.ui.components.FullPageButton
import com.aurora.music.ui.components.SongRow
import com.aurora.music.ui.layout.LocalPageGutter
import com.aurora.music.ui.layout.PageMetrics
import com.aurora.music.ui.layout.pagePadding
import com.aurora.music.ui.theme.LocalUiPrefs
import com.aurora.music.ui.theme.auroraPanel
import com.aurora.music.ui.theme.readableAccent
import com.aurora.music.util.rememberDominantColor
import com.aurora.music.viewmodel.DetailUiState
import kotlinx.coroutines.launch

private val MetaSeparator = Regex("\\s+[•·]\\s+|\\s*[•·]\\s*$")
private val YearPattern = Regex("(19|20)\\d{2}")
private val CountPattern = Regex("\\d+\\s+(songs?|tracks?)(\\s+you love)?", RegexOption.IGNORE_CASE)
private const val PopularPreview = 5
private val SectionHeaderHeight = 32.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    contentPadding: PaddingValues,
    state: DetailUiState,
    likedIds: Set<String>,
    currentSongId: String,
    isPlaying: Boolean,
    onBack: () -> Unit,
    onPlayAll: (List<Song>, Int) -> Unit,
    onShufflePlay: (List<Song>) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onToggleLike: (String) -> Unit,
    onOpenDetail: (String, String) -> Unit,
    itemKind: String,
    isItemLiked: Boolean,
    onToggleItemLike: () -> Unit,
    downloadedIds: Set<String>,
    onDownload: (Song) -> Unit,
    onRemoveDownload: (String) -> Unit,
    onDownloadAll: () -> Unit,
    onRemoveDownloads: () -> Unit,
    onEditPlaylist: suspend (String, String) -> Boolean,
    onDeletePlaylist: () -> Unit,
    onLoadMore: () -> Unit = {},
    canDownload: Boolean = true,
    isPinned: Boolean = false,
    onTogglePin: () -> Unit = {},
    onEditTags: ((Song) -> Unit)? = null,
    serverTagEditing: Boolean = false,
    artistInfo: ArtistInfo? = null,
    onExpand: (() -> Unit)? = null,
) {
    var headerMenu by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var genreFilter by remember(state.data?.info) { mutableStateOf<String?>(null) }
    var popularExpanded by remember(state.data?.info) { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val gutter = LocalPageGutter.current
    val data = state.data

    if (data == null) {
        Box(Modifier.fillMaxSize()) {
            HeaderIcon(Icons.AutoMirrored.Filled.ArrowBack, appString(R.string.text_back_b52b36), MaterialTheme.colorScheme.onSurface,
                Modifier.padding(start = gutter - 12.dp, top = 16.dp), onClick = onBack)
            if (onExpand != null) FullPageButton(onExpand, Modifier.align(Alignment.TopEnd).padding(end = gutter - 14.dp, top = 16.dp),
                tint = MaterialTheme.colorScheme.onSurface)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (state.loading) LottieLoader(modifier = Modifier.size(72.dp))
                else Text(appString(R.string.text_couldn_t_load_8b7b6b), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    val info = data.info
    val tracks = data.tracks
    val effectiveArt = artistInfo?.imageUrl?.takeIf { info.isArtist && it.isNotBlank() } ?: info.artUrl
    val accent by rememberDominantColor(effectiveArt, info.accent)
    val baseScheme = MaterialTheme.colorScheme
    val accentInk = readableAccent(accent, baseScheme.background.luminance() < 0.5f)
    val albumScheme = baseScheme.copy(primary = accentInk, onPrimary = if (accentInk.luminance() > 0.5f) Color.Black else Color.White)

    val listState = rememberLazyListState()
    LaunchedEffect(listState, state.canLoadMore) {
        snapshotFlow {
            val li = listState.layoutInfo
            (li.visibleItemsInfo.lastOrNull()?.index ?: 0) to li.totalItemsCount
        }.collect { (last, count) ->
            if (state.canLoadMore && count > 0 && last >= count - 6) onLoadMore()
        }
    }

    val isAlbum = itemKind == "album"
    val searchable = tracks.size > 5
    val segments = remember(info.subtitle) { info.subtitle.split(MetaSeparator).map { it.trim() }.filter { it.isNotBlank() } }
    val year = if (isAlbum) segments.firstOrNull { YearPattern.matches(it) } else null
    val described = segments.filterNot { it == year || CountPattern.matches(it) || it.equals(info.typeLabel, true) }
    val albumArtist = if (isAlbum) described.firstOrNull() ?: tracks.map { it.artist }.distinct().singleOrNull() else null
    val albumArtistId = if (!isAlbum) null else tracks.map { it.artistId }.filter { it.isNotBlank() }.distinct().singleOrNull()
        ?: tracks.firstOrNull { it.artist.equals(albumArtist, true) && it.artistId.isNotBlank() }?.artistId
    val description = if (!isAlbum && !info.isArtist) described.joinToString("  •  ").takeIf { it.isNotBlank() } else null
    val metaParts = when {
        info.isArtist -> listOf(info.subtitle).filter { it.isNotBlank() }
        else -> {
            val count = maxOf(info.songCount, tracks.size)
            val seconds = if (tracks.size >= count) tracks.sumOf { it.durationSec } else 0
            buildList {
                if (isAlbum) addAll(described.drop(1))
                year?.let(::add)
                if (count > 0) add(appPlural(R.plurals.track_count, count))
                if (seconds > 0) add(totalLength(seconds))
            }
        }
    }
    val onArtist = albumArtistId?.let { id -> { onOpenDetail("artist", id) } }

    val searchToggle: @Composable () -> Unit = {
        HeaderIcon(
            if (searchOpen) Icons.Filled.Close else Icons.Filled.Search,
            appString(if (searchOpen) R.string.text_close_search_0906f9 else R.string.text_search_tracks_8bc43d),
            MaterialTheme.colorScheme.onSurfaceVariant,
        ) { searchOpen = !searchOpen; if (!searchOpen) query = "" }
    }
    val actionsRow: @Composable (Modifier, Boolean) -> Unit = { rowModifier, wrap ->
        FlowRow(
            rowModifier,
            horizontalArrangement = if (wrap) Arrangement.spacedBy(4.dp) else Arrangement.Start,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            val onAccent = if (accent.luminance() > 0.6f) Color.Black else Color.White
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Brush.horizontalGradient(listOf(accent, accent.copy(alpha = 0.78f))))
                    .clickable(enabled = tracks.isNotEmpty()) { onPlayAll(tracks, 0) }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 28.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.PlayArrow, appString(R.string.text_play_5d12bd), tint = onAccent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(appString(R.string.text_play_5d12bd), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black, color = onAccent)
            }
            Spacer(Modifier.width(10.dp))
            HeaderIcon(Icons.Filled.Shuffle, appString(R.string.text_shuffle_5b772b), MaterialTheme.colorScheme.onSurface, enabled = tracks.isNotEmpty()) {
                onShufflePlay(tracks)
            }
            if (!wrap) Spacer(Modifier.weight(1f))
            if (itemKind == "album" || itemKind == "playlist" || itemKind == "artist") {
                HeaderIcon(
                    if (isItemLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    appString(if (isItemLiked) R.string.text_unlike_e4fc40 else R.string.text_like_c7e02c),
                    if (isItemLiked) accent else MaterialTheme.colorScheme.onSurface,
                    onClick = onToggleItemLike,
                )
            }
            if (canDownload) {
                val allDownloaded = tracks.isNotEmpty() && tracks.all { downloadedIds.contains(it.id) }
                HeaderIcon(
                    if (allDownloaded) Icons.Filled.DownloadDone else Icons.Filled.Download,
                    appString(if (allDownloaded) R.string.text_remove_downloads_cbe8be else R.string.text_download_a479c9),
                    if (allDownloaded) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                ) { if (allDownloaded) onRemoveDownloads() else onDownloadAll() }
            }
            if (searchable) searchToggle()
        }
    }
    val menuButton: @Composable (Color, Modifier) -> Unit = { tint, modifier ->
        Box(modifier) {
            HeaderIcon(Icons.Filled.MoreVert, appString(R.string.text_more_4bab2d), tint) { headerMenu = true }
            val isPlaylist = info.typeLabel.equals("Playlist", true)
            DropdownMenu(expanded = headerMenu, onDismissRequest = { headerMenu = false }) {
                DropdownMenuItem(text = { Text(appString(R.string.text_play_5d12bd)) }, enabled = tracks.isNotEmpty(), onClick = { headerMenu = false; onPlayAll(tracks, 0) }, leadingIcon = { Icon(Icons.Filled.PlayArrow, null) })
                DropdownMenuItem(text = { Text(appString(R.string.text_shuffle_5b772b)) }, enabled = tracks.isNotEmpty(), onClick = { headerMenu = false; onShufflePlay(tracks) }, leadingIcon = { Icon(Icons.Filled.Shuffle, null) })
                DropdownMenuItem(text = { Text(appString(R.string.text_add_all_to_queue_6cb104)) }, enabled = tracks.isNotEmpty(), onClick = { headerMenu = false; tracks.forEach { onAddToQueue(it) } }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) })
                DropdownMenuItem(
                    text = { Text(appString(if (isPinned) R.string.text_unpin_from_library_5b3f2e else R.string.text_pin_to_library_7b01e2)) },
                    onClick = { headerMenu = false; onTogglePin() },
                    leadingIcon = { Icon(if (isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, null) },
                )
                if (isPlaylist) {
                    DropdownMenuItem(text = { Text(appString(R.string.text_edit_playlist_1528d5)) }, onClick = { headerMenu = false; showEdit = true }, leadingIcon = { Icon(Icons.Filled.Edit, null) })
                    DropdownMenuItem(text = { Text(appString(R.string.text_delete_playlist_b55b18)) }, onClick = { headerMenu = false; onDeletePlaylist() }, leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) })
                }
            }
        }
    }
    val trackRow: @Composable (List<Song>, Int, Int?, Modifier) -> Unit = { list, i, number, rowModifier ->
        val s = list[i]
        SongRow(
            song = s,
            isPlaying = s.id == currentSongId && isPlaying,
            isLiked = likedIds.contains(s.id),
            onClick = { onPlayAll(list, i) },
            onToggleLike = { onToggleLike(s.id) },
            modifier = rowModifier,
            index = number,
            onAddToQueue = { onAddToQueue(s) },
            onPlayNext = { onPlayNext(s) },
            onGoToAlbum = if (!info.isArtist && s.albumId.isNotBlank()) ({ onOpenDetail("album", s.albumId) }) else null,
            onGoToArtist = if (s.artistId.isNotBlank()) ({ onOpenDetail("artist", s.artistId) }) else null,
            isDownloaded = canDownload && downloadedIds.contains(s.id),
            onDownload = if (canDownload) ({ onDownload(s) }) else null,
            onRemoveDownload = if (canDownload) ({ onRemoveDownload(s.id) }) else null,
            onEditTags = onEditTags?.let { cb -> { cb(s) } },
            serverTagEditing = serverTagEditing,
            showAlbum = !isAlbum,
            showArt = !isAlbum,
        )
    }

    MaterialTheme(colorScheme = albumScheme) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val pageWidth = maxWidth
    val pageHeight = maxHeight
    val wideHeader = pageWidth >= 480.dp
    val shortViewport = maxHeight < 700.dp
    val headerArt = when {
        shortViewport -> 144.dp
        maxWidth >= 1400.dp -> 232.dp
        maxWidth >= 800.dp -> 200.dp
        else -> 160.dp
    }
    val titleStyle = when {
        shortViewport -> MaterialTheme.typography.headlineLarge
        maxWidth >= 1400.dp -> MaterialTheme.typography.displayLarge
        maxWidth >= 1100.dp -> MaterialTheme.typography.displayMedium
        else -> MaterialTheme.typography.headlineLarge
    }
    val padding = pagePadding(contentPadding)
    val contentWidth = maxWidth - gutter * 2
    val aboutInfo = artistInfo?.takeIf {
        info.isArtist && (it.bio.isNotBlank() || it.tags.isNotEmpty() || it.country.isNotBlank() || it.yearsActive.isNotBlank())
    }
    val sideBySide = aboutInfo != null && maxWidth >= 1200.dp
    val columnGap = 32.dp
    val popularWidth = (contentWidth - columnGap) * 0.6f

    val isSongMix = info.typeLabel.equals("Playlist", true) || info.typeLabel.equals("Smart playlist", true) || info.typeLabel.equals("Liked", true)
    val genres = if (isSongMix) tracks.mapNotNull { t -> t.genre.trim().takeIf { it.isNotBlank() } }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() } else emptyList()
    val genreShown = genreFilter?.let { g -> tracks.filter { it.genre.equals(g, true) } } ?: tracks
    val filtering = searchOpen && query.isNotBlank()
    val shown = if (!filtering) genreShown
        else genreShown.filter { it.title.contains(query, true) || it.artist.contains(query, true) }
    val positions = remember(tracks) { tracks.withIndex().associate { it.value.id to it.index + 1 } }
    fun numberOf(i: Int) = if (shown === tracks) i + 1 else positions[shown[i].id]

    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = padding) {
        if (wideHeader) item(key = "header") {
            Column(
                Modifier.bleed(gutter / 2).padding(top = 12.dp)
                    .clip(panelShape())
                    .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.28f), accent.copy(alpha = 0.06f))))
                    .padding(start = gutter / 2, end = gutter / 2, top = 4.dp, bottom = 24.dp),
            ) {
                Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    HeaderIcon(Icons.AutoMirrored.Filled.ArrowBack, appString(R.string.text_back_b52b36), MaterialTheme.colorScheme.onSurface,
                        Modifier.offset(x = (-12).dp), onClick = onBack)
                    Spacer(Modifier.weight(1f))
                    if (onExpand != null) FullPageButton(onExpand, Modifier.offset(x = 12.dp), tint = MaterialTheme.colorScheme.onSurface)
                    menuButton(MaterialTheme.colorScheme.onSurface, Modifier.offset(x = 12.dp))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Artwork(effectiveArt, info.accent, Modifier.size(headerArt), corner = if (info.isArtist) headerArt / 2 else 20.dp)
                    Spacer(Modifier.width(if (pageWidth >= 800.dp) 32.dp else 24.dp))
                    Column(Modifier.weight(1f)) {
                        Eyebrow(info.typeLabel.localizedMediaType().uppercase(), accentInk)
                        Spacer(Modifier.height(6.dp))
                        Text(info.title, style = titleStyle, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (description != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(8.dp))
                        MetaLine(albumArtist, onArtist, metaParts, MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                        actionsRow(Modifier.fillMaxWidth(), true)
                    }
                }
            }
        }
        if (!wideHeader) item(key = "header") {
            Column(Modifier.bleed(gutter)) {
                Box(Modifier.fillMaxWidth().height(minOf(420.dp, pageHeight * 0.55f))) {
                    Artwork(effectiveArt, info.accent, Modifier.matchParentSize(), corner = 0.dp)
                    Box(
                        Modifier.matchParentSize().background(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0f to Color.Transparent,
                                    0.42f to MaterialTheme.colorScheme.background.copy(alpha = 0.10f),
                                    0.74f to MaterialTheme.colorScheme.background.copy(alpha = 0.72f),
                                    1f to MaterialTheme.colorScheme.background,
                                )
                            )
                        )
                    )
                    Box(Modifier.fillMaxWidth().height(130.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent))))
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp, start = gutter - 12.dp, end = gutter - 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        HeaderIcon(Icons.AutoMirrored.Filled.ArrowBack, appString(R.string.text_back_b52b36), Color.White, onClick = onBack)
                        Spacer(Modifier.weight(1f))
                        if (onExpand != null) FullPageButton(onExpand, tint = Color.White)
                        menuButton(Color.White, Modifier)
                    }
                    Column(Modifier.align(Alignment.BottomStart).padding(start = gutter, end = gutter, bottom = 14.dp)) {
                        Eyebrow(info.typeLabel.localizedMediaType().uppercase(), accent)
                        Spacer(Modifier.height(6.dp))
                        Text(info.title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black, color = Color.White,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (description != null) {
                            Spacer(Modifier.height(2.dp))
                            Text(description, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f),
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(4.dp))
                        MetaLine(albumArtist, onArtist, metaParts, Color.White, Color.White.copy(alpha = 0.85f))
                    }
                }
                actionsRow(Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 8.dp), false)
            }
        }

        if (searchable) item(key = "search") {
            LaunchedEffect(searchOpen) {
                if (searchOpen) runCatching { searchFocus.requestFocus() }
            }
            AnimatedVisibility(visible = searchOpen) {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.padding(top = 16.dp).widthIn(max = 480.dp).fillMaxWidth().focusRequester(searchFocus),
                    placeholder = { Text(appString(R.string.search_collection)) },
                    leadingIcon = { Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingIcon = {
                        if (query.isNotEmpty()) Icon(Icons.Filled.Close, appString(R.string.text_clear_719ea3),
                            modifier = Modifier.clip(CircleShape).clickable { query = "" }.pointerHoverIcon(PointerIcon.Hand).padding(4.dp))
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }
        }

        if (genres.size > 1) item(key = "genres") {
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                (listOf<String?>(null) + genres).forEach { genre ->
                    val selected = if (genre == null) genreFilter == null else genreFilter.equals(genre, true)
                    Text(
                        genre ?: appString(R.string.text_all_6a7208),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.Black else FontWeight.Medium,
                        color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (selected) accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHigh)
                            .then(if (selected) Modifier.border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(50)) else Modifier)
                            .clickable { genreFilter = genre }
                            .pointerHoverIcon(PointerIcon.Hand)
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
        }

        if (info.isArtist) {
            val popular = if (popularExpanded || filtering) shown else shown.take(PopularPreview)
            val popularHeader: @Composable (Modifier) -> Unit = { headerModifier ->
                SectionHeader(
                    appString(R.string.text_popular_9bc2c5),
                    headerModifier.heightIn(min = SectionHeaderHeight),
                    action = if (!filtering && shown.size > PopularPreview) appString(if (popularExpanded) R.string.text_show_less_4c852b else R.string.text_see_all_2941c5) else null,
                    onAction = { popularExpanded = !popularExpanded },
                )
            }
            if (sideBySide) {
                item(key = "popular-about") {
                    Row(Modifier.fillMaxWidth().padding(top = 28.dp)) {
                        Column(Modifier.weight(0.6f)) {
                            popularHeader(Modifier)
                            Spacer(Modifier.height(PageMetrics.HeaderToContent))
                            repeat(minOf(popular.size, PopularPreview)) { i -> trackRow(popular, i, i + 1, Modifier) }
                        }
                        Spacer(Modifier.width(columnGap))
                        ArtistAbout(aboutInfo, accent, Modifier.weight(0.4f), card = true)
                    }
                }
                if (popular.size > PopularPreview) items(popular.size - PopularPreview) { j ->
                    val i = j + PopularPreview
                    trackRow(popular, i, i + 1, Modifier.width(popularWidth))
                }
            } else if (popular.isNotEmpty()) {
                item(key = "popular") {
                    popularHeader(Modifier.padding(top = 28.dp))
                    Spacer(Modifier.height(PageMetrics.HeaderToContent))
                }
                items(popular.size) { i -> trackRow(popular, i, i + 1, Modifier) }
            }

            val (short, full) = data.albums.partition { it.typeLabel == "EP" || it.typeLabel == "Single" }
            val shelves: List<Pair<String, List<Album>>> = if (short.size <= 2) {
                listOf(appString(if (full.isEmpty()) R.string.text_eps_singles_f6feb2 else R.string.text_albums_4c45e7) to full + short)
            } else {
                listOf(appString(R.string.text_albums_4c45e7) to full, appString(R.string.text_eps_singles_f6feb2) to short)
            }
            shelves.filter { it.second.isNotEmpty() }.forEach { (title, albums) ->
                item(key = "shelf-$title") {
                    PageSection(title, Modifier.padding(top = PageMetrics.SectionGap), count = albums.size.takeIf { it > 1 }) {
                        AdaptiveShelf(albums, bleed = gutter, key = { it.id }) { album, width ->
                            AlbumCard(album, onClick = { onOpenDetail("album", album.id) }, width = width)
                        }
                    }
                }
            }
            if (!sideBySide && aboutInfo != null) item(key = "about") {
                ArtistAbout(aboutInfo, accent, Modifier.padding(top = PageMetrics.SectionGap), card = false)
            }
        } else {
            item(key = "columns") {
                SongListHeader(Modifier.padding(top = 20.dp), showIndex = true, showArt = !isAlbum, showAlbum = !isAlbum)
            }
            items(shown.size) { i -> trackRow(shown, i, numberOf(i), Modifier) }
        }

        if (state.loadingMore) {
            item(key = "more") {
                Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                    LottieLoader(modifier = Modifier.size(40.dp))
                }
            }
        }
    }
    }
    }

    if (showEdit) {
        EditPlaylistDialog(
            initialName = info.title,
            initialDesc = info.editableDescription ?: info.subtitle,
            onSave = onEditPlaylist,
            onDismiss = { showEdit = false },
        )
    }
}

private fun Modifier.bleed(amount: Dp): Modifier = layout { measurable, constraints ->
    val extra = amount.roundToPx()
    val width = constraints.maxWidth + extra * 2
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra, 0) }
}

@Composable
private fun panelShape(): Shape =
    if (LocalUiPrefs.current.themeStyle == ThemeStyle.AURORA) RoundedCornerShape(28.dp) else MaterialTheme.shapes.extraLarge

private fun totalLength(seconds: Int): String {
    val minutes = ((seconds + 30) / 60).coerceAtLeast(1)
    return if (minutes >= 60) appString(R.string.text_h_m_4dc25f, minutes / 60, minutes % 60)
    else appString(R.string.text_min_5c8f84, minutes)
}

@Composable
private fun HeaderIcon(
    icon: ImageVector,
    label: String,
    tint: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Icon(icon, label, tint = tint,
        modifier = modifier.size(48.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick).pointerHoverIcon(PointerIcon.Hand).padding(12.dp))
}

@Composable
private fun MetaLine(artist: String?, onArtist: (() -> Unit)?, parts: List<String>, strong: Color, muted: Color) {
    if (artist.isNullOrBlank() && parts.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!artist.isNullOrBlank()) {
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            Text(
                artist,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = strong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (hovered && onArtist != null) TextDecoration.Underline else null,
                modifier = Modifier.weight(1f, fill = false).then(
                    if (onArtist != null) Modifier.clickable(interactionSource = interaction, indication = null, onClick = onArtist).pointerHoverIcon(PointerIcon.Hand)
                    else Modifier
                ),
            )
        }
        if (parts.isNotEmpty()) {
            Text(
                (if (artist.isNullOrBlank()) "" else "  •  ") + parts.joinToString("  •  "),
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ArtistAbout(info: ArtistInfo, accent: Color, modifier: Modifier, card: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    var clipped by remember(info.bio) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        SectionHeader(appString(R.string.text_about_6b21fb), Modifier.heightIn(min = SectionHeaderHeight))
        Spacer(Modifier.height(PageMetrics.HeaderToContent))
        Column(
            if (card) Modifier.fillMaxWidth().auroraPanel(MaterialTheme.shapes.large).padding(20.dp)
            else Modifier.widthIn(max = PageMetrics.ReadingMaxWidth)
        ) {
            val meta = listOf(info.country, info.yearsActive).filter { it.isNotBlank() }.joinToString("  •  ")
            if (meta.isNotBlank()) {
                Text(meta, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = accent)
                Spacer(Modifier.height(8.dp))
            }
            if (info.bio.isNotBlank()) {
                Text(
                    info.bio,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 5,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (!expanded) clipped = it.hasVisualOverflow },
                    modifier = Modifier.widthIn(max = PageMetrics.ReadingMaxWidth).clickable(enabled = clipped || expanded) { expanded = !expanded },
                )
                if (clipped || expanded) Text(
                    appString(if (expanded) R.string.text_show_less_4c852b else R.string.text_show_more_25911d),
                    style = MaterialTheme.typography.labelLarge,
                    color = accent,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { expanded = !expanded }.pointerHoverIcon(PointerIcon.Hand).padding(vertical = 4.dp),
                )
            }
            if (info.tags.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    info.tags.forEach { tag ->
                        Text(
                            tag,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.clip(RoundedCornerShape(50))
                                .background(if (card) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerHigh)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EditPlaylistDialog(initialName: String, initialDesc: String, onSave: suspend (String, String) -> Boolean, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    var desc by remember { mutableStateOf(initialDesc) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(appString(R.string.text_edit_playlist_1528d5), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(value = name, onValueChange = { name = it; failed = false }, label = { Text(appString(R.string.text_name_709a23)) }, singleLine = true, enabled = !saving)
                Spacer(Modifier.height(10.dp))
                androidx.compose.material3.OutlinedTextField(value = desc, onValueChange = { desc = it; failed = false }, label = { Text(appString(R.string.text_description_55f8eb)) }, enabled = !saving)
                if (failed) {
                    Text(appString(R.string.playlist_update_failed), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                saving = true
                failed = false
                scope.launch {
                    try {
                        if (onSave(name.trim(), desc.trim())) onDismiss() else failed = true
                    } finally {
                        saving = false
                    }
                }
            }, enabled = name.isNotBlank() && !saving) {
                Text(appString(if (saving) R.string.playlist_saving else R.string.text_save_efc007))
            }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss, enabled = !saving) { Text(appString(R.string.text_cancel_77dfd2)) } },
    )
}
