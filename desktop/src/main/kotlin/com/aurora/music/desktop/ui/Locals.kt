package com.aurora.music.desktop.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.aurora.music.desktop.DesktopContainer
import com.aurora.music.desktop.player.PlayerController

val LocalDesktopContainer = staticCompositionLocalOf<DesktopContainer> { error("no desktop container") }

val LocalPlayer = staticCompositionLocalOf<PlayerController> { error("no player") }

val LocalTrayAvailable = staticCompositionLocalOf { true }
