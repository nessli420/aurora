package com.aurora.music.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.SingletonImageLoader
import com.aurora.music.desktop.audio.DesktopPlaybackEngine
import com.aurora.music.desktop.natives.WindowNative
import com.aurora.music.desktop.platform.BuildInfo
import com.aurora.music.desktop.platform.DesktopPaths
import com.aurora.music.desktop.platform.DesktopRuntime
import com.aurora.music.desktop.player.DesktopPlayer
import com.aurora.music.desktop.player.playerDependencies
import com.aurora.music.desktop.resources.AuroraLogo
import com.aurora.music.desktop.ui.AppTray
import com.aurora.music.desktop.ui.AuroraRoot
import com.aurora.music.desktop.ui.Shortcut
import com.aurora.music.desktop.ui.WindowChrome
import com.aurora.music.desktop.ui.claimsSpace
import com.aurora.music.desktop.ui.shortcut
import com.aurora.music.localization.AppStrings
import com.aurora.music.ui.AuroraApp
import com.aurora.music.util.AppLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Dimension
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import kotlin.math.roundToInt
import com.aurora.music.desktop.platform.WindowPlacement as SavedWindow

private const val TAG = "AuroraMain"

fun main() {
    val paths = DesktopPaths.default()
    val instance = DesktopRuntime.init(paths) ?: return
    AppLog.d(TAG, "Starting Aurora ${BuildInfo.VERSION_NAME} on Java ${Runtime.version()}")
    val container = DesktopContainer(paths)
    SingletonImageLoader.setSafe { container.imageLoader }
    val settings = container.desktopSettings
    val (language, savedWindow, initialPrefs) = runBlocking {
        Triple(settings.languageTag.first(), settings.window.first(), container.settingsStore.uiPrefs.first())
    }
    AppStrings.setLocale(language)
    val player = DesktopPlayer(DesktopPlaybackEngine(), container.playerDependencies())
    val shortcuts = MutableSharedFlow<Shortcut>(extraBufferCapacity = 8)
    Runtime.getRuntime().addShutdownHook(Thread { container.folderLibrary.close(); container.queueStore.flushNow(); container.playHistory.flushNow() })

    application {
        val windowState = rememberWindowState(
            placement = if (savedWindow?.maximized == true) WindowPlacement.Maximized else WindowPlacement.Floating,
            position = savedWindow?.takeIf(::onScreen)?.let { WindowPosition(it.x.dp, it.y.dp) } ?: WindowPosition(Alignment.Center),
            size = savedWindow?.let { DpSize(it.width.dp, it.height.dp) } ?: defaultWindowSize(),
        )
        var bounds by remember { mutableStateOf(savedWindow) }
        var restorePlacement by remember { mutableStateOf(windowState.placement) }
        var visible by remember { mutableStateOf(true) }
        var quitting by remember { mutableStateOf(false) }
        val closeToTray by settings.closeToTray.collectAsState(false)
        val icon = rememberVectorPainter(AuroraLogo)
        var systemAccent by remember { mutableStateOf(runCatching { WindowNative.accentColor() }.getOrNull()?.let { Color(it) }) }
        DisposableEffect(Unit) {
            val watch = runCatching { WindowNative.addAccentListener { systemAccent = Color(it) } }.getOrNull()
            onDispose { watch?.close() }
        }

        fun placement(): SavedWindow? {
            val position = windowState.position
            if (windowState.placement == WindowPlacement.Floating && position.isSpecified) {
                bounds = SavedWindow(position.x.value.roundToInt(), position.y.value.roundToInt(),
                    windowState.size.width.value.roundToInt(), windowState.size.height.value.roundToInt())
            }
            val effective = if (windowState.placement == WindowPlacement.Fullscreen) restorePlacement else windowState.placement
            return bounds?.copy(maximized = effective == WindowPlacement.Maximized)
        }

        fun setFullscreen(on: Boolean) {
            val current = windowState.placement
            if (on && current != WindowPlacement.Fullscreen) {
                restorePlacement = current
                windowState.placement = WindowPlacement.Fullscreen
            } else if (!on && current == WindowPlacement.Fullscreen) {
                windowState.placement = restorePlacement
            }
        }

        fun show() {
            visible = true
            windowState.isMinimized = false
        }

        fun quit() {
            if (quitting) return
            quitting = true
            AppLog.d(TAG, "Quitting")
            placement()?.let { runCatching { runBlocking { settings.setWindow(it) } } }
            player.close()
            container.close()
            instance.close()
            exitApplication()
        }

        LaunchedEffect(windowState) {
            snapshotFlow { Triple(windowState.placement, windowState.position, windowState.size) }.collectLatest {
                delay(500)
                placement()?.let { settings.setWindow(it) }
            }
        }

        AppTray(icon, player, onShow = ::show, onQuit = ::quit)

        Window(
            onCloseRequest = { if (closeToTray) visible = false else quit() },
            state = windowState,
            visible = visible,
            title = "Aurora",
            icon = icon,
            onPreviewKeyEvent = { it.claimsSpace() },
            onKeyEvent = { event -> event.shortcut()?.let(shortcuts::tryEmit) == true },
        ) {
            var windowHandle by remember { mutableStateOf(0L) }
            LaunchedEffect(window) {
                window.minimumSize = Dimension(960, 600)
                while (!window.isDisplayable) delay(16)
                windowHandle = window.windowHandle
                player.mediaControls.attach(windowHandle)
                AppLog.d(TAG, "Window shown")
            }
            LaunchedEffect(window) {
                instance.activations.collect {
                    show()
                    // toFront only takes effect once compose has shown and restored the window
                    withTimeoutOrNull(1_000) { while (!window.isVisible || (window.extendedState and Frame.ICONIFIED) != 0) delay(16) }
                    window.toFront()
                    window.requestFocus()
                }
            }
            AuroraRoot(container, player, initialPrefs, systemAccent) {
                WindowChrome(windowHandle)
                AuroraApp(
                    shortcuts = shortcuts,
                    fullscreen = windowState.placement == WindowPlacement.Fullscreen,
                    onFullscreenChange = ::setFullscreen,
                )
            }
        }
    }
}

private fun onScreen(window: SavedWindow): Boolean = runCatching {
    val area = Rectangle(window.x, window.y, window.width, window.height)
    GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.any { it.defaultConfiguration.bounds.intersects(area) }
}.getOrDefault(false)

private fun defaultWindowSize(): DpSize {
    val screen = runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }.getOrNull()
    val width = screen?.let { minOf(1440, it.width - 48) } ?: 1440
    val height = screen?.let { minOf(900, it.height - 48) } ?: 900
    return DpSize(width.coerceAtLeast(960).dp, height.coerceAtLeast(600).dp)
}
