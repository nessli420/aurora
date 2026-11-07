package com.aurora.music.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
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
import com.aurora.music.desktop.linux.LinuxDesktop
import com.aurora.music.desktop.platform.BuildInfo
import com.aurora.music.desktop.platform.CaptionFrame
import com.aurora.music.desktop.platform.DesktopPaths
import com.aurora.music.desktop.platform.DesktopRuntime
import com.aurora.music.desktop.platform.HostPlatform
import com.aurora.music.desktop.platform.SystemAccent
import com.aurora.music.desktop.platform.WindowBackdrop
import com.aurora.music.desktop.player.DesktopPlayer
import com.aurora.music.desktop.player.playerDependencies
import com.aurora.music.desktop.resources.AuroraLogo
import com.aurora.music.desktop.ui.AppTray
import com.aurora.music.desktop.ui.AuroraRoot
import com.aurora.music.desktop.ui.LocalBackdropAvailable
import com.aurora.music.desktop.ui.LocalTrayAvailable
import com.aurora.music.desktop.ui.LocalWindowShown
import com.aurora.music.desktop.ui.Shortcut
import com.aurora.music.desktop.ui.TitleBar
import com.aurora.music.desktop.ui.WindowChrome
import com.aurora.music.desktop.ui.WindowResizeEdges
import com.aurora.music.ui.theme.GlassLook
import com.aurora.music.desktop.ui.claimsSpace
import com.aurora.music.desktop.ui.shortcut
import com.aurora.music.localization.AppStrings
import com.aurora.music.ui.AuroraApp
import com.aurora.music.util.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Dimension
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.SystemTray
import java.time.Duration
import java.time.Instant
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import com.aurora.music.desktop.platform.WindowPlacement as SavedWindow

private const val TAG = "AuroraMain"

