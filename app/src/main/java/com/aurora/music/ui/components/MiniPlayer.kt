package com.aurora.music.ui.components

import com.aurora.music.localization.appString
import com.aurora.music.ui.theme.paperClip
import com.aurora.music.R

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.aurora.music.model.Song
import com.aurora.music.ui.theme.Motion
import com.aurora.music.ui.theme.likePop
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.util.lerp
import com.aurora.music.ui.components.glass.InteractiveHighlight
import com.aurora.music.ui.theme.LocalGlassBackdrop
import com.aurora.music.ui.theme.liquidGlass
import com.kyant.shapes.Capsule
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aurora.music.data.MiniProgress
import com.aurora.music.data.MiniStyle
import com.aurora.music.data.ThemeStyle
import com.aurora.music.ui.theme.LocalUiPrefs
import com.aurora.music.ui.theme.auroraPanel
import com.aurora.music.viewmodel.PlayerUiState
import com.aurora.music.model.accent

@Composable
fun MiniPlayer(
    state: PlayerUiState,
    onExpand: () -> Unit,
    onTogglePlay: () -> Unit,
    onToggleLike: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (LocalUiPrefs.current.themeStyle == ThemeStyle.GLASS) {
        GlassMiniPlayer(state, onExpand, onTogglePlay, onToggleLike, onNext, modifier)
        return
    }
    val song = state.current
    val isMix = song.id.startsWith("aurora-mix:")
    val ui = LocalUiPrefs.current
    val position = LocalPlaybackPosition.current
    val accent = com.aurora.music.ui.theme.LocalContextAccent.current.color ?: MaterialTheme.colorScheme.primary
    val progressBrush = com.aurora.music.ui.theme.rememberColorSweep(accent)
    val likeTint by animateColorAsState(
        if (state.isCurrentLiked) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "likeTint",
    )

    val artSize = when (ui.miniStyle) { MiniStyle.COMPACT -> 38.dp; MiniStyle.PROMINENT -> 54.dp; else -> 44.dp }
    val rowPad = when (ui.miniStyle) { MiniStyle.COMPACT -> 6.dp; MiniStyle.PROMINENT -> 12.dp; else -> 8.dp }
    val showLike = ui.miniStyle != MiniStyle.COMPACT && !isMix && LocalCanLike.current(song.id)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (ui.themeStyle != ThemeStyle.AURORA) Modifier.auroraPanel(MaterialTheme.shapes.large, emphasized = true)
            else Modifier.paperClip(RoundedCornerShape(20.dp)).background(
                Brush.horizontalGradient(
                    listOf(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        lerp(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.primary, 0.18f),
                    )
                )
            ))
            .clickable(onClick = onExpand),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(rowPad),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(song.artworkUrl, song.accent, Modifier.size(artSize), corner = 10.dp)
            Spacer(Modifier.width(10.dp))
            TrackLabel(song, Modifier.weight(1f)) { track ->
                MarqueeText(
                    track.title,
                    if (ui.miniStyle == MiniStyle.PROMINENT) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                    MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    paused = state.expanded,
                )
                MarqueeText(track.artist, MaterialTheme.typography.bodySmall, MaterialTheme.colorScheme.onSurfaceVariant, paused = state.expanded)
            }
            if (showLike) {
                LikeHeart(song, onToggleLike, Modifier.size(40.dp).paperClip(CircleShape)) {
                    Icon(
                        imageVector = if (state.isCurrentLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = appString(R.string.text_like_c7e02c),
                        tint = likeTint,
                        modifier = Modifier.size(24.dp).likePop(state.isCurrentLiked, song.id),
                    )
                }
            }
            val playPause = appString(R.string.text_play_pause_14a1d0)
            Box(Modifier.size(40.dp).paperClip(CircleShape).clickable(role = Role.Button, onClick = onTogglePlay)
                .semantics { contentDescription = playPause }.padding(6.dp)) {
                PlayPauseIcon(state.isPlaying, MaterialTheme.colorScheme.onSurface, Modifier.fillMaxSize())
            }
            if (!isMix) Icon(
                imageVector = Icons.Filled.SkipNext,
                contentDescription = appString(R.string.text_next_bc9819),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(40.dp).paperClip(CircleShape).clickable(onClick = onNext).padding(6.dp),
            )
        }
        if (ui.miniProgress != MiniProgress.NONE) {
            val barH = if (ui.miniProgress == MiniProgress.BAR) 5.dp else 2.dp
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .height(barH)
                    .paperClip(RoundedCornerShape(barH))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                    .drawBehind {
                        val r = size.height / 2f
                        drawRoundRect(progressBrush, size = size.copy(width = size.width * state.progressAt(position.value)), cornerRadius = CornerRadius(r, r))
                    },
            )
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun GlassMiniPlayer(
    state: PlayerUiState,
    onExpand: () -> Unit,
    onTogglePlay: () -> Unit,
    onToggleLike: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier,
) {
    val song = state.current
    val isMix = song.id.startsWith("aurora-mix:")
    val ui = LocalUiPrefs.current
    val colors = MaterialTheme.colorScheme
    val position = LocalPlaybackPosition.current
    val accent = com.aurora.music.ui.theme.LocalContextAccent.current.color ?: colors.primary
    val likeTint by animateColorAsState(if (state.isCurrentLiked) accent else colors.onSurface, label = "glassLike")
    val scope = rememberCoroutineScope()
    val highlight = remember(scope) { InteractiveHighlight(scope) }
    val height = when (ui.miniStyle) { MiniStyle.COMPACT -> 54.dp; MiniStyle.PROMINENT -> 70.dp; else -> 62.dp }
    val art = when (ui.miniStyle) { MiniStyle.COMPACT -> 38.dp; MiniStyle.PROMINENT -> 52.dp; else -> 44.dp }
    val track = colors.onSurface.copy(alpha = .12f)
    Row(
        modifier
            .fillMaxWidth()
            .height(height)
            .liquidGlass(LocalGlassBackdrop.current, Capsule(), refraction = 20.dp, layerBlock = {
                val scale = lerp(1f, 1f + 4.dp.toPx() / size.height, highlight.pressProgress)
                scaleX = scale
                scaleY = scale
            }, onDrawSurface = {
                if (ui.miniProgress != MiniProgress.NONE) {
                    val width = (if (ui.miniProgress == MiniProgress.BAR) 3.5f else 2f).dp.toPx()
                    val inset = size.height / 2f
                    val y = size.height - width / 2f - 2.5.dp.toPx()
                    drawLine(track, Offset(inset, y), Offset(size.width - inset, y), width, StrokeCap.Round)
                    drawLine(accent, Offset(inset, y), Offset(inset + (size.width - inset * 2) * state.progressAt(position.value), y), width, StrokeCap.Round)
                }
            })
            .clickable(interactionSource = null, indication = null, onClick = onExpand)
            .then(highlight.modifier)
            .then(highlight.gestureModifier)
            .padding(start = (height - art) / 2, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(song.artworkUrl, song.accent, Modifier.size(art), corner = 10.dp)
        Spacer(Modifier.width(12.dp))
        TrackLabel(song, Modifier.weight(1f)) { track ->
            MarqueeText(track.title, MaterialTheme.typography.titleSmall, colors.onSurface, fontWeight = FontWeight.SemiBold, paused = state.expanded)
            MarqueeText(track.artist, MaterialTheme.typography.bodySmall, colors.onSurfaceVariant, paused = state.expanded)
        }
        if (ui.miniStyle != MiniStyle.COMPACT && !isMix && LocalCanLike.current(song.id)) LikeHeart(song, onToggleLike, Modifier.size(44.dp).paperClip(CircleShape)) {
            Icon(if (state.isCurrentLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, appString(R.string.text_like_c7e02c),
                tint = likeTint, modifier = Modifier.size(24.dp).likePop(state.isCurrentLiked, song.id))
        }
        val playPause = appString(R.string.text_play_pause_14a1d0)
        Box(Modifier.size(44.dp).paperClip(CircleShape).clickable(role = Role.Button, onClick = onTogglePlay)
            .semantics { contentDescription = playPause }, contentAlignment = Alignment.Center) {
            PlayPauseIcon(state.isPlaying, colors.onSurface, Modifier.size(32.dp))
        }
        if (!isMix) GlassGlyph(Icons.Filled.SkipNext, appString(R.string.text_next_bc9819), colors.onSurface, 32.dp, onNext)
    }
}

// title and artist crossfade when the track changes; edits to the same track update in place
@Composable
internal fun TrackLabel(song: Song, modifier: Modifier, content: @Composable (Song) -> Unit) {
    AnimatedContent(
        targetState = song,
        modifier = modifier,
        transitionSpec = { fadeIn(Motion.enter()) togetherWith fadeOut(Motion.exit()) using null },
        contentKey = { it.id },
        label = "miniTrack",
    ) { track -> Column { content(track) } }
}

@Composable
private fun GlassGlyph(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: androidx.compose.ui.graphics.Color,
    size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).paperClip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(size))
    }
}
