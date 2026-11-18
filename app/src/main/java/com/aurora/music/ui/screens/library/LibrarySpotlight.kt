package com.aurora.music.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aurora.music.R
import com.aurora.music.data.Pin
import com.aurora.music.data.PlaylistFolder
import com.aurora.music.localization.appPlural
import com.aurora.music.localization.appString
import com.aurora.music.localization.localizedMediaType
import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.LibraryFilter
import com.aurora.music.model.LibrarySort
import com.aurora.music.model.label
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.EmptyState
import com.aurora.music.ui.components.Eyebrow
import com.aurora.music.ui.layout.LocalWindowLayout
import com.aurora.music.ui.screens.home.IconPill
import com.aurora.music.ui.screens.home.SpotlightPill
import com.aurora.music.ui.screens.home.rememberEntrance
import com.aurora.music.ui.screens.home.rise
import com.aurora.music.ui.screens.home.spotSurface
import com.aurora.music.ui.screens.home.spotlightTint
import com.aurora.music.ui.theme.PaperTokens
import com.aurora.music.ui.theme.PressScaleIndication
import com.aurora.music.ui.theme.isGlassTheme
import com.aurora.music.ui.theme.isPaperTheme
import com.aurora.music.ui.theme.paperClip
import com.aurora.music.ui.theme.paperFrame
import com.aurora.music.ui.theme.pressClickable
import com.aurora.music.util.accentFor
import com.aurora.music.viewmodel.LibraryUiState
import java.util.Locale

private val Gutter = 20.dp
private val Edge = 14.dp

@Composable
internal fun LibrarySpotlightHeader(
    state: LibraryUiState,
    username: String,
    stats: String,
    tabs: List<LibraryFilter>,
    filter: LibraryFilter,
    onOpenDrawer: () -> Unit,
    onOpenSearch: () -> Unit,
    onCreate: () -> Unit,
    onCycleView: () -> Unit,
    onFilter: (LibraryFilter) -> Unit,
    onCustomizeTabs: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    Row(Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        LibraryAvatar(username, onOpenDrawer)
        Spacer(Modifier.width(12.dp))
        Text(appString(R.string.text_library_b8100f), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        IconPill(Icons.Filled.Search, appString(R.string.text_search_bce064), onOpenSearch)
        if (state.signedIn) {
            Spacer(Modifier.width(8.dp))
            val create = appString(R.string.text_create_playlist_62c988)
            if (isGlassTheme()) IconPill(Icons.Filled.Add, create, onCreate)
            else Box(
                Modifier.size(40.dp).paperFrame(CircleShape, { background(colors.primary.copy(alpha = 0.2f)) }).clickable(onClick = onCreate),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Add, create, tint = if (isPaperTheme()) PaperTokens.ink else colors.primary, modifier = Modifier.size(22.dp)) }
        }
    }
    if (stats.isNotBlank()) {
        Text(stats, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = Gutter, end = Gutter, top = 4.dp))
    }
    if (state.views.size > 1) LibraryViewPills(state, Gutter, onCycleView)
    Row(
        Modifier.padding(top = 14.dp).horizontalScroll(rememberScrollState()).padding(horizontal = Gutter),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            SpotlightPill(tab.label, tab == filter, accent = true, onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onCustomizeTabs()
            }) { onFilter(tab) }
        }
        Icon(Icons.Filled.Tune, appString(R.string.library_tabs_customize), tint = colors.onSurfaceVariant,
            modifier = Modifier.size(40.dp).paperClip(CircleShape).clickable(onClick = onCustomizeTabs).padding(10.dp))
    }
}

@Composable
internal fun LibraryViewPills(state: LibraryUiState, gutter: Dp, onCycleView: () -> Unit) {
    val at = state.views.indexOfFirst { it.id == state.view }.coerceAtLeast(0)
    Row(Modifier.padding(top = 14.dp).horizontalScroll(rememberScrollState()).padding(horizontal = gutter), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.views.forEachIndexed { i, view ->
            // shortcut: the view model can only step to the next view, so a far pill starts loads it cancels; call a select-by-id once it has one
            SpotlightPill(view.label, i == at) { repeat((i - at).mod(state.views.size)) { onCycleView() } }
        }
    }
}