fun main() {
    val start = ProcessHandle.current().info().startInstant().orElse(null)
    fun since() = start?.let { " +${Duration.between(it, Instant.now()).toMillis()} ms" }.orEmpty()
    val paths = DesktopPaths.default()
    val instance = DesktopRuntime.init(paths) ?: return
    if (HostPlatform.isLinux) LinuxDesktop.applyWindowClass()
    // skiko's setup on linux needs compose's autodpi property first, and its laf branch would touch swing off the edt
    if (HostPlatform.isWindows && System.getProperty("skiko.rendering.laf.global") == "false") {
        thread(isDaemon = true, name = "skiko-warm") { runCatching { org.jetbrains.skiko.Library.load() } }
    }
    AppLog.d(TAG, "Starting Aurora ${BuildInfo.VERSION_NAME} on Java ${Runtime.version()}${since()}")
    val shown = CompletableDeferred<Unit>()
    com.aurora.music.desktop.web.Chromium.holdUntil(shown)
    val container = DesktopContainer(paths)
    AppLog.d(TAG, "Container built${since()}")
    SingletonImageLoader.setSafe { container.imageLoader }
    val settings = container.desktopSettings
    val (language, savedWindow, initialPrefs) = runBlocking {
        Triple(settings.languageTag.first(), settings.window.first(), container.settingsStore.uiPrefs.first())
    }
    val initialTranslucent = runBlocking { settings.translucentWindow.first() }
    val glassAtStart = initialTranslucent && WindowBackdrop.supported
    val customTitleBar = !runBlocking { settings.systemTitleBar.first() }
    AppStrings.setLocale(language)
    thread(isDaemon = true, name = "strings-warm") { AppStrings.table }
    val player = DesktopPlayer(DesktopPlaybackEngine(resolver = container.streamResolver), container.playerDependencies())
    container.mixService.attach(player, container.scope)
    val connect = com.aurora.music.desktop.connect.DesktopConnect(container, player)
    AppLog.d(TAG, "Player built${since()}")
    val shortcuts = MutableSharedFlow<Shortcut>(extraBufferCapacity = 8)
    val raises = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    Runtime.getRuntime().addShutdownHook(Thread { container.folderLibrary.close(); container.queueStore.flushNow(); container.playHistory.flushNow() })

    application {
        remember { AppLog.d(TAG, "Composing${since()}") }
        val windowState = rememberWindowState(
            placement = if (savedWindow?.maximized == true) WindowPlacement.Maximized else WindowPlacement.Floating,
            position = savedWindow?.takeIf(::onScreen)?.let { WindowPosition(it.x.dp, it.y.dp) } ?: WindowPosition(Alignment.Center),
            size = savedWindow?.let { DpSize(it.width.dp, it.height.dp) } ?: defaultWindowSize(),
        )
        var bounds by remember { mutableStateOf(savedWindow) }
        var restorePlacement by remember { mutableStateOf(windowState.placement) }
        var composeWindow by remember { mutableStateOf<androidx.compose.ui.awt.ComposeWindow?>(null) }
        var borderless by remember { mutableStateOf(false) }
        var visible by remember { mutableStateOf(true) }
        var revealed by remember { mutableStateOf(!glassAtStart && !(customTitleBar && HostPlatform.isWindows)) }
        var caption by remember { mutableStateOf<CaptionFrame?>(null) }
        var quitting by remember { mutableStateOf(false) }
        val closeToTray by settings.closeToTray.collectAsState(false)
        val translucentWindow by settings.translucentWindow.collectAsState(initialTranslucent)
        val micaBackdrop by settings.micaBackdrop.collectAsState(false)
        val glassLook by settings.glassLook.collectAsState(GlassLook())
        val trayAvailable = remember { runCatching { SystemTray.isSupported() }.getOrDefault(false) }
        val icon = rememberVectorPainter(AuroraLogo)
        var systemAccent by remember { mutableStateOf(SystemAccent.current()?.let { Color(it) }) }
        DisposableEffect(Unit) {
            val watch = SystemAccent.listen { systemAccent = Color(it) }
            onDispose { watch?.close() }
        }

        fun placement(): SavedWindow? {
            val position = windowState.position
            if (windowState.placement == WindowPlacement.Floating && position.isSpecified && !borderless) {
                bounds = SavedWindow(position.x.value.roundToInt(), position.y.value.roundToInt(),
                    windowState.size.width.value.roundToInt(), windowState.size.height.value.roundToInt())
            }
            val effective = if (borderless || windowState.placement == WindowPlacement.Fullscreen) restorePlacement else windowState.placement
            return bounds?.copy(maximized = effective == WindowPlacement.Maximized)
        }

        fun setFullscreen(on: Boolean) {
            val handle = composeWindow?.windowHandle ?: 0L
            if (com.aurora.music.desktop.platform.BorderlessFullscreen.supported && handle != 0L) {
                if (on && !borderless) {
                    restorePlacement = windowState.placement
                    borderless = com.aurora.music.desktop.platform.BorderlessFullscreen.enter(handle)
                } else if (!on && borderless) borderless = !com.aurora.music.desktop.platform.BorderlessFullscreen.exit(handle)
                return
            }
            val current = windowState.placement
            if (on && current != WindowPlacement.Fullscreen) {
                restorePlacement = current
                windowState.placement = WindowPlacement.Fullscreen
            } else if (!on && current == WindowPlacement.Fullscreen) {
                // compose only leaves full screen on the way to floating; going straight to maximized keeps the bare full-screen frame
                composeWindow?.let { window ->
                    window.placement = WindowPlacement.Floating
                    window.placement = restorePlacement
                }
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
            connect.close()
            player.close()
            container.close()
            com.aurora.music.desktop.web.Chromium.shutdown()
            instance.close()
            exitApplication()
        }

        fun close() {
            if (closeToTray && trayAvailable) visible = false else quit()
        }

        LaunchedEffect(Unit) { container.appUpdater.quitRequests.collect { quit() } }

        LaunchedEffect(windowState) {
            snapshotFlow { Triple(windowState.placement, windowState.position, windowState.size) }.collectLatest {
                delay(500)
                placement()?.let { settings.setWindow(it) }
            }
        }

        if (trayAvailable) AppTray(icon, player, onShow = ::show, onQuit = ::quit)

        Window(
            onCloseRequest = ::close,
            state = windowState,
            visible = visible && revealed,
            title = "Aurora",
            icon = icon,
            undecorated = customTitleBar && HostPlatform.isLinux,
            onPreviewKeyEvent = { it.claimsSpace() },
            onKeyEvent = { event -> event.shortcut()?.let(shortcuts::tryEmit) == true },
        ) {
            var windowHandle by remember { mutableStateOf(0L) }
            var translucent by remember { mutableStateOf(false) }
            LaunchedEffect(window) {
                composeWindow = window
                window.minimumSize = Dimension(960, 600)
                if (!revealed) {
                    if (glassAtStart) WindowBackdrop.prepare(window)
                    if (customTitleBar) caption = CaptionFrame.install(window)
                    revealed = true
                }
                while (!window.isDisplayable) delay(16)
                val handle = window.windowHandle
                windowHandle = handle
                // creating the system media session waits on winrt, so it stays off the reveal
                launch(Dispatchers.IO) { player.mediaControls.attach(handle) { raises.tryEmit(Unit) } }
                AppLog.d(TAG, "Window shown${since()}")
                withFrameNanos { }
                AppLog.d(TAG, "First frame${since()}")
                shown.complete(Unit)
            }
            val fullscreen = borderless || windowState.placement == WindowPlacement.Fullscreen
            // a window covering the whole screen skips dwm composition whenever it can, so its backdrop blinks in and out
            LaunchedEffect(windowHandle, translucentWindow, micaBackdrop, fullscreen) {
                if (windowHandle != 0L) translucent = WindowBackdrop.apply(window, translucentWindow && !fullscreen, micaBackdrop)
            }
            LaunchedEffect(window) {
                merge(instance.activations, raises).collect {
                    show()
                    // toFront only takes effect once compose has shown and restored the window
                    withTimeoutOrNull(1_000) { while (!window.isVisible || (window.extendedState and Frame.ICONIFIED) != 0) delay(16) }
                    window.toFront()
                    window.requestFocus()
                }
            }
            val activePlayer by connect.active.collectAsState()
            CompositionLocalProvider(LocalTrayAvailable provides trayAvailable, LocalBackdropAvailable provides WindowBackdrop.supported,
                LocalWindowShown provides (visible && !windowState.isMinimized), com.aurora.music.desktop.connect.LocalConnect provides connect) {
                AuroraRoot(container, activePlayer, initialPrefs, systemAccent, translucent, glassLook) {
                    val undecorated = customTitleBar && HostPlatform.isLinux
                    WindowChrome(windowHandle, translucent)
                    AuroraApp(
                        shortcuts = shortcuts,
                        fullscreen = fullscreen,
                        onFullscreenChange = ::setFullscreen,
                        titleBar = { if ((caption != null || undecorated) && !fullscreen) TitleBar(window, windowState, caption, ::close) },
                    )
                    if (undecorated && windowState.placement == WindowPlacement.Floating) WindowResizeEdges(window)
                    com.aurora.music.ui.screens.auth.SoundCloudCaptchaWindow(container.soundcloudWriter)
                }
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
