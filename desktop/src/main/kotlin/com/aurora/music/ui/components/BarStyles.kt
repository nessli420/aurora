package com.aurora.music.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.R
import com.aurora.music.data.BarStyle
import com.aurora.music.data.MiniProgress
import com.aurora.music.data.MiniStyle
import com.aurora.music.data.Preservation
import com.aurora.music.desktop.player.PlayerUiState
import com.aurora.music.desktop.player.RepeatMode
import com.aurora.music.desktop.ui.LocalPlayer
import com.aurora.music.localization.appString
import com.aurora.music.model.accent
import com.aurora.music.ui.layout.TabletMetrics
import com.aurora.music.ui.screens.home.spotlightTint
import com.aurora.music.ui.screens.player.PlayerPane
import com.aurora.music.ui.theme.LocalGlass
import com.aurora.music.ui.theme.LocalUiPrefs
import com.aurora.music.ui.theme.Motion
import com.aurora.music.ui.theme.PaperTokens
import com.aurora.music.ui.theme.auroraPanel
import com.aurora.music.ui.theme.isPaperTheme
import com.aurora.music.ui.theme.paperFrame
import com.aurora.music.ui.theme.paperSquare
import com.aurora.music.ui.theme.themedCorner
import com.aurora.music.util.MotionTokens
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
fun StyledDock(
    style: Int, djMode: Boolean, state: PlayerUiState, openPane: PlayerPane?, volume: Float,
    onExpand: () -> Unit, onTogglePlay: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit, onSeek: (Float) -> Unit,
    onToggleLike: () -> Unit, onToggleShuffle: () -> Unit, onCycleRepeat: () -> Unit, onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit, onOpenOutput: () -> Unit, onPane: (PlayerPane) -> Unit, modifier: Modifier = Modifier, onOpenArtist: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    when (style) {
        BarStyle.SPOTLIGHT -> {
            val tint by animateColorAsState(spotlightTint(state.current.artworkUrl, state.current.accent), Motion.state(MotionTokens.SLOW), label = "dockTint")
            // the accent has no sure contrast on a cover's colour, so the marks that use it take the text colour
            MaterialTheme(colorScheme = remember(colors) { colors.copy(primary = colors.onSurface, onSurfaceVariant = colors.onSurface.copy(alpha = 0.62f)) }) {
                PlaybackDock(djMode, state, openPane, volume, onExpand, onTogglePlay, onPrevious, onNext, onSeek, onToggleLike, onToggleShuffle, onCycleRepeat,
                    onVolumeChange, onToggleMute, onOpenOutput, onPane, modifier, onOpenArtist,
                    surface = Modifier.paperFrame(RectangleShape, { background(tint) }, fill = tint).padding(horizontal = 12.dp),
                    play = colors.inverseSurface, onPlay = colors.inverseOnSurface, playShape = paperSquare(CircleShape))
            }
        }
        BarStyle.CONSOLE -> ConsoleDock(djMode, state, openPane, volume, onExpand, onTogglePlay, onPrevious, onNext, onSeek, onToggleLike, onToggleShuffle,
            onCycleRepeat, onVolumeChange, onToggleMute, onOpenOutput, onPane, modifier, onOpenArtist)
        BarStyle.DRIFT -> DriftDock(djMode, state, openPane, volume, onExpand, onTogglePlay, onPrevious, onNext, onSeek, onToggleLike, onToggleShuffle,
            onCycleRepeat, onVolumeChange, onToggleMute, onOpenOutput, onPane, modifier, onOpenArtist)
        else -> PlaybackDock(djMode, state, openPane, volume, onExpand, onTogglePlay, onPrevious, onNext, onSeek, onToggleLike, onToggleShuffle, onCycleRepeat,
            onVolumeChange, onToggleMute, onOpenOutput, onPane, modifier, onOpenArtist)
    }
}

