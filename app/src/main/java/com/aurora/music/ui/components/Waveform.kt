package com.aurora.music.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.random.Random

private data class WaveformSeek(val fraction: Float, val dragged: Boolean, val sequence: Int)

// gesture claimed on touch-down so the player overlay cant steal it
@Composable
fun Waveform(
    progress: Float,
    accent: Color,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    seed: Int = 0,
    barCount: Int = 60,
    height: Dp = 52.dp,
    animated: Boolean = true,
) {
    val bars = remember(seed, barCount) {
        val rng = Random(seed)
        FloatArray(barCount) {
            val env = 0.45f + 0.55f * kotlin.math.sin(Math.PI * it / barCount).toFloat()
            (0.18f + rng.nextFloat() * 0.82f) * env
        }
    }
    val inactive = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
    val target = progress.coerceIn(0f, 1f)
    val shown = remember(seed) { Animatable(target) }
    val seek by rememberUpdatedState(onSeek)
    val touchSlop = LocalViewConfiguration.current.touchSlop
    var touching by remember(seed) { mutableStateOf(false) }
    var dragPosition by remember(seed) { mutableStateOf<Float?>(null) }
    var pending by remember(seed) { mutableStateOf<WaveformSeek?>(null) }
    var seekRequest by remember(seed) { mutableStateOf<WaveformSeek?>(null) }
    var seekSequence by remember(seed) { mutableStateOf(0) }
    var animatingSeek by remember(seed) { mutableStateOf(false) }

    LaunchedEffect(seed, target, touching, animatingSeek, pending) {
        if (touching || animatingSeek) return@LaunchedEffect
        pending?.let { if (abs(target - it.fraction) > 0.002f) return@LaunchedEffect }
        pending = null
        if (animated) shown.animateTo(target, tween(250, easing = LinearEasing)) else shown.snapTo(target)
    }
    LaunchedEffect(seed, seekRequest) {
        val request = seekRequest ?: return@LaunchedEffect
        if (request.dragged || !animated) shown.snapTo(request.fraction)
        else shown.animateTo(request.fraction, tween(320, easing = FastOutSlowInEasing))
        dragPosition = null
        animatingSeek = false
    }
    LaunchedEffect(seed, pending) {
        if (pending != null) {
            delay(1500)
            pending = null
        }
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(seed, barCount, touchSlop) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    touching = true
                    seekRequest = null
                    animatingSeek = false
                    pending = null
                    dragPosition = null
                    var last = (down.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    var moved = false
                    var released = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                if (moved) last = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                change.consume()
                                released = true
                                break
                            }
                            if (abs(change.position.x - down.position.x) > touchSlop) moved = true
                            if (moved) {
                                last = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                dragPosition = last
                            }
                            change.consume()
                        }
                        if (released) {
                            val request = WaveformSeek(last, moved, ++seekSequence)
                            pending = request
                            seekRequest = request
                            animatingSeek = true
                            seek(last)
                        }
                    } finally {
                        touching = false
                        if (!released) dragPosition = null
                    }
                }
            },
    ) {
        val n = bars.size
        val gapRatio = 0.4f
        val unit = size.width / (n * (1f + gapRatio))
        val barW = unit
        val gap = unit * gapRatio
        val midY = size.height / 2f
        val playedX = size.width * (dragPosition ?: shown.value).coerceIn(0f, 1f)

        for (i in 0 until n) {
            val x = i * (barW + gap)
            val barH = (bars[i] * size.height).coerceAtLeast(barW)
            drawRoundRect(
                color = inactive,
                topLeft = Offset(x, midY - barH / 2f),
                size = Size(barW, barH),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f),
            )
        }

        if (playedX > 0f) {
            clipRect(right = playedX) {
                for (i in 0 until n) {
                    val x = i * (barW + gap)
                    if (x > playedX) break
                    val barH = (bars[i] * size.height).coerceAtLeast(barW)
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(x, midY - barH / 2f),
                        size = Size(barW, barH),
                        cornerRadius = CornerRadius(barW / 2f, barW / 2f),
                    )
                }
            }
            drawLine(
                color = accent,
                start = Offset(playedX, midY - size.height / 2f),
                end = Offset(playedX, midY + size.height / 2f),
                strokeWidth = barW * 0.5f,
            )
        }
    }
}
