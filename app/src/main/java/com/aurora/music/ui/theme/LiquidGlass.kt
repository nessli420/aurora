package com.aurora.music.ui.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aurora.music.data.ThemeStyle
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule

// everything behind the floating chrome: ambient backdrop plus the page
val LocalGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }

// for glass inside a page, which may only refract the ambient backdrop beneath it
val LocalGlassAmbient = staticCompositionLocalOf<Backdrop?> { null }

val liveGlassSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

class GlassLayers(val ambient: LayerBackdrop, val page: LayerBackdrop)

// the app shell records its backdrop and page into these; null unless live glass is on
val LocalGlassLayers = staticCompositionLocalOf<GlassLayers?> { null }

@Composable
fun GlassHost(content: @Composable () -> Unit) {
    val ambient = rememberLayerBackdrop()
    val page = rememberLayerBackdrop()
    val chrome = rememberCombinedBackdrop(ambient, page)
    val layers = remember(ambient, page) { GlassLayers(ambient, page) }
    val live = isGlassTheme() && liveGlassSupported
    CompositionLocalProvider(
        LocalGlassLayers provides layers.takeIf { live },
        LocalGlassBackdrop provides chrome.takeIf { live },
        LocalGlassAmbient provides ambient.takeIf { live },
        content = content,
    )
}

@Composable
@ReadOnlyComposable
fun isGlassTheme(): Boolean = LocalUiPrefs.current.themeStyle == ThemeStyle.GLASS

@Composable
@ReadOnlyComposable
fun glassIsDark(): Boolean = MaterialTheme.colorScheme.background.luminance() < .3f

object GlassColors {
    fun surface(dark: Boolean, live: Boolean): Color = when {
        live && dark -> Color(0xFF0E0E10).copy(alpha = .58f)
        live -> Color(0xFFFAFAFA).copy(alpha = .6f)
        dark -> Color(0xFF1C1C1E).copy(alpha = .96f)
        else -> Color(0xFFF9F9FB).copy(alpha = .96f)
    }

    fun panel(dark: Boolean): Color = if (dark) Color(0xFF1C1C1E).copy(alpha = .74f) else Color.White.copy(alpha = .78f)

    fun selection(dark: Boolean): Color = if (dark) Color.White.copy(alpha = .12f) else Color.Black.copy(alpha = .07f)

    fun rim(dark: Boolean): Brush = if (dark) Brush.verticalGradient(listOf(Color.White.copy(alpha = .22f), Color.White.copy(alpha = .05f), Color.White.copy(alpha = .12f)))
    else Brush.verticalGradient(listOf(Color.White, Color.White.copy(alpha = .55f), Color.White.copy(alpha = .85f)))

    fun card(dark: Boolean): Brush = if (dark) Brush.verticalGradient(listOf(Color.White.copy(alpha = .12f), Color.White.copy(alpha = .055f)))
    else Brush.verticalGradient(listOf(Color.White.copy(alpha = .78f), Color.White.copy(alpha = .56f)))

    // light pages need a denser tint to keep the ink on it readable
    fun accent(color: Color, dark: Boolean): Color = color.copy(alpha = if (dark) .66f else .88f)
}

// floating glass that bends and blurs whatever scrolls beneath it
@Composable
fun Modifier.liquidGlass(
    backdrop: Backdrop?,
    shape: Shape,
    refraction: Dp = 24.dp,
    depth: Dp = 24.dp,
    blur: Dp = 8.dp,
    surface: Color = Color.Unspecified,
    shadow: Boolean = true,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    onDrawSurface: (DrawScope.() -> Unit)? = null,
): Modifier {
    val dark = glassIsDark()
    if (backdrop == null || !liveGlassSupported) {
        val fill = if (surface.isSpecified) surface.copy(alpha = maxOf(surface.alpha, .9f)) else GlassColors.surface(dark, live = false)
        return this.clip(shape).background(fill).border(1.dp, GlassColors.rim(dark), shape)
            .then(if (onDrawSurface != null) Modifier.drawWithCache { onDrawBehind(onDrawSurface) } else Modifier)
    }
    val fill = if (surface.isSpecified) surface else GlassColors.surface(dark, live = true)
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(blur.toPx())
            // the lens throws on a shape without corners, so a plain rectangle is frosted only
            if (shape != androidx.compose.ui.graphics.RectangleShape) lens(refraction.toPx(), depth.toPx())
        },
        highlight = { if (dark) Highlight.Default.copy(alpha = .9f) else Highlight.Default },
        shadow = if (shadow) ({ Shadow(radius = 20.dp, color = Color.Black.copy(alpha = if (dark) .32f else .12f)) }) else null,
        layerBlock = layerBlock,
        onDrawSurface = {
            drawRect(fill)
            onDrawSurface?.invoke(this)
        },
    )
}

// in-page grouped surface: frosted fill with a thin light-catching rim
@Composable
fun Modifier.glassPanel(shape: Shape, emphasized: Boolean = false): Modifier {
    val dark = glassIsDark()
    val fill = if (emphasized) GlassColors.surface(dark, live = false) else GlassColors.panel(dark)
    val rim = GlassColors.rim(dark)
    return this.clip(shape).background(fill).drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val stroke = Stroke(1.dp.toPx())
        onDrawWithContent {
            drawContent()
            drawOutline(outline, rim, style = stroke)
        }
    }
}

@Composable
fun Modifier.glassCard(shape: Shape, tint: Color = Color.Unspecified): Modifier {
    val dark = glassIsDark()
    val fill = GlassColors.card(dark)
    val rim = GlassColors.rim(dark)
    return this.clip(shape).drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val stroke = Stroke(1.5.dp.toPx())
        onDrawWithContent {
            drawRect(fill)
            if (tint.isSpecified) drawRect(tint, alpha = if (dark) .26f else .18f)
            drawContent()
            drawOutline(outline, rim, style = stroke)
        }
    }
}

@Composable
fun Modifier.glassShelf(tint: Color = Color.Unspecified): Modifier =
    if (isGlassTheme()) padding(horizontal = 12.dp).glassCard(MaterialTheme.shapes.large, tint).padding(vertical = 14.dp) else this

@Composable
fun Modifier.glassChip(selected: Boolean = false, backdrop: Backdrop? = LocalGlassAmbient.current): Modifier =
    liquidGlass(backdrop, Capsule(), refraction = 12.dp, depth = 12.dp, blur = 6.dp, shadow = false,
        surface = if (selected) GlassColors.accent(MaterialTheme.colorScheme.primary, glassIsDark()) else Color.Unspecified).clip(Capsule())

fun Modifier.glassEdge(shape: Shape, dark: Boolean): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val stroke = Stroke(1.5.dp.toPx())
    val corner = Offset(size.width, size.height)
    val rim = Brush.linearGradient(0f to Color.White.copy(alpha = if (dark) .46f else .9f), .5f to Color.White.copy(alpha = .06f),
        1f to Color.White.copy(alpha = if (dark) .22f else .5f), start = Offset.Zero, end = corner)
    val sheen = Brush.linearGradient(0f to Color.White.copy(alpha = .17f), .42f to Color.Transparent, start = Offset.Zero, end = corner)
    onDrawWithContent {
        drawContent()
        drawOutline(outline, sheen)
        drawOutline(outline, rim, style = stroke)
    }
}
