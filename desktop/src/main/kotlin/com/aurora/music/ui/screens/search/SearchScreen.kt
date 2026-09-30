package com.aurora.music.ui.screens.search

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PlayCircle
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aurora.music.data.SearchResults
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
import com.aurora.music.ui.theme.auroraPanel
import com.aurora.music.util.accentFor
import com.aurora.music.viewmodel.SearchUiState
import kotlin.math.roundToInt

private enum class SearchFilter(private val labelRes: Int) {
    ALL(R.string.text_all_6a7208), SONGS(R.string.text_songs_e1404b), ALBUMS(R.string.text_albums_4c45e7), ARTISTS(R.string.text_artists_1528d8), PLAYLISTS(R.string.text_playlists_77b69f);
    val label: String get() = appString(labelRes)
}

private fun sectionNonEmpty(f: SearchFilter, r: SearchResults): Boolean = when (f) {
    SearchFilter.ALL -> true
    SearchFilter.SONGS -> r.songs.isNotEmpty()
    SearchFilter.ALBUMS -> r.albums.isNotEmpty()
    SearchFilter.ARTISTS -> r.artists.isNotEmpty()
    SearchFilter.PLAYLISTS -> r.playlists.isNotEmpty()
}

private val WideResults = 1000.dp
private val TopCardMinWidth = 320.dp
private val TopCardMinHeight = 240.dp
private const val TopSongs = 4
private const val NarrowSongs = 5

private class TopHit(
    val kind: String,
    val id: String,
    val title: String,
    val type: String,
    val subtitle: String,
    val art: String,
    val accent: Color,
    val round: Boolean,
    val songIndex: Int = -1,
)

private fun topHit(query: String, r: SearchResults): TopHit? {
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
            add(score(a.name) to TopHit("artist", a.id, a.name, appString(R.string.text_artist_6c3f3d), "", a.imageUrl, accentFor(a.id), round = true))
        }
        r.albums.firstOrNull()?.let { a: Album ->
            add(score(a.title) to TopHit("album", a.id, a.title, a.typeLabel.localizedMediaType(), a.artist, a.artworkUrl, accentFor(a.id), round = false))
        }
        r.songs.firstOrNull()?.let { s: Song ->
            add(score(s.title) to TopHit("song", s.id, s.title, appString(R.string.text_song_bd1189), s.artist, s.artworkUrl, s.accent, round = false, songIndex = 0))
        }
        r.playlists.firstOrNull()?.let { p: Playlist ->
            add(score(p.title) to TopHit("playlist", p.id, p.title, appString(R.string.text_playlist_cd95b4), p.subtitle, p.coverUrl, p.accent, round = false))
        }
    }
    return hits.maxByOrNull { it.first }?.second
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
                    Icon(Icons.Filled.Close, appString(R.string.text_clear_719ea3), modifier = Modifier.size(36.dp).clip(CircleShape)
                        .clickable { onQuery("") }.pointerHoverIcon(PointerIcon.Hand).padding(7.dp))
                }),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
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

        val empty = results.songs.isEmpty() && results.albums.isEmpty() && results.artists.isEmpty() && results.playlists.isEmpty()
        when {
            state.error != null -> EmptyHint(appString(R.string.text_search_unavailable_c32b9f), state.error)
            state.query.isBlank() ->
                if (recentSearches.isEmpty()) EmptyHint(appString(R.string.text_search_your_library_d9fa3f), appString(R.string.text_find_any_artist_album_song_or_playlist_9fcb23))
                else RecentSearches(recentSearches, onRecentClick, onRemoveRecent, onClearRecents, contentPadding)
            state.loading && empty ->
                Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                    LottieLoader(modifier = Modifier.size(72.dp))
                }
            empty -> EmptyHint(appString(R.string.text_no_results_b993b0), appString(R.string.text_nothing_matched_f74d36, (state.query)))
            else -> {
                // fall back to ALL when chosen type has no results
                val effective = if (sectionNonEmpty(filter, results)) filter else SearchFilter.ALL
                FilterChips(effective, results) { filter = it }
                Results(
                    effective, state.query, results, likedIds, currentSongId, isPlaying, contentPadding,
                    onFilter = { filter = it },
                    onPlayAll = { songs, i -> commitAnd { onPlayAll(songs, i) } },
                    onAddToQueue = onAddToQueue, onPlayNext = onPlayNext, onToggleLike = onToggleLike,
                    onOpenDetail = { k, id -> commitAnd { onOpenDetail(k, id) } },
                    downloadedIds = downloadedIds, onDownload = onDownload, onRemoveDownload = onRemoveDownload, canDownload = canDownload,
                )
            }
        }
    }
}

