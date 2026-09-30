package com.aurora.music.ui.screens.library

import com.aurora.music.localization.appString
import com.aurora.music.R

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aurora.music.data.FolderContent
import com.aurora.music.model.Song
import com.aurora.music.ui.components.LottieLoader
import com.aurora.music.ui.components.PageHeader
import com.aurora.music.ui.components.SongListHeader
import com.aurora.music.ui.components.SongRow
import com.aurora.music.data.ThemeStyle
import com.aurora.music.ui.layout.LocalPageGutter
import com.aurora.music.ui.layout.PageMetrics
import com.aurora.music.ui.theme.LocalUiPrefs
import com.aurora.music.ui.theme.auroraPanel
import kotlin.math.floor

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
    val header = title.ifBlank { content?.title ?: appString(R.string.text_folders_19adc4) }
    val gutter = LocalPageGutter.current

    Column(Modifier.fillMaxSize()) {
        val songs = content?.songs.orEmpty()
        PageHeader(header, Modifier.padding(start = gutter, end = gutter - 8.dp), onBack = onBack) {
            if (songs.isNotEmpty()) {
                IconButton(onClick = { onShufflePlay(songs) }, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                    Icon(Icons.Filled.Shuffle, appString(R.string.text_shuffle_folder_681a21))
                }
                FilledIconButton(onClick = { onPlayAll(songs, 0) }, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                    Icon(Icons.Filled.PlayArrow, appString(R.string.text_play_folder_3f9fd7))
                }
            }
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LottieLoader(modifier = Modifier.size(72.dp)) }
            content == null || (content.folders.isEmpty() && content.songs.isEmpty()) ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(appString(R.string.text_nothing_in_this_folder_d2a003), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            else -> {
                val bottom = contentPadding.calculateBottomPadding() + 24.dp
                val listState = rememberLazyListState()
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        state = listState,
                        contentPadding = PaddingValues(start = gutter - 8.dp, end = gutter - 8.dp, top = 8.dp, bottom = bottom),
                    ) {
                        if (content.folders.isNotEmpty()) {
                            item {
                                FolderGrid(content.folders.map { it.id to it.name }, Modifier.padding(horizontal = 8.dp)) { id, name -> onOpenFolder(id, name) }
                            }
                        }
                        if (content.songs.isNotEmpty()) {
                            item {
                                SongListHeader(Modifier.padding(top = if (content.folders.isNotEmpty()) PageMetrics.SectionGap else 0.dp), showIndex = false)
                            }
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
                    PaneScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(bottom = bottom))
                }
            }
        }
    }
}

@Composable
private fun FolderGrid(folders: List<Pair<String, String>>, modifier: Modifier = Modifier, onOpen: (String, String) -> Unit) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gap = 12.dp
        val columns = floor((maxWidth + gap) / (240.dp + gap)).toInt().coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            folders.chunked(columns).forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    line.forEach { (id, name) -> FolderTile(name, Modifier.weight(1f)) { onOpen(id, name) } }
                    repeat(columns - line.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun FolderTile(name: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val aurora = LocalUiPrefs.current.themeStyle == ThemeStyle.AURORA
    val shape = if (aurora) RoundedCornerShape(14.dp) else MaterialTheme.shapes.medium
    Row(
        modifier
            .height(56.dp)
            .then(if (aurora) Modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.45f)) else Modifier.auroraPanel(shape))
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
