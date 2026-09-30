package com.aurora.music.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.aurora.music.data.AppTypeface
import com.aurora.music.data.ThemeStyle
import com.aurora.music.desktop.resources.DmSans
import com.aurora.music.desktop.resources.Manrope
import com.aurora.music.desktop.resources.PlusJakartaSans

fun auroraFontFamily(typeface: Int = AppTypeface.THEME_DEFAULT, style: Int = ThemeStyle.AURORA): FontFamily = when (typeface) {
    AppTypeface.DM_SANS -> DmSans
    AppTypeface.PLUS_JAKARTA_SANS -> PlusJakartaSans
    AppTypeface.MANROPE -> Manrope
    else -> when (style) {
        ThemeStyle.RETRO -> FontFamily.Monospace
        ThemeStyle.AERO -> FontFamily.SansSerif
        else -> DmSans
    }
}

fun auroraTypography(scale: Float = 1f, style: Int = ThemeStyle.AURORA, typeface: Int = AppTypeface.THEME_DEFAULT): Typography {
    val s = scale.coerceIn(0.8f, 1.4f)
    val family = auroraFontFamily(typeface, style)
    fun t(weight: FontWeight, size: Float, line: Float, letter: Float = 0f) = TextStyle(
        fontFamily = family,
        fontWeight = when {
            style == ThemeStyle.GLASS && size >= 22f -> FontWeight.Light
            style == ThemeStyle.AERO && size >= 22f -> FontWeight.Normal
            else -> weight
        },
        fontSize = (size * s).sp, lineHeight = (line * s).sp,
        letterSpacing = (if (style == ThemeStyle.RETRO) 0f else letter).sp,
    )
    return Typography(
        displayLarge = t(FontWeight.Black, 40f, 46f, -0.5f),
        displayMedium = t(FontWeight.Black, 32f, 38f, -0.5f),
        displaySmall = t(FontWeight.Bold, 28f, 34f),
        headlineLarge = t(FontWeight.Bold, 26f, 32f, -0.3f),
        headlineMedium = t(FontWeight.Bold, 22f, 28f),
        headlineSmall = t(FontWeight.Bold, 19f, 24f),
        titleLarge = t(FontWeight.Bold, 18f, 24f),
        titleMedium = t(FontWeight.Medium, 16f, 22f),
        titleSmall = t(FontWeight.Medium, 14f, 20f),
        bodyLarge = t(FontWeight.Normal, 16f, 24f),
        bodyMedium = t(FontWeight.Normal, 14f, 20f),
        bodySmall = t(FontWeight.Normal, 12f, 16f),
        labelLarge = t(FontWeight.Medium, 14f, 18f),
        labelMedium = t(FontWeight.Medium, 12f, 16f),
        labelSmall = t(FontWeight.Medium, 11f, 14f, 0.5f),
    )
}
