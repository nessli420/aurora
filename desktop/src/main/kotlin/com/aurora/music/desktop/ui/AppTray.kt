package com.aurora.music.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Tray
import com.aurora.music.R
import com.aurora.music.desktop.player.PlayerController
import com.aurora.music.localization.appString

@Composable
fun ApplicationScope.AppTray(icon: Painter, player: PlayerController, onShow: () -> Unit, onQuit: () -> Unit) {
    val state by player.state.collectAsState()
    Tray(
        icon = icon,
        tooltip = if (state.hasTrack) "${state.current.title} · ${state.current.artist}".take(120) else "Aurora",
        onAction = onShow,
        menu = {
            Item(appString(R.string.text_play_pause_14a1d0), enabled = state.hasTrack, onClick = player::togglePlay)
            Item(appString(R.string.text_next_bc9819), enabled = state.hasTrack, onClick = player::next)
            Item(appString(R.string.text_previous_50f942), enabled = state.hasTrack, onClick = player::previous)
            Separator()
            Item(appString(R.string.text_show_d97d1e), onClick = onShow)
            Item(appString(R.string.text_close_bbfa77), onClick = onQuit)
        },
    )
}
