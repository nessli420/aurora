package com.aurora.music.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.aurora.music.data.AppTypeface
import com.aurora.music.data.ThemeStyle
import com.aurora.music.desktop.resources.Archivo
import com.aurora.music.desktop.resources.ChivoMono
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
        ThemeStyle.PAPER -> Archivo
        else -> DmSans
    }
}

fun auroraTypography(scale: Float = 1f, style: Int = ThemeStyle.AURORA, typeface: Int = AppTypeface.THEME_DEFAULT): Typography {
    val s = scale.coerceIn(0.8f, 1.4f)
    val family = auroraFontFamily(typeface, style)
    // paper sets its small labels in chivo mono, the way the website labels its data sheets
    val paper = style == ThemeStyle.PAPER
    val paperMono = paper && AppTypeface.normalize(typeface) == AppTypeface.THEME_DEFAULT
    fun t(weight: FontWeight, size: Float, line: Float, letter: Float = 0f, mono: Boolean = false) = TextStyle(
        fontFamily = if (mono && paperMono) ChivoMono else family,
        fontWeight = when {
            style == ThemeStyle.GLASS && size >= 22f -> FontWeight.Light
            style == ThemeStyle.AERO && size >= 22f -> FontWeight.Normal
            paper && mono -> FontWeight.Normal
            paper && size >= 19f -> FontWeight(640)
            paper && weight >= FontWeight.Bold -> FontWeight(720)
            paper && weight == FontWeight.Medium -> FontWeight(if (size == 14f && line == 18f) 600 else 560)
            else -> weight
        },
        fontSize = (size * s).sp, lineHeight = (line * s).sp,
        letterSpacing = when {
            style == ThemeStyle.RETRO -> 0f
            paper && mono -> 0.01f * size
            paper && size >= 19f -> -0.018f * size
            paper -> 0f
            else -> letter
        }.sp,
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
        labelMedium = t(FontWeight.Medium, 12f, 16f, mono = true),
        labelSmall = t(FontWeight.Medium, 11f, 14f, 0.5f, mono = true),
    )
}
