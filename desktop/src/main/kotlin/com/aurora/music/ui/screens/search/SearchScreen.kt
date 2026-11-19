package com.aurora.music.ui.screens.search

import com.aurora.music.localization.appString
import com.aurora.music.ui.theme.paperClip
import com.aurora.music.R

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.GraphicEq
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.aurora.music.data.PageLayout
import com.aurora.music.data.SearchResults
import com.aurora.music.localization.appPlural
import com.aurora.music.localization.localizedMediaType
import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import com.aurora.music.model.accent
import com.aurora.music.ui.components.AdaptiveShelf
import com.aurora.music.ui.components.AlbumCard
import com.aurora.music.ui.components.ArtistCircle
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.EmptyState
import com.aurora.music.ui.components.LottieLoader
import com.aurora.music.ui.components.PageHeader
import com.aurora.music.ui.components.PageSection
import com.aurora.music.ui.components.PlaylistCard
import com.aurora.music.ui.components.SectionHeader
import com.aurora.music.ui.components.SongListHeader
import com.aurora.music.ui.components.SongRow
import com.aurora.music.ui.components.shelfColumns
import com.aurora.music.ui.components.shelfItemWidth
import com.aurora.music.ui.layout.LocalPageGutter
import com.aurora.music.ui.layout.PageMetrics
import com.aurora.music.ui.layout.pagePadding
import com.aurora.music.ui.theme.LocalUiPrefs
import com.aurora.music.ui.theme.Motion
import com.aurora.music.ui.theme.auroraPanel
import com.aurora.music.ui.theme.isPaperTheme
import com.aurora.music.ui.theme.pressClickable
import com.aurora.music.util.accentFor
import com.aurora.music.viewmodel.SearchUiState
import kotlin.math.roundToInt
import com.aurora.music.ui.theme.themedCorner

internal enum class Pane { ERROR, HINT, RECENTS, LOADING, NONE, RESULTS }

// a pane carries its own data so the one fading out keeps what it showed
internal class SearchPane(val kind: Pane, val state: SearchUiState, val recents: List<String>)

internal enum class SearchFilter(private val labelRes: Int) {
    ALL(R.string.text_all_6a7208), SONGS(R.string.text_songs_e1404b), ALBUMS(R.string.text_albums_4c45e7), ARTISTS(R.string.text_artists_1528d8), PLAYLISTS(R.string.text_playlists_77b69f);
    val label: String get() = appString(labelRes)

    fun count(r: SearchResults): Int = when (this) {
        ALL -> r.songs.size + r.albums.size + r.artists.size + r.playlists.size
        SONGS -> r.songs.size
        ALBUMS -> r.albums.size
        ARTISTS -> r.artists.size
        PLAYLISTS -> r.playlists.size
    }
}

internal fun filtersFor(r: SearchResults): List<SearchFilter> = SearchFilter.entries.filter { it == SearchFilter.ALL || it.count(r) > 0 }

private val WideResults = 1000.dp
private val TopCardMinWidth = 320.dp
private val TopCardMaxWidth = 480.dp
private val TopCardMinHeight = 240.dp
private const val TopSongs = 4
private const val NarrowSongs = 5

internal class TopHit(
    val kind: String,
    val id: String,
    val title: String,
    val type: String,
    val subtitle: String,
    val art: String,
    val accent: Color,
    val round: Boolean,
    val songIndex: Int = -1,
    val counts: List<String> = emptyList(),
) {
    val line: String get() = (listOf(subtitle) + counts).filter { it.isNotBlank() }.joinToString(" · ")
}

private fun Artist.counts(r: SearchResults): List<String> = listOfNotNull(
    r.songs.count { it.artistId == id }.takeIf { it > 0 }?.let { appPlural(R.plurals.track_count, it) },
    r.albums.count { it.artist.equals(name, ignoreCase = true) }.takeIf { it > 0 }?.let { appPlural(R.plurals.album_count, it) },
)

internal fun Artist.hit(counts: List<String> = emptyList()) =
    TopHit("artist", id, name, appString(R.string.text_artist_6c3f3d), "", imageUrl, accentFor(id), round = true, counts = counts)

