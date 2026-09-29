package com.aurora.music.desktop.ui

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aurora.music.data.UiPrefs
import com.aurora.music.desktop.DesktopContainer
import com.aurora.music.desktop.player.PlayerController
import com.aurora.music.ui.layout.LocalWindowLayout
import com.aurora.music.ui.layout.WindowLayout
import com.aurora.music.ui.theme.AuroraTheme

@Composable
fun AuroraRoot(
    container: DesktopContainer,
    player: PlayerController,
    initialPrefs: UiPrefs = UiPrefs(),
    systemAccent: Color? = null,
    content: @Composable () -> Unit,
) {
    val uiPrefs by container.settingsStore.uiPrefs.collectAsState(initialPrefs)
    CompositionLocalProvider(LocalDesktopContainer provides container, LocalPlayer provides player) {
        AuroraTheme(uiPrefs, systemAccent) {
            val ink = MaterialTheme.colorScheme.onSurface
            val scrollbar = ScrollbarStyle(
                minimalHeight = 24.dp,
                thickness = 8.dp,
                shape = RoundedCornerShape(4.dp),
                hoverDurationMillis = 300,
                unhoverColor = ink.copy(alpha = 0.18f),
                hoverColor = ink.copy(alpha = 0.45f),
            )
            CompositionLocalProvider(LocalScrollbarStyle provides scrollbar) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalWindowLayout provides WindowLayout(maxWidth.value.toInt(), maxHeight.value.toInt())) {
                        content()
                    }
                }
            }
        }
    }
}