@Composable
private fun ConsoleDock(
    djMode: Boolean, state: PlayerUiState, openPane: PlayerPane?, volume: Float,
    onExpand: () -> Unit, onTogglePlay: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit, onSeek: (Float) -> Unit,
    onToggleLike: () -> Unit, onToggleShuffle: () -> Unit, onCycleRepeat: () -> Unit, onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit, onOpenOutput: () -> Unit, onPane: (PlayerPane) -> Unit, modifier: Modifier, onOpenArtist: (() -> Unit)?,
) {
    val song = state.current
    val ui = LocalUiPrefs.current
    val colors = MaterialTheme.colorScheme
    val scale = ui.tabletDockScale
    val compact = ui.miniStyle == MiniStyle.COMPACT
    val prominent = ui.miniStyle == MiniStyle.PROMINENT
    val progress = ui.miniProgress != MiniProgress.NONE
    val modes = !djMode && !state.skipButtons
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val roomy = maxWidth >= 760.dp
        val showExpand = maxWidth >= 1400.dp
        val volumeWidth = if (maxWidth >= 1100.dp) 140.dp else 104.dp
        val trailing = DockButton * ((if (modes) 2 else 0) + (if (roomy) 4 else 2) + (if (showExpand) 1 else 0)) + (if (roomy) volumeWidth else 0.dp)
        val center = (if (state.skipButtons) 288.dp else 176.dp) + (if (roomy && progress) 120.dp else 0.dp)
        val side = maxOf(trailing, (maxWidth - 32.dp - center) / 2)
        val lead = maxWidth - 32.dp - center - side
        Row(
            Modifier.fillMaxWidth().auroraPanel(RectangleShape, emphasized = true)
                .heightIn(min = (if (compact) 60.dp else if (prominent) 84.dp else 72.dp) * scale).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                DockTrack(state, onExpand, onOpenArtist, Modifier.weight(1f, fill = false)) {
                    Artwork(song.artworkUrl, song.accent, Modifier.size((if (compact) 40.dp else if (prominent) 60.dp else 48.dp) * scale), corner = 10.dp)
                }
                if (!compact) DockLike(state, onToggleLike)
                if (lead >= 400.dp) SignalChips(format = lead >= 550.dp)
            }
            Row(Modifier.width(center), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                DockTransport(djMode, state, onTogglePlay, onPrevious, onNext, onSeek, onToggleShuffle, onCycleRepeat, modes = false,
                    play = colors.inverseSurface, onPlay = colors.inverseOnSurface, playShape = paperSquare(RoundedCornerShape(themedCorner(14.dp))))
                if (roomy && progress) {
                    Spacer(Modifier.width(12.dp))
                    if (state.durationSec > 0) Text("${formatTime(state.positionSec)} / ${formatTime(state.durationSec)}",
                        style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1)
                    else DockSeek(state, onSeek, showTimes = false)
                }
            }
            Row(Modifier.width(side), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (modes) {
                    DockShuffle(state, onToggleShuffle)
                    DockRepeat(state, onCycleRepeat)
                }
                PaneToggle(PlayerPane.LYRICS, Icons.Filled.Lyrics, openPane == PlayerPane.LYRICS) { onPane(PlayerPane.LYRICS) }
                if (!djMode) PaneToggle(PlayerPane.QUEUE, Icons.AutoMirrored.Filled.QueueMusic, openPane == PlayerPane.QUEUE) { onPane(PlayerPane.QUEUE) }
                if (roomy) DockIcon(Icons.Filled.Speaker, appString(R.string.text_output_device_709178), onOpenOutput)
                if (roomy) VolumeControl(volume, onVolumeChange, onToggleMute, volumeWidth)
                if (showExpand) DockIcon(Icons.Filled.OpenInFull, appString(R.string.tablet_open_now_playing), onExpand)
            }
        }
        if (progress && state.durationSec > 0) {
            val thick = ui.miniProgress == MiniProgress.BAR
            // the slider insets its track by half a thumb, so it is that much wider than the dock to run edge to edge
            Box(Modifier.align(Alignment.TopCenter).requiredWidth(maxWidth + if (thick) 14.dp else 12.dp).offset(y = (-12).dp)) {
                DockSeek(state, onSeek, showTimes = false, thick = thick)
            }
        }
    }
}