internal fun Album.hit() = TopHit("album", id, title, typeLabel.localizedMediaType(), artist, artworkUrl, accentFor(id), round = false)

internal fun Playlist.hit() = TopHit("playlist", id, title, appString(R.string.text_playlist_cd95b4), subtitle, coverUrl, accent, round = false)

internal fun topHit(query: String, r: SearchResults): TopHit? {
    val q = query.trim().lowercase()
    fun score(name: String): Int {
        val n = name.trim().lowercase()
        return when {
            q.isEmpty() -> 0
            n == q -> 3
            n.startsWith(q) -> 2
            n.split(' ').any { it.startsWith(q) } -> 1
            else -> 0
        }
    }
    val hits = buildList {
        r.artists.firstOrNull()?.let { a: Artist ->
            add(score(a.name) to a.hit(a.counts(r)))
        }
        r.albums.firstOrNull()?.let { a: Album ->
            add(score(a.title) to a.hit())
        }
        r.songs.firstOrNull()?.let { s: Song ->
            add(score(s.title) to TopHit("song", s.id, s.title, appString(R.string.text_song_bd1189), s.artist, s.artworkUrl, s.accent, round = false, songIndex = 0))
        }
        r.playlists.firstOrNull()?.let { p: Playlist ->
            add(score(p.title) to p.hit())
        }
    }
    return hits.maxByOrNull { it.first }?.second
}

internal class SearchParts(
    val state: SearchUiState,
    val pane: SearchPane,
    // the results a layout's frame was drawn from, kept through the loading between two keystrokes
    val held: SearchPane?,
    val filter: SearchFilter,
    val jumpBackIn: List<Album>,
    val contentPadding: PaddingValues,
    val focus: FocusRequester,
    val likedIds: Set<String>,
    val currentSongId: String,
    val isPlaying: Boolean,
    val downloadedIds: Set<String>,
    val canDownload: Boolean,
    val onQuery: (String) -> Unit,
    val onCommit: () -> Unit,
    val onFilter: (SearchFilter) -> Unit,
    val onSelectSource: (String) -> Unit,
    val onRecentClick: (String) -> Unit,
    val onRemoveRecent: (String) -> Unit,
    val onClearRecents: () -> Unit,
    val onPlayAll: (List<Song>, Int) -> Unit,
    val onOpenDetail: (String, String) -> Unit,
    val onJumpBack: (String, String) -> Unit,
    val onPlayCollection: ((String, String, Boolean) -> Unit)?,
    val onAddToQueue: (Song) -> Unit,
    val onPlayNext: (Song) -> Unit,
    val onToggleLike: (String) -> Unit,
    val onDownload: (Song) -> Unit,
    val onRemoveDownload: (String) -> Unit,
) {
    // fall back to ALL when chosen type has no results
    fun filterFor(r: SearchResults): SearchFilter = if (filter.count(r) > 0) filter else SearchFilter.ALL

    fun open(hit: TopHit, songs: List<Song>) = if (hit.songIndex >= 0) onPlayAll(songs, hit.songIndex) else onOpenDetail(hit.kind, hit.id)

    fun play(hit: TopHit, songs: List<Song>, shuffle: Boolean = false): (() -> Unit)? = when {
        hit.songIndex < 0 -> onPlayCollection?.let { play -> { play(hit.kind, hit.id, shuffle) } }
        shuffle -> null
        else -> ({ onPlayAll(songs, hit.songIndex) })
    }
}

