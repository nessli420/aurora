package com.aurora.music.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.aurora.music.R

val Circular = FontFamily(
    Font(R.font.circular_light, FontWeight.Light),
    Font(R.font.circular_light_italic, FontWeight.Light, FontStyle.Italic),
    Font(R.font.circular_book, FontWeight.Normal),
    Font(R.font.circular_book_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.circular_medium, FontWeight.Medium),
    Font(R.font.circular_medium_italic, FontWeight.Medium, FontStyle.Italic),
    Font(R.font.circular_bold, FontWeight.Bold),
    Font(R.font.circular_bold_italic, FontWeight.Bold, FontStyle.Italic),
    Font(R.font.circular_black, FontWeight.Black),
    Font(R.font.circular_black_italic, FontWeight.Black, FontStyle.Italic),
)

/** Typography scaled by [scale] (font-size preference). lineHeight scales with it; letter spacing
 *  stays fixed. [AuroraTypography] is the default-scale instance used as a fallback. */
fun auroraTypography(scale: Float = 1f): Typography {
    val s = scale.coerceIn(0.8f, 1.4f)
    fun t(weight: FontWeight, size: Float, line: Float, letter: Float = 0f) = TextStyle(
        fontFamily = Circular, fontWeight = weight,
        fontSize = (size * s).sp, lineHeight = (line * s).sp, letterSpacing = letter.sp,
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

val AuroraTypography = auroraTypography(1f)
