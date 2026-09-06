package com.aurora.music.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.aurora.music.data.ThemeStyle

/** Complete visual identities, independent of the classic Aurora accent controls. */
data class ThemeIdentity(val id: Int, val name: String, val description: String, val detail: String)

val ThemeIdentities = listOf(
    ThemeIdentity(ThemeStyle.AURORA, "Aurora", "Your familiar music space", "Soft gradients · custom accents · rounded cards"),
    ThemeIdentity(ThemeStyle.RETRO, "Retro hi-fi", "A little analogue soul", "Amber displays · monospace type · tactile frames"),
    ThemeIdentity(ThemeStyle.AERO, "Aero", "A desktop classic, reimagined", "Windows 7 inspired · blue glass · glossy panels"),
    ThemeIdentity(ThemeStyle.GLASS, "Liquid glass", "Light, flowing, luminous", "Pearlescent surfaces · spacious curves · soft light"),
)

fun styleColorScheme(style: Int, dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val palette = when (style) {
        ThemeStyle.RETRO -> if (dark) listOf(
            Color(0xFF141612), Color(0xFF20241D), Color(0xFF2C3126), Color(0xFFF1EBCF),
            Color(0xFFC1C4AB), Color(0xFFFFC568), Color(0xFFB7D58C), Color(0xFF747B62),
        ) else listOf(
            Color(0xFFF2EDD9), Color(0xFFFFFAE9), Color(0xFFE4DFC8), Color(0xFF24291D),
            Color(0xFF545A45), Color(0xFF815000), Color(0xFF466320), Color(0xFF858C70),
        )
        ThemeStyle.AERO -> if (dark) listOf(
            Color(0xFF0B1729), Color(0xFF152A40), Color(0xFF243F5A), Color(0xFFF1F8FF),
            Color(0xFFBAD1E8), Color(0xFF91D7FF), Color(0xFFA9C2FF), Color(0xFF6387AB),
        ) else listOf(
            Color(0xFFE2F0FC), Color(0xFFF5FAFF), Color(0xFFD0E6F6), Color(0xFF112C43),
            Color(0xFF355B76), Color(0xFF005B95), Color(0xFF385DAC), Color(0xFF6C9DBD),
        )
        ThemeStyle.GLASS -> if (dark) listOf(
            Color(0xFF101426), Color(0xFF1D253D), Color(0xFF303B57), Color(0xFFF5F6FF),
            Color(0xFFCED3E9), Color(0xFFD4C6FF), Color(0xFF9DE5DF), Color(0xFF747F9D),
        ) else listOf(
            Color(0xFFEFF2FB), Color(0xFFFCFDFF), Color(0xFFE0E6F4), Color(0xFF222A44),
            Color(0xFF505E7B), Color(0xFF5D42A4), Color(0xFF216D72), Color(0xFF99A8C4),
        )
        else -> if (dark) listOf(
            DarkBackground, DarkSurface, DarkSurfaceElevated, TextPrimaryDark,
            TextSecondaryDark, AuroraRose, AuroraMagenta, DarkOutline,
        ) else listOf(
            LightBackground, LightSurface, LightSurfaceElevated, TextPrimaryLight,
            TextSecondaryLight, AuroraRoseDeep, AuroraMagenta, LightOutline,
        )
    }
    val (background, surface, elevated, text) = palette
    val secondaryText = palette[4]
    val accent = palette[5]
    val tertiary = palette[6]
    val outline = palette[7]
    val container = lerp(surface, accent, if (dark) 0.23f else 0.13f)
    val onAccent = if (dark) background else Color.White
    return base.copy(
        primary = accent, onPrimary = onAccent,
        primaryContainer = container, onPrimaryContainer = text,
        secondary = tertiary, onSecondary = onAccent,
        secondaryContainer = lerp(surface, tertiary, 0.18f), onSecondaryContainer = text,
        tertiary = tertiary, onTertiary = onAccent,
        tertiaryContainer = lerp(surface, tertiary, 0.18f), onTertiaryContainer = text,
        background = background, onBackground = text,
        surface = surface, onSurface = text,
        surfaceVariant = elevated, onSurfaceVariant = secondaryText,
        surfaceContainerLowest = background, surfaceContainerLow = surface,
        surfaceContainer = elevated, surfaceContainerHigh = lerp(elevated, text, 0.035f),
        surfaceContainerHighest = lerp(elevated, text, 0.075f),
        surfaceBright = lerp(surface, Color.White, if (dark) 0.12f else 0.6f),
        surfaceDim = lerp(surface, Color.Black, if (dark) 0.2f else 0.06f),
        surfaceTint = accent, outline = outline, outlineVariant = outline.copy(alpha = 0.5f),
        inverseSurface = text, inverseOnSurface = background,
        inversePrimary = if (dark) lerp(accent, Color.Black, 0.5f) else lerp(accent, Color.White, 0.55f),
    )
}