@Composable
fun SearchScreen(
    contentPadding: PaddingValues,
    state: SearchUiState,
    likedIds: Set<String>,
    currentSongId: String,
    isPlaying: Boolean,
    onQuery: (String) -> Unit,
    onPlayAll: (List<Song>, Int) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onToggleLike: (String) -> Unit,
    onOpenDetail: (String, String) -> Unit,
    downloadedIds: Set<String>,
    onDownload: (Song) -> Unit,
    onRemoveDownload: (String) -> Unit,
    canDownload: Boolean = true,
    recentSearches: List<String> = emptyList(),
    onRecentClick: (String) -> Unit = {},
    onRemoveRecent: (String) -> Unit = {},
    onClearRecents: () -> Unit = {},
    onCommitSearch: () -> Unit = {},
    onSelectSource: (String) -> Unit = {},
    jumpBackIn: List<Album> = emptyList(),
    onPlayCollection: ((kind: String, id: String, shuffle: Boolean) -> Unit)? = null,
) {
    val results = state.results
    var filter by rememberSaveable { mutableStateOf(SearchFilter.ALL) }
    var filterQuery by rememberSaveable { mutableStateOf(state.query) }
    // reset filter on new query so it can't strand on an empty section
    LaunchedEffect(state.query) {
        if (filterQuery != state.query) { filterQuery = state.query; filter = SearchFilter.ALL }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val commitAnd: (() -> Unit) -> Unit = { action -> onCommitSearch(); action() }
    val gutter = LocalPageGutter.current
    val empty = results.songs.isEmpty() && results.albums.isEmpty() && results.artists.isEmpty() && results.playlists.isEmpty()
    val kind = when {
        state.error != null -> Pane.ERROR
        state.query.isBlank() -> if (recentSearches.isEmpty()) Pane.HINT else Pane.RECENTS
        state.loading && empty -> Pane.LOADING
        empty -> Pane.NONE
        else -> Pane.RESULTS
    }
    val pane = remember(kind, state, recentSearches) { SearchPane(kind, state, recentSearches) }
    val held = remember { arrayOfNulls<SearchPane>(1) }
    if (kind == Pane.RESULTS) held[0] = pane else if (kind != Pane.LOADING) held[0] = null
    val parts = SearchParts(
        state, pane, held[0], filter, jumpBackIn, contentPadding, focus, likedIds, currentSongId, isPlaying, downloadedIds, canDownload,
        onQuery = onQuery, onCommit = onCommitSearch, onFilter = { filter = it }, onSelectSource = onSelectSource,
        onRecentClick = onRecentClick, onRemoveRecent = onRemoveRecent, onClearRecents = onClearRecents,
        onPlayAll = { songs, i -> commitAnd { onPlayAll(songs, i) } },
        onOpenDetail = { k, id -> commitAnd { onOpenDetail(k, id) } },
        onJumpBack = onOpenDetail,
        onPlayCollection = onPlayCollection?.let { play -> { k, id, shuffle -> commitAnd { play(k, id, shuffle) } } },
        onAddToQueue = onAddToQueue, onPlayNext = onPlayNext, onToggleLike = onToggleLike, onDownload = onDownload, onRemoveDownload = onRemoveDownload,
    )
    when (LocalUiPrefs.current.searchLayout) {
        PageLayout.SPOTLIGHT -> return SearchSpotlight(parts)
        PageLayout.CONSOLE -> return SearchConsole(parts)
        PageLayout.DRIFT -> return SearchDrift(parts)
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader(appString(R.string.text_search_bce064), Modifier.padding(horizontal = gutter))
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = gutter), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = state.query,
                onValueChange = onQuery,
                modifier = Modifier.weight(1f, fill = false).widthIn(max = PageMetrics.SearchFieldMaxWidth).fillMaxWidth().focusRequester(focus),
                placeholder = { Text(appString(R.string.text_artists_albums_or_songs_d1863c)) },
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.primary) },
                trailingIcon = if (state.query.isEmpty()) null else ({
                    Icon(Icons.Filled.Close, appString(R.string.text_clear_719ea3), modifier = Modifier.size(36.dp).paperClip(CircleShape)
                        .clickable { onQuery("") }.pointerHoverIcon(PointerIcon.Hand).padding(7.dp))
                }),
                singleLine = true,
                shape = com.aurora.music.ui.theme.paperSquare(RoundedCornerShape(themedCorner(16.dp))),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onCommitSearch() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
            )
            if (state.sources.size > 1) {
                Spacer(Modifier.width(12.dp))
                SourcePicker(state, onSelectSource)
            }
        }

        Spacer(Modifier.height(16.dp))

        SearchPanes(pane) { shown ->
        when (shown.kind) {
            Pane.RECENTS -> RecentSearches(shown.recents, onRecentClick, onRemoveRecent, onClearRecents, contentPadding)
            Pane.RESULTS -> Column {
                val found = shown.state.results
                val effective = parts.filterFor(found)
                FilterChips(effective, found) { filter = it }
                Results(effective, shown.state.query, found, parts)
            }
            else -> SearchNotice(shown, Modifier.fillMaxSize())
        }
        }
    }
}

