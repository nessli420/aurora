package com.aurora.music.ui.theme

import com.aurora.music.localization.appString
import com.aurora.music.R

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

val ThemeIdentities: List<ThemeIdentity> get() = listOf(
    ThemeIdentity(ThemeStyle.AURORA, appString(R.string.text_aurora_eeee9b), appString(R.string.text_your_familiar_music_space_fab491), appString(R.string.text_soft_gradients_custom_accents_rounded_cards_d3f466)),
    ThemeIdentity(ThemeStyle.RETRO, appString(R.string.text_retro_hi_fi_a68d38), appString(R.string.text_a_little_analogue_soul_a0d7ac), appString(R.string.text_amber_displays_monospace_type_tactile_frames_27d627)),
    ThemeIdentity(ThemeStyle.AERO, "Aero", appString(R.string.text_a_desktop_classic_reimagined_18633f), appString(R.string.text_windows_7_inspired_blue_glass_glossy_panels_27d09a)),
    ThemeIdentity(ThemeStyle.GLASS, appString(R.string.text_liquid_glass_51a330), appString(R.string.text_light_flowing_luminous_6acb9b), appString(R.string.text_pearlescent_surfaces_spacious_curves_soft_light_b4c29d)),
    ThemeIdentity(ThemeStyle.PAPER, appString(R.string.theme_paper_name), appString(R.string.theme_paper_description), appString(R.string.theme_paper_detail)),
)

/** auroraplayer.lol's paper edition, token for token: warm paper, ink, hairline rules and a rose accent. */
object PaperTokens {
    val paper = Color(0xFFF1ECE3)
    val plate = Color(0xFFE9E3D8)
    val plate2 = Color(0xFFE2DBCE)
    val ink = Color(0xFF1B1418)
    val ink2 = Color(0xFF54484D)
    val ink3 = Color(0xFF6B5F63)
    val hair = Color(0x3D1B1418)
    val hair2 = Color(0x1F1B1418)
    val rose = Color(0xFFFF2E7E)
    val roseInk = Color(0xFFB5104F)
    val roseWash = Color(0x21FF2E7E)
    val lit = Color(0xFFF2C9D3)
    val amber = Color(0xFFF7B733)
}

// the website only ships one paper edition, so this ignores dark mode
private fun paperColorScheme(): ColorScheme = with(PaperTokens) {
    lightColorScheme(
        primary = rose, onPrimary = ink, primaryContainer = lit, onPrimaryContainer = ink,
        secondary = roseInk, onSecondary = paper, secondaryContainer = lit, onSecondaryContainer = ink,
        // the site keeps amber for its beta tags; tertiary stays rose so the app's primary-to-tertiary gradients read as flat rose
        tertiary = rose, onTertiary = ink, tertiaryContainer = lit, onTertiaryContainer = ink,
        background = paper, onBackground = ink,
        surface = paper, onSurface = ink,
        surfaceVariant = plate, onSurfaceVariant = ink2,
        surfaceContainerLowest = paper, surfaceContainerLow = paper,
        surfaceContainer = plate, surfaceContainerHigh = plate, surfaceContainerHighest = plate2,
        surfaceBright = paper, surfaceDim = plate2, surfaceTint = Color.Transparent,
        outline = ink3, outlineVariant = hair,
        inverseSurface = ink, inverseOnSurface = paper, inversePrimary = rose,
        error = Color(0xFFB3261E), onError = paper,
    )
}

fun styleColorScheme(style: Int, dark: Boolean): ColorScheme {
    if (style == ThemeStyle.PAPER) return paperColorScheme()
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
            Color(0xFF04060C), Color(0xFF1C1C1E), Color(0xFF2C2C2E), Color(0xFFFFFFFF),
            Color(0xFF98989F), Color(0xFF0A84FF), Color(0xFF64D2FF), Color(0xFF38383A),
        ) else listOf(
            Color(0xFFF2F2F7), Color(0xFFFFFFFF), Color(0xFFE5E5EA), Color(0xFF000000),
            Color(0xFF6C6C70), Color(0xFF007AFF), Color(0xFF32ADE6), Color(0xFFC6C6C8),
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
    val onAccent = if (dark && style != ThemeStyle.GLASS) background else Color.White
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
    if (style == ThemeStyle.GLASS) return glassPanel(shape, emphasized)
    // a data-sheet box: a 1px ink frame on paper, plate behind the parts that float; rows inside split on hairlines
    if (style == ThemeStyle.PAPER) return this.clip(shape)
        .background(if (emphasized) PaperTokens.plate else PaperTokens.paper)
        .border(1.dp, PaperTokens.ink, shape)
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.3f
    val fill = colors.surfaceContainerHigh
    val edge = when (style) {
        ThemeStyle.RETRO -> colors.outline.copy(alpha = 0.75f)
        ThemeStyle.AERO -> colors.primary.copy(alpha = if (dark) 0.48f else 0.36f)
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
        else -> Brush.verticalGradient(listOf(fill.copy(alpha = 0.96f), fill.copy(alpha = 0.96f)))
    }
    return this.clip(shape)
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
                // flat paper, like the site behind its sections
                ThemeStyle.PAPER -> Unit
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
                ThemeStyle.GLASS -> drawGlassBackdrop(colors.primary, colors.tertiary, dark)
                else -> {
                    drawRect(topGlow)
                    drawRect(lowerGlow)
                }
            }
        }
    }
}

// deep wallpaper-like glows for the glass to bend and pick colour up from
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGlassBackdrop(primary: Color, tertiary: Color, dark: Boolean) {
    val w = size.width
    val h = size.height
    fun glow(color: Color, alpha: Float, x: Float, y: Float, r: Float) = drawRect(Brush.radialGradient(
        listOf(color.copy(alpha = alpha), color.copy(alpha = alpha * .35f), Color.Transparent), Offset(w * x, h * y), w * r))
    if (dark) {
        glow(Color(0xFF0B3BB8), .55f, .95f, .78f, 1.25f)
        glow(tertiary, .32f, .55f, 1.02f, .8f)
        glow(primary, .26f, .1f, .55f, .7f)
        glow(Color(0xFF2B1C7A), .3f, 1f, .12f, .9f)
    } else {
        glow(primary, .14f, .95f, .85f, 1.1f)
        glow(tertiary, .16f, .1f, .95f, .9f)
        glow(Color(0xFFFF6FB1), .08f, .9f, .08f, .9f)
    }
}
