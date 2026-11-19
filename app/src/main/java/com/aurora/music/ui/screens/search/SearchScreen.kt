package com.aurora.music.ui.screens.search

import com.aurora.music.localization.appString
import com.aurora.music.ui.theme.glassCard
import com.aurora.music.ui.theme.glassChip
import com.aurora.music.ui.theme.paperClip
import com.aurora.music.R

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.LibraryMusic
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aurora.music.data.SearchResults
import com.aurora.music.model.Song
import com.aurora.music.ui.components.AlbumCard
import com.aurora.music.ui.components.ArtistCircle
import com.aurora.music.ui.components.Eyebrow
import com.aurora.music.ui.components.PlaylistCard
import com.aurora.music.ui.components.SectionHeader
import com.aurora.music.ui.components.SongRow
import com.aurora.music.viewmodel.SearchUiState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.SearchOff
import com.aurora.music.ui.components.EmptyState
import com.aurora.music.ui.components.LoadingState
import com.aurora.music.ui.theme.Motion
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import com.aurora.music.localization.appPlural
import com.aurora.music.localization.localizedMediaType
import com.aurora.music.model.Album
import com.aurora.music.model.accent
import com.aurora.music.ui.screens.home.Hero
import com.aurora.music.ui.screens.home.spotSurface
import com.aurora.music.ui.theme.isGlassTheme
import com.aurora.music.ui.theme.paperFrame
import com.aurora.music.util.accentFor
import java.util.Locale

internal enum class Pane { ERROR, HINT, RECENTS, LOADING, NONE, RESULTS }

// a pane carries its own data so the one fading out keeps what it showed
internal class SearchPane(val kind: Pane, val state: SearchUiState, val recents: List<String>) {
    val hits: List<Hero> by lazy { if (kind == Pane.RESULTS) topHits(state.query, state.results) else emptyList() }
}

internal enum class SearchFilter(@androidx.annotation.StringRes private val labelRes: Int) {
    ALL(R.string.text_all_6a7208), SONGS(R.string.text_songs_e1404b), ALBUMS(R.string.text_albums_4c45e7), ARTISTS(R.string.text_artists_1528d8), PLAYLISTS(R.string.text_playlists_77b69f);
    val label: String get() = appString(labelRes)
}

internal fun sectionNonEmpty(f: SearchFilter, r: SearchResults): Boolean = when (f) {
    SearchFilter.ALL -> true
    SearchFilter.SONGS -> r.songs.isNotEmpty()
    SearchFilter.ALBUMS -> r.albums.isNotEmpty()
    SearchFilter.ARTISTS -> r.artists.isNotEmpty()
    SearchFilter.PLAYLISTS -> r.playlists.isNotEmpty()
}

internal fun matchScore(query: String, name: String): Int {
    val q = query.trim().lowercase()
    val n = name.trim().lowercase()
    return when {
        q.isEmpty() -> 0
        n == q -> 3
        n.startsWith(q) -> 2
        n.split(' ').any { it.startsWith(q) } -> 1
        else -> 0
    }
}

// desktop's rule: the best name match among the first result of each kind, in this order on a tie
internal fun topHits(query: String, r: SearchResults): List<Hero> {
    fun score(name: String) = matchScore(query, name)
    fun label(kind: String) = kind.uppercase(Locale.getDefault())
    fun line(vararg parts: String) = parts.filter { it.isNotBlank() }.joinToString(" · ")
    return buildList {
        r.artists.firstOrNull()?.let {
            val found = line(if (r.songs.isEmpty()) "" else appPlural(R.plurals.track_count, r.songs.size), if (r.albums.isEmpty()) "" else appPlural(R.plurals.album_count, r.albums.size))
            add(score(it.name) to Hero("artist", it.id, label(appString(R.string.text_artist_6c3f3d)), it.name, "", found, it.imageUrl, accentFor(it.id)))
        }
        r.albums.firstOrNull()?.let {
            val detail = line(if (it.year > 0) it.year.toString() else "", if (it.songCount > 0) appPlural(R.plurals.track_count, it.songCount) else "")
            add(score(it.title) to Hero("album", it.id, label(it.typeLabel.localizedMediaType()), it.title, it.artist, detail, it.artworkUrl, accentFor(it.id)))
        }
        r.songs.firstOrNull()?.let { add(score(it.title) to Hero("song", it.id, label(appString(R.string.text_song_bd1189)), it.title, it.artist, it.album, it.artworkUrl, it.accent)) }
        r.playlists.firstOrNull()?.let {
            add(score(it.title) to Hero("playlist", it.id, label(appString(R.string.text_playlist_cd95b4)), it.title, it.subtitle,
                if (it.songCount > 0) appPlural(R.plurals.track_count, it.songCount) else "", it.coverUrl, it.accent))
        }
    }.sortedByDescending { it.first }.map { it.second }
}

