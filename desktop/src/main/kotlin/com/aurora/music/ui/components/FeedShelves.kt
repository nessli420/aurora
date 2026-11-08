package com.aurora.music.ui.components

import com.aurora.music.localization.appString
import com.aurora.music.ui.theme.paperClip
import com.aurora.music.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aurora.music.data.HomeFeedItem
import com.aurora.music.data.HomeFeedSection
import com.aurora.music.data.ThemeStyle
import com.aurora.music.model.Song
import com.aurora.music.model.accent
import com.aurora.music.ui.layout.PageMetrics
import com.aurora.music.ui.theme.LocalUiPrefs
import com.aurora.music.ui.theme.auroraPanel
import com.aurora.music.ui.theme.themedCorner

private const val GridRows = 4
private val GridRowHeight = 60.dp
private val GridCellWidth = 330.dp

// one streaming feed shelf; a shelf of songs reads like youtube music's quick picks, a compact grid you play from
@Composable
fun FeedSection(section: HomeFeedSection, bleed: Dp, onOpenDetail: (String, String) -> Unit, onPlayAll: (List<Song>, Int) -> Unit) {
    val tracks = remember(section) { section.items.filterIsInstance<HomeFeedItem.Track>().map { it.song } }
    Column {
        SectionHeader(section.title)
        if (section.subtitle.isNotBlank()) Text(section.subtitle,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 14.dp, top = 2.dp))
        Spacer(Modifier.height(PageMetrics.HeaderToContent))
        if (tracks.size == section.items.size && tracks.size >= GridRows * 2) {
            TrackGrid(tracks) { index -> onPlayAll(tracks, index) }
        } else AdaptiveShelf(section.items, bleed = bleed, key = { it.key }) { entry, width ->
            when (entry) {
                is HomeFeedItem.Record -> AlbumCard(entry.album, { onOpenDetail("album", entry.album.id) }, width = width)
                is HomeFeedItem.Collection -> PlaylistCard(entry.playlist, { onOpenDetail("playlist", entry.playlist.id) }, width = width)
                is HomeFeedItem.Performer -> ArtistCircle(entry.artist, { onOpenDetail("artist", entry.artist.id) }, width = width)
                is HomeFeedItem.Track -> FeedTrackCard(entry.song, width) {
                    onPlayAll(tracks, tracks.indexOfFirst { it.id == entry.song.id }.coerceAtLeast(0))
                }
            }
        }
    }
}

@Composable
fun TrackGrid(tracks: List<Song>, onPlay: (Int) -> Unit) {
    val rows = GridRows.coerceAtMost(tracks.size)
    LazyHorizontalGrid(
        rows = GridCells.Fixed(rows),
        modifier = Modifier.fillMaxWidth().height(GridRowHeight * rows + 4.dp * (rows - 1)),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(tracks.size, key = { tracks[it].id }) { index -> TrackGridRow(tracks[index]) { onPlay(index) } }
    }
}

@Composable
private fun TrackGridRow(song: Song, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        Modifier.width(GridCellWidth).height(GridRowHeight).paperClip(RoundedCornerShape(themedCorner(10.dp)))
            .background(if (hovered) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f) else MaterialTheme.colorScheme.surface.copy(alpha = 0f))
            .hoverable(hover).clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Artwork(song.artworkUrl, song.accent, Modifier.size(48.dp), corner = 6.dp)
            if (hovered) Box(Modifier.size(48.dp).paperClip(RoundedCornerShape(themedCorner(6.dp)))
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, appString(R.string.text_play_5d12bd), tint = androidx.compose.ui.graphics.Color.White)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun FeedTrackCard(song: Song, width: Dp, onClick: () -> Unit) {
    val aurora = LocalUiPrefs.current.themeStyle == ThemeStyle.AURORA
    val shape = if (aurora) RoundedCornerShape(themedCorner(16.dp)) else MaterialTheme.shapes.medium
    val inset = if (aurora) 0.dp else 8.dp
    val art = width - inset * 2
    Column(
        Modifier.width(width).clip(shape)
            .then(if (aurora) Modifier else Modifier.auroraPanel(shape))
            .clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
            .padding(inset),
    ) {
        Box {
            Artwork(song.artworkUrl, song.accent, Modifier.size(art), corner = 14.dp)
            Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).size(34.dp).paperClip(CircleShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, appString(R.string.text_play_5d12bd), tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(song.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(song.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (aurora) Spacer(Modifier.height(6.dp))
    }
}

// a row of choices such as which library or feed to show
@Composable
fun ChoiceTabs(options: List<Triple<String, String, ImageVector>>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
        items(options.size) { i ->
            val (id, label, icon) = options[i]
            val active = id == selected
            Row(
                Modifier.height(36.dp).paperClip(RoundedCornerShape(50))
                    .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onSelect(id) }.pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, Modifier.size(18.dp), tint = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                    color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

fun feedIcon(id: String): ImageVector = when (id) {
    "discovery", "youtube" -> Icons.Outlined.PlayCircle
    "spotify" -> Icons.Outlined.GraphicEq
    "soundcloud" -> Icons.Outlined.Cloud
    else -> Icons.Outlined.LibraryMusic
}

// starts a radio from a song wherever a song offers its menu; absent where radio makes no sense
val LocalStartRadio = androidx.compose.runtime.staticCompositionLocalOf<((Song) -> Unit)?> { null }
