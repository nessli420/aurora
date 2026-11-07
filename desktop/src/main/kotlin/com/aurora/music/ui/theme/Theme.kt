package com.aurora.music.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.aurora.music.data.AccentMode
import com.aurora.music.data.ThemeMode
import com.aurora.music.data.ThemeStyle
import com.aurora.music.data.UiPrefs
import com.aurora.music.util.rememberDominantColor

private val DarkColors = darkColorScheme(
    primary = AuroraRose,
    onPrimary = Color.White,
    primaryContainer = AuroraRoseDeep,
    onPrimaryContainer = Color.White,
    secondary = AuroraCoral,
    onSecondary = Color(0xFF2A0E06),
    tertiary = AuroraMagenta,
    onTertiary = Color.White,
    background = DarkBackground,
    onBackground = TextPrimaryDark,
    surface = DarkSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = DarkSurfaceElevated,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainer = DarkSurfaceElevated,
    surfaceContainerHigh = DarkSurfaceHigh,
    surfaceContainerHighest = DarkSurfaceHigh,
    outline = DarkOutline,
    outlineVariant = DarkOutline,
    error = Color(0xFFFF5470),
)

private val LightColors = lightColorScheme(
    primary = AuroraRoseDeep,
    onPrimary = Color.White,
    primaryContainer = AuroraBlush,
    onPrimaryContainer = TextPrimaryLight,
    secondary = AuroraCoral,
    onSecondary = Color.White,
    tertiary = AuroraMagenta,
    background = LightBackground,
    onBackground = TextPrimaryLight,
    surface = LightSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = LightSurfaceElevated,
    onSurfaceVariant = TextSecondaryLight,
    surfaceContainer = LightSurfaceElevated,
    surfaceContainerHigh = LightSurfaceElevated,
    outline = LightOutline,
    error = Color(0xFFD11A4B),
)

val LocalUiPrefs = compositionLocalOf { UiPrefs() }

fun Color.fade(alpha: Float): Color = copy(alpha = this.alpha * alpha)

data class GlassLook(val tint: Float = 0.28f, val frost: Float = 0.08f, val edges: Boolean = true)

fun ColorScheme.glassScheme(dark: Boolean, look: GlassLook = GlassLook()): ColorScheme {
    val panel = if (dark) look.frost else (0.15f + look.frost * 3f).coerceAtMost(0.75f)
    return copy(
        background = Color.Transparent,
        surface = Color.Transparent,
        surfaceContainerLowest = Color.White.copy(alpha = panel * 0.4f),
        surfaceContainerLow = Color.White.copy(alpha = panel * 0.6f),
        surfaceContainer = surfaceContainer.copy(alpha = 0.92f),
        surfaceContainerHigh = Color.White.copy(alpha = panel),
        surfaceContainerHighest = Color.White.copy(alpha = (panel * 1.5f).coerceAtMost(0.9f)),
    )
}

val LocalGlass = staticCompositionLocalOf { false }

val LocalGlassLook = staticCompositionLocalOf { GlassLook() }

// the scheme before glass thinned it; dialogs and sheets float over busy content, so they stay solid
val LocalSolidColors = staticCompositionLocalOf<ColorScheme?> { null }

@Composable
fun solidColors(): ColorScheme = LocalSolidColors.current ?: MaterialTheme.colorScheme

@Composable
fun Modifier.glassEdge(shape: Shape): Modifier = if (!LocalGlass.current || !LocalGlassLook.current.edges) this else
    border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.28f), Color.White.copy(alpha = 0.05f))), shape)

@Composable
fun glassSheen(): Brush {
    val frost = LocalGlassLook.current.frost
    return Brush.verticalGradient(listOf(Color.White.copy(alpha = frost * 2f), Color.White.copy(alpha = frost * 0.75f)))
}

private fun onAccent(seed: Color): Color = if (seed.luminance() > 0.5f) Color(0xFF1A1016) else Color.White

private fun ColorScheme.withAccent(seed: Color, dark: Boolean): ColorScheme {
    val on = onAccent(seed)
    return copy(
        primary = seed,
        onPrimary = on,
        primaryContainer = if (dark) lerp(seed, Color.Black, 0.45f) else lerp(seed, Color.White, 0.6f),
        onPrimaryContainer = if (dark) Color.White else lerp(seed, Color.Black, 0.6f),
        secondary = seed,
        onSecondary = on,
        tertiary = if (dark) lerp(seed, Color.White, 0.22f) else lerp(seed, Color.Black, 0.18f),
        onTertiary = on,
    )
}

private fun ColorScheme.toAmoled(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color(0xFF0B0B0B),
    surfaceContainer = Color(0xFF0B0B0B),
    surfaceContainerHigh = Color(0xFF151515),
    surfaceContainerHighest = Color(0xFF1E1E1E),
)

