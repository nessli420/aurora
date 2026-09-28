package com.aurora.music.util

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.materialkolor.quantize.QuantizerCelebi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

private val dominantColors = object : LinkedHashMap<String, Int>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>) = size > 256
}

class Swatch(val rgb: Int, val population: Int) {
    val hsl: FloatArray = run {
        val r = (rgb shr 16 and 0xFF) / 255f
        val g = (rgb shr 8 and 0xFF) / 255f
        val b = (rgb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        val l = (max + min) / 2f
        if (delta == 0f) return@run floatArrayOf(0f, 0f, l)
        val h = when (max) {
            r -> ((g - b) / delta) % 6f
            g -> (b - r) / delta + 2f
            else -> (r - g) / delta + 4f
        }
        floatArrayOf((h * 60f + 360f) % 360f, delta / (1f - abs(2f * l - 1f)), l)
    }
}

class ArtworkPalette(val swatches: List<Swatch>) {
    private val used = mutableSetOf<Int>()
    val dominantSwatch: Swatch? = swatches.maxByOrNull { it.population }
    val lightVibrantSwatch = pick(0.55f..1f, 0.74f, vibrant = true)
    val vibrantSwatch = pick(0.3f..0.7f, 0.5f, vibrant = true)
    val darkVibrantSwatch = pick(0f..0.45f, 0.26f, vibrant = true)
    val lightMutedSwatch = pick(0.55f..1f, 0.74f, vibrant = false)
    val mutedSwatch = pick(0.3f..0.7f, 0.5f, vibrant = false)
    val darkMutedSwatch = pick(0f..0.45f, 0.26f, vibrant = false)

    private fun pick(lightness: ClosedFloatingPointRange<Float>, targetLightness: Float, vibrant: Boolean): Swatch? {
        val saturation = if (vibrant) 0.35f..1f else 0f..0.4f
        val targetSaturation = if (vibrant) 1f else 0.3f
        val maxPopulation = dominantSwatch?.population?.toFloat() ?: return null
        return swatches.filter { it.rgb !in used && it.hsl[1] in saturation && it.hsl[2] in lightness }
            .maxByOrNull {
                0.24f * (1f - abs(it.hsl[1] - targetSaturation)) + 0.52f * (1f - abs(it.hsl[2] - targetLightness)) +
                    0.24f * it.population / maxPopulation
            }
            ?.also { used += it.rgb }
    }

    companion object {
        fun from(pixels: IntArray): ArtworkPalette = ArtworkPalette(
            QuantizerCelebi.quantize(pixels.filter { it ushr 24 == 0xFF }.toIntArray(), 16)
                .filterValues { it > 0 }
                .map { (rgb, count) -> Swatch(rgb, count) },
        )
    }
}

suspend fun artworkPalette(context: PlatformContext, url: String): ArtworkPalette? {
    val request = ImageRequest.Builder(context).data(url).size(160).build()
    val bitmap = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap() ?: return null
    val info = ImageInfo(bitmap.width, bitmap.height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL)
    val bytes = bitmap.readPixels(info, bitmap.width * 4, 0, 0) ?: return null
    val pixels = IntArray(bytes.size / 4).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(it) }
    return ArtworkPalette.from(pixels)
}

@Composable
fun rememberDominantColor(url: String, fallback: Color): State<Color> {
    val context = LocalPlatformContext.current
    val resolved = remember { mutableStateOf(url.takeIf { it.isNotBlank() }?.let { dominantColors[it] }?.let(::Color) ?: fallback) }

    LaunchedEffect(url, fallback) {
        if (url.isBlank()) {
            resolved.value = fallback
            return@LaunchedEffect
        }
        dominantColors[url]?.let {
            resolved.value = Color(it)
            return@LaunchedEffect
        }
        val color = withContext(Dispatchers.IO) {
            runCatching {
                val palette = artworkPalette(context, url) ?: return@runCatching null
                val swatch = palette.vibrantSwatch
                    ?: palette.lightVibrantSwatch
                    ?: palette.dominantSwatch
                    ?: palette.mutedSwatch
                swatch?.let { boost(Color(it.rgb)) }
            }.getOrNull()
        }
        if (color != null) dominantColors[url] = color.toArgb()
        resolved.value = color ?: fallback
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