@Composable
private fun DriftDock(
    djMode: Boolean, state: PlayerUiState, openPane: PlayerPane?, volume: Float,
    onExpand: () -> Unit, onTogglePlay: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit, onSeek: (Float) -> Unit,
    onToggleLike: () -> Unit, onToggleShuffle: () -> Unit, onCycleRepeat: () -> Unit, onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit, onOpenOutput: () -> Unit, onPane: (PlayerPane) -> Unit, modifier: Modifier, onOpenArtist: (() -> Unit)?,
) {
    val song = state.current
    val ui = LocalUiPrefs.current
    val colors = MaterialTheme.colorScheme
    val scale = ui.tabletDockScale
    val compact = ui.miniStyle == MiniStyle.COMPACT
    val seek = ui.miniProgress != MiniProgress.NONE
    val modes = !djMode && !state.skipButtons
    val square = isPaperTheme()
    val shape = paperSquare(CircleShape)
    var more by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier.fillMaxWidth().padding(bottom = 10.dp), contentAlignment = Alignment.Center) {
        val width = minOf(maxWidth, 920.dp)
        val wide = width >= 900.dp
        Row(
            Modifier.width(width).then(if (square || LocalGlass.current) Modifier else Modifier.shadow(16.dp, shape))
                .auroraPanel(shape, emphasized = true)
                .heightIn(min = (if (compact) 64.dp else if (ui.miniStyle == MiniStyle.PROMINENT) 88.dp else 76.dp) * scale)
                .padding(start = 8.dp * scale - 4.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DockTrack(state, onExpand, onOpenArtist, if (seek) Modifier.width(if (wide) 200.dp else 170.dp) else Modifier.weight(1f), shape) {
                Box(Modifier.size((if (compact) 48.dp else 60.dp) * scale)
                    .then(if (seek) Modifier.progressRing(state.progress, colors.primary, colors.onSurface.copy(alpha = 0.14f), square) else Modifier)) {
                    Artwork(song.artworkUrl, song.accent, Modifier.fillMaxSize().padding(5.dp).clip(shape), corner = 0.dp)
                }
            }
            DockTransport(djMode, state, onTogglePlay, onPrevious, onNext, onSeek, onToggleShuffle, onCycleRepeat, modes = false,
                play = colors.inverseSurface, onPlay = colors.inverseOnSurface, playShape = shape)
            if (seek) Box(Modifier.weight(1f).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                DockSeek(state, onSeek, showTimes = width >= 760.dp && !state.skipButtons, thick = ui.miniProgress == MiniProgress.BAR)
            }
            if (!compact) DockLike(state, onToggleLike)
            if (!djMode) PaneToggle(PlayerPane.QUEUE, Icons.AutoMirrored.Filled.QueueMusic, openPane == PlayerPane.QUEUE) { onPane(PlayerPane.QUEUE) }
            if (wide) VolumeControl(volume, onVolumeChange, onToggleMute, 88.dp)
            Box {
                val held = openPane == PlayerPane.LYRICS || modes && (state.shuffle || state.repeat != RepeatMode.OFF)
                IconButton(onClick = { more = true }) {
                    Icon(Icons.Filled.MoreHoriz, appString(R.string.text_more_4bab2d), tint = if (held) colors.primary else colors.onSurfaceVariant)
                }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (modes) {
                            DockShuffle(state, onToggleShuffle)
                            DockRepeat(state, onCycleRepeat)
                        }
                        PaneToggle(PlayerPane.LYRICS, Icons.Filled.Lyrics, openPane == PlayerPane.LYRICS) { more = false; onPane(PlayerPane.LYRICS) }
                        DockIcon(Icons.Filled.Speaker, appString(R.string.text_output_device_709178)) { more = false; onOpenOutput() }
                        DockIcon(Icons.Filled.OpenInFull, appString(R.string.tablet_open_now_playing)) { more = false; onExpand() }
                        if (!wide) VolumeControl(volume, onVolumeChange, onToggleMute, 104.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DockIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, label, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
}

internal fun Modifier.progressRing(progress: Float, color: Color, track: Color, square: Boolean, width: Dp = 3.dp): Modifier = drawBehind {
    val stroke = Stroke(width.toPx())
    val inset = stroke.width / 2
    if (square) {
        val frame = Path().apply {
            moveTo(size.width / 2, inset)
            lineTo(size.width - inset, inset)
            lineTo(size.width - inset, size.height - inset)
            lineTo(inset, size.height - inset)
            lineTo(inset, inset)
            close()
        }
        val done = Path()
        PathMeasure().apply { setPath(frame, false) }.let { it.getSegment(0f, it.length * progress, done, true) }
        drawPath(frame, track, style = stroke)
        drawPath(done, color, style = stroke)
    } else {
        drawCircle(track, size.minDimension / 2 - inset, style = stroke)
        drawArc(color, -90f, 360f * progress, false, Offset(inset, inset), Size(size.width - stroke.width, size.height - stroke.width), style = stroke)
    }
}

@Composable
private fun SignalChips(format: Boolean) {
    val player = LocalPlayer.current
    // the flow polls twice a second and its meters differ every time, so they are dropped before anything here reads it
    val path by remember(player) { player.signalPath.map { it.copy(measurements = null, nodeMeters = emptyList()) }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(player.signalPath.value.copy(measurements = null, nodeMeters = emptyList()))
    if (!path.active) return
    val source = listOfNotNull(
        path.codec.takeIf { it.isNotBlank() },
        path.sampleRateHz.takeIf { it > 0 }?.let { appString(R.string.text_1f_khz_92ed69, it / 1000f) },
        path.bitDepth.takeIf { it > 0 }?.let { appString(R.string.text_bit_fd7850, it) },
    ).joinToString(" · ")
    if (format && source.isNotBlank()) SignalChip(source, tinted = false)
    when (path.preservation) {
        Preservation.PRESERVED -> SignalChip(appString(R.string.text_samples_preserved_091001), tinted = true)
        Preservation.MODIFIED -> SignalChip(appString(R.string.text_samples_modified_d04e9f), tinted = true)
        Preservation.UNKNOWN -> Unit
    }
}

@Composable
private fun SignalChip(text: String, tinted: Boolean) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.extraSmall
    Text(
        text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        color = if (!tinted) colors.onSurfaceVariant else if (isPaperTheme()) PaperTokens.ink else colors.primary,
        modifier = Modifier.padding(start = 8.dp)
            .paperFrame(shape, { if (tinted) background(colors.primary.copy(alpha = 0.18f)) else border(1.dp, colors.outlineVariant, shape) },
                fill = if (tinted) colors.primary else PaperTokens.paper)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

// a rail item is narrower than the rail, so the mark is drawn outside it, on the rail's own edge
@Composable
internal fun Modifier.railMark(selected: Boolean): Modifier {
    val color = MaterialTheme.colorScheme.primary
    val square = isPaperTheme()
    val shown by animateFloatAsState(if (selected) 1f else 0f, Motion.state(), label = "railMark")
    return drawBehind {
        val width = 3.dp.toPx()
        val height = 28.dp.toPx() * shown
        val edge = (size.width - TabletMetrics.RailWidth.toPx()) / 2
        if (height > 0f) drawRoundRect(
            color, Offset(if (layoutDirection == LayoutDirection.Rtl) size.width - edge - width else edge, (size.height - height) / 2),
            Size(width, height), CornerRadius(if (square) 0f else width / 2),
        )
    }
}
