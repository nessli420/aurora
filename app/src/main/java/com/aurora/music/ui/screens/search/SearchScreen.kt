package com.aurora.music.ui.screens.search

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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

private enum class SearchFilter(val label: String) {
    ALL("All"), SONGS("Songs"), ALBUMS("Albums"), ARTISTS("Artists"), PLAYLISTS("Playlists")
}

private fun sectionNonEmpty(f: SearchFilter, r: SearchResults): Boolean = when (f) {
    SearchFilter.ALL -> true
    SearchFilter.SONGS -> r.songs.isNotEmpty()
    SearchFilter.ALBUMS -> r.albums.isNotEmpty()
    SearchFilter.ARTISTS -> r.artists.isNotEmpty()
    SearchFilter.PLAYLISTS -> r.playlists.isNotEmpty()
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
) {
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val results = state.results
    val keyboard = LocalSoftwareKeyboardController.current
    var filter by remember { mutableStateOf(SearchFilter.ALL) }
    // A fresh query yields a fresh result set — reset the type filter so it can't strand the user
    // on a now-empty section.
    androidx.compose.runtime.LaunchedEffect(state.query) { filter = SearchFilter.ALL }

    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) onQuery(spoken)
    }
    fun launchVoice() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to search")
        }
        runCatching { voiceLauncher.launch(intent) }
    }
    // Recording a recent search is a useful-search signal — fire it on result interaction too.
    val commitAnd: (() -> Unit) -> Unit = { action -> onCommitSearch(); action() }

    Column(Modifier.fillMaxWidth().padding(top = topInset)) {
        Column(Modifier.padding(start = 16.dp, top = 14.dp, bottom = 12.dp)) {
            Eyebrow("DISCOVER", MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(2.dp))
            Text("Search", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black)
        }
        TextField(
            value = state.query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = { Text("Artists, albums, or songs") },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.primary) },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.query.isNotEmpty()) {
                        Icon(Icons.Filled.Close, "Clear", modifier = Modifier.size(36.dp).clip(CircleShape).clickable { onQuery("") }.padding(7.dp))
                    }
                    Icon(Icons.Filled.Mic, "Voice search", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp).clip(CircleShape).clickable { launchVoice() }.padding(8.dp))
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onCommitSearch(); keyboard?.hide() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
        )

        Spacer(Modifier.height(12.dp))

        val empty = results.songs.isEmpty() && results.albums.isEmpty() && results.artists.isEmpty() && results.playlists.isEmpty()
        when {
            state.query.isBlank() ->
                if (recentSearches.isEmpty()) EmptyHint("Search your library", "Find any artist, album, song, or playlist")
                else RecentSearches(recentSearches, onRecentClick, onRemoveRecent, onClearRecents, contentPadding)
            state.loading && empty ->
                Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                    com.aurora.music.ui.components.LottieLoader(modifier = Modifier.size(72.dp))
                }
            empty -> EmptyHint("No results", "Nothing matched \"${state.query}\"")
            else -> {
                // Fall back to ALL whenever the chosen type has no results (e.g. the result set
                // changed on an offline flip without the query string changing).
                val effective = if (sectionNonEmpty(filter, results)) filter else SearchFilter.ALL
                FilterChips(effective, results) { filter = it }
                Spacer(Modifier.height(6.dp))
                Results(
                    effective, results, likedIds, currentSongId, isPlaying, contentPadding,
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
            Text(
                f.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.clip(RoundedCornerShape(50))
                    .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onSelect(f) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun Results(
    filter: SearchFilter,
    results: SearchResults,
    likedIds: Set<String>,
    currentSongId: String,
    isPlaying: Boolean,
    contentPadding: PaddingValues,
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
    fun show(f: SearchFilter) = filter == SearchFilter.ALL || filter == f
    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (show(SearchFilter.ARTISTS) && results.artists.isNotEmpty()) {
            item { SectionHeader("Artists", Modifier.padding(horizontal = 16.dp)) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
                    items(results.artists.size) { i -> ArtistCircle(results.artists[i], onClick = { onOpenDetail("artist", results.artists[i].id) }) }
                }
            }
        }
        if (show(SearchFilter.ALBUMS) && results.albums.isNotEmpty()) {
            item { SectionHeader("Albums", Modifier.padding(horizontal = 16.dp)) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
                    items(results.albums.size) { i -> AlbumCard(results.albums[i], onClick = { onOpenDetail("album", results.albums[i].id) }) }
                }
            }
        }
        if (show(SearchFilter.PLAYLISTS) && results.playlists.isNotEmpty()) {
            item { SectionHeader("Playlists", Modifier.padding(horizontal = 16.dp)) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
                    items(results.playlists.size) { i -> PlaylistCard(results.playlists[i], onClick = { onOpenDetail("playlist", results.playlists[i].id) }) }
                }
            }
        }
        if (show(SearchFilter.SONGS) && results.songs.isNotEmpty()) {
            item { SectionHeader("Songs", Modifier.padding(horizontal = 16.dp)) }
            items(results.songs.size) { i ->
                val song = results.songs[i]
                SongRow(
                    song = song,
                    isPlaying = song.id == currentSongId && isPlaying,
                    isLiked = likedIds.contains(song.id),
                    onClick = { onPlayAll(results.songs, i) },
                    onToggleLike = { onToggleLike(song.id) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                    onAddToQueue = { onAddToQueue(song) },
                    onPlayNext = { onPlayNext(song) },
                    onGoToAlbum = if (song.albumId.isNotBlank()) ({ onOpenDetail("album", song.albumId) }) else null,
                    onGoToArtist = if (song.artistId.isNotBlank()) ({ onOpenDetail("artist", song.artistId) }) else null,
                    isDownloaded = canDownload && downloadedIds.contains(song.id),
                    onDownload = if (canDownload) ({ onDownload(song) }) else null,
                    onRemoveDownload = if (canDownload) ({ onRemoveDownload(song.id) }) else null,
                )
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
    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Recent", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("Clear", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClear).padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
        items(recents.size) { i ->
            val q = recents[i]
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onClick(q) }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(14.dp))
                Text(q, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.Close, "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(30.dp).clip(CircleShape).clickable { onRemove(q) }.padding(6.dp))
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
