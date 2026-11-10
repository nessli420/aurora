package com.aurora.music.desktop.mac

import com.aurora.music.desktop.platform.HostPlatform
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MacQuitMenuTest {
    @Test fun theQuitMenuItemCallsAuroraDirectly() {
        assumeTrue(HostPlatform.isMac)
        java.awt.EventQueue.invokeAndWait { javax.swing.JFrame().apply { setSize(200, 200); isVisible = true; dispose() } }
        val quit = CountDownLatch(1)
        MacQuitMenu.install { quit.countDown() }
        ObjC.onMain {
            val app = ObjC.msg(ObjC.cls("NSApplication"), "sharedApplication")!!
            val appMenu = ObjC.msg(ObjC.msg(ObjC.msg(app, "mainMenu"), "itemAtIndex:", 0L), "submenu")!!
            ObjC.msgVoid(appMenu, "performActionForItemAtIndex:", MacQuitMenu.quitItemIndex()!!)
        }
        assertTrue("the quit item did not reach aurora", quit.await(5, TimeUnit.SECONDS))
    }
}
