package com.aurora.music.ui.screens.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
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
import com.aurora.music.ui.components.Eyebrow
import com.aurora.music.ui.components.HoverTooltip
import com.aurora.music.ui.components.PageHeader
import com.aurora.music.ui.layout.PageMetrics
import com.aurora.music.ui.screens.home.SpotlightPill
import com.aurora.music.ui.screens.home.TileGrid
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
import java.util.Locale

private val CardGap = 16.dp
// what liked songs and a pin ask for before the row wraps
private val LikedMinWidth = 420.dp
private val PinMinWidth = 250.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibrarySpotlightHeader(
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
    val views: @Composable (Modifier) -> Unit = { modifier ->
        Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            state.views.forEach { view -> SpotlightPill(view.label, view.id == state.view) { onSelectView(view.id) } }
        }
    }
    PageHeader(
        appString(R.string.text_library_b8100f),
        Modifier.padding(start = insets.start, end = (insets.end - 8.dp).coerceAtLeast(0.dp)),
        subtitle = stats,
    ) {
        if (state.views.size > 1 && !compact) views(Modifier.widthIn(max = (width - 560.dp).coerceAtLeast(0.dp)))
        HeaderAction(Icons.Filled.Search, appString(R.string.text_search_bce064), onOpenSearch)
        onOpenMix?.let { HeaderAction(Icons.Filled.GraphicEq, appString(R.string.text_mix_studio_668363), it) }
        if (state.signedIn) HeaderAction(Icons.Filled.Add, appString(R.string.text_create_playlist_62c988), onCreate)
    }
    if (state.views.size > 1 && compact) views(Modifier.padding(start = insets.start, end = insets.end, bottom = 4.dp))
    Row(Modifier.fillMaxWidth().height(52.dp).padding(end = insets.end), verticalAlignment = Alignment.CenterVertically) {
        LazyRow(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = insets.start, end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(visibleTabs.size) { i ->
                val tab = visibleTabs[i]
                // a narrow pane keeps the label for the chosen tab only, as classic's tabs do
                val bare = compact && tab != filter
                val chip: @Composable () -> Unit = { SpotlightPill(tab.label, tab == filter, accent = true, icon = tabIcon(tab).takeIf { bare }) { onFilter(tab) } }
                if (bare) TooltipArea(tooltip = { HoverTooltip(tab.label) }, delayMillis = 400) { chip() } else chip()
            }
            item { LibraryTabsMenu(tabs, canDownload, onTabs) }
        }
        if (filter != LibraryFilter.ALL) SortButton(sort, iconOnly = compact, onSort = onSort)
        if (filter != LibraryFilter.ALL && filter != LibraryFilter.SONGS) {
            Spacer(Modifier.width(6.dp))
            LayoutToggle(layout, onToggleLayout)
        }
    }
}

