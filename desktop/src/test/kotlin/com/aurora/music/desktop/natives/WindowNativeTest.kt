package com.aurora.music.desktop.natives

import com.aurora.music.desktop.platform.HostPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class WindowNativeTest {
    @Before fun windowsOnly() = assumeTrue(HostPlatform.isWindows)

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