@Composable
internal fun LibrarySpotlightAll(
    state: LibraryUiState,
    pins: List<Pin>,
    bottom: Dp,
    columns: Int,
    tree: PlaylistFolder?,
    actions: LibActions,
    tiles: List<QuickTile>,
    onOpenDetail: (String, String) -> Unit,
    onOpenMix: () -> Unit,
    onOpenFolder: ((String) -> Unit)?,
    onSignIn: () -> Unit,
) {
    val folders = tree.takeIf { onOpenFolder != null }
    // the playlists tab's own top level, without the sort and the liked row it adds
    val playlists = remember(state.playlists, state.smartPlaylists, folders) {
        buildRows(state, LibraryFilter.PLAYLISTS, LibrarySort.RECENT, emptyList(), folders).filterNot { it.kind == "liked" }
    }
    val order = remember(playlists.size, state.albums.size, state.artists.size) { interleave(playlists.size, state.albums.size, state.artists.size) }
    val albumLabel = appString(R.string.text_album_dfb4c9)
    val artistLabel = appString(R.string.text_artist_6c3f3d)
    val strip = tiles + QuickTile(appString(R.string.text_mix_studio_668363), appString(R.string.text_edit_mix_transitions_76ee01), Icons.Filled.Apps, "", onClick = onOpenMix)
    val clock = rememberEntrance()
    LazyVerticalGrid(
        columns = if (LocalWindowLayout.current.useNavigationRail) GridCells.Adaptive((360f / columns).coerceAtLeast(120f).dp) else GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Edge, end = Edge, bottom = bottom),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, contentType = "strip") {
            LazyRow(Modifier.bleed(Edge).rise(clock, 0), contentPadding = PaddingValues(start = Gutter, end = Gutter, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(strip.size) { i -> StripCard(strip[i]) }
            }
        }
        if (state.signedIn) item(span = { GridItemSpan(maxLineSpan) }, contentType = "liked") {
            LikedCard(state, actions, Modifier.padding(horizontal = Gutter - Edge, vertical = 4.dp).rise(clock, 1)) { onOpenDetail("liked", state.likedId) }
        }
        if (pins.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, contentType = "pinned") {
            Column(Modifier.bleed(Edge).rise(clock, 2)) {
                Box(Modifier.padding(start = Gutter - 16.dp)) { ShelfHeader(appString(R.string.text_pinned_f93121), null) {} }
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
        if (order.isEmpty() && state.songs.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }, contentType = "empty") {
            val guest = !state.signedIn
            Box(Modifier.padding(horizontal = Gutter - Edge, vertical = 4.dp).rise(clock, 2).fillMaxWidth().spotSurface(MaterialTheme.shapes.large, MaterialTheme.colorScheme.surface, outlined = true)) {
                EmptyState(Icons.Filled.LibraryMusic, appString(R.string.text_nothing_here_yet_e89225),
                    subtitle = appString(if (guest) R.string.guest_detail else R.string.home_empty_body),
                    action = appString(if (guest) R.string.guest_sign_in else R.string.home_add_source), onAction = onSignIn)
            }
        }
        items(order.size, contentType = { "card" }) { i ->
            val slot = order[i]
            val row = when (slot % 3) {
                0 -> playlists[slot / 3]
                1 -> state.albums[slot / 3].let { remember(it) { albumRow(it, albumLabel) } }
                else -> state.artists[slot / 3].let { remember(it) { artistRow(it, artistLabel) } }
            }
            LibGridItem(row, actions) { if (row.kind == "folder") onOpenFolder?.invoke(row.id) else onOpenDetail(row.kind, row.id) }
        }
    }
}

