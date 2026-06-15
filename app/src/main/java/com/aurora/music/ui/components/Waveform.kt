package com.aurora.music.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.random.Random

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
    val shown by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = if (animated) tween(durationMillis = 250, easing = LinearEasing) else tween(0),
        label = "waveformProgress",
    )
    // non-null only while scrubbing so playhead tracks finger instantly
    var scrub by remember { mutableStateOf<Float?>(null) }
    val playedFraction = scrub ?: shown

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(barCount) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val f0 = (down.position.x / size.width).coerceIn(0f, 1f)
                    scrub = f0
                    onSeek(f0)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val f = (change.position.x / size.width).coerceIn(0f, 1f)
                        scrub = f
                        onSeek(f)
                        change.consume()
                    }
                    scrub = null
                }
            },
    ) {
        val n = bars.size
        val gapRatio = 0.4f
        val unit = size.width / (n * (1f + gapRatio))
        val barW = unit
        val gap = unit * gapRatio
        val midY = size.height / 2f
        val playedX = size.width * playedFraction.coerceIn(0f, 1f)

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
