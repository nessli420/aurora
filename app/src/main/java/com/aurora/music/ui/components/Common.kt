package com.aurora.music.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import com.aurora.music.ui.theme.Motion
import com.aurora.music.ui.theme.isGlassTheme
import com.aurora.music.util.MotionTokens
import com.aurora.music.ui.theme.paperClip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.aurora.music.data.ThemeStyle
import com.aurora.music.ui.theme.LocalUiPrefs
import com.aurora.music.ui.theme.auroraBackdrop

@Composable
fun Artwork(
    url: String,
    accent: Color,
    modifier: Modifier = Modifier,
    corner: Dp = 12.dp,
    contentScale: ContentScale = ContentScale.Crop,
    animated: CoverKey? = null,
    decodeSize: Int = 0,
) {
    // Keep flush artwork and circular avatars intact; frame music covers to match the identity.
    val artCorner = if (corner == 0.dp || corner >= 22.dp) corner else when (LocalUiPrefs.current.themeStyle) {
        ThemeStyle.RETRO -> 2.dp
        ThemeStyle.AERO -> 5.dp
        ThemeStyle.GLASS -> corner.coerceIn(10.dp, 16.dp)
        ThemeStyle.PAPER -> 0.dp
        else -> corner
    }
    val placeholder = remember(accent) { Brush.linearGradient(listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = 0.12f))) }
    val context = LocalContext.current
    Box(
        modifier = modifier.paperClip(RoundedCornerShape(artCorner)).background(placeholder),
        contentAlignment = Alignment.Center,
    ) {
        // the gradient behind shows while loading or on error so no subcomposed slots are needed
        val mix = url.startsWith(com.aurora.music.foryou.MIX_COVER_PREFIX)
        val picture = if (mix) url.substringAfter('|', "") else url
        if (picture.isNotBlank()) AsyncImage(
            model = remember(picture, decodeSize) {
                ImageRequest.Builder(context).data(picture).crossfade(com.aurora.music.util.MotionTokens.STANDARD).apply { if (decodeSize > 0) size(decodeSize) }.build()
            },
            contentDescription = null,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize(),
        )
        if (mix) MixCover(url.removePrefix(com.aurora.music.foryou.MIX_COVER_PREFIX).substringBefore('|'), accent, Modifier.fillMaxSize(), picture.isNotBlank())
        if (animated != null) AnimatedCoverLayer(animated, Modifier.fillMaxSize())
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // paper keys a section with a flat rose block instead of the gradient bar
        if (com.aurora.music.ui.theme.isPaperTheme()) Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.primary))
        else Box(
            Modifier
                .width(4.dp)
                .height(22.dp)
                .paperClip(RoundedCornerShape(50))
                .background(
                    Brush.verticalGradient(
                        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
                    )
                ),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f),
        )
        if (action != null && onAction != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(com.aurora.music.ui.theme.paperSquare(RoundedCornerShape(50)))
                    .clickable(onClick = onAction)
                    .padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Text(
                    text = action,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(3.dp))
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForwardIos,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(11.dp),
                )
            }
        }
    }
}

@Composable
fun Eyebrow(text: String, color: Color, modifier: Modifier = Modifier) {
    // paper's eyebrows are the site's mono labels: regular weight, no extra tracking
    val paper = com.aurora.music.ui.theme.isPaperTheme()
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (paper) FontWeight.Normal else FontWeight.Black,
        color = color,
        letterSpacing = if (paper) MaterialTheme.typography.labelSmall.letterSpacing else 1.5.sp,
        modifier = modifier,
    )
}

@Composable
fun AmbientBackground(modifier: Modifier = Modifier) {
    if (LocalUiPrefs.current.themeStyle != ThemeStyle.AURORA) {
        Box(modifier.fillMaxSize().auroraBackdrop())
        return
    }
    val base = MaterialTheme.colorScheme.background
    val top = MaterialTheme.colorScheme.primary
    val bottom = MaterialTheme.colorScheme.tertiary
    Box(modifier.fillMaxSize().background(base)) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(
                    colors = listOf(top.copy(alpha = 0.22f), Color.Transparent),
                    center = Offset(120f, -80f),
                    radius = 1100f,
                )
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.55f to Color.Transparent,
                        1f to bottom.copy(alpha = 0.12f),
                    )
                )
            )
        )
    }
}

fun formatTime(totalSec: Int): String {
    val safe = totalSec.coerceAtLeast(0)
    val h = safe / 3600
    val m = safe % 3600 / 60
    val s = safe % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatTime(totalSec: Float): String = formatTime(totalSec.toInt())

// play and pause swap with a short scale and fade instead of a hard cut
@Composable
fun PlayPauseIcon(playing: Boolean, tint: Color, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = playing,
        modifier = modifier,
        transitionSpec = {
            (scaleIn(Motion.enter(MotionTokens.FAST), initialScale = 0.6f) + fadeIn(Motion.enter(MotionTokens.FAST))) togetherWith
                (scaleOut(Motion.exit(MotionTokens.INSTANT), targetScale = 0.6f) + fadeOut(Motion.exit(MotionTokens.INSTANT)))
        },
        contentAlignment = Alignment.Center,
        label = "playPause",
    ) { on ->
        Icon(if (on) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = tint, modifier = Modifier.fillMaxSize())
    }
}

// keeps the clock readable on pages that scroll under the status bar
@Composable
fun StatusBarScrim(modifier: Modifier = Modifier) {
    if (isGlassTheme()) return
    val base = MaterialTheme.colorScheme.background
    val inset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(
        modifier.fillMaxWidth().height(inset + 12.dp).background(
            Brush.verticalGradient(0f to base.copy(alpha = 0.92f), 0.6f to base.copy(alpha = 0.7f), 1f to base.copy(alpha = 0f))
        ),
    )
}

// the now-playing mark: bars that move while music plays and rest when it stops
@Composable
fun PlayingBars(color: Color, modifier: Modifier = Modifier, playing: Boolean = true) {
    val square = com.aurora.music.ui.theme.isPaperTheme()
    // the loop only exists while playing, and its value is read in the draw phase alone
    val phase = if (playing) rememberInfiniteTransition(label = "bars")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "phase") else null
    Spacer(
        modifier.drawBehind {
            val gap = size.width * 0.12f
            val bar = (size.width - gap * (BAR_REST.size - 1)) / BAR_REST.size
            val radius = if (square) CornerRadius.Zero else CornerRadius(bar / 2f)
            val t = phase?.value
            for (i in BAR_REST.indices) {
                val level = if (t == null) BAR_REST[i]
                    else 0.28f + 0.72f * (0.5f + 0.5f * kotlin.math.sin(2f * Math.PI.toFloat() * (BAR_SPEED[i] * t + BAR_REST[i])))
                val h = size.height * level
                drawRoundRect(color, Offset(i * (bar + gap), size.height - h), Size(bar, h), radius)
            }
        },
    )
}

private val BAR_REST = floatArrayOf(0.45f, 0.8f, 0.55f, 0.3f)
private val BAR_SPEED = intArrayOf(2, 3, 2, 3)