@Composable
internal fun SearchPanes(pane: SearchPane, modifier: Modifier = Modifier, content: @Composable (SearchPane) -> Unit) = AnimatedContent(
    targetState = pane,
    modifier = modifier,
    transitionSpec = { fadeIn(Motion.enter()) togetherWith fadeOut(Motion.exit()) using null },
    contentKey = { it.kind },
    label = "searchPane",
) { content(it) }

@Composable
internal fun SearchNotice(pane: SearchPane, modifier: Modifier = Modifier) = when (pane.kind) {
    Pane.ERROR -> EmptyState(Icons.Outlined.ErrorOutline, appString(R.string.text_search_unavailable_c32b9f), modifier, subtitle = pane.state.error)
    Pane.LOADING ->
        Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
            LottieLoader(modifier = Modifier.size(72.dp))
        }
    Pane.NONE -> EmptyState(Icons.Outlined.SearchOff, appString(R.string.text_no_results_b993b0), modifier, subtitle = appString(R.string.text_nothing_matched_f74d36, (pane.state.query)))
    else -> EmptyState(Icons.Filled.Search, appString(R.string.text_search_your_library_d9fa3f), modifier, subtitle = appString(R.string.text_find_any_artist_album_song_or_playlist_9fcb23))
}

@Composable
internal fun SearchBody(p: SearchParts, modifier: Modifier = Modifier, idle: @Composable (SearchPane) -> Unit, results: @Composable (SearchPane) -> Unit) =
    SearchPanes(p.pane, modifier) { shown ->
        when (shown.kind) {
            Pane.RESULTS -> results(shown)
            Pane.HINT, Pane.RECENTS -> idle(shown)
            else -> SearchNotice(shown, Modifier.fillMaxSize())
        }
    }

@Composable
internal fun SearchInput(
    p: SearchParts,
    style: TextStyle,
    modifier: Modifier = Modifier,
    hint: String = appString(R.string.text_artists_albums_or_songs_d1863c),
    hintColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    caret: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val square = isPaperTheme()
    val query = p.state.query
    var own by remember { mutableStateOf(TextFieldValue(query, TextRange(query.length))) }
    // the query last seen and the text last typed here: only a query from elsewhere, a recent search or a clear, replaces the field's own
    val told = remember { arrayOf(query, query) }
    if (query != told[0]) {
        told[0] = query
        if (query != told[1]) {
            told[1] = query
            own = TextFieldValue(query, TextRange(query.length))
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var hintWidth by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { runCatching { p.focus.requestFocus() } }
    BasicTextField(
        value = own,
        onValueChange = {
            own = it
            if (it.text != told[1]) {
                told[1] = it.text
                p.onQuery(it.text)
            }
        },
        modifier = modifier.focusRequester(p.focus),
        textStyle = style.copy(color = colors.onSurface),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { p.onCommit() }),
        onTextLayout = { layout = it },
        cursorBrush = SolidColor(if (caret) Color.Unspecified else colors.primary),
        decorationBox = { field ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (own.text.isEmpty()) Text(hint, style = style, color = hintColor, maxLines = 1, overflow = TextOverflow.Ellipsis, onTextLayout = { hintWidth = it.size.width })
                Box(Modifier.drawWithContent {
                    drawContent()
                    if (!caret || !own.selection.collapsed) return@drawWithContent
                    val rtl = layoutDirection == LayoutDirection.Rtl
                    val at = own.selection.end
                    val edge = if (own.text.isEmpty()) (if (rtl) size.width - hintWidth else hintWidth.toFloat())
                    else layout?.takeIf { at <= it.layoutInput.text.length }?.getCursorRect(at)?.left ?: return@drawWithContent
                    val em = style.fontSize.toPx()
                    val gap = if (at == own.text.length) em * 0.08f else 0f
                    val bar = Size(maxOf(3.dp.toPx(), em * 0.05f), em * 0.8f)
                    drawRoundRect(colors.primary, Offset(if (rtl) edge - gap - bar.width else edge + gap, (size.height - bar.height) / 2f), bar,
                        if (square) CornerRadius.Zero else CornerRadius(bar.width / 2f))
                }) { field() }
            }
        },
    )
}