/** Shared app chrome: opaque-enough fills keep text legible over every background. */
@Composable
fun Modifier.auroraPanel(
    shape: Shape = MaterialTheme.shapes.large,
    emphasized: Boolean = false,
): Modifier {
    val style = LocalUiPrefs.current.themeStyle
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.3f
    val fill = colors.surfaceContainerHigh
    val edge = when (style) {
        ThemeStyle.RETRO -> colors.outline.copy(alpha = 0.75f)
        ThemeStyle.AERO -> colors.primary.copy(alpha = if (dark) 0.48f else 0.36f)
        ThemeStyle.GLASS -> if (dark) Color.White.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.94f)
        else -> colors.onSurface.copy(alpha = 0.06f)
    }
    val brush = when (style) {
        ThemeStyle.RETRO -> Brush.verticalGradient(listOf(lerp(fill, Color.White, 0.025f), fill))
        ThemeStyle.AERO -> Brush.verticalGradient(
            0f to lerp(fill, Color.White, if (dark) 0.10f else 0.55f),
            0.48f to lerp(fill, Color.White, if (dark) 0.06f else 0.18f),
            0.5f to fill,
            1f to lerp(fill, colors.primary, 0.08f),
        )
        ThemeStyle.GLASS -> Brush.linearGradient(listOf(
            lerp(fill, Color.White, if (dark) 0.12f else 0.65f).copy(alpha = 0.94f),
            fill.copy(alpha = 0.88f),
            lerp(fill, colors.tertiary, 0.10f).copy(alpha = 0.96f),
        ))
        else -> Brush.verticalGradient(listOf(fill.copy(alpha = 0.96f), fill.copy(alpha = 0.96f)))
    }
    return this.clip(shape)
        // Floating controls sit over scrolling text; the shine must not reveal that text.
        .then(if (emphasized && style == ThemeStyle.GLASS) Modifier.background(fill) else Modifier)
        .background(brush)
        .border(if (emphasized && style == ThemeStyle.RETRO) 1.5.dp else 1.dp, edge, shape)
}

/** Static, cached decoration: no blur pass or continuously running animation. */
@Composable
fun Modifier.auroraBackdrop(): Modifier {
    val style = LocalUiPrefs.current.themeStyle
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.3f
    return background(colors.background).drawWithCache {
        val w = size.width
        val h = size.height
        val topGlow = Brush.radialGradient(
            listOf(colors.primary.copy(alpha = if (style == ThemeStyle.GLASS) 0.22f else 0.16f), Color.Transparent),
            center = Offset(w * 0.15f, h * 0.12f), radius = w * 1.2f,
        )
        val lowerGlow = Brush.radialGradient(
            listOf(colors.tertiary.copy(alpha = if (style == ThemeStyle.GLASS) 0.22f else 0.10f), Color.Transparent),
            center = Offset(w * 0.95f, h * 0.66f), radius = w * 1.25f,
        )
        val lineStep = 5.dp.toPx()
        val lineWidth = 0.5.dp.toPx()
        onDrawBehind {
            when (style) {
                ThemeStyle.RETRO -> {
                    var y = 0f
                    while (y < h) {
                        drawLine(colors.onBackground.copy(alpha = if (dark) 0.027f else 0.035f), Offset(0f, y), Offset(w, y), lineWidth)
                        y += lineStep
                    }
                    drawRect(colors.primary.copy(alpha = 0.08f), topLeft = Offset(w - 9.dp.toPx(), 0f), size = Size(3.dp.toPx(), h))
                }
                ThemeStyle.AERO -> {
                    drawRect(topGlow)
                    drawRect(lowerGlow)
                    drawLine(Color.White.copy(alpha = if (dark) 0.045f else 0.22f), Offset(w * 1.2f, 0f), Offset(-w * 0.4f, h), w * 0.22f)
                    drawLine(Color.White.copy(alpha = if (dark) 0.04f else 0.18f), Offset(w * 1.7f, 0f), Offset(w * 0.1f, h), w * 0.08f)
                }
                else -> {
                    drawRect(topGlow)
                    drawRect(lowerGlow)
                }
            }
        }
    }
}