internal class SearchParts(
    val state: SearchUiState,
    val pane: SearchPane,
    val filter: SearchFilter,
    val onFilter: (SearchFilter) -> Unit,
    val jumpBackIn: List<Album>,
    val bottom: Dp,
    val likedIds: Set<String>,
    val currentSongId: String,
    val isPlaying: Boolean,
    val downloadedIds: Set<String>,
    val canDownload: Boolean,
    val onQuery: (String) -> Unit,
    val onSubmit: () -> Unit,
    val onVoice: () -> Unit,
    val onSelectSource: (String) -> Unit,
    val onRecent: (String) -> Unit,
    val onRemoveRecent: (String) -> Unit,
    val onClearRecents: () -> Unit,
    val onPlayAll: (List<Song>, Int) -> Unit,
    val onOpen: (String, String) -> Unit,
    val onPlayCollection: (String, String) -> Unit,
    val onAddToQueue: (Song) -> Unit,
    val onPlayNext: (Song) -> Unit,
    val onToggleLike: (String) -> Unit,
    val onDownload: (Song) -> Unit,
    val onRemoveDownload: (String) -> Unit,
) {
    val source: String get() = state.sources.firstOrNull { it.id == state.selectedSource }?.label.orEmpty()
    val hasQuery: Boolean get() = state.query.isNotEmpty()
    val hasResults: Boolean get() = pane.kind == Pane.RESULTS
    val refreshing: Boolean get() = state.loading && hasResults
    val hasChoices: Boolean get() = hasResults || state.sources.size > 1
    val endIcon: ImageVector get() = if (hasQuery) Icons.Filled.Close else Icons.Filled.Mic
    val endLabel: String get() = appString(if (hasQuery) R.string.text_clear_719ea3 else R.string.text_voice_search_9df53f)
    val onEnd: () -> Unit get() = { if (hasQuery) onQuery("") else onVoice() }

    // fall back to ALL when chosen type has no results
    fun filterFor(r: SearchResults): SearchFilter = if (sectionNonEmpty(filter, r)) filter else SearchFilter.ALL
    fun filters(r: SearchResults): List<SearchFilter> = SearchFilter.entries.filter { sectionNonEmpty(it, r) }
    fun count(f: SearchFilter, r: SearchResults): Int = when (f) {
        SearchFilter.ALL -> r.songs.size + r.albums.size + r.artists.size + r.playlists.size
        SearchFilter.SONGS -> r.songs.size
        SearchFilter.ALBUMS -> r.albums.size
        SearchFilter.ARTISTS -> r.artists.size
        SearchFilter.PLAYLISTS -> r.playlists.size
    }

    fun playHit(hit: Hero) = if (hit.kind == "song") onPlayAll(pane.state.results.songs, 0) else onPlayCollection(hit.kind, hit.id)
    fun openHit(hit: Hero) = if (hit.kind == "song") playHit(hit) else onOpen(hit.kind, hit.id)
}

@Composable
internal fun SearchParts.SongItem(
    songs: List<Song>,
    index: Int,
    modifier: Modifier = Modifier,
    showArt: Boolean = true,
    titleStyle: TextStyle? = null,
    lead: (@Composable () -> Unit)? = null,
    tag: (@Composable () -> Unit)? = null,
) {
    val song = songs[index]
    SongRow(
        song = song,
        isPlaying = song.id == currentSongId && isPlaying,
        isLiked = likedIds.contains(song.id),
        onClick = { onPlayAll(songs, index) },
        onToggleLike = { onToggleLike(song.id) },
        modifier = modifier,
        onAddToQueue = { onAddToQueue(song) },
        onPlayNext = { onPlayNext(song) },
        onGoToAlbum = if (song.albumId.isNotBlank()) ({ onOpen("album", song.albumId) }) else null,
        onGoToArtist = if (song.artistId.isNotBlank()) ({ onOpen("artist", song.artistId) }) else null,
        isDownloaded = canDownload && downloadedIds.contains(song.id),
        onDownload = if (canDownload) ({ onDownload(song) }) else null,
        onRemoveDownload = if (canDownload) ({ onRemoveDownload(song.id) }) else null,
        showArt = showArt,
        titleStyle = titleStyle,
        lead = lead,
        tag = tag,
    )
}