@Composable
internal fun SearchParts.Track(
    songs: List<Song>,
    i: Int,
    index: Int? = null,
    showArt: Boolean = true,
    lead: (@Composable () -> Unit)? = null,
    badge: String? = null,
    titleStyle: TextStyle? = null,
    tableMinWidth: Dp = PageMetrics.SongTableMinWidth,
) {
    val song = songs[i]
    SongRow(
        song = song,
        isPlaying = song.id == currentSongId && isPlaying,
        isLiked = likedIds.contains(song.id),
        onClick = { onPlayAll(songs, i) },
        onToggleLike = { onToggleLike(song.id) },
        onAddToQueue = { onAddToQueue(song) },
        onPlayNext = { onPlayNext(song) },
        onGoToAlbum = if (song.albumId.isNotBlank()) ({ onOpenDetail("album", song.albumId) }) else null,
        onGoToArtist = if (song.artistId.isNotBlank()) ({ onOpenDetail("artist", song.artistId) }) else null,
        isDownloaded = canDownload && downloadedIds.contains(song.id),
        onDownload = if (canDownload) ({ onDownload(song) }) else null,
        onRemoveDownload = if (canDownload) ({ onRemoveDownload(song.id) }) else null,
        index = index, showArt = showArt, lead = lead, badge = badge, titleStyle = titleStyle, tableMinWidth = tableMinWidth,
    )
}

internal fun sourceIcon(id: String): ImageVector = when (id) {
    "library" -> Icons.Outlined.LibraryMusic
    "discovery" -> Icons.Outlined.PlayCircle
    "spotify" -> Icons.Outlined.GraphicEq
    "soundcloud" -> Icons.Outlined.Cloud
    "apple" -> Icons.Outlined.Album
    else -> Icons.Outlined.MusicNote
}