// playlist, album, artist in turn until each list runs out; a slot is index * 3 + which list
private fun interleave(playlists: Int, albums: Int, artists: Int): IntArray {
    val slots = IntArray(playlists + albums + artists)
    var n = 0
    for (i in 0 until maxOf(playlists, albums, artists)) {
        if (i < playlists) slots[n++] = i * 3
        if (i < albums) slots[n++] = i * 3 + 1
        if (i < artists) slots[n++] = i * 3 + 2
    }
    return slots
}

internal fun albumRow(album: Album, albumLabel: String): LibRow {
    val label = album.typeLabel.localizedMediaType()
    return LibRow(album.title, "$label · ${album.artist}", album.artworkUrl, accentFor(album.id), album.id, "album",
        badge = if (label == albumLabel) "" else label.uppercase(Locale.getDefault()))
}

internal fun artistRow(artist: Artist, label: String) = LibRow(artist.name, label, artist.imageUrl, accentFor(artist.id), artist.id, "artist", circle = true)

internal fun Modifier.bleed(by: Dp): Modifier = layout { measurable, constraints ->
    val extra = by.roundToPx()
    val width = constraints.maxWidth + extra * 2
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra, 0) }
}

@Composable
private fun StripCard(tile: QuickTile) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.width(128.dp).heightIn(min = 88.dp).spotSurface(MaterialTheme.shapes.medium, colors.surface, outlined = true)
            .combinedClickable(interactionSource = null, indication = PressScaleIndication, onClick = tile.onClick, onLongClick = tile.onLongClick,
                onLongClickLabel = if (tile.onLongClick != null) appString(R.string.liked_sources_choose) else null)
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(tile.icon, null, tint = colors.primary, modifier = Modifier.size(22.dp))
        Column {
            tile.menu?.invoke()
            Text(tile.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tile.subtitle, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LikedCard(state: LibraryUiState, actions: LibActions, modifier: Modifier, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tint = spotlightTint(state.likedCover)
    val soft = colors.onSurface.copy(alpha = 0.85f)
    Row(
        modifier.fillMaxWidth().heightIn(min = 124.dp).paperFrame(MaterialTheme.shapes.large, { background(tint) }, fill = tint)
            .combinedClickable(interactionSource = null, indication = PressScaleIndication, onClick = onOpen, onLongClick = actions.onOpenAllLiked,
                onLongClickLabel = if (actions.onOpenAllLiked != null) appString(R.string.liked_sources_choose) else null)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.likedPicker()
        if (state.likedCover.isNotBlank()) Artwork(state.likedCover, colors.primary, Modifier.size(92.dp), corner = 16.dp)
        else Box(
            Modifier.size(92.dp).paperClip(MaterialTheme.shapes.medium)
                .background(Brush.linearGradient(listOf(colors.primary.copy(alpha = 0.85f), colors.tertiary.copy(alpha = 0.85f)))),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Favorite, null, tint = colors.onPrimary, modifier = Modifier.size(40.dp)) }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Eyebrow(appString(R.string.text_collection_30c54a).uppercase(Locale.getDefault()), soft)
            Text(appString(R.string.text_liked_songs_58c3a9), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // 0 doubles as not counted yet, so no number rather than a wrong 0
            if (state.likedSongCount > 0) Text(appPlural(R.plurals.track_count, state.likedSongCount), style = MaterialTheme.typography.bodySmall, color = soft, maxLines = 1)
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier.size(52.dp).paperFrame(CircleShape, { background(colors.inverseSurface) }, fill = PaperTokens.rose)
                .pressClickable(role = Role.Button) { actions.onShuffle(LibRow(appString(R.string.text_liked_songs_58c3a9), "", state.likedCover, accentFor("liked"), state.likedId, "liked")) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Shuffle, appString(R.string.text_shuffle_5b772b), tint = if (isPaperTheme()) PaperTokens.ink else colors.inverseOnSurface, modifier = Modifier.size(24.dp))
        }
    }
}