@Composable
fun rememberPlayerColorScheme(artworkUrl: String, fallback: Color): ColorScheme {
    val prefs = LocalUiPrefs.current
    val appColors = MaterialTheme.colorScheme
    if (prefs.themeStyle != ThemeStyle.AURORA) return appColors

    val accent by rememberDominantColor(artworkUrl, fallback)
    val dark = appColors.onBackground.luminance() > 0.5f
    val amoled = prefs.themeMode == ThemeMode.AMOLED
    return remember(accent, dark, amoled) { playerColorScheme(accent, dark).let { if (amoled) it.toAmoled() else it } }
}

// its own restart scope, so the accent fade recomposes only the player and not the shell around it
@Composable
fun PlayerTheme(artworkUrl: String, fallback: Color, content: @Composable () -> Unit) {
    val glass = LocalGlass.current
    val glassLook = LocalGlassLook.current
    val artColors = rememberPlayerColorScheme(artworkUrl, fallback)
    val playerColors = remember(artColors, glass, glassLook) {
        if (glass) artColors.glassScheme(artColors.onBackground.luminance() > 0.5f, glassLook) else artColors
    }
    MaterialTheme(colorScheme = playerColors, content = content)
}

fun playerColorScheme(accent: Color, dark: Boolean): ColorScheme {
    val base = (if (dark) DarkColors else LightColors).withAccent(accent, dark)
    fun foreground(color: Color) = if (color.luminance() > 0.179f) Color.Black else Color.White
    return base.copy(
        onPrimary = foreground(base.primary),
        onPrimaryContainer = foreground(base.primaryContainer),
        onSecondary = foreground(base.secondary),
        secondaryContainer = base.primaryContainer,
        onSecondaryContainer = foreground(base.primaryContainer),
        onTertiary = foreground(base.tertiary),
        tertiaryContainer = base.primaryContainer,
        onTertiaryContainer = foreground(base.primaryContainer),
        surfaceTint = accent,
        inversePrimary = accent,
    )
}

fun UiPrefs.accentSeed(systemAccent: Color?): Color = when {
    accentMode == AccentMode.CUSTOM -> Color(accentColor.toInt())
    accentMode == AccentMode.MATERIAL_YOU && systemAccent != null -> systemAccent
    else -> AccentPresets.getOrElse(accentPreset) { AccentPresets[0] }.seed
}

@Composable
fun AuroraTheme(
    uiPrefs: UiPrefs = UiPrefs(),
    systemAccent: Color? = null,
    playingAccent: Color? = null,
    translucent: Boolean = false,
    glassLook: GlassLook = GlassLook(),
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val useDark = when (uiPrefs.themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
        else -> systemDark
    }
    val amoled = uiPrefs.themeMode == ThemeMode.AMOLED

    // a fresh ColorScheme has no equals, so rebuilding it on every pass would invalidate the whole tree
    val (colors, solid, tint) = remember(useDark, amoled, uiPrefs.themeStyle, uiPrefs.accentMode, uiPrefs.accentColor,
        uiPrefs.accentPreset, systemAccent, playingAccent, translucent, glassLook) {
        var scheme = when {
            uiPrefs.themeStyle != ThemeStyle.AURORA -> styleColorScheme(uiPrefs.themeStyle, useDark)
            else -> {
                val seed = playingAccent?.let { readableAccent(it, useDark) } ?: uiPrefs.accentSeed(systemAccent)
                (if (useDark) DarkColors else LightColors).withAccent(seed, useDark)
            }
        }
        if (amoled) scheme = scheme.toAmoled()
        val tint = scheme.background.copy(alpha = (0.35f + glassLook.tint * 0.65f + if (amoled) 0.15f else 0f).coerceIn(0f, 1f))
        Triple(if (translucent) scheme.glassScheme(useDark, glassLook) else scheme, scheme, tint)
    }

    MaterialTheme(
        colorScheme = colors,
        typography = auroraTypography(uiPrefs.fontScale, uiPrefs.themeStyle, uiPrefs.typeface),
        shapes = auroraShapes(uiPrefs.cornerStyle, uiPrefs.themeStyle),
    ) {
        // content renders outside any m3 surface so set default content color else text falls back to black
        CompositionLocalProvider(
            LocalContentColor provides colors.onBackground,
            LocalUiPrefs provides uiPrefs,
            LocalGlass provides translucent,
            LocalGlassLook provides glassLook,
            LocalSolidColors provides solid,
        ) {
            if (translucent) Box(Modifier.fillMaxSize().background(tint)) { content() } else content()
        }
    }
}
