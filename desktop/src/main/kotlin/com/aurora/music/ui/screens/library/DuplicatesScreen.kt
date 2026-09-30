package com.aurora.music.ui.screens.library

import com.aurora.music.localization.appPlural

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
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
import com.aurora.music.data.DuplicateGroup
import com.aurora.music.model.Song
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.LottieLoader
import com.aurora.music.ui.components.PageHeader
import com.aurora.music.data.ThemeStyle
import com.aurora.music.ui.layout.LocalPageGutter
import com.aurora.music.ui.theme.LocalUiPrefs
import com.aurora.music.ui.theme.auroraPanel
import com.aurora.music.model.accent

@Composable
fun DuplicatesScreen(
    contentPadding: PaddingValues,
    loading: Boolean,
    scanned: Int,
    groups: List<DuplicateGroup>,
    currentSongId: String,
    onBack: () -> Unit,
    onPlay: (Song) -> Unit,
) {
    val gutter = LocalPageGutter.current
    Column(Modifier.fillMaxSize()) {
        val counts = if (loading || groups.isEmpty()) null else appString(
            R.string.duplicates_counts, appPlural(R.plurals.group_count, (groups.size)), appPlural(R.plurals.track_count, (groups.sumOf { it.songs.size })), (scanned),
        )
        PageHeader(appString(R.string.text_duplicates_889a9c), Modifier.padding(horizontal = gutter), subtitle = counts, onBack = onBack)
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LottieLoader(modifier = Modifier.size(72.dp)) }
            groups.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(appString(R.string.text_no_duplicates_found_across_tracks_8af6bb, (scanned)), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                val bottom = contentPadding.calculateBottomPadding() + 24.dp
                val gridState = rememberLazyGridState()
                Box(Modifier.fillMaxSize()) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(380.dp),
                        modifier = Modifier.fillMaxSize(),
                        state = gridState,
                        contentPadding = PaddingValues(start = gutter, end = gutter, top = 12.dp, bottom = bottom),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(groups.size) { i -> GroupCard(groups[i], currentSongId, onPlay) }
                    }
                    PaneScrollbar(rememberScrollbarAdapter(gridState), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(bottom = bottom))
                }
            }
        }
    }
}

@Composable
private fun GroupCard(group: DuplicateGroup, currentSongId: String, onPlay: (Song) -> Unit) {
    val aurora = LocalUiPrefs.current.themeStyle == ThemeStyle.AURORA
    val shape = if (aurora) RoundedCornerShape(18.dp) else MaterialTheme.shapes.medium
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (aurora) Modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.45f)) else Modifier.auroraPanel(shape))
            .padding(12.dp),
    ) {
        Text(group.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            appString(R.string.text_copies_0ebf61, (group.artist), (group.songs.size)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        group.songs.forEach { s ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPlay(s) }.pointerHoverIcon(PointerIcon.Hand).padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(s.artworkUrl, s.accent, Modifier.size(40.dp), corner = 10.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.album.ifBlank { appString(R.string.text_unknown_album_feeeda) }, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(specLine(s), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (s.id == currentSongId) {
                    Icon(Icons.Filled.MusicNote, appString(R.string.text_playing_298c39), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

private fun specLine(s: Song): String {
    val parts = mutableListOf<String>()
    if (s.suffix.isNotBlank()) parts += s.suffix.uppercase()
    if (s.bitrateKbps > 0) parts += appString(R.string.text_kbps_f89f2e, (s.bitrateKbps))
    if (s.durationSec > 0) parts += "%d:%02d".format(s.durationSec / 60, s.durationSec % 60)
    return parts.joinToString(" • ").ifBlank { appString(R.string.text_unknown_format_13526e) }
}
