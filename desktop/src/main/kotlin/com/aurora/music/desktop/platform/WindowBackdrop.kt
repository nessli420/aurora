package com.aurora.music.desktop.platform

import androidx.compose.ui.awt.ComposeWindow
import com.aurora.music.desktop.natives.WindowNative
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinUser
import org.jetbrains.skiko.SkiaLayer
import java.awt.Canvas
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.EventQueue
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent

object WindowBackdrop {
    private const val NONE = 1
    private const val MICA = 2
    private const val ACRYLIC = 3
    private const val GA_ROOT = 2
    private const val FIRST_BACKDROP_BUILD = 22523
    private const val REDRAW_ALL = 0x0001 or 0x0004 or 0x0080 or 0x0100
    private const val BLACK_BRUSH = 4
    private const val GWLP_WNDPROC = -4
    private const val WM_NCACTIVATE = 0x0086

    val supported: Boolean by lazy {
        HostPlatform.isMac || (HostPlatform.isWindows && runCatching { windowsBuild() >= FIRST_BACKDROP_BUILD }.getOrDefault(false))
    }

    fun prepare(window: ComposeWindow) {
        if (HostPlatform.isMac) return com.aurora.music.desktop.mac.MacBackdrop.prepare(window)
        runCatching {
            window.find(SkiaLayer::class.java)?.transparency = true
            window.paintBehind(Color.BLACK)
        }
    }

    fun apply(window: ComposeWindow, enabled: Boolean, mica: Boolean = false): Boolean {
        if (!supported) return false
        // on macos the second backdrop is the frosted blur rather than mica
        if (HostPlatform.isMac) return com.aurora.music.desktop.mac.MacBackdrop.apply(window, enabled, frosted = mica)
        val hwnd = window.windowHandle
        if (hwnd == 0L) return false
        return runCatching {
            val root = User.api.GetAncestor(Pointer(hwnd), GA_ROOT) ?: Pointer(hwnd)
            val layer = window.find(SkiaLayer::class.java) ?: return false
            if (!enabled || !WindowNative.setSystemBackdrop(hwnd, if (mica) MICA else ACRYLIC) || Dwm.api.DwmExtendFrameIntoClientArea(root, Margins(-1)) != 0) {
                steady = false
                WindowNative.setSystemBackdrop(hwnd, NONE)
                Dwm.api.DwmExtendFrameIntoClientArea(root, Margins(0))
                layer.transparency = false
                window.paintBehind(null)
                window.watchResize(false)
                return false
            }
            layer.transparency = true
            window.paintBehind(Color.BLACK)
            window.watchResize(true)
            window.wipeSurfaces()
            keepActive(root)
            true
        }.getOrDefault(false)
    }

    // the frame always reports itself active, so dwm never flickers the backdrop to its inactive look on focus changes
    @Volatile private var steady = false
    private val activeProcs = mutableMapOf<Pointer, WinUser.WindowProc>()

    private fun keepActive(root: Pointer) {
        steady = true
        if (root in activeProcs) return
        val hwnd = HWND(root)
        val original = Pointer(User32.INSTANCE.GetWindowLongPtr(hwnd, GWLP_WNDPROC).toLong())
        val proc = WinUser.WindowProc { h, msg, w, l ->
            User32.INSTANCE.CallWindowProc(original, h, msg, if (msg == WM_NCACTIVATE && steady) WPARAM(1) else w, l)
        }
        activeProcs[root] = proc
        User.api.SetWindowLongPtrW(hwnd, GWLP_WNDPROC, proc)
    }

    private val originals = mutableMapOf<Component, Color?>()

    private fun ComposeWindow.paintBehind(color: Color?) {
        val parts = descendants()
        parts.forEach { part ->
            if (color != null) {
                if (part !in originals) originals[part] = part.background
                part.background = color
            } else if (part in originals) {
                part.background = originals.remove(part)
            }
        }
        val hwnd = windowHandle
        if (hwnd != 0L) User.api.RedrawWindow(User.api.GetAncestor(Pointer(hwnd), GA_ROOT) ?: Pointer(hwnd), null, null, REDRAW_ALL)
        repaint()
    }

    private val resizeWatchers = mutableMapOf<ComposeWindow, Pair<ComponentAdapter, List<Component>>>()