@Composable
internal fun LibrarySpotlightAll(
    state: LibraryUiState,
    pins: List<Pin>,
    width: Dp,
    compact: Boolean,
    bottom: Dp,
    cardMin: Dp,
    actions: LibActions,
    tree: PlaylistFolder?,
    tiles: List<QuickTile>,
    onOpen: (LibRow) -> Unit,
    onSignIn: () -> Unit,
) {
    val insets = LocalLibInsets.current
    // the playlists tab's own top level, without the sort and the liked row it adds
    val playlists = remember(state.playlists, state.smartPlaylists, tree) {
        buildRows(state, LibraryFilter.PLAYLISTS, LibrarySort.RECENT, emptyList(), tree).filterNot { it.kind == "liked" }
    }
    val albums = remember(state.albums) {
        state.albums.map { album -> albumRow(album).let { it.copy(subtitle = listOf(it.detail, album.artist).filter(String::isNotBlank).joinToString(" · ")) } }
    }
    val artists = remember(state.artists) { state.artists.map { artistRow(it, 0) } }
    val order = remember(playlists.size, albums.size, artists.size) { interleave(playlists.size, albums.size, artists.size) }
    val pinned = remember(pins) {
        pins.map { p -> LibRow(p.title, p.subtitle.ifBlank { kindLabel(p.kind) }, p.coverUrl, accentFor(p.id), p.id, p.kind, circle = p.kind == "artist") }
    }
    // 0 doubles as not counted yet, so no number rather than a wrong 0
    val liked = if (!state.signedIn) null else LibRow(appString(R.string.text_liked_songs_58c3a9),
        if (state.likedSongCount > 0) appPlural(R.plurals.track_count, state.likedSongCount) else "", state.likedCover, accentFor("liked"), state.likedId, "liked")
    val clock = rememberEntrance()
    val line: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(cardMin),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = insets.start, end = insets.end, top = 8.dp, bottom = bottom),
        horizontalArrangement = Arrangement.spacedBy(PageMetrics.ShelfSpacing),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item(span = line, contentType = "strip") {
            TileGrid(Modifier.rise(clock, 0), minWidth = 170.dp, stretch = true) { tiles.forEach { StripCard(it) } }
        }
        if (liked != null || pinned.isNotEmpty()) item(span = line, contentType = "featured") {
            Featured(liked, pinned, width - insets.start - insets.end, compact, actions, Modifier.rise(clock, 1), onOpen)
        }
        if (order.isEmpty() && state.songs.isEmpty()) item(span = line, contentType = "empty") {
            val guest = !state.signedIn
            Box(Modifier.fillMaxWidth().rise(clock, 2).spotSurface(MaterialTheme.shapes.large, outlined = true)) {
                EmptyState(Icons.Filled.LibraryMusic, appString(R.string.text_nothing_here_yet_e89225),
                    subtitle = appString(if (guest) R.string.guest_detail else R.string.home_empty_body),
                    action = appString(if (guest) R.string.guest_sign_in else R.string.home_add_source), onAction = onSignIn)
            }
        }
        items(order.size, contentType = { "card" }) { i ->
            val slot = order[i]
            val row = when (slot % 3) {
                0 -> playlists[slot / 3]
                1 -> albums[slot / 3]
                else -> artists[slot / 3]
            }
            LibCard(row, actions) { onOpen(row) }
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

@Composable
private fun StripCard(tile: QuickTile) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.height(72.dp).spotSurface(MaterialTheme.shapes.medium, outlined = true).pressClickable(onClick = tile.onClick)
            .pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tile.icon?.let { Icon(it, null, tint = colors.primary, modifier = Modifier.size(24.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(tile.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tile.subtitle, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// liked songs takes a double share of the first row; the pins fill it up and wrap into rows they share evenly
@Composable
private fun Featured(liked: LibRow?, pins: List<LibRow>, width: Dp, compact: Boolean, actions: LibActions, modifier: Modifier, onOpen: (LibRow) -> Unit) {
    val perRow = ((width + CardGap) / (PinMinWidth + CardGap)).toInt().coerceAtLeast(1)
    val beside = if (liked == null) 0 else ((width - LikedMinWidth) / (PinMinWidth + CardGap)).toInt().coerceIn(0, pins.size)
    val pinLabel = appString(R.string.text_pinned_f93121).uppercase(Locale.getDefault())
    Column(modifier, verticalArrangement = Arrangement.spacedBy(CardGap)) {
        if (liked != null) Row(horizontalArrangement = Arrangement.spacedBy(CardGap)) {
            TintCard(liked, appString(R.string.text_collection_30c54a).uppercase(Locale.getDefault()), compact, actions, Modifier.weight(2f)) { onOpen(liked) }
            pins.take(beside).forEach { pin -> TintCard(pin, pinLabel, compact, actions, Modifier.weight(1f)) { onOpen(pin) } }
        }
        pins.drop(beside).chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(CardGap)) {
                row.forEach { pin -> TintCard(pin, pinLabel, compact, actions, Modifier.weight(1f)) { onOpen(pin) } }
            }
        }
    }
}

@Composable
private fun TintCard(row: LibRow, label: String, compact: Boolean, actions: LibActions, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val isLiked = row.kind == "liked"
    val tint = spotlightTint(row.art, if (isLiked) Color.Unspecified else row.accent)
    val soft = colors.onSurface.copy(alpha = 0.85f)
    val art = if (compact) 64.dp else 104.dp
    val shape = MaterialTheme.shapes.large
    val ink = if (isPaperTheme()) PaperTokens.ink else colors.inverseOnSurface
    var contextAt by remember { mutableStateOf<Offset?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(modifier.onSecondaryPress { contextAt = it }) {
        Box(
            Modifier.fillMaxWidth().height(art + 44.dp).paperFrame(shape, { background(tint) }, fill = tint)
                .then(if (isSelected(row)) Modifier.border(3.dp, colors.primary, paperSquare(shape)) else Modifier)
                .clickable(interactionSource = interaction, indication = PressScaleIndication, onClick = onClick)
                .pointerHoverIcon(PointerIcon.Hand),
        ) {
            Row(Modifier.fillMaxSize().padding(horizontal = if (compact) 16.dp else 22.dp), verticalAlignment = Alignment.CenterVertically) {
                if (isLiked && row.art.isBlank()) Box(
                    Modifier.size(art).paperClip(MaterialTheme.shapes.medium)
                        .background(Brush.linearGradient(listOf(colors.primary.copy(alpha = 0.85f), colors.tertiary.copy(alpha = 0.85f)))),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Favorite, null, tint = colors.onPrimary, modifier = Modifier.size(art * 0.4f)) }
                else Artwork(row.art, row.accent, Modifier.size(art), corner = if (row.circle) art else 18.dp)
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Eyebrow(label, soft)
                    Spacer(Modifier.height(4.dp))
                    Text(row.title, style = if (isLiked && !compact) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black, maxLines = if (isLiked) 1 else 2, overflow = TextOverflow.Ellipsis)
                    if (row.subtitle.isNotBlank()) Text(row.subtitle, style = MaterialTheme.typography.bodySmall, color = soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (isLiked) {
                    Spacer(Modifier.width(12.dp))
                    Box(
                        Modifier.size(56.dp).paperFrame(CircleShape, { background(colors.inverseSurface) }, fill = PaperTokens.rose)
                            .clickable(onClickLabel = appString(R.string.text_shuffle_5b772b)) { actions.onShuffle(row) }.pointerHoverIcon(PointerIcon.Hand),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.Shuffle, appString(R.string.text_shuffle_5b772b), tint = ink, modifier = Modifier.size(24.dp)) }
                }
            }
            if (hovered || menuOpen) Row(
                Modifier.align(if (isLiked) Alignment.TopEnd else Alignment.BottomEnd).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    Box(
                        Modifier.size(32.dp).paperClip(CircleShape).background(Color.Black.copy(alpha = 0.45f))
                            .clickable(onClickLabel = appString(R.string.text_more_4bab2d)) { menuOpen = true }.pointerHoverIcon(PointerIcon.Hand),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.MoreVert, appString(R.string.text_more_4bab2d), tint = Color.White, modifier = Modifier.size(18.dp)) }
                    CollectionMenu(row, actions, expanded = menuOpen, onDismiss = { menuOpen = false })
                }
                if (!isLiked) Box(
                    Modifier.size(40.dp).paperClip(CircleShape).background(colors.primary)
                        .clickable(onClickLabel = appString(R.string.text_play_5d12bd)) { actions.onPlay(row) }.pointerHoverIcon(PointerIcon.Hand),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.PlayArrow, appString(R.string.text_play_5d12bd), tint = colors.onPrimary, modifier = Modifier.size(22.dp)) }
            }
        }
        contextAt?.let { at ->
            Box(Modifier.offset { at.round() }) { ContextMenu(row, actions, onDismiss = { contextAt = null }) }
        }
    }
}
