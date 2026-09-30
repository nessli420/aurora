package com.aurora.music.desktop.natives

import java.util.concurrent.CopyOnWriteArrayList

object WindowNative {
    private const val DARK_MODE = 20
    private const val BORDER_COLOR = 34
    private const val CAPTION_COLOR = 35
    private const val TEXT_COLOR = 36
    private const val DEFAULT_COLOR = -1

    private val accentListeners = CopyOnWriteArrayList<(Int) -> Unit>()

    init { NativeLoader.load("aurora_native") }

    fun setDarkMode(hwnd: Long, dark: Boolean): Boolean = setAttribute(hwnd, DARK_MODE, if (dark) 1 else 0) == 0

    fun setCaptionColor(hwnd: Long, argb: Int?): Boolean = setAttribute(hwnd, CAPTION_COLOR, colorRef(argb)) == 0

    fun setTextColor(hwnd: Long, argb: Int?): Boolean = setAttribute(hwnd, TEXT_COLOR, colorRef(argb)) == 0

    fun setBorderColor(hwnd: Long, argb: Int?): Boolean = setAttribute(hwnd, BORDER_COLOR, colorRef(argb)) == 0

    fun accentColor(): Int? = accent().takeIf { it != 0 }

    fun addAccentListener(listener: (Int) -> Unit): AutoCloseable {
        synchronized(accentListeners) {
            if (accentListeners.isEmpty()) watchAccent { argb -> accentListeners.forEach { it(argb) } }
            accentListeners += listener
        }
        return AutoCloseable {
            synchronized(accentListeners) {
                if (accentListeners.remove(listener) && accentListeners.isEmpty()) watchAccent(null)
            }
        }
    }

    private fun colorRef(argb: Int?): Int =
        if (argb == null) DEFAULT_COLOR else (argb shr 16 and 0xFF) or (argb and 0xFF00) or (argb and 0xFF shl 16)

    private external fun setAttribute(hwnd: Long, attribute: Int, value: Int): Int
    private external fun accent(): Int
    private external fun watchAccent(listener: AccentListener?): Int

    private fun interface AccentListener {
        fun onAccent(argb: Int)
    }
}
