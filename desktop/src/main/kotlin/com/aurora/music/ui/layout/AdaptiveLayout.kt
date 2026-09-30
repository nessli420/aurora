package com.aurora.music.ui.layout

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class WindowLayout(val widthDp: Int = 0, val heightDp: Int = 0) {
    val useNavigationRail: Boolean get() = widthDp >= 600
    val canExpandSidebar: Boolean get() = widthDp >= 1200
    val canShowSidePanel: Boolean get() = useNavigationRail &&
        widthDp - TabletMetrics.RailWidth.value - TabletMetrics.SidePanelWidth.value >= TabletMetrics.MinContentWidth.value
    val pageGutter: Dp get() = when {
        widthDp >= 1800 -> 48.dp
        widthDp >= 1400 -> 40.dp
        widthDp >= 1100 -> 32.dp
        else -> 24.dp
    }
}

object TabletMetrics {
    val RailWidth: Dp = 88.dp
    val SidebarWidth: Dp = 232.dp
    val SidePanelWidth: Dp = 360.dp
    val MinContentWidth: Dp = 600.dp
    val ComfortableContentWidth: Dp = 600.dp
    val NavGap: Dp = 8.dp
    val WindowInset: Dp = 12.dp
}

object PageMetrics {
    val HeaderTop: Dp = 24.dp
    val HeaderHeight: Dp = 56.dp
    val SectionGap: Dp = 32.dp
    val HeaderToContent: Dp = 12.dp
    val RowGap: Dp = 4.dp
    val PaneGutter: Dp = 24.dp
    val CardWidth: Dp = 176.dp
    val ShelfMinItemWidth: Dp = 168.dp
    val ShelfSpacing: Dp = 16.dp
    val SongRowHeight: Dp = 56.dp
    val SongTableMinWidth: Dp = 760.dp
    val ReadingMaxWidth: Dp = 680.dp
    val SearchFieldMaxWidth: Dp = 720.dp
    val FormMaxWidth: Dp = 840.dp
    val SignInMaxWidth: Dp = 1040.dp
}

data class ShellLayout(val expandedSidebar: Boolean, val sidePanel: Boolean) {
    val navWidth: Dp get() = if (expandedSidebar) TabletMetrics.SidebarWidth else TabletMetrics.RailWidth
}

fun WindowLayout.shell(panelRequested: Boolean): ShellLayout {
    val panel = panelRequested && canShowSidePanel
    val roomWithSidebar = widthDp - TabletMetrics.SidebarWidth.value - (if (panel) TabletMetrics.SidePanelWidth.value else 0f)
    return ShellLayout(
        expandedSidebar = canExpandSidebar && roomWithSidebar >= TabletMetrics.ComfortableContentWidth.value,
        sidePanel = panel,
    )
}

val LocalWindowLayout = staticCompositionLocalOf { WindowLayout() }

val LocalPageGutter = staticCompositionLocalOf { 24.dp }