@Composable
internal fun SearchParts.Choices(choice: @Composable (label: String, selected: Boolean, onClick: () -> Unit) -> Unit) {
    if (hasResults) {
        val on = filterFor(state.results)
        filters(state.results).forEach { f -> choice(f.label, f == on) { onFilter(f) } }
    } else state.sources.forEach { s -> choice(s.label, s.id == state.selectedSource) { onSelectSource(s.id) } }
}

@Composable
internal fun SearchParts.Field(
    style: TextStyle,
    modifier: Modifier = Modifier,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    placeholder: @Composable () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    BasicTextField(
        value = state.query,
        onValueChange = onQuery,
        modifier = modifier,
        textStyle = style.copy(color = MaterialTheme.colorScheme.onSurface),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit(); keyboard?.hide() }),
        onTextLayout = { if (hasQuery) onTextLayout(it) },
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (!hasQuery) placeholder()
                inner()
            }
        },
    )
}

// the loader only covers an empty list, so a refresh over old results shows on this line
@Composable
internal fun SearchProgress(p: SearchParts, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(2.dp)) {
        if (p.refreshing) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxSize())
    }
}

@Composable
internal fun SearchNotice(shown: SearchPane) {
    when (shown.kind) {
        Pane.ERROR -> EmptyState(Icons.Outlined.ErrorOutline, appString(R.string.text_search_unavailable_c32b9f), Modifier.fillMaxSize(), subtitle = shown.state.error)
        Pane.LOADING -> LoadingState(Modifier.height(240.dp))
        Pane.NONE -> EmptyState(Icons.Outlined.SearchOff, appString(R.string.text_no_results_b993b0), Modifier.fillMaxSize(), subtitle = appString(R.string.text_nothing_matched_f74d36, (shown.state.query)))
        else -> EmptyState(Icons.Filled.Search, appString(R.string.text_search_your_library_d9fa3f), Modifier.fillMaxSize(), subtitle = appString(R.string.text_find_any_artist_album_song_or_playlist_9fcb23))
    }
}

@Composable
internal fun SearchPanes(p: SearchParts, modifier: Modifier = Modifier, idle: @Composable (SearchPane) -> Unit, results: @Composable (SearchPane) -> Unit) {
    AnimatedContent(
        targetState = p.pane,
        modifier = modifier,
        transitionSpec = { fadeIn(Motion.enter()) togetherWith fadeOut(Motion.exit()) using null },
        contentKey = { it.kind },
        label = "searchPane",
    ) { shown ->
        when (shown.kind) {
            Pane.RESULTS -> results(shown)
            Pane.HINT, Pane.RECENTS -> if (shown.recents.isEmpty() && p.jumpBackIn.isEmpty()) SearchNotice(shown) else idle(shown)
            else -> SearchNotice(shown)
        }
    }
}

@Composable
internal fun Modifier.searchCard(shape: Shape, tint: Color = Color.Unspecified, outlined: Boolean = false): Modifier {
    val colors = MaterialTheme.colorScheme
    return when {
        isGlassTheme() -> glassCard(shape, tint)
        tint.isUnspecified -> spotSurface(shape, if (outlined) colors.surface else colors.surfaceContainer, outlined)
        else -> paperFrame(shape, { background(tint) }, fill = tint)
    }
}

