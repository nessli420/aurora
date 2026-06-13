package com.aurora.music.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aurora.music.data.FolderContent
import com.aurora.music.model.Song
import com.aurora.music.ui.components.LottieLoader
import com.aurora.music.ui.components.SongRow

/** One level of the folder/file-tree browser: subfolders on top, this folder's tracks below. */
@Composable
fun FolderScreen(
    contentPadding: PaddingValues,
    title: String,
    loading: Boolean,
    content: FolderContent?,
    likedIds: Set<String>,
    currentSongId: String,
    isPlaying: Boolean,
    onBack: () -> Unit,
    onOpenFolder: (String, String) -> Unit,
    onPlayAll: (List<Song>, Int) -> Unit,
    onShufflePlay: (List<Song>) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onToggleLike: (String) -> Unit,
    onOpenDetail: (String, String) -> Unit,
    downloadedIds: Set<String>,
    onDownload: (Song) -> Unit,
    onRemoveDownload: (String) -> Unit,
    canDownload: Boolean = true,
    onEditTags: ((Song) -> Unit)? = null,
    serverTagEditing: Boolean = false,
) {
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val header = title.ifBlank { content?.title ?: "Folders" }

    Column(Modifier.fillMaxSize().padding(top = topInset)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, "Back",
                modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onBack).padding(8.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(header, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            val songs = content?.songs.orEmpty()
            if (songs.isNotEmpty()) {
                Icon(
                    Icons.Filled.Shuffle, "Shuffle folder",
                    modifier = Modifier.size(40.dp).clip(CircleShape).clickable { onShufflePlay(songs) }.padding(8.dp),
                )
                Icon(
                    Icons.Filled.PlayArrow, "Play folder",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(44.dp).clip(CircleShape).clickable { onPlayAll(songs, 0) }.padding(8.dp),
                )
            }
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LottieLoader(modifier = Modifier.size(72.dp)) }
            content == null || (content.folders.isEmpty() && content.songs.isEmpty()) ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nothing in this folder", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            else -> {
                val bottom = contentPadding.calculateBottomPadding() + 24.dp
                LazyColumn(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    contentPadding = PaddingValues(bottom = bottom),
                ) {
                    items(content.folders.size) { i ->
                        val f = content.folders[i]
                        FolderRow(name = f.name) { onOpenFolder(f.id, f.name) }
                    }
                    if (content.folders.isNotEmpty() && content.songs.isNotEmpty()) {
                        item { Spacer(Modifier.height(8.dp)) }
                    }
                    items(content.songs.size) { i ->
                        val s = content.songs[i]
                        SongRow(
                            s, isPlaying = s.id == currentSongId && isPlaying, isLiked = likedIds.contains(s.id),
                            onClick = { onPlayAll(content.songs, i) }, onToggleLike = { onToggleLike(s.id) },
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
            }
        }
    }
}

@Composable
private fun FolderRow(name: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.45f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
        Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
