package com.aurora.music.desktop.mac

import com.aurora.music.desktop.platform.HostPlatform
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MacDockTest {
    private fun policy(): Long {
        var value = -1L
        val done = CountDownLatch(1)
        ObjC.onMain {
            value = ObjC.msgLong(ObjC.msg(ObjC.cls("NSApplication"), "sharedApplication")!!, "activationPolicy")
            done.countDown()
        }
        check(done.await(5, TimeUnit.SECONDS))
        return value
    }

    @Test fun leavesAndReturnsToTheDock() {
        assumeTrue(HostPlatform.isMac)
        java.awt.Toolkit.getDefaultToolkit()
        MacDock.setVisible(false)
        assertEquals(1L, policy()) // accessory: no dock icon
        MacDock.setVisible(true)
        assertEquals(0L, policy()) // regular
    }
}