// rows of a fixed grid as lazy items, so a long list of results stays lazy in the page's own column
internal fun <T> LazyListScope.gridRows(list: List<T>, columns: Int, modifier: Modifier, gap: Dp, cell: @Composable (T, Modifier) -> Unit) {
    items((list.size + columns - 1) / columns) { row ->
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(gap)) {
            for (i in row * columns until (row + 1) * columns) {
                val item = list.getOrNull(i)
                if (item == null) Spacer(Modifier.weight(1f)) else cell(item, Modifier.weight(1f))
            }
        }
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
    onPlayCollection: (kind: String, id: String) -> Unit = { _, _ -> },
) {
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val results = state.results
    val keyboard = LocalSoftwareKeyboardController.current
    var filter by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(SearchFilter.ALL) }
    var filterQuery by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(state.query) }
    // reset filter on new query so it can't strand on an empty section
    androidx.compose.runtime.LaunchedEffect(state.query) {
        if (filterQuery != state.query) { filterQuery = state.query; filter = SearchFilter.ALL }
    }
    val tablet = com.aurora.music.ui.layout.LocalWindowLayout.current.useNavigationRail
    val glass = com.aurora.music.ui.theme.isGlassTheme()

    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) onQuery(spoken)
    }
    fun launchVoice() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, appString(R.string.text_speak_to_search_91f0aa))
        }
        runCatching { voiceLauncher.launch(intent) }
    }
    val commitAnd: (() -> Unit) -> Unit = { action -> onCommitSearch(); action() }

    val empty = results.songs.isEmpty() && results.albums.isEmpty() && results.artists.isEmpty() && results.playlists.isEmpty()
    val kind = when {
        state.error != null -> Pane.ERROR
        state.query.isBlank() -> if (recentSearches.isEmpty()) Pane.HINT else Pane.RECENTS
        state.loading && empty -> Pane.LOADING
        empty -> Pane.NONE
        else -> Pane.RESULTS
    }
    val pane = remember(kind, state, recentSearches) { SearchPane(kind, state, recentSearches) }
    val parts = SearchParts(
        state, pane, filter, { filter = it }, jumpBackIn, contentPadding.calculateBottomPadding() + 24.dp,
        likedIds, currentSongId, isPlaying, downloadedIds, canDownload,
        onQuery = onQuery, onSubmit = onCommitSearch, onVoice = ::launchVoice, onSelectSource = onSelectSource,
        onRecent = onRecentClick, onRemoveRecent = onRemoveRecent, onClearRecents = onClearRecents,
        onPlayAll = { songs, i -> commitAnd { onPlayAll(songs, i) } },
        onOpen = { k, id -> commitAnd { onOpenDetail(k, id) } },
        onPlayCollection = { k, id -> commitAnd { onPlayCollection(k, id) } },
        onAddToQueue = onAddToQueue, onPlayNext = onPlayNext, onToggleLike = onToggleLike, onDownload = onDownload, onRemoveDownload = onRemoveDownload,
    )
    when (com.aurora.music.ui.theme.LocalUiPrefs.current.searchLayout) {
        com.aurora.music.data.PageLayout.SPOTLIGHT -> return SearchSpotlight(parts)
        com.aurora.music.data.PageLayout.CONSOLE -> return SearchConsole(parts)
        com.aurora.music.data.PageLayout.DRIFT -> return SearchDrift(parts)
    }

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
    Column(Modifier.widthIn(max = if (tablet) 960.dp else androidx.compose.ui.unit.Dp.Unspecified).fillMaxWidth().padding(top = topInset)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Eyebrow(appString(R.string.text_discover_d3f02e), MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(2.dp))
                Text(appString(R.string.text_search_bce064), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (state.sources.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.sources.forEach { source ->
                        val selected = source.id == state.selectedSource
                        val icon = when (source.id) {
                            "library" -> Icons.Outlined.LibraryMusic
                            "discovery" -> Icons.Outlined.PlayCircle
                            "spotify" -> Icons.Outlined.GraphicEq
                            "soundcloud" -> Icons.Outlined.Cloud
                            "apple" -> Icons.Outlined.Album
                            else -> Icons.Outlined.MusicNote
                        }
                        val fill by animateColorAsState(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, Motion.state(), label = "sourceFill")
                        val tint by animateColorAsState(if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, Motion.state(), label = "sourceTint")
                        Box(
                            Modifier.size(40.dp).then(if (glass) Modifier.glassChip(selected) else Modifier.paperClip(CircleShape).background(fill))
                                .selectable(selected = selected, role = Role.RadioButton) { onSelectSource(source.id) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(icon, source.label,
                                tint = if (glass) (if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface) else tint,
                                modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
        TextField(
            value = state.query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).then(if (glass) Modifier.glassCard(RoundedCornerShape(16.dp)) else Modifier),
            placeholder = { Text(appString(R.string.text_artists_albums_or_songs_d1863c)) },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.primary) },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.query.isNotEmpty()) {
                        Icon(Icons.Filled.Close, appString(R.string.text_clear_719ea3), modifier = Modifier.size(40.dp).paperClip(CircleShape).clickable { onQuery("") }.padding(9.dp))
                    }
                    Icon(Icons.Filled.Mic, appString(R.string.text_voice_search_9df53f), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp).paperClip(CircleShape).clickable { launchVoice() }.padding(8.dp))
                }
            },
            singleLine = true,
            shape = com.aurora.music.ui.theme.paperSquare(RoundedCornerShape(16.dp)),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onCommitSearch(); keyboard?.hide() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = if (glass) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = if (glass) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
        )

        // the loader below only covers an empty list, so a refresh over old results shows here
        Box(Modifier.fillMaxWidth().height(12.dp), contentAlignment = Alignment.Center) {
            if (state.loading && !empty) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(2.dp))
        }
        AnimatedContent(
            targetState = pane,
            transitionSpec = { fadeIn(Motion.enter()) togetherWith fadeOut(Motion.exit()) using null },
            contentKey = { it.kind },
            label = "searchPane",
        ) { shown ->
        when (shown.kind) {
            Pane.RECENTS -> RecentSearches(shown.recents, onRecentClick, onRemoveRecent, onClearRecents, contentPadding)
            Pane.RESULTS -> Column {
                val found = shown.state.results
                val effective = parts.filterFor(found)
                FilterChips(effective, found) { filter = it }
                Spacer(Modifier.height(6.dp))
                Results(effective, found, parts)
            }
            else -> SearchNotice(shown)
        }
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
        contentPadding = PaddingValues(horizontal = 16.dp),
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
                modifier = Modifier.then(if (com.aurora.music.ui.theme.isGlassTheme()) Modifier.glassChip(on) else Modifier.paperClip(RoundedCornerShape(50)).background(fill))
                    .clickable { onSelect(f) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun Results(filter: SearchFilter, results: SearchResults, parts: SearchParts) {
    val onOpenDetail = parts.onOpen
    fun show(f: SearchFilter) = filter == SearchFilter.ALL || filter == f
    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = parts.bottom),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (show(SearchFilter.ARTISTS) && results.artists.isNotEmpty()) {
            item { SectionHeader(appString(R.string.text_artists_1528d8), Modifier.padding(horizontal = 16.dp)) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
                    items(results.artists.size) { i -> ArtistCircle(results.artists[i], onClick = { onOpenDetail("artist", results.artists[i].id) }) }
                }
            }
        }
        if (show(SearchFilter.ALBUMS) && results.albums.isNotEmpty()) {
            item { SectionHeader(appString(R.string.text_albums_4c45e7), Modifier.padding(horizontal = 16.dp)) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
                    items(results.albums.size) { i -> AlbumCard(results.albums[i], onClick = { onOpenDetail("album", results.albums[i].id) }) }
                }
            }
        }
        if (show(SearchFilter.PLAYLISTS) && results.playlists.isNotEmpty()) {
            item { SectionHeader(appString(R.string.text_playlists_77b69f), Modifier.padding(horizontal = 16.dp)) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
                    items(results.playlists.size) { i -> PlaylistCard(results.playlists[i], onClick = { onOpenDetail("playlist", results.playlists[i].id) }) }
                }
            }
        }
        if (show(SearchFilter.SONGS) && results.songs.isNotEmpty()) {
            item { SectionHeader(appString(R.string.text_songs_e1404b), Modifier.padding(horizontal = 16.dp)) }
            items(results.songs.size) { i -> parts.SongItem(results.songs, i, Modifier.padding(horizontal = 8.dp)) }
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
    val glass = com.aurora.music.ui.theme.isGlassTheme()
    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(appString(R.string.text_recent_76eec7), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(appString(R.string.text_clear_719ea3), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.paperClip(RoundedCornerShape(50)).clickable(onClick = onClear).padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
        items(recents.size) { i ->
            val q = recents[i]
            Row(
                Modifier.fillMaxWidth()
                    .then(if (glass) Modifier.padding(horizontal = 16.dp, vertical = 3.dp).glassCard(MaterialTheme.shapes.small) else Modifier.paperClip(RoundedCornerShape(12.dp)))
                    .clickable { onClick(q) }.padding(start = 16.dp, end = 11.dp, top = if (glass) 3.dp else 7.dp, bottom = if (glass) 3.dp else 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(14.dp))
                Text(q, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.Close, appString(R.string.text_remove_e96390), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp).paperClip(CircleShape).clickable { onRemove(q) }.padding(11.dp))
            }
        }
    }
}