    private fun ComposeWindow.watchResize(on: Boolean) {
        resizeWatchers.remove(this)?.let { (watcher, parts) -> parts.forEach { it.removeComponentListener(watcher) } }
        if (!on) return
        val watcher = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = EventQueue.invokeLater { wipeSurfaces() }
        }
        val parts = listOf<Component>(this) + descendants().filterIsInstance<Canvas>()
        parts.forEach { it.addComponentListener(watcher) }
        resizeWatchers[this] = watcher to parts
    }

    // stale GDI pixels left at an older window size show through the backdrop as a lighter veil
    private fun ComposeWindow.wipeSurfaces() {
        val canvases = runCatching { descendants().filterIsInstance<Canvas>().mapNotNull { Native.getComponentPointer(it) } }.getOrDefault(emptyList())
        (listOf(Pointer(windowHandle)) + canvases).forEach { hwnd ->
            runCatching {
                val dc = User.api.GetDC(hwnd) ?: return@runCatching
                val area = Rect()
                if (User.api.GetClientRect(hwnd, area)) User.api.FillRect(dc, area, Gdi.api.GetStockObject(BLACK_BRUSH))
                User.api.ReleaseDC(hwnd, dc)
            }
        }
        find(SkiaLayer::class.java)?.needRedraw()
    }

    private fun Component.descendants(): List<Component> =
        listOf(this) + ((this as? Container)?.components?.flatMap { it.descendants() } ?: emptyList())

    private fun windowsBuild(): Int {
        val info = OsVersion()
        check(Nt.api.RtlGetVersion(info) == 0)
        return info.buildNumber
    }

    private fun <T> Container.find(type: Class<T>): T? {
        components.forEach { child ->
            if (type.isInstance(child)) return type.cast(child)
            if (child is Container) child.find(type)?.let { return it }
        }
        return null
    }

    @Structure.FieldOrder("left", "right", "top", "bottom")
    class Margins(@JvmField var left: Int = 0, @JvmField var right: Int = 0, @JvmField var top: Int = 0, @JvmField var bottom: Int = 0) : Structure() {
        constructor(all: Int) : this(all, all, all, all)
    }

    @Structure.FieldOrder("left", "top", "right", "bottom")
    class Rect : Structure() {
        @JvmField var left: Int = 0
        @JvmField var top: Int = 0
        @JvmField var right: Int = 0
        @JvmField var bottom: Int = 0
    }

    @Structure.FieldOrder("size", "majorVersion", "minorVersion", "buildNumber", "platformId", "servicePack")
    class OsVersion : Structure() {
        @JvmField var size: Int = 276
        @JvmField var majorVersion: Int = 0
        @JvmField var minorVersion: Int = 0
        @JvmField var buildNumber: Int = 0
        @JvmField var platformId: Int = 0
        @JvmField var servicePack: CharArray = CharArray(128)
    }

    @Suppress("FunctionName")
    private interface DwmApi : Library {
        fun DwmExtendFrameIntoClientArea(hwnd: Pointer, margins: Margins): Int
    }

    @Suppress("FunctionName")
    private interface UserApi : Library {
        fun GetAncestor(hwnd: Pointer, flags: Int): Pointer?
        fun RedrawWindow(hwnd: Pointer, rect: Pointer?, region: Pointer?, flags: Int): Boolean
        fun GetDC(hwnd: Pointer): Pointer?
        fun ReleaseDC(hwnd: Pointer, dc: Pointer): Int
        fun GetClientRect(hwnd: Pointer, rect: Rect): Boolean
        fun FillRect(dc: Pointer, rect: Rect, brush: Pointer?): Int
        fun SetWindowLongPtrW(hwnd: HWND, index: Int, proc: WinUser.WindowProc): Pointer?
    }

    @Suppress("FunctionName")
    private interface GdiApi : Library {
        fun GetStockObject(index: Int): Pointer?
    }

    @Suppress("FunctionName")
    private interface NtApi : Library {
        fun RtlGetVersion(info: OsVersion): Int
    }

    private object Dwm {
        val api: DwmApi = Native.load("dwmapi", DwmApi::class.java)
    }

    private object User {
        val api: UserApi = Native.load("user32", UserApi::class.java)
    }

    private object Gdi {
        val api: GdiApi = Native.load("gdi32", GdiApi::class.java)
    }

    private object Nt {
        val api: NtApi = Native.load("ntdll", NtApi::class.java)
    }
}