@Composable
private fun SourcePicker(state: SearchUiState, onSelectSource: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.sources.forEach { source ->
            val selected = source.id == state.selectedSource
            val fill by animateColorAsState(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, Motion.state(), label = "sourceFill")
            val tint by animateColorAsState(if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, Motion.state(), label = "sourceTint")
            Box(
                Modifier.size(40.dp).paperClip(CircleShape)
                    .background(fill)
                    .selectable(selected = selected, role = Role.RadioButton) { onSelectSource(source.id) }
                    .pointerHoverIcon(PointerIcon.Hand),
                contentAlignment = Alignment.Center,
            ) {
                Icon(sourceIcon(source.id), source.label,
                    tint = tint,
                    modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun FilterChips(selected: SearchFilter, results: SearchResults, onSelect: (SearchFilter) -> Unit) {
    val available = filtersFor(results)
    LazyRow(
        contentPadding = PaddingValues(horizontal = LocalPageGutter.current),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(available.size) { i ->
            val f = available[i]
            val on = f == selected
            val fill by animateColorAsState(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh, Motion.state(), label = "chipFill")
            val ink by animateColorAsState(if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, Motion.state(), label = "chipInk")
            Text(
                f.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = ink,
                maxLines = 1,
                modifier = Modifier.paperClip(RoundedCornerShape(50))
                    .background(fill)
                    .clickable { onSelect(f) }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun Results(filter: SearchFilter, query: String, results: SearchResults, p: SearchParts) {
    val onFilter = p.onFilter
    val onPlayAll = p.onPlayAll
    val onOpenDetail = p.onOpenDetail
    val gutter = LocalPageGutter.current
    val listState = rememberLazyListState()
    val songs = results.songs
    val seeAll = appString(R.string.text_see_all_2941c5)
    val songRow: @Composable (Int) -> Unit = { i -> p.Track(songs, i) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth - gutter * 2
        val gap = when (filter) {
            SearchFilter.ALL -> PageMetrics.SectionGap
            SearchFilter.SONGS -> 0.dp
            else -> 24.dp
        }
        val shelfFits = shelfColumns(width)
        fun more(count: Int) = seeAll.takeIf { count > shelfFits }
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = pagePadding(p.contentPadding, top = 24.dp),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            when (filter) {
                SearchFilter.ALL -> {
                    val hit = if (width >= WideResults && songs.isNotEmpty()) topHit(query, results) else null
                    if (hit != null) {
                        item(key = "top") {
                            TopRow(
                                card = {
                                    TopResultCard(
                                        hit,
                                        onOpen = { if (hit.songIndex >= 0) onPlayAll(songs, hit.songIndex) else onOpenDetail(hit.kind, hit.id) },
                                        onPlay = if (hit.songIndex >= 0) ({ onPlayAll(songs, hit.songIndex) }) else null,
                                    )
                                },
                                list = {
                                    Column {
                                        SectionHeader(appString(R.string.text_songs_e1404b),
                                            action = seeAll.takeIf { songs.size > TopSongs }, onAction = { onFilter(SearchFilter.SONGS) })
                                        Spacer(Modifier.height(PageMetrics.HeaderToContent))
                                        repeat(songs.size.coerceAtMost(TopSongs)) { songRow(it) }
                                    }
                                },
                            )
                        }
                    } else if (songs.isNotEmpty()) {
                        item(key = "songs") {
                            PageSection(appString(R.string.text_songs_e1404b),
                                action = seeAll.takeIf { songs.size > NarrowSongs }, onAction = { onFilter(SearchFilter.SONGS) }) {
                                repeat(songs.size.coerceAtMost(NarrowSongs)) { songRow(it) }
                            }
                        }
                    }
                    if (results.artists.isNotEmpty()) item(key = "artists") {
                        PageSection(appString(R.string.text_artists_1528d8), action = more(results.artists.size), onAction = { onFilter(SearchFilter.ARTISTS) }) {
                            AdaptiveShelf(results.artists, bleed = gutter) { artist, w ->
                                ArtistCircle(artist, onClick = { onOpenDetail("artist", artist.id) }, width = w)
                            }
                        }
                    }
                    if (results.albums.isNotEmpty()) item(key = "albums") {
                        PageSection(appString(R.string.text_albums_4c45e7), action = more(results.albums.size), onAction = { onFilter(SearchFilter.ALBUMS) }) {
                            AdaptiveShelf(results.albums, bleed = gutter) { album, w ->
                                AlbumCard(album, onClick = { onOpenDetail("album", album.id) }, width = w)
                            }
                        }
                    }
                    if (results.playlists.isNotEmpty()) item(key = "playlists") {
                        PageSection(appString(R.string.text_playlists_77b69f), action = more(results.playlists.size), onAction = { onFilter(SearchFilter.PLAYLISTS) }) {
                            AdaptiveShelf(results.playlists, bleed = gutter) { playlist, w ->
                                PlaylistCard(playlist, onClick = { onOpenDetail("playlist", playlist.id) }, width = w)
                            }
                        }
                    }
                }
                else -> oneKind(filter, results, width, p)
            }
        }
    }
}

internal fun LazyListScope.oneKind(filter: SearchFilter, results: SearchResults, width: Dp, p: SearchParts, numbered: Boolean = false, tableMinWidth: Dp = PageMetrics.SongTableMinWidth) {
    when (filter) {
        SearchFilter.ALBUMS -> grid(results.albums, width) { album, w ->
            AlbumCard(album, onClick = { p.onOpenDetail("album", album.id) }, width = w)
        }
        SearchFilter.ARTISTS -> grid(results.artists, width) { artist, w ->
            ArtistCircle(artist, onClick = { p.onOpenDetail("artist", artist.id) }, width = w)
        }
        SearchFilter.PLAYLISTS -> grid(results.playlists, width) { playlist, w ->
            PlaylistCard(playlist, onClick = { p.onOpenDetail("playlist", playlist.id) }, width = w)
        }
        else -> {
            item(key = "songs-header") { SongListHeader(showIndex = numbered, tableMinWidth = tableMinWidth) }
            items(results.songs.size) { i -> p.Track(results.songs, i, index = if (numbered) i + 1 else null, tableMinWidth = tableMinWidth) }
        }
    }
}

private fun <T> LazyListScope.grid(items: List<T>, width: Dp, card: @Composable (T, Dp) -> Unit) {
    val columns = shelfColumns(width)
    val itemWidth = shelfItemWidth(width)
    val rows = items.chunked(columns)
    items(rows.size) { r ->
        Row(horizontalArrangement = Arrangement.spacedBy(PageMetrics.ShelfSpacing)) {
            rows[r].forEach { card(it, itemWidth) }
        }
    }
}

@Composable
internal fun TopRow(card: @Composable () -> Unit, list: @Composable () -> Unit) {
    Layout(content = { card(); list() }) { measurables, constraints ->
        val gap = 24.dp.roundToPx()
        val width = constraints.maxWidth
        val cardWidth = ((width - gap) * 0.4f).roundToInt().coerceIn(TopCardMinWidth.roundToPx(), TopCardMaxWidth.roundToPx()).coerceAtMost(width)
        val listPlaceable = measurables[1].measure(Constraints.fixedWidth((width - gap - cardWidth).coerceAtLeast(0)))
        val height = maxOf(listPlaceable.height, TopCardMinHeight.roundToPx())
        val cardPlaceable = measurables[0].measure(Constraints.fixed(cardWidth, height))
        layout(width, height) {
            cardPlaceable.place(0, 0)
            listPlaceable.place(cardWidth + gap, 0)
        }
    }
}

@Composable
private fun TopResultCard(hit: TopHit, onOpen: () -> Unit, onPlay: (() -> Unit)?) {
    val shape = com.aurora.music.ui.theme.paperSquare(RoundedCornerShape(themedCorner(24.dp)))
    Box(
        Modifier.fillMaxSize().auroraPanel(shape)
            .background(Brush.linearGradient(listOf(Color.Transparent, hit.accent.copy(alpha = 0.18f))))
            .pressClickable(onClick = onOpen).pointerHoverIcon(PointerIcon.Hand)
            .padding(20.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            Artwork(hit.art, hit.accent, Modifier.size(112.dp), corner = if (hit.round) 56.dp else 16.dp)
            Spacer(Modifier.weight(1f))
            Text(hit.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = if (onPlay != null) 68.dp else 0.dp))
            Spacer(Modifier.height(8.dp))
            Row(Modifier.padding(end = if (onPlay != null) 68.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(hit.type, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.paperClip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                        .padding(horizontal = 10.dp, vertical = 4.dp))
                if (hit.subtitle.isNotBlank()) {
                    Spacer(Modifier.width(10.dp))
                    Text(hit.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (onPlay != null) {
            Box(
                Modifier.align(Alignment.BottomEnd).size(56.dp).paperClip(CircleShape).background(MaterialTheme.colorScheme.primary)
                    .clickable(onClickLabel = appString(R.string.text_play_5d12bd), onClick = onPlay).pointerHoverIcon(PointerIcon.Hand),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, appString(R.string.text_play_5d12bd), tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(30.dp))
            }
        }
    }
}

@Composable
private fun RecentSearches(
    recents: List<String>,
    onClick: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
    contentPadding: PaddingValues,
) {
    val gutter = LocalPageGutter.current
    LazyColumn(
        Modifier.widthIn(max = PageMetrics.SearchFieldMaxWidth + gutter * 2).fillMaxWidth(),
        contentPadding = pagePadding(contentPadding),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(appString(R.string.text_recent_76eec7), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(appString(R.string.text_clear_719ea3), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.paperClip(RoundedCornerShape(50)).clickable(onClick = onClear).pointerHoverIcon(PointerIcon.Hand)
                        .padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
        items(recents.size) { i ->
            val q = recents[i]
            Row(
                Modifier.fillMaxWidth().paperClip(RoundedCornerShape(themedCorner(12.dp))).clickable { onClick(q) }.pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(14.dp))
                Text(q, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.Close, appString(R.string.text_remove_e96390), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp).paperClip(CircleShape).clickable { onRemove(q) }.pointerHoverIcon(PointerIcon.Hand).padding(7.dp))
            }
        }
    }
}

