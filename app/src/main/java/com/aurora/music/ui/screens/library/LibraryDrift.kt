package com.aurora.music.ui.screens.library

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aurora.music.R
import com.aurora.music.data.Pin
import com.aurora.music.data.PlaylistFolder
import com.aurora.music.localization.appPlural
import com.aurora.music.localization.appString
import com.aurora.music.model.Album
import com.aurora.music.model.LibraryFilter
import com.aurora.music.model.LibrarySort
import com.aurora.music.model.label
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.layout.LocalWindowLayout
import com.aurora.music.ui.screens.home.ConsoleLabel
import com.aurora.music.ui.screens.home.DriftForm
import com.aurora.music.ui.screens.home.DriftGutter
import com.aurora.music.ui.screens.home.DriftHeading
import com.aurora.music.ui.screens.home.DriftShelf
import com.aurora.music.ui.screens.home.DriftTab
import com.aurora.music.ui.screens.home.DriftTile
import com.aurora.music.ui.screens.home.DriftTitle
import com.aurora.music.ui.screens.home.FanStack
import com.aurora.music.ui.screens.home.IconPill
import com.aurora.music.ui.screens.home.rememberEntrance
import com.aurora.music.ui.screens.home.rise
import com.aurora.music.ui.screens.home.spotSurface
import com.aurora.music.ui.theme.PaperTokens
import com.aurora.music.ui.theme.PressScaleIndication
import com.aurora.music.ui.theme.isGlassTheme
import com.aurora.music.ui.theme.isPaperTheme
import com.aurora.music.ui.theme.paperClip
import com.aurora.music.ui.theme.paperFrame
import com.aurora.music.ui.theme.paperSquare
import com.aurora.music.ui.theme.pressClickable
import com.aurora.music.util.accentFor
import com.aurora.music.viewmodel.LibraryUiState

private const val WALL_ROWS = 4

@Composable
internal fun LibraryDriftHeader(
    state: LibraryUiState,
    username: String,
    source: String,
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
    val clock = rememberEntrance()
    Row(Modifier.fillMaxWidth().padding(start = DriftGutter, end = DriftGutter, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ConsoleLabel(state.views.firstOrNull { it.id == state.view }?.label ?: source, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        IconPill(Icons.Filled.Search, appString(R.string.text_search_bce064), onOpenSearch)
        if (state.signedIn) {
            Spacer(Modifier.width(8.dp))
            val create = appString(R.string.text_create_playlist_62c988)
            if (isGlassTheme()) IconPill(Icons.Filled.Add, create, onCreate)
            else Box(
                Modifier.size(40.dp).paperFrame(CircleShape, { background(colors.inverseSurface) }, fill = PaperTokens.rose).clickable(onClick = onCreate),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Add, create, tint = if (isPaperTheme()) PaperTokens.ink else colors.inverseOnSurface, modifier = Modifier.size(22.dp)) }
        }
        Spacer(Modifier.width(8.dp))
        LibraryAvatar(username, onOpenDrawer)
    }
    DriftTitle(appString(R.string.text_library_b8100f), if (LocalWindowLayout.current.useNavigationRail) 1.6f else 1.2f,
        Modifier.padding(start = DriftGutter, end = DriftGutter, top = 10.dp), clock)
    if (stats.isNotBlank()) {
        Text(stats, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = DriftGutter, end = DriftGutter, top = 8.dp).rise(clock, 1))
    }
    if (state.views.size > 1) LibraryViewPills(state, DriftGutter, onCycleView)
    Row(
        Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()).padding(horizontal = DriftGutter),
        horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            DriftTab(tab.label, tab == filter, onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onCustomizeTabs()
            }) { onFilter(tab) }
        }
        Icon(Icons.Filled.Tune, appString(R.string.library_tabs_customize), tint = colors.onSurfaceVariant,
            modifier = Modifier.size(40.dp).paperClip(CircleShape).clickable(onClick = onCustomizeTabs).padding(10.dp))
    }
}

