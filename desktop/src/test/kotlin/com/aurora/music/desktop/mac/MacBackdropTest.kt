package com.aurora.music.desktop.mac

import androidx.compose.ui.awt.ComposeWindow
import com.aurora.music.desktop.platform.HostPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.EventQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MacBackdropTest {
    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        val done = CountDownLatch(1)
        ObjC.onMain { result = runCatching(block); done.countDown() }
        check(done.await(5, TimeUnit.SECONDS))
        return result!!.getOrThrow()
    }

    private fun backdropClasses(handle: Long): List<String> = onMain {
        val nsWindow = com.sun.jna.Pointer(handle)
        val frame = ObjC.msg(ObjC.msg(nsWindow, "contentView"), "superview")!!
        val views = ObjC.msg(frame, "subviews")!!
        (0 until ObjC.msgLong(views, "count")).map { i ->
            val view = ObjC.msg(views, "objectAtIndex:", i)!!
            ObjC.msg(ObjC.msg(view, "className"), "UTF8String")!!.getString(0)
        }
    }

    private fun opaque(handle: Long) = onMain { ObjC.msgLong(com.sun.jna.Pointer(handle), "isOpaque") and 0xff }

    @Test fun putsGlassBehindTheWindowAndTakesItAway() {
        assumeTrue(HostPlatform.isMac)
        lateinit var window: ComposeWindow
        EventQueue.invokeAndWait {
            window = ComposeWindow()
            window.setSize(600, 400)
            window.setContent { }
            window.isVisible = true
        }
        try {
            Thread.sleep(800)
            val handle = window.windowHandle
            assertTrue(handle != 0L)
            var applied = false
            EventQueue.invokeAndWait { applied = MacBackdrop.apply(window, enabled = true, frosted = false) }
            Thread.sleep(300)
            assertTrue(applied)
            assertEquals(0L, opaque(handle))
            assertTrue("NSVisualEffectView" in backdropClasses(handle))

            EventQueue.invokeAndWait { MacBackdrop.apply(window, enabled = true, frosted = true) }
            Thread.sleep(300)
            val frosted = backdropClasses(handle)
            assertEquals(1, frosted.count { it == "NSVisualEffectView" })

            EventQueue.invokeAndWait { MacBackdrop.apply(window, enabled = false, frosted = false) }
            Thread.sleep(300)
            assertEquals(1L, opaque(handle))
            assertTrue(backdropClasses(handle).none { it == "NSVisualEffectView" })
        } finally {
            EventQueue.invokeAndWait { window.dispose() }
        }
    }
}
