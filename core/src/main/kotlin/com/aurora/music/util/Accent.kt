package com.aurora.music.util

import kotlin.math.absoluteValue

val AccentPalette = listOf(
    0xFFFF2E7E, 0xFFFF7A59, 0xFFC24CE0,
    0xFFFB7185, 0xFFF7B733, 0xFFA855F7,
    0xFFFF5C8A, 0xFFFF8E6E,
)

// stable accent per id so an item always looks the same
fun accentArgbFor(seed: String): Long = AccentPalette[seed.hashCode().absoluteValue % AccentPalette.size]
