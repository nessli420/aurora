package com.aurora.music.desktop.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aurora.music.data.TabletSetting
import com.aurora.music.data.ThemeStyle
import com.aurora.music.data.UiPrefs
import com.aurora.music.desktop.DesktopContainer
import com.aurora.music.desktop.player.PlayerController
import com.aurora.music.model.accent
import com.aurora.music.ui.layout.LocalPageGutter
import com.aurora.music.ui.layout.LocalWindowLayout
import com.aurora.music.ui.layout.WindowLayout
import com.aurora.music.ui.theme.AuroraTheme
import com.aurora.music.util.rememberDominantColor
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
fun AuroraRoot(
    container: DesktopContainer,
    player: PlayerController,
    initialPrefs: UiPrefs = UiPrefs(),
    systemAccent: Color? = null,
    content: @Composable () -> Unit,
) {
    val uiPrefs by container.settingsStore.uiPrefs.collectAsState(initialPrefs)
    val followPlaying by container.desktopSettings.accentFollowsPlaying.collectAsState(false)
    val cover by remember(player) { player.state.map { it.current }.distinctUntilChanged { a, b -> a.artworkUrl == b.artworkUrl && a.accent == b.accent } }
        .collectAsState(null)
    val playingAccent = cover?.takeIf { followPlaying && uiPrefs.themeStyle == ThemeStyle.AURORA && it.artworkUrl.isNotBlank() }
        ?.let { rememberDominantColor(it.artworkUrl, it.accent).value }
    CompositionLocalProvider(LocalDesktopContainer provides container, LocalPlayer provides player) {
        AuroraTheme(uiPrefs, systemAccent, playingAccent) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val window = WindowLayout(maxWidth.value.toInt(), maxHeight.value.toInt())
                val margin = uiPrefs.tabletPageMargin - TabletSetting.PAGE_MARGIN.default
                val gutter = (window.pageGutter + margin.dp).coerceAtLeast(0.dp)
                CompositionLocalProvider(LocalWindowLayout provides window, LocalPageGutter provides gutter) {
                    content()
                }
            }
        }
    }
}