@Composable
internal fun LibraryDriftAll(
    state: LibraryUiState,
    pins: List<Pin>,
    bottom: Dp,
    tabs: List<LibraryFilter>,
    tree: PlaylistFolder?,
    actions: LibActions,
    tiles: List<QuickTile>,
    onFilter: (LibraryFilter) -> Unit,
    onOpenDetail: (String, String) -> Unit,
    onOpenMix: () -> Unit,
    onOpenFolder: ((String) -> Unit)?,
    onSignIn: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val folders = tree.takeIf { onOpenFolder != null }
    // the playlists tab's own top level, without the sort and the liked row it adds
    val playlists = remember(state.playlists, state.smartPlaylists, folders) {
        buildRows(state, LibraryFilter.PLAYLISTS, LibrarySort.RECENT, emptyList(), folders).filterNot { it.kind == "liked" }
    }
    val strip = buildList {
        // 0 doubles as not counted yet, so no number rather than a wrong 0
        if (state.signedIn) add(QuickTile(appString(R.string.text_liked_songs_58c3a9), if (state.likedSongCount > 0) appPlural(R.plurals.track_count, state.likedSongCount) else "",
            Icons.Filled.Favorite, state.likedCover, onLongClick = actions.onOpenAllLiked, menu = actions.likedPicker) { onOpenDetail("liked", state.likedId) })
        addAll(tiles)
        add(QuickTile(appString(R.string.text_mix_studio_668363), "", Icons.Filled.Apps, "", onClick = onOpenMix))
    }
    fun opening(tab: LibraryFilter): (() -> Unit)? = if (tab in tabs) ({ onFilter(tab) }) else null
    val clock = rememberEntrance()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 960.dp
        val columns = ((maxWidth - DriftGutter * 2 + 8.dp) / 158.dp).toInt().coerceIn(3, 8)
        // shortcut: playlists and albums preview their first rows and the heading opens the whole tab; make them lazy items before showing more
        val crates = playlists.take(if (wide) 6 else 4)
        val wall = state.albums.take(1 + (columns - 2) * 2 + columns * (WALL_ROWS - 2))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom), verticalArrangement = Arrangement.spacedBy(34.dp)) {
            var block = 0
            fun entering(): Modifier = if (block < 3) Modifier.rise(clock, block++) else Modifier

            val first = entering()
            item(key = "collections") {
                LazyRow(first, contentPadding = PaddingValues(start = DriftGutter - 4.dp, end = DriftGutter - 4.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(strip.size) { i -> CollectionDisc(strip[i]) }
                }
            }
            if (pins.isNotEmpty()) {
                val enter = entering()
                item(key = "pinned") {
                    Box(enter) {
                        DriftShelf(appString(R.string.text_pinned_f93121)) {
                            items(pins.size) { i ->
                                val pin = pins[i]
                                DriftTile(pin.title, pin.subtitle, pin.coverUrl, accentFor(pin.id),
                                    when (pin.kind) { "artist" -> DriftForm.CIRCLE; "album" -> DriftForm.COVER; else -> DriftForm.STACK }, clock) { onOpenDetail(pin.kind, pin.id) }
                            }
                        }
                    }
                }
            }
            if (playlists.isEmpty() && state.albums.isEmpty() && state.artists.isEmpty() && state.songs.isEmpty()) {
                val enter = entering()
                item(key = "empty") {
                    val guest = !state.signedIn
                    Column(enter.padding(start = DriftGutter, end = DriftGutter, top = 10.dp).widthIn(max = 620.dp)) {
                        DriftTitle(appString(R.string.text_nothing_here_yet_e89225), 0.85f)
                        Text(appString(if (guest) R.string.guest_detail else R.string.home_empty_body), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp))
                        Box(
                            Modifier.padding(top = 20.dp).height(50.dp).paperFrame(CircleShape, { background(colors.inverseSurface) }, fill = PaperTokens.rose)
                                .pressClickable(role = Role.Button, onClick = onSignIn).padding(horizontal = 28.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(appString(if (guest) R.string.guest_sign_in else R.string.home_add_source), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                                color = if (isPaperTheme()) PaperTokens.ink else colors.inverseOnSurface, maxLines = 1)
                        }
                    }
                }
            }
            if (crates.isNotEmpty()) {
                val enter = entering()
                item(key = "playlists") {
                    Column(enter.padding(horizontal = DriftGutter - 8.dp)) {
                        DriftHeading(LibraryFilter.PLAYLISTS.label, Modifier.padding(horizontal = 8.dp), (state.playlists.size + state.smartPlaylists.size).toString(), opening(LibraryFilter.PLAYLISTS))
                        crates.chunked(if (wide) 2 else 1).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                pair.forEach { row ->
                                    Crate(row, actions, clock, Modifier.weight(1f)) { if (row.kind == "folder") onOpenFolder?.invoke(row.id) else onOpenDetail(row.kind, row.id) }
                                }
                                if (wide && pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            if (wall.isNotEmpty()) {
                val enter = entering()
                item(key = "albums") {
                    Column(enter.padding(horizontal = DriftGutter)) {
                        DriftHeading(LibraryFilter.ALBUMS.label, note = state.albums.size.toString(), onClick = opening(LibraryFilter.ALBUMS))
                        AlbumWall(wall, columns, actions, Modifier.padding(top = 12.dp)) { onOpenDetail("album", it.id) }
                    }
                }
            }
            if (state.artists.isNotEmpty()) {
                val enter = entering()
                item(key = "artists") {
                    Box(enter) {
                        DriftShelf(LibraryFilter.ARTISTS.label, state.artists.size.toString(), opening(LibraryFilter.ARTISTS)) {
                            items(state.artists.size) { i ->
                                val artist = state.artists[i]
                                DriftTile(artist.name, "", artist.imageUrl, accentFor(artist.id), DriftForm.CIRCLE, clock) { onOpenDetail("artist", artist.id) }
                            }
                        }
                    }
                }
            }
        }
    }
}

// the label takes a second line and a little more width before it truncates, so the two download tiles stay apart
@Composable
private fun CollectionDisc(tile: QuickTile) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.widthIn(min = 76.dp, max = 104.dp).paperClip(RoundedCornerShape(14.dp))
            .combinedClickable(interactionSource = null, indication = PressScaleIndication, onClick = tile.onClick, onLongClick = tile.onLongClick,
                onLongClickLabel = if (tile.onLongClick != null) appString(R.string.liked_sources_choose) else null)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        tile.menu?.invoke()
        Box(Modifier.size(68.dp).spotSurface(paperSquare(CircleShape), colors.surface, outlined = true), contentAlignment = Alignment.Center) {
            Icon(tile.icon, null, tint = colors.primary, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(tile.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

// a long press opens the menu classic keeps behind a row's more button
@Composable
private fun MenuOnHold(row: LibRow, actions: LibActions, modifier: Modifier = Modifier, content: @Composable (hold: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Box(modifier) {
        content {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            open = true
        }
        CollectionMenu(row, actions, open) { open = false }
    }
}

@Composable
private fun Crate(row: LibRow, actions: LibActions, clock: Animatable<Float, AnimationVector1D>, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    MenuOnHold(row, actions, modifier) { hold ->
        Row(
            Modifier.fillMaxWidth().paperClip(RoundedCornerShape(14.dp))
                .combinedClickable(interactionSource = null, indication = PressScaleIndication, onLongClick = hold, onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FanStack(row.art, row.accent, 84.dp, clock, mark = row.icon)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(row.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(row.subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

// the first album takes a 2x2 cell and the others fill the grid beside and below it
@Composable
private fun AlbumWall(albums: List<Album>, columns: Int, actions: LibActions, modifier: Modifier, onOpen: (Album) -> Unit) {
    val label = appString(R.string.text_album_dfb4c9)
    Layout(content = { albums.forEachIndexed { i, album -> WallCover(album, i == 0, label, actions) { onOpen(album) } } }, modifier = modifier) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        val cell = (constraints.maxWidth - gap * (columns - 1)) / columns
        val step = cell + gap
        val placeables = measurables.mapIndexed { i, measurable -> (if (i == 0) cell + step else cell).let { measurable.measure(Constraints.fixed(it, it)) } }
        val below = (placeables.size - 1 - (columns - 2) * 2).coerceAtLeast(0)
        layout(constraints.maxWidth, (2 + (below + columns - 1) / columns) * step - gap) {
            var slot = 0
            placeables.forEachIndexed { i, placeable ->
                if (i > 0) while (slot / columns < 2 && slot % columns < 2) slot++
                placeable.placeRelative(slot % columns * step, slot / columns * step)
                slot++
            }
        }
    }
}

@Composable
private fun WallCover(album: Album, big: Boolean, albumLabel: String, actions: LibActions, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val row = remember(album, albumLabel) { albumRow(album, albumLabel) }
    MenuOnHold(row, actions) { hold ->
        Box(
            Modifier.fillMaxSize().paperClip(MaterialTheme.shapes.medium)
                .then(if (isPaperTheme()) Modifier.border(1.dp, PaperTokens.ink) else Modifier)
                .semantics { contentDescription = "${album.title}, ${album.artist}" }
                .combinedClickable(interactionSource = null, indication = PressScaleIndication, onLongClick = hold, onClick = onClick),
        ) {
            Artwork(album.artworkUrl, row.accent, Modifier.matchParentSize(), corner = 0.dp)
            if (big) Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(colors.background.copy(alpha = 0.8f)).padding(horizontal = 14.dp, vertical = 10.dp).clearAndSetSemantics {}) {
                Text(album.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(album.artist, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
