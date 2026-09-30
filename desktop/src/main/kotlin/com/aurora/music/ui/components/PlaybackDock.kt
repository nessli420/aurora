package com.aurora.music.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import com.aurora.music.R
import com.aurora.music.data.ThemeStyle
import com.aurora.music.desktop.player.PlayerUiState
import com.aurora.music.desktop.player.RepeatMode
import com.aurora.music.localization.appString
import com.aurora.music.model.accent
import com.aurora.music.ui.screens.player.PlayerPane
import com.aurora.music.ui.screens.player.volumeIcon
import com.aurora.music.ui.theme.LocalUiPrefs
import com.aurora.music.ui.theme.auroraPanel
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

private val DockButton = 48.dp
private const val DockCenterShare = 1.4f / 3.4f

@Composable
fun PlaybackDock(
    state: PlayerUiState,
    openPane: PlayerPane?,
    volume: Float,
    onExpand: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    onToggleLike: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onOpenOutput: () -> Unit,
    onPane: (PlayerPane) -> Unit,
    modifier: Modifier = Modifier,
    onOpenArtist: (() -> Unit)? = null,
) {
    val song = state.current
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
        val showExpand = maxWidth >= 1400.dp
        val volumeWidth = if (maxWidth >= 1100.dp) 140.dp else 104.dp
        val trailing = DockButton * (if (roomy) 4 else 2) + (if (roomy) volumeWidth else 0.dp) + (if (showExpand) DockButton else 0.dp)
        val inner = maxWidth - 32.dp
        val center = (inner * DockCenterShare).coerceAtMost(680.dp)
        val side = if (roomy) maxOf((inner - center) / 2, trailing) else trailing
        Row(
            Modifier.fillMaxWidth().heightIn(min = 84.dp * scale).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f, fill = false).clip(RoundedCornerShape(14.dp))
                        .clickable(onClickLabel = appString(R.string.tablet_open_now_playing), onClick = onExpand)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(song.artworkUrl, song.accent, Modifier.size((if (roomy) 62.dp else 54.dp) * scale), corner = 10.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f, fill = false)) {
                        Text(song.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                            color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        DockArtist(song.artist, if (song.artistId.isNotBlank()) onOpenArtist else null)
                    }
                }
                val likeTint by animateColorAsState(if (state.isCurrentLiked) colors.primary else colors.onSurfaceVariant, label = "dockLike")
                IconButton(onClick = onToggleLike) {
                    Icon(if (state.isCurrentLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        appString(R.string.text_like_c7e02c), tint = likeTint, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.width(center), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = onToggleShuffle) {
                        Icon(Icons.Filled.Shuffle, appString(R.string.text_shuffle_5b772b), modifier = Modifier.size(20.dp),
                            tint = if (state.shuffle) colors.primary else colors.onSurfaceVariant)
                    }
                    IconButton(onClick = onPrevious) {
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
                    IconButton(onClick = onNext) {
                        Icon(Icons.Filled.SkipNext, appString(R.string.text_next_bc9819), tint = colors.onSurface)
                    }
                    IconButton(onClick = onCycleRepeat) {
                        Icon(if (state.repeat == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                            appString(R.string.text_repeat_659eba), modifier = Modifier.size(20.dp),
                            tint = if (state.repeat != RepeatMode.OFF) colors.primary else colors.onSurfaceVariant)
                    }
                }
                DockSeek(state, onSeek, showTimes = roomy)
            }
            Spacer(Modifier.width(8.dp))
            Row(Modifier.width(side), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                PaneToggle(PlayerPane.LYRICS, Icons.Filled.Lyrics, openPane == PlayerPane.LYRICS) { onPane(PlayerPane.LYRICS) }
                PaneToggle(PlayerPane.QUEUE, Icons.AutoMirrored.Filled.QueueMusic, openPane == PlayerPane.QUEUE) { onPane(PlayerPane.QUEUE) }
                if (roomy) IconButton(onClick = onOpenOutput) {
                    Icon(Icons.Filled.Speaker, appString(R.string.text_output_device_709178), tint = colors.onSurfaceVariant)
                }
                if (roomy) VolumeControl(volume, onVolumeChange, onToggleMute, volumeWidth)
                if (showExpand) IconButton(onClick = onExpand) {
                    Icon(Icons.Filled.OpenInFull, appString(R.string.tablet_open_now_playing), tint = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun DockArtist(artist: String, onOpenArtist: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val link = onOpenArtist != null
    Text(
        artist, style = MaterialTheme.typography.bodySmall,
        color = if (link && hovered) colors.onSurface else colors.onSurfaceVariant,
        textDecoration = if (link && hovered) TextDecoration.Underline else null,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = if (onOpenArtist == null) Modifier else Modifier
            .clickable(interactionSource = hover, indication = null, onClickLabel = appString(R.string.text_go_to_artist_d8f70c), onClick = onOpenArtist)
            .pointerHoverIcon(PointerIcon.Hand),
    )
}

@Composable
private fun PaneToggle(pane: PlayerPane, icon: ImageVector, checked: Boolean, onClick: () -> Unit) {
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun VolumeControl(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    sliderWidth: Dp,
    modifier: Modifier = Modifier,
    fill: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val colors = MaterialTheme.colorScheme
    val current by rememberUpdatedState(volume)
    val change by rememberUpdatedState(onVolumeChange)
    val hover = remember { MutableInteractionSource() }
    val touch = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val dragged by touch.collectIsDraggedAsState()
    val pressed by touch.collectIsPressedAsState()
    val level = volume.coerceIn(0f, 1f)
    Row(
        modifier.hoverable(hover).onPointerEvent(PointerEventType.Scroll) { event ->
            val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
            if (delta != 0f) change((current - delta * 0.05f).coerceIn(0f, 1f))
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onToggleMute) {
            Icon(volumeIcon(volume), appString(R.string.text_mute_0f0973), tint = colors.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
        Box(Modifier.width(sliderWidth), contentAlignment = Alignment.Center) {
            Slider(
                value = level,
                onValueChange = onVolumeChange,
                interactionSource = touch,
                modifier = Modifier.fillMaxWidth().height(24.dp).semantics { contentDescription = appString(R.string.text_volume_3b18e8) },
                colors = SliderDefaults.colors(thumbColor = colors.primary, activeTrackColor = colors.primary),
                thumb = { Box(Modifier.size(12.dp).clip(CircleShape).background(colors.onSurface)) },
                track = { slider ->
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(colors.onSurface.copy(alpha = 0.14f))) {
                        Box(Modifier.fillMaxWidth(slider.value.coerceIn(0f, 1f)).fillMaxHeight().background(fill))
                    }
                },
            )
            if (hovered || dragged || pressed) VolumeTip(level, sliderWidth)
        }
    }
}

@Composable
private fun VolumeTip(level: Float, width: Dp) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val gap = with(density) { 6.dp.roundToPx() }
    val thumbX = with(density) { (6.dp + (width - 12.dp) * level).roundToPx() }
    val provider = remember(thumbX, gap) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
                IntOffset(
                    (anchorBounds.left + thumbX - popupContentSize.width / 2).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                    anchorBounds.top - popupContentSize.height - gap,
                )
        }
    }
    Popup(popupPositionProvider = provider) {
        Text(
            "${(level * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = colors.inverseOnSurface,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(colors.inverseSurface).padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
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
