package com.aurora.music.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueuePlayNext
import androidx.compose.material.icons.outlined.Explicit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.sp
import com.aurora.music.R
import com.aurora.music.data.ThemeStyle
import com.aurora.music.localization.appString
import com.aurora.music.localization.localizedMediaType
import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import com.aurora.music.model.accent
import com.aurora.music.ui.layout.PageMetrics
import com.aurora.music.ui.theme.LocalUiPrefs
import com.aurora.music.ui.theme.auroraPanel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val RowInset = 8.dp
private val IndexWidth = 32.dp
private val ArtSize = 40.dp
private val CellGap = 12.dp
private val ColumnGap = 16.dp
private val ActionSize = 36.dp
private val DurationWidth = 52.dp
private val DateWidth = 120.dp
private val CardPanelPadding = 8.dp
private const val TitleWeight = 0.42f
private const val AlbumWeight = 0.33f
private val addedFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

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
    showAlbum: Boolean = true,
    showArt: Boolean = true,
    showDateAdded: Boolean = false,
) {
    var menuOpen by remember(song.id, song.playbackSource?.providerId) { mutableStateOf(false) }
    var contextAt by remember(song.id, song.playbackSource?.providerId) { mutableStateOf<Offset?>(null) }
    var showPlaylists by remember(song.id, song.playbackSource?.providerId) { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = rowShape()
    val menuItems: @Composable (close: () -> Unit) -> Unit = { close ->
        DropdownMenuItem(
            text = { Text(appString(R.string.text_play_5d12bd)) },
            onClick = { close(); onClick() },
            leadingIcon = { Icon(Icons.Filled.PlayArrow, null) },
        )
        if (onPlayNext != null) DropdownMenuItem(
            text = { Text(appString(R.string.text_play_next_40d33c)) },
            onClick = { close(); onPlayNext() },
            leadingIcon = { Icon(Icons.Filled.QueuePlayNext, null) },
        )
        if (onAddToQueue != null) DropdownMenuItem(
            text = { Text(appString(R.string.text_add_to_queue_69b498)) },
            onClick = { close(); onAddToQueue() },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) },
        )
        DropdownMenuItem(
            text = { Text(if (isLiked) appString(R.string.text_remove_from_liked_9d1568) else appString(R.string.text_add_to_liked_b99f26)) },
            onClick = { close(); onToggleLike() },
            leadingIcon = { Icon(if (isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, null) },
        )
        DropdownMenuItem(
            text = { Text(appString(R.string.track_swipe_playlists)) },
            onClick = { close(); showPlaylists = true },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
        )
        if (isDownloaded && onRemoveDownload != null) DropdownMenuItem(
            text = { Text(appString(R.string.text_remove_download_147742)) },
            onClick = { close(); onRemoveDownload() },
            leadingIcon = { Icon(Icons.Filled.DownloadDone, null, tint = MaterialTheme.colorScheme.primary) },
        ) else if (onDownload != null) DropdownMenuItem(
            text = { Text(appString(R.string.text_download_a479c9)) },
            onClick = { close(); onDownload() },
            leadingIcon = { Icon(Icons.Filled.Download, null) },
        )
        if (onGoToAlbum != null) DropdownMenuItem(
            text = { Text(appString(R.string.text_go_to_album_e2d3b3)) },
            onClick = { close(); onGoToAlbum() },
            leadingIcon = { Icon(Icons.Filled.Album, null) },
        )
        if (onGoToArtist != null) DropdownMenuItem(
            text = { Text(appString(R.string.text_go_to_artist_d8f70c)) },
            onClick = { close(); onGoToArtist() },
            leadingIcon = { Icon(Icons.Filled.Person, null) },
        )
        if (onEditTags != null && (song.streamUrl.startsWith("file:") || serverTagEditing)) DropdownMenuItem(
            text = { Text(appString(R.string.text_edit_tags_d8a5fc)) },
            onClick = { close(); onEditTags() },
            leadingIcon = { Icon(Icons.Filled.Edit, null) },
        )
    }
    key(song.id, song.playbackSource?.providerId) {
    Box(modifier.fillMaxWidth().pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                    event.changes.forEach { it.consume() }
                    contextAt = event.changes.first().position
                }
            }
        }
    }) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val table = maxWidth >= PageMetrics.SongTableMinWidth
    val albumColumn = showAlbum && table
    val dateColumn = showDateAdded && table
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PageMetrics.SongRowHeight)
            .clip(shape)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = RowInset, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null) {
            Box(Modifier.width(IndexWidth), contentAlignment = Alignment.CenterEnd) {
                if (isPlaying) Icon(Icons.Filled.GraphicEq, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                else Text(index.toString(), style = MaterialTheme.typography.labelLarge, color = muted, maxLines = 1)
            }
            Spacer(Modifier.width(CellGap))
        }
        if (showArt) {
            Box(contentAlignment = Alignment.Center) {
                Artwork(song.artworkUrl, song.accent, Modifier.size(ArtSize), corner = 8.dp)
                if (isPlaying && index == null) {
                    Box(
                        Modifier.size(ArtSize).clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.GraphicEq, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Spacer(Modifier.width(CellGap))
        }
        Column(Modifier.weight(if (albumColumn) TitleWeight else 1f)) {
            Text(
                song.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (song.explicit) {
                    Icon(Icons.Outlined.Explicit, null, tint = muted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                }
                if (isDownloaded) {
                    Icon(Icons.Filled.DownloadDone, appString(R.string.text_downloaded_c61970), tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(song.artist, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (albumColumn) {
            Text(
                song.album,
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(AlbumWeight).padding(start = ColumnGap),
            )
        }
        if (dateColumn) {
            Text(
                song.dateAddedSec.takeIf { it > 0 }?.let { addedFormat.format(Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault())) }.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(DateWidth).padding(start = ColumnGap),
            )
        }
        Spacer(Modifier.width(RowInset))
        RowAction(
            if (isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            appString(if (isLiked) R.string.text_remove_from_liked_9d1568 else R.string.text_add_to_liked_b99f26),
            tint = if (isLiked) MaterialTheme.colorScheme.primary else muted,
            visible = hovered || isLiked,
            onClick = onToggleLike,
        )
        Text(
            if (song.durationSec > 0) formatTime(song.durationSec) else "—",
            style = MaterialTheme.typography.labelMedium,
            color = muted,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(DurationWidth).padding(end = 8.dp),
        )
        Box {
            RowAction(Icons.Outlined.MoreVert, appString(R.string.text_more_4bab2d), tint = muted, visible = hovered || menuOpen) { menuOpen = true }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) { menuItems { menuOpen = false } }
        }
    }
    }
    contextAt?.let { at ->
        Box(Modifier.offset { at.round() }) {
            DropdownMenu(expanded = true, onDismissRequest = { contextAt = null }) { menuItems { contextAt = null } }
        }
    }
    }
    }
    if (showPlaylists) TrackPlaylistsSheet(song, onDismiss = { showPlaylists = false })
}

@Composable
fun SongListHeader(
    modifier: Modifier = Modifier,
    showIndex: Boolean = true,
    showArt: Boolean = true,
    showAlbum: Boolean = true,
    showDateAdded: Boolean = false,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val table = maxWidth >= PageMetrics.SongTableMinWidth
        val albumColumn = showAlbum && table
        val dateColumn = showDateAdded && table
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        val label: @Composable RowScope.(String, Modifier, TextAlign) -> Unit = { text, cell, align ->
            Text(text, style = MaterialTheme.typography.labelMedium, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = align, modifier = cell)
        }
        Column {
            Row(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = RowInset), verticalAlignment = Alignment.CenterVertically) {
                if (showIndex) {
                    label("#", Modifier.width(IndexWidth), TextAlign.End)
                    Spacer(Modifier.width(CellGap))
                }
                if (showArt) Spacer(Modifier.width(ArtSize + CellGap))
                label(appString(R.string.text_title_768e0c), Modifier.weight(if (albumColumn) TitleWeight else 1f), TextAlign.Start)
                if (albumColumn) label(appString(R.string.text_album_dfb4c9), Modifier.weight(AlbumWeight).padding(start = ColumnGap), TextAlign.Start)
                if (dateColumn) label(appString(R.string.text_date_added_56ab7a), Modifier.width(DateWidth).padding(start = ColumnGap), TextAlign.Start)
                Spacer(Modifier.width(RowInset + ActionSize))
                Box(Modifier.width(DurationWidth).padding(end = 8.dp), contentAlignment = Alignment.CenterEnd) {
                    Icon(Icons.Outlined.Schedule, null, tint = muted, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(ActionSize))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        }
    }
}

@Composable
private fun RowAction(icon: ImageVector, label: String, tint: Color, visible: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(ActionSize).alpha(if (visible) 1f else 0f).clip(CircleShape)
            .clickable(onClickLabel = label, onClick = onClick).pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun rowShape(): Shape = if (LocalUiPrefs.current.themeStyle == ThemeStyle.AURORA) RoundedCornerShape(12.dp) else MaterialTheme.shapes.small

@Composable
private fun MediaCard(width: Dp, onClick: () -> Unit, modifier: Modifier, content: @Composable ColumnScope.(art: Dp) -> Unit) {
    val aurora = LocalUiPrefs.current.themeStyle == ThemeStyle.AURORA
    val shape = if (aurora) RoundedCornerShape(16.dp) else MaterialTheme.shapes.medium
    Column(
        modifier = modifier
            .width(width)
            .clip(shape)
            .then(if (aurora) Modifier else Modifier.auroraPanel(shape))
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(if (aurora) 0.dp else CardPanelPadding),
    ) {
        content(if (aurora) width else width - CardPanelPadding * 2)
        if (aurora) Spacer(Modifier.height(6.dp))
    }
}

@Composable
fun PlaylistCard(
    playlist: Playlist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = PageMetrics.CardWidth,
) {
    MediaCard(width, onClick, modifier) { art ->
        Artwork(playlist.coverUrl, playlist.accent, Modifier.size(art), corner = 14.dp)
        Spacer(Modifier.height(8.dp))
        Text(playlist.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            playlist.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun AlbumCard(album: Album, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp = PageMetrics.CardWidth) {
    MediaCard(width, onClick, modifier) { art ->
        Box {
            Artwork(album.artworkUrl, MaterialTheme.colorScheme.secondary, Modifier.size(art), corner = 14.dp)
            val label = album.typeLabel.localizedMediaType()
            if (label != appString(R.string.text_album_dfb4c9)) {
                Text(
                    label.uppercase(),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
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
fun ArtistCircle(artist: Artist, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp = PageMetrics.CardWidth) {
    Column(
        modifier = modifier
            .width(width)
            .clip(if (LocalUiPrefs.current.themeStyle == ThemeStyle.AURORA) RoundedCornerShape(16.dp) else MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Artwork(artist.imageUrl, MaterialTheme.colorScheme.tertiary, Modifier.size(width), corner = width)
        Spacer(Modifier.height(8.dp))
        Text(artist.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(appString(R.string.text_artist_6c3f3d), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