@Composable
private fun SourcePicker(state: SearchUiState, onSelectSource: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.sources.forEach { source ->
            val selected = source.id == state.selectedSource
            val icon = when (source.id) {
                "library" -> Icons.Outlined.LibraryMusic
                "discovery" -> Icons.Outlined.PlayCircle
                else -> Icons.Outlined.MusicNote
            }
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .selectable(selected = selected, role = Role.RadioButton) { onSelectSource(source.id) }
                    .pointerHoverIcon(PointerIcon.Hand),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, source.label,
                    tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun FilterChips(selected: SearchFilter, results: SearchResults, onSelect: (SearchFilter) -> Unit) {
    val available = buildList {
        add(SearchFilter.ALL)
        if (results.songs.isNotEmpty()) add(SearchFilter.SONGS)
        if (results.albums.isNotEmpty()) add(SearchFilter.ALBUMS)
        if (results.artists.isNotEmpty()) add(SearchFilter.ARTISTS)
        if (results.playlists.isNotEmpty()) add(SearchFilter.PLAYLISTS)
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = LocalPageGutter.current),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(available.size) { i ->
            val f = available[i]
            val on = f == selected
            Text(
                f.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.clip(RoundedCornerShape(50))
                    .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onSelect(f) }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun Results(
    filter: SearchFilter,
    query: String,
    results: SearchResults,
    likedIds: Set<String>,
    currentSongId: String,
    isPlaying: Boolean,
    contentPadding: PaddingValues,
    onFilter: (SearchFilter) -> Unit,
    onPlayAll: (List<Song>, Int) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onToggleLike: (String) -> Unit,
    onOpenDetail: (String, String) -> Unit,
    downloadedIds: Set<String>,
    onDownload: (Song) -> Unit,
    onRemoveDownload: (String) -> Unit,
    canDownload: Boolean,
) {
    val gutter = LocalPageGutter.current
    val listState = rememberLazyListState()
    val songs = results.songs
    val seeAll = appString(R.string.text_see_all_2941c5)
    val songRow: @Composable (Int) -> Unit = { i ->
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
        )
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth - gutter * 2
        val gap = when (filter) {
            SearchFilter.ALL -> PageMetrics.SectionGap
            SearchFilter.SONGS -> 0.dp
            else -> 24.dp
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = pagePadding(contentPadding, top = 24.dp),
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
                        PageSection(appString(R.string.text_artists_1528d8), action = seeAll, onAction = { onFilter(SearchFilter.ARTISTS) }) {
                            AdaptiveShelf(results.artists, bleed = gutter) { artist, w ->
                                ArtistCircle(artist, onClick = { onOpenDetail("artist", artist.id) }, width = w)
                            }
                        }
                    }
                    if (results.albums.isNotEmpty()) item(key = "albums") {
                        PageSection(appString(R.string.text_albums_4c45e7), action = seeAll, onAction = { onFilter(SearchFilter.ALBUMS) }) {
                            AdaptiveShelf(results.albums, bleed = gutter) { album, w ->
                                AlbumCard(album, onClick = { onOpenDetail("album", album.id) }, width = w)
                            }
                        }
                    }
                    if (results.playlists.isNotEmpty()) item(key = "playlists") {
                        PageSection(appString(R.string.text_playlists_77b69f), action = seeAll, onAction = { onFilter(SearchFilter.PLAYLISTS) }) {
                            AdaptiveShelf(results.playlists, bleed = gutter) { playlist, w ->
                                PlaylistCard(playlist, onClick = { onOpenDetail("playlist", playlist.id) }, width = w)
                            }
                        }
                    }
                }
                SearchFilter.SONGS -> {
                    item(key = "songs-header") { SongListHeader(showIndex = false) }
                    items(songs.size) { i -> songRow(i) }
                }
                SearchFilter.ALBUMS -> grid(results.albums, width) { album, w ->
                    AlbumCard(album, onClick = { onOpenDetail("album", album.id) }, width = w)
                }
                SearchFilter.ARTISTS -> grid(results.artists, width) { artist, w ->
                    ArtistCircle(artist, onClick = { onOpenDetail("artist", artist.id) }, width = w)
                }
                SearchFilter.PLAYLISTS -> grid(results.playlists, width) { playlist, w ->
                    PlaylistCard(playlist, onClick = { onOpenDetail("playlist", playlist.id) }, width = w)
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(listState),
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(bottom = contentPadding.calculateBottomPadding()))
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
private fun TopRow(card: @Composable () -> Unit, list: @Composable () -> Unit) {
    Layout(content = { card(); list() }) { measurables, constraints ->
        val gap = 24.dp.roundToPx()
        val width = constraints.maxWidth
        val cardWidth = maxOf(TopCardMinWidth.roundToPx(), ((width - gap) * 0.4f).roundToInt()).coerceAtMost(width)
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
    val shape = RoundedCornerShape(24.dp)
    Box(
        Modifier.fillMaxSize().auroraPanel(shape)
            .background(Brush.linearGradient(listOf(Color.Transparent, hit.accent.copy(alpha = 0.18f))))
            .clickable(onClick = onOpen).pointerHoverIcon(PointerIcon.Hand)
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
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
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
                Modifier.align(Alignment.BottomEnd).size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
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
                Text(appString(R.string.text_recent_76eec7), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(appString(R.string.text_clear_719ea3), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClear).pointerHoverIcon(PointerIcon.Hand)
                        .padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
        items(recents.size) { i ->
            val q = recents[i]
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onClick(q) }.pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(14.dp))
                Text(q, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.Close, appString(R.string.text_remove_e96390), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(30.dp).clip(CircleShape).clickable { onRemove(q) }.pointerHoverIcon(PointerIcon.Hand).padding(6.dp))
            }
        }
    }
}

@Composable
private fun EmptyHint(title: String, subtitle: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
