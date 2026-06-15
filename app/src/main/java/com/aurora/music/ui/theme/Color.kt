package com.aurora.music.ui.theme

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

val AccentPresets = listOf(
    AccentPreset("Rose", AuroraRose),
    AccentPreset("Coral", AuroraCoral),
    AccentPreset("Magenta", AuroraMagenta),
    AccentPreset("Violet", AuroraViolet),
    AccentPreset("Amber", AuroraAmber),
    AccentPreset("Blue", Color(0xFF3B82F6)),
    AccentPreset("Teal", Color(0xFF14B8A6)),
    AccentPreset("Green", Color(0xFF22C55E)),
    AccentPreset("Sky", Color(0xFF38BDF8)),
    AccentPreset("Mono", Color(0xFFB8B0B4)),
)
