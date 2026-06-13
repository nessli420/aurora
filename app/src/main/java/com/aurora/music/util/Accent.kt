package com.aurora.music.util

import androidx.compose.ui.graphics.Color
import kotlin.math.absoluteValue

/** Warm rose/coral/magenta brand palette reused for deterministic per-item accents. */
val AccentPalette = listOf(
    Color(0xFFFF2E7E), Color(0xFFFF7A59), Color(0xFFC24CE0),
    Color(0xFFFB7185), Color(0xFFF7B733), Color(0xFFA855F7),
    Color(0xFFFF5C8A), Color(0xFFFF8E6E),
)

/** Stable accent color derived from an id/string, so a given item always looks the same. */
fun accentFor(seed: String): Color = AccentPalette[seed.hashCode().absoluteValue % AccentPalette.size]
