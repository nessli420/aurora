package com.aurora.music.ui.layout

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class WindowLayout(val widthDp: Int = 0, val heightDp: Int = 0) {
    val useNavigationRail: Boolean get() = widthDp >= 600
    val canExpandSidebar: Boolean get() = widthDp >= 1200
    val canShowSidePanel: Boolean get() = useNavigationRail &&
        widthDp - TabletMetrics.RailWidth.value - TabletMetrics.SidePanelWidth.value >= TabletMetrics.MinContentWidth.value
    val gutter: Dp get() = if (useNavigationRail) TabletMetrics.Gutter else 16.dp
}

object TabletMetrics {
    val RailWidth: Dp = 88.dp
    val SidebarWidth: Dp = 232.dp
    val SidePanelWidth: Dp = 380.dp
    val MinContentWidth: Dp = 600.dp
    val ComfortableContentWidth: Dp = 720.dp
    val Gutter: Dp = 24.dp
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
