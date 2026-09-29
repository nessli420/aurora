package com.aurora.music.desktop.natives

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class WindowNativeTest {
    @Test
    fun accentColorIsOpaque() {
        val accent = WindowNative.accentColor()
        assertNotNull(accent)
        assertEquals(0xFF, accent!! ushr 24)
    }

    @Test
    fun accentListenersRegisterAndClose() {
        val first = WindowNative.addAccentListener {}
        val second = WindowNative.addAccentListener {}
        first.close()
        second.close()
        WindowNative.addAccentListener {}.close()
        assertNotNull(WindowNative.accentColor())
    }
}
