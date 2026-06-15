package com.aurora.music.util

import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun rememberDominantColor(url: String, fallback: Color): State<Color> {
    val context = LocalContext.current
    val resolved = remember(url) { mutableStateOf(fallback) }

    LaunchedEffect(url) {
        if (url.isBlank()) {
            resolved.value = fallback
            return@LaunchedEffect
        }
        val color = withContext(Dispatchers.IO) {
            runCatching {
                val loader = ImageLoader(context)
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .allowHardware(false)
                    .size(160)
                    .build()
                val result = loader.execute(request)
                val bitmap = (result as? SuccessResult)?.drawable
                    ?.let { it as? BitmapDrawable }?.bitmap ?: return@runCatching null
                val palette = Palette.from(bitmap).clearFilters().generate()
                val rgb = palette.vibrantSwatch?.rgb
                    ?: palette.lightVibrantSwatch?.rgb
                    ?: palette.dominantSwatch?.rgb
                    ?: palette.mutedSwatch?.rgb
                rgb?.let { boost(Color(it)) }
            }.getOrNull()
        }
        if (color != null) resolved.value = color
    }
    return animateColorAsState(resolved.value, tween(450), label = "dominant")
}

private fun boost(c: Color): Color {
    val lum = c.luminance()
    return when {
        lum < 0.12f -> lerp(c, Color.White, 0.30f)
        lum > 0.85f -> lerp(c, Color.Black, 0.22f)
        else -> c
    }
}

private fun lerp(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f,
)
