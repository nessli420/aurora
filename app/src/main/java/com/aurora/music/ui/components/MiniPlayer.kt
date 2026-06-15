package com.aurora.music.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aurora.music.data.MiniProgress
import com.aurora.music.data.MiniStyle
import com.aurora.music.ui.theme.LocalUiPrefs
import com.aurora.music.viewmodel.PlayerUiState

@Composable
fun MiniPlayer(
    state: PlayerUiState,
    onExpand: () -> Unit,
    onTogglePlay: () -> Unit,
    onToggleLike: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val song = state.current
    val ui = LocalUiPrefs.current
    val progress by animateFloatAsState(state.progress, label = "miniProgress")
    val likeTint by animateColorAsState(
        if (state.isCurrentLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "likeTint",
    )

    val artSize = when (ui.miniStyle) { MiniStyle.COMPACT -> 38.dp; MiniStyle.PROMINENT -> 54.dp; else -> 44.dp }
    val rowPad = when (ui.miniStyle) { MiniStyle.COMPACT -> 6.dp; MiniStyle.PROMINENT -> 12.dp; else -> 8.dp }
    val showLike = ui.miniStyle != MiniStyle.COMPACT

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        song.accent.copy(alpha = 0.28f),
                    )
                )
            )
            .clickable(onClick = onExpand),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(rowPad),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(song.artworkUrl, song.accent, Modifier.size(artSize), corner = 10.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    song.title,
                    style = if (ui.miniStyle == MiniStyle.PROMINENT) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showLike) {
                Icon(
                    imageVector = if (state.isCurrentLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = "Like",
                    tint = likeTint,
                    modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onToggleLike).padding(8.dp),
                )
            }
            Icon(
                imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = "Play/pause",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onTogglePlay).padding(6.dp),
            )
            Icon(
                imageVector = Icons.Filled.SkipNext,
                contentDescription = "Next",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onNext).padding(6.dp),
            )
        }
        if (ui.miniProgress != MiniProgress.NONE) {
            val barH = if (ui.miniProgress == MiniProgress.BAR) 5.dp else 2.dp
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .height(barH)
                    .clip(RoundedCornerShape(barH))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress)
                        .height(barH)
                        .clip(RoundedCornerShape(barH))
                        .background(song.accent),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}
