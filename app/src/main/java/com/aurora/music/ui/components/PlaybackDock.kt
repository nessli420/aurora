package com.aurora.music.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aurora.music.R
import com.aurora.music.data.ThemeStyle
import com.aurora.music.localization.appString
import com.aurora.music.ui.screens.player.PlayerPane
import com.aurora.music.ui.theme.LocalUiPrefs
import com.aurora.music.ui.theme.auroraPanel
import com.aurora.music.viewmodel.PlayerUiState
import kotlinx.coroutines.delay
import kotlin.math.abs
import com.aurora.music.model.accent

@Composable
fun PlaybackDock(
    state: PlayerUiState,
    openPane: PlayerPane?,
    onExpand: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    onToggleLike: () -> Unit,
    onOpenOutput: () -> Unit,
    onPane: (PlayerPane) -> Unit,
    modifier: Modifier = Modifier,
) {
    val song = state.current
    val isMix = song.id.startsWith("aurora-mix:")
    val ui = LocalUiPrefs.current
    val classic = ui.themeStyle == ThemeStyle.AURORA
    val colors = MaterialTheme.colorScheme
    val scale = ui.tabletDockScale
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .then(
                if (classic) Modifier.clip(RoundedCornerShape(22.dp)).background(
                    Brush.horizontalGradient(listOf(colors.surfaceContainerHigh, lerp(colors.surfaceContainerHigh, colors.primary, 0.14f)))
                ) else Modifier.auroraPanel(MaterialTheme.shapes.extraLarge, emphasized = true)
            ),
    ) {
        val roomy = maxWidth >= 760.dp
        Row(
            Modifier.fillMaxWidth().heightIn(min = 84.dp * scale).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f, fill = false).clip(RoundedCornerShape(14.dp))
                        .clickable(onClickLabel = appString(R.string.tablet_open_now_playing), onClick = onExpand)
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(song.artworkUrl, song.accent, Modifier.size((if (roomy) 62.dp else 54.dp) * scale), corner = 10.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f, fill = false)) {
                        Text(song.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                            color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(song.artist, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (!isMix) {
                    val likeTint by animateColorAsState(if (state.isCurrentLiked) colors.primary else colors.onSurfaceVariant, label = "dockLike")
                    IconButton(onClick = onToggleLike) {
                        Icon(if (state.isCurrentLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            appString(R.string.text_like_c7e02c), tint = likeTint, modifier = Modifier.size(22.dp))
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(if (roomy) 1.3f else 0.9f).widthIn(max = 560.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!isMix) IconButton(onClick = onPrevious) {
                        Icon(Icons.Filled.SkipPrevious, appString(R.string.text_previous_50f942), tint = colors.onSurface)
                    }
                    FilledIconButton(
                        onClick = onTogglePlay,
                        modifier = Modifier.size((48.dp * scale).coerceAtLeast(36.dp)),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
                    ) {
                        Icon(if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            appString(R.string.text_play_pause_14a1d0), modifier = Modifier.size(26.dp * scale))
                    }
                    if (!isMix) IconButton(onClick = onNext) {
                        Icon(Icons.Filled.SkipNext, appString(R.string.text_next_bc9819), tint = colors.onSurface)
                    }
                }
                DockSeek(state, onSeek, showTimes = roomy)
            }
            Spacer(Modifier.width(8.dp))
            Row(if (roomy) Modifier.weight(1f) else Modifier, horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (roomy) IconButton(onClick = onOpenOutput) {
                    Icon(Icons.Filled.Speaker, appString(R.string.text_output_device_709178), tint = colors.onSurfaceVariant)
                }
                PaneToggle(PlayerPane.LYRICS, Icons.Filled.Lyrics, openPane == PlayerPane.LYRICS) { onPane(PlayerPane.LYRICS) }
                PaneToggle(PlayerPane.QUEUE, Icons.AutoMirrored.Filled.QueueMusic, openPane == PlayerPane.QUEUE) { onPane(PlayerPane.QUEUE) }
                if (roomy) IconButton(onClick = onExpand) {
                    Icon(Icons.Filled.OpenInFull, appString(R.string.tablet_open_now_playing), tint = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun PaneToggle(pane: PlayerPane, icon: androidx.compose.ui.graphics.vector.ImageVector, checked: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    IconToggleButton(
        checked = checked,
        onCheckedChange = { onClick() },
        colors = IconButtonDefaults.iconToggleButtonColors(
            contentColor = colors.onSurfaceVariant,
            checkedContentColor = colors.primary,
        ),
        modifier = Modifier.then(if (checked) Modifier.clip(CircleShape).background(colors.primary.copy(alpha = 0.14f)) else Modifier),
    ) { Icon(icon, pane.label) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DockSeek(state: PlayerUiState, onSeek: (Float) -> Unit, showTimes: Boolean) {
    val colors = MaterialTheme.colorScheme
    val duration = state.durationSec
    if (duration <= 0) {
        Row(Modifier.height(24.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.isLive) Box(Modifier.size(8.dp).clip(CircleShape).background(colors.primary))
            Spacer(Modifier.width(6.dp))
            Text(if (state.isLive) appString(R.string.text_live_6990f0) else appString(R.string.text_streaming_1e8325),
                style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = colors.primary)
        }
        return
    }
    val seekTarget = com.aurora.music.ui.theme.LocalContextAccent.current.color ?: colors.primary
    val seekBrush = com.aurora.music.ui.theme.rememberColorSweep(seekTarget)
    val thumbColor by animateColorAsState(seekTarget, androidx.compose.animation.core.tween(750), label = "dockThumb")
    var dragging by remember(state.current.id) { mutableStateOf<Float?>(null) }
    var pending by remember(state.current.id) { mutableStateOf<Float?>(null) }
    LaunchedEffect(state.progress) {
        pending?.let { if (abs(state.progress - it) <= 0.02f) pending = null }
    }
    LaunchedEffect(pending) {
        if (pending != null) { delay(1500); pending = null }
    }
    val value = dragging ?: pending ?: state.progress
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (showTimes) Text(formatTime((value * duration).toInt()), style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant, modifier = Modifier.widthIn(min = 40.dp))
        Slider(
            value = value.coerceIn(0f, 1f),
            onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { onSeek(it); pending = it }; dragging = null },
            modifier = Modifier.weight(1f).height(24.dp).semantics { contentDescription = appString(R.string.tablet_seek) },
            colors = SliderDefaults.colors(thumbColor = colors.primary, activeTrackColor = colors.primary),
            thumb = { Box(Modifier.size(12.dp).clip(CircleShape).background(thumbColor)) },
            track = { slider ->
                Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(colors.onSurface.copy(alpha = 0.14f))) {
                    Box(Modifier.fillMaxWidth(slider.value.coerceIn(0f, 1f)).fillMaxHeight().background(seekBrush))
                }
            },
        )
        if (showTimes) Text(formatTime(duration), style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant, modifier = Modifier.widthIn(min = 40.dp).padding(start = 6.dp))
    }
}
