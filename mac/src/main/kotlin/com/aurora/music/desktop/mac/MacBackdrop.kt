package com.aurora.music.desktop.mac

import androidx.compose.ui.awt.ComposeWindow
import com.aurora.music.util.AppLog
import com.sun.jna.Pointer
import org.jetbrains.skiko.SkiaLayer
import java.awt.Color
import java.awt.Component
import java.awt.Container
import javax.swing.JComponent

/**
 * The translucent window on macOS: a behind-window blur sits under the window's content and compose draws over it
 * with a transparent surface. Liquid Glass views only refract content inside their own window, so they can't do this.
 */
object MacBackdrop {
    private const val TAG = "MacBackdrop"
    private const val BEHIND_WINDOW = 0L
    // measured over a blue window: hud lets the most colour through, popover is milkier; under-window-background is solid grey
    private const val HUD_WINDOW = 13L
    private const val POPOVER = 6L
    private const val ACTIVE = 1L
    private const val BELOW = -1L
    private const val SIZE_WITH_WINDOW = 2L or 16L

    private val effects = java.util.WeakHashMap<ComposeWindow, Pointer>()
    private val originals = java.util.WeakHashMap<Component, Pair<Boolean?, Color?>>()
    private val clear = Color(0, 0, 0, 0)

    fun prepare(window: ComposeWindow) {
        runCatching { window.find(SkiaLayer::class.java)?.transparency = true }
    }

    /** Must run on the AWT event thread; returns whether the window is now translucent. */
    fun apply(window: ComposeWindow, enabled: Boolean, frosted: Boolean): Boolean {
        val handle = window.windowHandle
        if (handle == 0L) return false
        return runCatching {
            val layer = window.find(SkiaLayer::class.java) ?: return false
            layer.transparency = enabled
            window.clearSwing(enabled)
            // frames with a title bar refuse a translucent background through the public api, so the peer is told directly
            val accessor = Class.forName("sun.awt.AWTAccessor").getMethod("getComponentAccessor").invoke(null)
            val peer = Class.forName("sun.awt.AWTAccessor\$ComponentAccessor").getMethod("getPeer", Component::class.java).invoke(accessor, window)
            // the peer also paints the frame's own background, which a decorated frame won't let go clear the public way
            peer?.javaClass?.getMethod("setBackground", Color::class.java)?.invoke(peer, if (enabled) clear else window.background)
            peer?.javaClass?.getMethod("setOpaque", Boolean::class.javaPrimitiveType)?.invoke(peer, !enabled)
            val width = window.width.toDouble()
            val height = (window.height - window.insets.top).toDouble()
            ObjC.onMain {
                place(Pointer(handle), window, enabled, frosted, width, height)
                AppLog.d(TAG, "backdrop ${if (!enabled) "off" else if (frosted) "frosted" else "clear"}, " +
                    "window opaque=${ObjC.msgLong(Pointer(handle), "isOpaque") and 0xff}")
            }
            layer.needRedraw()
            window.repaint()
            enabled
        }.onFailure { AppLog.w(TAG, "The translucent window could not be set up", it) }.getOrDefault(false)
    }

    private fun place(nsWindow: Pointer, window: ComposeWindow, enabled: Boolean, frosted: Boolean, width: Double, height: Double) {
        synchronized(effects) {
            effects.remove(window)?.let { old ->
                ObjC.msgVoid(old, "removeFromSuperview")
                ObjC.release(old)
            }
        }
        if (!enabled) return
        val content = ObjC.msg(nsWindow, "contentView") ?: return
        val frame = ObjC.msg(content, "superview") ?: return
        val effect = ObjC.msg(ObjC.msg(ObjC.cls("NSVisualEffectView"), "alloc"), "initWithFrame:", ObjC.NSRect.ByValue(0.0, 0.0, width, height)) ?: return
        ObjC.msgVoid(effect, "setAutoresizingMask:", SIZE_WITH_WINDOW)
        ObjC.msgVoid(effect, "setBlendingMode:", BEHIND_WINDOW)
        ObjC.msgVoid(effect, "setMaterial:", if (frosted) POPOVER else HUD_WINDOW)
        ObjC.msgVoid(effect, "setState:", ACTIVE)
        ObjC.msgVoid(frame, "addSubview:positioned:relativeTo:", effect, BELOW, content)
        synchronized(effects) { effects[window] = effect }
    }

    // awt paints every part's background, compose's canvas included, behind compose's surface
    private fun Container.clearSwing(on: Boolean) {
        descendants().filter { it !== this }.forEach { part ->
            if (on) {
                originals.getOrPut(part) { (part as? JComponent)?.isOpaque to part.background }
                (part as? JComponent)?.isOpaque = false
                part.background = clear
            } else originals.remove(part)?.let { (opaque, background) ->
                if (opaque != null) (part as JComponent).isOpaque = opaque
                part.background = background
            }
        }
    }

    private fun Component.descendants(): List<Component> =
        listOf(this) + ((this as? Container)?.components?.flatMap { it.descendants() } ?: emptyList())

    private fun <T> Container.find(type: Class<T>): T? {
        components.forEach { child ->
            if (type.isInstance(child)) return type.cast(child)
            if (child is Container) child.find(type)?.let { return it }
        }
        return null
    }
}
