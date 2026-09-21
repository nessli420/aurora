package com.aurora.music.ui.layout

import androidx.compose.runtime.staticCompositionLocalOf

data class WindowLayout(val widthDp: Int = 0, val heightDp: Int = 0) {
    val useNavigationRail: Boolean get() = widthDp >= 600
    val useLandscapePlayer: Boolean get() = widthDp >= 840 && widthDp > heightDp
}

val LocalWindowLayout = staticCompositionLocalOf { WindowLayout() }

internal fun isLargeDisplay(widthPx: Int, heightPx: Int, density: Float): Boolean =
    density > 0f && minOf(widthPx, heightPx) / density >= 600f
