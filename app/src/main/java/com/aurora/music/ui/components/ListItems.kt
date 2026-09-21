package com.aurora.music.ui.components

import com.aurora.music.localization.localizedMediaType

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueuePlayNext
import androidx.compose.material.icons.outlined.Explicit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import com.aurora.music.data.ThemeStyle
import com.aurora.music.ui.theme.LocalUiPrefs
import com.aurora.music.ui.theme.auroraPanel

@Composable
fun SongRow(
    song: Song,
    isPlaying: Boolean,
    isLiked: Boolean,
    onClick: () -> Unit,
    onToggleLike: () -> Unit,
    modifier: Modifier = Modifier,
    index: Int? = null,
    onPlayNext: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    onGoToAlbum: (() -> Unit)? = null,
    onGoToArtist: (() -> Unit)? = null,
    isDownloaded: Boolean = false,
    onDownload: (() -> Unit)? = null,
    onRemoveDownload: (() -> Unit)? = null,
    onEditTags: (() -> Unit)? = null,
    serverTagEditing: Boolean = false,
) {
    var menuOpen by remember(song.id, song.playbackSource?.providerId) { mutableStateOf(false) }
    var showPlaylists by remember(song.id, song.playbackSource?.providerId) { mutableStateOf(false) }
    val shape = if (LocalUiPrefs.current.themeStyle == ThemeStyle.AURORA) RoundedCornerShape(14.dp) else MaterialTheme.shapes.small
    key(song.id, song.playbackSource?.providerId) {
    TrackSwipeActions(modifier, shape, isLiked, enabled = !menuOpen && !showPlaylists,
        onPlayNext = onPlayNext, onQueue = onAddToQueue, onLike = onToggleLike,
        onPlaylists = { showPlaylists = true }) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Artwork(song.artworkUrl, song.accent, Modifier.size(52.dp), corner = 10.dp)
            if (isPlaying) {
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.GraphicEq, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (song.explicit) {
                    Icon(
                        Icons.Outlined.Explicit, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp).padding(end = 0.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        if (isDownloaded) {
            Icon(Icons.Filled.DownloadDone, appString(R.string.text_downloaded_c61970), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Icon(
            imageVector = if (isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription = appString(if (isLiked) R.string.text_remove_from_liked_9d1568 else R.string.text_add_to_liked_b99f26),
            tint = if (isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(onClick = onToggleLike)
                .padding(6.dp),
        )
        Text(
            if (song.durationSec > 0) formatTime(song.durationSec) else "—",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        Box {
            Icon(
                Icons.Outlined.MoreVert, appString(R.string.text_more_4bab2d),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp).clip(CircleShape).clickable { menuOpen = true }.padding(4.dp),
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(appString(R.string.text_play_5d12bd)) },
                    onClick = { menuOpen = false; onClick() },
                    leadingIcon = { Icon(Icons.Filled.PlayArrow, null) },
                )
                if (onPlayNext != null) DropdownMenuItem(
                    text = { Text(appString(R.string.text_play_next_40d33c)) },
                    onClick = { menuOpen = false; onPlayNext() },
                    leadingIcon = { Icon(Icons.Filled.QueuePlayNext, null) },
                )
                if (onAddToQueue != null) DropdownMenuItem(
                    text = { Text(appString(R.string.text_add_to_queue_69b498)) },
                    onClick = { menuOpen = false; onAddToQueue() },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) },
                )
                DropdownMenuItem(
                    text = { Text(if (isLiked) appString(R.string.text_remove_from_liked_9d1568) else appString(R.string.text_add_to_liked_b99f26)) },
                    onClick = { menuOpen = false; onToggleLike() },
                    leadingIcon = { Icon(if (isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, null) },
                )
                DropdownMenuItem(
                    text = { Text(appString(R.string.track_swipe_playlists)) },
                    onClick = { menuOpen = false; showPlaylists = true },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                )
                if (isDownloaded && onRemoveDownload != null) DropdownMenuItem(
                    text = { Text(appString(R.string.text_remove_download_147742)) },
                    onClick = { menuOpen = false; onRemoveDownload() },
                    leadingIcon = { Icon(Icons.Filled.DownloadDone, null, tint = MaterialTheme.colorScheme.primary) },
                ) else if (onDownload != null) DropdownMenuItem(
                    text = { Text(appString(R.string.text_download_a479c9)) },
                    onClick = { menuOpen = false; onDownload() },
                    leadingIcon = { Icon(Icons.Filled.Download, null) },
                )
                if (onGoToAlbum != null) DropdownMenuItem(
                    text = { Text(appString(R.string.text_go_to_album_e2d3b3)) },
                    onClick = { menuOpen = false; onGoToAlbum() },
                    leadingIcon = { Icon(Icons.Filled.Album, null) },
                )
                if (onGoToArtist != null) DropdownMenuItem(
                    text = { Text(appString(R.string.text_go_to_artist_d8f70c)) },
                    onClick = { menuOpen = false; onGoToArtist() },
                    leadingIcon = { Icon(Icons.Filled.Person, null) },
                )
                // tag edit only on content:// files or backends with metadata write
                if (onEditTags != null && (song.streamUrl.startsWith("content://") || serverTagEditing)) DropdownMenuItem(
                    text = { Text(appString(R.string.text_edit_tags_d8a5fc)) },
                    onClick = { menuOpen = false; onEditTags() },
                    leadingIcon = { Icon(Icons.Filled.Edit, null) },
                )
            }
        }
    }
    }
    }
    if (showPlaylists) TrackPlaylistsSheet(song, onDismiss = { showPlaylists = false })
}

@Composable
fun PlaylistCard(
    playlist: Playlist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp = 156.dp,
) {
    Column(
        modifier = modifier
            .width(width)
            .clip(if (LocalUiPrefs.current.themeStyle == ThemeStyle.AURORA) RoundedCornerShape(16.dp) else MaterialTheme.shapes.medium)
            .then(if (LocalUiPrefs.current.themeStyle != ThemeStyle.AURORA) Modifier.auroraPanel(MaterialTheme.shapes.medium) else Modifier)
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        Artwork(playlist.coverUrl, playlist.accent, Modifier.size(width - 16.dp), corner = 14.dp)
        Spacer(Modifier.height(10.dp))
        Text(
            playlist.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            playlist.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun AlbumCard(album: Album, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .width(156.dp)
            .clip(if (LocalUiPrefs.current.themeStyle == ThemeStyle.AURORA) RoundedCornerShape(16.dp) else MaterialTheme.shapes.medium)
            .then(if (LocalUiPrefs.current.themeStyle != ThemeStyle.AURORA) Modifier.auroraPanel(MaterialTheme.shapes.medium) else Modifier)
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        Box {
            Artwork(album.artworkUrl, MaterialTheme.colorScheme.secondary, Modifier.size(140.dp), corner = 14.dp)
            val label = album.typeLabel.localizedMediaType()
            if (label != appString(R.string.text_album_dfb4c9)) {
                Text(
                    label.uppercase(),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(album.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOf(album.artist, album.year.takeIf { it > 0 }?.toString().orEmpty()).filter { it.isNotBlank() }.joinToString(" • "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun ArtistCircle(artist: Artist, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .width(124.dp)
            .clip(if (LocalUiPrefs.current.themeStyle == ThemeStyle.AURORA) RoundedCornerShape(16.dp) else MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Artwork(artist.imageUrl, MaterialTheme.colorScheme.tertiary, Modifier.size(108.dp), corner = 108.dp)
        Spacer(Modifier.height(8.dp))
        Text(artist.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(appString(R.string.text_artist_6c3f3d), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun RecentTile(playlist: Playlist, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(60.dp)
            .then(
                if (LocalUiPrefs.current.themeStyle == ThemeStyle.AURORA)
                    Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                else Modifier.auroraPanel(MaterialTheme.shapes.small)
            )
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(playlist.coverUrl, playlist.accent, Modifier.size(60.dp), corner = 0.dp)
        Text(
            playlist.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp).weight(1f),
        )
    }
}
