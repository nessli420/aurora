package com.aurora.music.ui.theme

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

val AuroraRose = Color(0xFFFF2E7E)
val AuroraRoseDeep = Color(0xFFC7245C)
val AuroraCoral = Color(0xFFFF7A59)
val AuroraMagenta = Color(0xFFC24CE0)
val AuroraBlush = Color(0xFFFF9BBA)
val AuroraAmber = Color(0xFFF7B733)
val AuroraViolet = Color(0xFFA855F7)

val DarkBackground = Color(0xFF0E0A0D)
val DarkSurface = Color(0xFF191217)
val DarkSurfaceElevated = Color(0xFF221820)
val DarkSurfaceHigh = Color(0xFF2C2028)
val DarkOutline = Color(0xFF3A2C34)
val TextPrimaryDark = Color(0xFFF7F1F4)
val TextSecondaryDark = Color(0xFFB3A4AD)

val LightBackground = Color(0xFFFDF8FA)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceElevated = Color(0xFFF6EEF2)
val LightOutline = Color(0xFFEADCE3)
val TextPrimaryLight = Color(0xFF1A1016)
val TextSecondaryLight = Color(0xFF6B5560)

val PlayerGradient = listOf(Color(0xFF3A1626), Color(0xFF1A0F16), DarkBackground)
val AuthGradient = listOf(Color(0xFF2A0E1C), Color(0xFF0E0A0D))

fun brandGradient() = Brush.linearGradient(listOf(AuroraRose, AuroraCoral))

// seed becomes the material primary scheme built around it in AuroraTheme
data class AccentPreset(val name: String, val seed: Color)

val AccentPresets: List<AccentPreset> get() = listOf(
    AccentPreset(appString(R.string.text_rose_51ad0e), AuroraRose),
    AccentPreset(appString(R.string.text_coral_d27ca6), AuroraCoral),
    AccentPreset(appString(R.string.text_magenta_ff6912), AuroraMagenta),
    AccentPreset(appString(R.string.text_violet_ddcd2f), AuroraViolet),
    AccentPreset(appString(R.string.text_amber_27a01d), AuroraAmber),
    AccentPreset(appString(R.string.text_blue_7d44bc), Color(0xFF3B82F6)),
    AccentPreset(appString(R.string.text_teal_df0ad3), Color(0xFF14B8A6)),
    AccentPreset(appString(R.string.text_green_933bf2), Color(0xFF22C55E)),
    AccentPreset(appString(R.string.text_sky_b3d97f), Color(0xFF38BDF8)),
    AccentPreset(appString(R.string.text_mono_c5c553), Color(0xFFB8B0B4)),
)
