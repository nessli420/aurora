package com.aurora.music.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aurora.music.data.DuplicateGroup
import com.aurora.music.model.Song
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.LottieLoader
import com.aurora.music.ui.screens.settings.SettingsTopBar

/** Result list of the duplicate scan (4.3): one card per suspected-duplicate group. */
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
    Column(Modifier.fillMaxSize()) {
        SettingsTopBar(title = "Duplicates", onBack = onBack)
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LottieLoader(modifier = Modifier.size(72.dp)) }
            groups.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No duplicates found across $scanned tracks", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                val dupCount = groups.sumOf { it.songs.size }
                LazyColumn(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
                ) {
                    item {
                        Text(
                            "${groups.size} group${if (groups.size == 1) "" else "s"} • $dupCount tracks • $scanned scanned",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                    items(groups.size) { i -> GroupCard(groups[i], currentSongId, onPlay) }
                }
            }
        }
    }
}

@Composable
private fun GroupCard(group: DuplicateGroup, currentSongId: String, onPlay: (Song) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.45f))
            .padding(12.dp),
    ) {
        Text(group.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            "${group.artist} • ${group.songs.size} copies",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        group.songs.forEach { s ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPlay(s) }.padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(s.artworkUrl, s.accent, Modifier.size(40.dp), corner = 10.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.album.ifBlank { "Unknown album" }, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(specLine(s), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (s.id == currentSongId) {
                    Icon(Icons.Filled.MusicNote, "Playing", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

private fun specLine(s: Song): String {
    val parts = mutableListOf<String>()
    if (s.suffix.isNotBlank()) parts += s.suffix.uppercase()
    if (s.bitrateKbps > 0) parts += "${s.bitrateKbps} kbps"
    if (s.durationSec > 0) parts += "%d:%02d".format(s.durationSec / 60, s.durationSec % 60)
    return parts.joinToString(" • ").ifBlank { "Unknown format" }
}
