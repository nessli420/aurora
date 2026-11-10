package com.aurora.music.desktop.platform

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.awaitApplication
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.GraphicsEnvironment

class CaptionFrameTest {
    @Test fun composeOwnsTheCaptionWhileTheNativeFrameKeepsSnapAndResize() = runBlocking {
        assumeTrue(HostPlatform.isWindows && !GraphicsEnvironment.isHeadless())
        val state = WindowState(size = DpSize(1000.dp, 700.dp))
        var window by mutableStateOf<ComposeWindow?>(null)
        var caption: CaptionFrame? = null
        val app = launch(Dispatchers.Default) {
            awaitApplication {
                Window(onCloseRequest = ::exitApplication, state = state, title = "caption", visible = window != null) {
                    LaunchedEffect(Unit) {
                        caption = CaptionFrame.install(this@Window.window)
                        window = this@Window.window
                    }
                    Box(Modifier.fillMaxSize().background(Color.DarkGray))
                }
            }
        }
        try {
            while (window?.isShowing != true) delay(50)
            delay(800)
            val frame = window!!
            val hwnd = WinDef.HWND(Pointer(frame.windowHandle))
            val scale = frame.graphicsConfiguration.defaultTransform.scaleY
            val bar = (36 * scale).toInt()
            val button = (46 * scale).toInt()
            withContext(Dispatchers.Swing) {
                val outer = frame.client(hwnd)
                caption!!.bar = CaptionFrame.Bar(bar, outer.width - 3 * button until outer.width, outer.width - 2 * button until outer.width - button)
                assertEquals("no system caption above compose", 0, frame.insets.top)
                assertEquals("dwm draws no caption buttons of its own", 0, User32.INSTANCE.GetWindowLong(hwnd, WinUser.GWL_STYLE) and 0x00080000)
                assertEquals(outer.height / scale, frame.contentPane.height.toDouble(), 1.0)
                assertEquals(HTTOP, hit(hwnd, outer, outer.width / 2, 1))
                assertEquals(HTCAPTION, hit(hwnd, outer, outer.width / 2, bar / 2))
                assertEquals(HTMAXBUTTON, hit(hwnd, outer, outer.width - button - button / 2, bar / 2))
                assertEquals(HTCLIENT, hit(hwnd, outer, outer.width - button / 2, bar / 2))
                assertEquals(HTCLIENT, hit(hwnd, outer, outer.width / 2, bar + 20))
                assertEquals(HTLEFT, hit(hwnd, outer, -2, outer.height / 2))
                state.placement = WindowPlacement.Maximized
            }
            delay(1000)
            withContext(Dispatchers.Swing) {
                val outer = frame.client(hwnd)
                val monitor = WinUser.MONITORINFO()
                User32.INSTANCE.GetMonitorInfo(User32.INSTANCE.MonitorFromWindow(hwnd, WinUser.MONITOR_DEFAULTTONEAREST), monitor)
                assertEquals("maximized client fills the work area, clear of the taskbar", monitor.rcWork.toRectangle(), outer)
                // the title bar lays out again at the new width, as TitleBar does in the app
                caption!!.bar = CaptionFrame.Bar(bar, outer.width - 3 * button until outer.width, outer.width - 2 * button until outer.width - button)
                assertEquals(HTCAPTION, hit(hwnd, outer, outer.width / 2, 1))
                caption!!.bar = CaptionFrame.Bar()
                assertTrue(BorderlessFullscreen.enter(frame.windowHandle))
            }
            delay(800)
            withContext(Dispatchers.Swing) {
                val monitor = WinUser.MONITORINFO()
                User32.INSTANCE.GetMonitorInfo(User32.INSTANCE.MonitorFromWindow(hwnd, WinUser.MONITOR_DEFAULTTONEAREST), monitor)
                assertEquals(monitor.rcMonitor.toRectangle(), frame.client(hwnd))
                assertEquals(HTCLIENT, hit(hwnd, frame.client(hwnd), 20, 1))
                assertTrue(BorderlessFullscreen.exit(frame.windowHandle))
            }
            delay(1000)
            withContext(Dispatchers.Swing) {
                val monitor = WinUser.MONITORINFO()
                User32.INSTANCE.GetMonitorInfo(User32.INSTANCE.MonitorFromWindow(hwnd, WinUser.MONITOR_DEFAULTTONEAREST), monitor)
                assertEquals(WindowPlacement.Maximized, state.placement)
                assertEquals("leaving full screen lands back in the work area", monitor.rcWork.toRectangle(), frame.client(hwnd))
            }
        } finally {
            app.cancel()
        }
    }

    private fun ComposeWindow.client(hwnd: WinDef.HWND): java.awt.Rectangle {
        val area = WinDef.RECT().also { User32.INSTANCE.GetClientRect(hwnd, it) }
        val origin = WinDef.POINT(0, 0).also { Screen.api.ClientToScreen(hwnd, it) }
        return java.awt.Rectangle(origin.x, origin.y, area.right, area.bottom)
    }

    private fun hit(hwnd: WinDef.HWND, client: java.awt.Rectangle, x: Int, y: Int): Int {
        val at = ((client.y + y) shl 16) or ((client.x + x) and 0xFFFF)
        return User32.INSTANCE.SendMessage(hwnd, 0x0084, WinDef.WPARAM(0), WinDef.LPARAM(at.toLong())).toInt()
    }

    private interface ScreenApi : com.sun.jna.win32.StdCallLibrary {
        fun ClientToScreen(hwnd: WinDef.HWND, point: WinDef.POINT): Boolean
    }

    private object Screen {
        val api: ScreenApi = com.sun.jna.Native.load("user32", ScreenApi::class.java)
    }

    private companion object {
        const val HTCLIENT = 1
        const val HTCAPTION = 2
        const val HTMAXBUTTON = 9
        const val HTLEFT = 10
        const val HTTOP = 12
    }
}
