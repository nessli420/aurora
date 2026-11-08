package com.aurora.music.ui.components

import com.aurora.music.localization.appString
import com.aurora.music.ui.theme.paperClip
import com.aurora.music.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aurora.music.data.HomeFeedItem
import com.aurora.music.data.HomeFeedSection
import com.aurora.music.model.Song
import com.aurora.music.model.accent

val LocalStartRadio = androidx.compose.runtime.staticCompositionLocalOf<((Song) -> Unit)?> { null }

private const val GridRows = 4
private val GridRowHeight = 60.dp
private val GridCellWidth = 330.dp

// one streaming feed shelf as home shows it; a shelf of songs reads like youtube music's quick picks, a compact grid you play from
@Composable
fun FeedSection(
    section: HomeFeedSection,
    onOpenDetail: (String, String) -> Unit,
    onPlayAll: (List<Song>, Int) -> Unit,
    gutter: Dp = 16.dp,
) {
    val tracks = remember(section) { section.items.filterIsInstance<HomeFeedItem.Track>().map { it.song } }
    Column {
        SectionHeader(section.title, Modifier.padding(horizontal = gutter))
        if (section.subtitle.isNotBlank()) Text(section.subtitle,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = gutter, vertical = 4.dp))
        Spacer(Modifier.height(10.dp))
        if (tracks.size == section.items.size && tracks.size >= GridRows * 2) {
            TrackGrid(tracks, gutter) { index -> onPlayAll(tracks, index) }
        } else LazyRow(contentPadding = PaddingValues(horizontal = (gutter - 8.dp).coerceAtLeast(0.dp))) {
            items(section.items.size, key = { section.items[it].key }) { index ->
                when (val entry = section.items[index]) {
                    is HomeFeedItem.Record -> AlbumCard(entry.album, { onOpenDetail("album", entry.album.id) })
                    is HomeFeedItem.Collection -> PlaylistCard(entry.playlist, { onOpenDetail("playlist", entry.playlist.id) })
                    is HomeFeedItem.Performer -> ArtistCircle(entry.artist, { onOpenDetail("artist", entry.artist.id) })
                    is HomeFeedItem.Track -> FeedTrackCard(entry.song) {
                        onPlayAll(tracks, tracks.indexOfFirst { it.id == entry.song.id }.coerceAtLeast(0))
                    }
                }
            }
        }
    }
}

// on a phone a column is a little narrower than the screen, so the next one peeks in
@Composable
fun TrackGrid(tracks: List<Song>, gutter: Dp, onPlay: (Int) -> Unit) {
    val rows = GridRows.coerceAtMost(tracks.size)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cell = GridCellWidth.coerceAtMost(maxWidth - gutter * 2 - 40.dp)
        LazyHorizontalGrid(
            rows = GridCells.Fixed(rows),
            modifier = Modifier.fillMaxWidth().height(GridRowHeight * rows + 4.dp * (rows - 1)),
            contentPadding = PaddingValues(horizontal = (gutter - 6.dp).coerceAtLeast(0.dp)),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(tracks.size, key = { tracks[it].id }) { index -> TrackGridRow(tracks[index], cell) { onPlay(index) } }
        }
    }
}

@Composable
private fun TrackGridRow(song: Song, width: Dp, onClick: () -> Unit) {
    Row(
        Modifier.width(width).height(GridRowHeight).clip(MaterialTheme.shapes.small).clickable(onClick = onClick).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(song.artworkUrl, song.accent, Modifier.size(48.dp), corner = 6.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun FeedTrackCard(song: Song, onClick: () -> Unit) {
    Column(Modifier.width(172.dp).clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).padding(8.dp)) {
        Box {
            Artwork(song.artworkUrl, song.accent, Modifier.size(156.dp), corner = 14.dp)
            Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).size(34.dp).paperClip(CircleShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, appString(R.string.text_play_5d12bd), tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(song.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(song.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
