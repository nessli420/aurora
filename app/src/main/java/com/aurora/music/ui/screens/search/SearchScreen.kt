package com.aurora.music.ui.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aurora.music.model.Song
import com.aurora.music.ui.components.AlbumCard
import com.aurora.music.ui.components.ArtistCircle
import com.aurora.music.ui.components.Eyebrow
import com.aurora.music.ui.components.PlaylistCard
import com.aurora.music.ui.components.SectionHeader
import com.aurora.music.ui.components.SongRow
import com.aurora.music.viewmodel.SearchUiState

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
) {
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val results = state.results

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
                if (state.query.isNotEmpty()) {
                    Icon(Icons.Filled.Close, "Clear", modifier = Modifier.clip(RoundedCornerShape(50)).clickable { onQuery("") }.padding(4.dp))
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
        )

        Spacer(Modifier.height(14.dp))

        val empty = results.songs.isEmpty() && results.albums.isEmpty() && results.artists.isEmpty() && results.playlists.isEmpty()
        when {
            state.query.isBlank() -> EmptyHint("Search your library", "Find any artist, album, song, or playlist")
            state.loading && empty ->
                Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                    com.aurora.music.ui.components.LottieLoader(modifier = Modifier.size(72.dp))
                }
            empty ->
                EmptyHint("No results", "Nothing matched \"${state.query}\"")
            else -> LazyColumn(
                Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (results.artists.isNotEmpty()) {
                    item { SectionHeader("Artists", Modifier.padding(horizontal = 16.dp)) }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
                            items(results.artists.size) { i -> ArtistCircle(results.artists[i], onClick = { onOpenDetail("artist", results.artists[i].id) }) }
                        }
                    }
                }
                if (results.albums.isNotEmpty()) {
                    item { SectionHeader("Albums", Modifier.padding(horizontal = 16.dp)) }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
                            items(results.albums.size) { i -> AlbumCard(results.albums[i], onClick = { onOpenDetail("album", results.albums[i].id) }) }
                        }
                    }
                }
                if (results.playlists.isNotEmpty()) {
                    item { SectionHeader("Playlists", Modifier.padding(horizontal = 16.dp)) }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
                            items(results.playlists.size) { i -> PlaylistCard(results.playlists[i], onClick = { onOpenDetail("playlist", results.playlists[i].id) }) }
                        }
                    }
                }
                if (results.songs.isNotEmpty()) {
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
