package com.aurora.music.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.aurora.music.R
import com.aurora.music.data.AppTypeface
import com.aurora.music.data.ThemeStyle

@OptIn(ExperimentalTextApi::class)
private fun variableFontFamily(
    regular: Int,
    italic: Int? = null,
    weightRange: IntRange,
    opticalSize: Float? = null,
): FontFamily = FontFamily(
    buildList {
        for (weight in 100..900 step 100) {
            val settings = buildList {
                add(FontVariation.weight(weight.coerceIn(weightRange)))
                opticalSize?.let { add(FontVariation.Setting("opsz", it)) }
            }
            add(Font(regular, FontWeight(weight), variationSettings = FontVariation.Settings(*settings.toTypedArray())))
            if (italic != null) {
                add(Font(italic, FontWeight(weight), FontStyle.Italic, variationSettings = FontVariation.Settings(*settings.toTypedArray())))
            }
        }
    },
)

private val DmSans = variableFontFamily(R.font.dm_sans, R.font.dm_sans_italic, 100..1000, opticalSize = 14f)
private val PlusJakartaSans = variableFontFamily(R.font.plus_jakarta_sans, R.font.plus_jakarta_sans_italic, 200..800)
private val Manrope = variableFontFamily(R.font.manrope, weightRange = 200..800)

// the website's own in-between weights (560, 640, 720) get exact instances of the variable fonts
@OptIn(ExperimentalTextApi::class)
private fun exactWeights(res: Int, weights: List<Int>, heaviest: Int = 1000): FontFamily = FontFamily(weights.map { weight ->
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight.coerceAtMost(heaviest))))
})
// the site never sets type heavier than 720, so screens that ask for extra bold or black get 720 too
private val Archivo = exactWeights(R.font.archivo, (100..900 step 100).toList() + listOf(560, 640, 720), heaviest = 720)
// and its mono labels are always regular
private val ChivoMono = exactWeights(R.font.chivo_mono, (100..900 step 100).toList(), heaviest = 400)

fun auroraFontFamily(typeface: Int = AppTypeface.THEME_DEFAULT, style: Int = ThemeStyle.AURORA): FontFamily = when (typeface) {
    AppTypeface.DM_SANS -> DmSans
    AppTypeface.PLUS_JAKARTA_SANS -> PlusJakartaSans
    AppTypeface.MANROPE -> Manrope
    else -> when (style) {
        ThemeStyle.RETRO -> FontFamily.Monospace
        ThemeStyle.AERO, ThemeStyle.GLASS -> FontFamily.SansSerif
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
            style == ThemeStyle.GLASS && size >= 22f -> FontWeight.Bold
            style == ThemeStyle.GLASS && weight == FontWeight.Medium -> FontWeight.SemiBold
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
            style == ThemeStyle.GLASS && size >= 22f -> -0.02f * size
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

val AuroraTypography = auroraTypography(1f)
