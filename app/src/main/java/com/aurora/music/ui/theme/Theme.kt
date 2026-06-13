package com.aurora.music.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.aurora.music.data.AccentMode
import com.aurora.music.data.ThemeMode
import com.aurora.music.data.UiPrefs

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

/** Visual prefs available to any composable (player gradient intensity, density, etc.). */
val LocalUiPrefs = staticCompositionLocalOf { UiPrefs() }

/** Contrasting on-color for an arbitrary accent. */
private fun onAccent(seed: Color): Color = if (seed.luminance() > 0.5f) Color(0xFF1A1016) else Color.White

/** Recolor a base scheme around an accent [seed], keeping Aurora's warm surface ramp. */
private fun ColorScheme.withAccent(seed: Color, dark: Boolean): ColorScheme {
    val on = onAccent(seed)
    return copy(
        primary = seed,
        onPrimary = on,
        primaryContainer = if (dark) lerp(seed, Color.Black, 0.45f) else lerp(seed, Color.White, 0.6f),
        onPrimaryContainer = if (dark) Color.White else lerp(seed, Color.Black, 0.6f),
        secondary = seed,
        onSecondary = on,
        // Keep gradient variety (avatars/signature bars use primary→tertiary).
        tertiary = if (dark) lerp(seed, Color.White, 0.22f) else lerp(seed, Color.Black, 0.18f),
        onTertiary = on,
    )
}

/** Pure-black surfaces for OLED screens. */
private fun ColorScheme.toAmoled(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color(0xFF0B0B0B),
    surfaceContainer = Color(0xFF0B0B0B),
    surfaceContainerHigh = Color(0xFF151515),
    surfaceContainerHighest = Color(0xFF1E1E1E),
)

@Composable
fun AuroraTheme(
    uiPrefs: UiPrefs = UiPrefs(),
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val useDark = when (uiPrefs.themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
        else -> systemDark
    }
    val amoled = uiPrefs.themeMode == ThemeMode.AMOLED
    val context = LocalContext.current

    val materialYou = uiPrefs.accentMode == AccentMode.MATERIAL_YOU && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    var colors = when {
        materialYou -> if (useDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> {
            val seed = when (uiPrefs.accentMode) {
                AccentMode.CUSTOM -> Color(uiPrefs.accentColor.toInt())
                else -> AccentPresets.getOrElse(uiPrefs.accentPreset) { AccentPresets[0] }.seed
            }
            (if (useDark) DarkColors else LightColors).withAccent(seed, useDark)
        }
    }
    if (amoled) colors = colors.toAmoled()

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !useDark
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !useDark
        }
    }
    MaterialTheme(
        colorScheme = colors,
        typography = auroraTypography(uiPrefs.fontScale),
        shapes = auroraShapes(uiPrefs.cornerStyle),
    ) {
        // App content renders outside any M3 Surface (we paint our own ambient background), so
        // establish a default content color — otherwise unstyled Text/Icon falls back to black.
        CompositionLocalProvider(
            LocalContentColor provides colors.onBackground,
            LocalUiPrefs provides uiPrefs,
            content = content,
        )
    }
}
