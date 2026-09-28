package com.aurora.music.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlinx.coroutines.launch
import kotlin.math.abs

@Stable
class ContextAccentState {
    var color by mutableStateOf<Color?>(null)
        private set
    private var owner: Any? = null

    fun claim(token: Any, value: Color) {
        owner = token
        color = value
    }

    fun release(token: Any) {
        if (owner === token) {
            owner = null
            color = null
        }
    }
}

val LocalContextAccent = staticCompositionLocalOf { ContextAccentState() }

@Composable
fun ProvideContextAccent(color: Color) {
    val state = LocalContextAccent.current
    val token = remember { Any() }
    SideEffect { state.claim(token, color) }
    DisposableEffect(state, token) { onDispose { state.release(token) } }
}

fun readableAccent(color: Color, darkBackground: Boolean): Color {
    val lum = color.luminance()
    return when {
        darkBackground && lum < 0.2f -> lerp(color, Color.White, 0.35f)
        !darkBackground && lum > 0.45f -> lerp(color, Color.Black, 0.35f)
        else -> color
    }
}

@Composable
fun rememberColorSweep(target: Color, durationMillis: Int = 750): Brush {
    var from by remember { mutableStateOf(target) }
    var to by remember { mutableStateOf(target) }
    val sweep = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(target) {
        if (target == to) return@LaunchedEffect
        val running = sweep.isRunning
        val distance = abs(target.red - to.red) + abs(target.green - to.green) + abs(target.blue - to.blue)
        if (running || distance < 0.08f) {
            if (!running) from = target
            to = target
            return@LaunchedEffect
        }
        from = to
        to = target
        scope.launch {
            sweep.snapTo(0f)
            sweep.animateTo(1f, tween(durationMillis, easing = FastOutSlowInEasing))
            from = to
        }
    }

    val edge = 0.45f
    val head = -edge + (1f + edge) * sweep.value
    return Brush.horizontalGradient(
        0f to to,
        head.coerceIn(0f, 1f) to to,
        (head + edge).coerceIn(0f, 1f) to from,
        1f to from,
    )
}
