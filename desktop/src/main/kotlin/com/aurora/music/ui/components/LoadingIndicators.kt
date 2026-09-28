package com.aurora.music.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import com.aurora.music.ui.theme.AuroraRose

private val EqualizerEasing = CubicBezierEasing(0.58f, 0f, 0.42f, 1f)
private val LoaderEasing = CubicBezierEasing(0.6f, 0f, 0.4f, 1f)

private val EqualizerBars = listOf(
    floatArrayOf(1f, 0.3f, 1f, 0.55f, 1f),
    floatArrayOf(0.55f, 1f, 0.4f, 1f, 0.55f),
    floatArrayOf(1f, 0.45f, 1f, 0.3f, 1f),
    floatArrayOf(0.4f, 1f, 0.6f, 1f, 0.4f),
)

@Composable
fun LottieEqualizer(modifier: Modifier = Modifier, isPlaying: Boolean = true) {
    val progress = rememberLoopProgress(2000, isPlaying)
    Canvas(modifier) {
        artboard { unit ->
            val step = progress.value * (EqualizerBars[0].size - 1)
            val index = step.toInt().coerceAtMost(EqualizerBars[0].size - 2)
            val fraction = EqualizerEasing.transform(step - index)
            EqualizerBars.forEachIndexed { bar, heights ->
                val scale = heights[index] + (heights[index + 1] - heights[index]) * fraction
                val height = 80f * scale * unit
                drawRoundRect(
                    color = AuroraRose,
                    topLeft = Offset((40f + 40f * bar - 9f) * unit, 150f * unit - height),
                    size = Size(18f * unit, height),
                    cornerRadius = CornerRadius(6f * unit, 6f * scale * unit),
                )
            }
        }
    }
}

@Composable
fun LottieLoader(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val progress = rememberLoopProgress(3000)
    Canvas(modifier) {
        artboard { unit ->
            val frame = progress.value * 90f
            for (dot in 0 until 3) {
                val pulse = pulse(frame - dot * 10f, LoaderEasing)
                drawCircle(
                    color = color.copy(alpha = color.alpha * (0.3f + 0.7f * pulse)),
                    radius = 13f * (0.8f + 0.4f * pulse) * unit,
                    center = Offset((60f + 40f * dot) * unit, 100f * unit),
                )
            }
        }
    }
}

private fun pulse(frame: Float, easing: Easing): Float = when {
    frame <= 0f || frame >= 30f -> 0f
    frame < 15f -> easing.transform(frame / 15f)
    else -> 1f - easing.transform((frame - 15f) / 15f)
}

private inline fun DrawScope.artboard(draw: DrawScope.(unit: Float) -> Unit) {
    val unit = size.minDimension / 200f
    translate((size.width - 200f * unit) / 2f, (size.height - 200f * unit) / 2f) { draw(unit) }
}

@Composable
private fun rememberLoopProgress(durationMillis: Int, playing: Boolean = true): State<Float> {
    val progress = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing, durationMillis) {
        if (!playing) return@LaunchedEffect
        val start = progress.floatValue
        val origin = withInfiniteAnimationFrameMillis { it }
        while (true) {
            withInfiniteAnimationFrameMillis { progress.floatValue = (start + (it - origin).toFloat() / durationMillis) % 1f }
        }
    }
    return progress
}
