package com.aurora.music.desktop.web

import com.aurora.music.desktop.platform.HostPlatform
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.BorderLayout
import java.awt.EventQueue
import java.awt.event.KeyEvent
import java.io.File
import java.util.concurrent.TimeUnit
import javax.swing.JFrame

// drives a real chromium page on macos: it must paint, take focus and accept typing without jogl.
// it needs the downloaded runtime and the network, so it runs only when AURORA_CHROMIUM_SMOKE names the chromium folder
class MacBrowserSmokeTest {
    @Test fun aPageDrawsAndTakesTyping() = runBlocking<Unit> {
        val dir = System.getenv("AURORA_CHROMIUM_SMOKE")?.let(::File)
        assumeTrue(HostPlatform.isMac && dir != null && dir.isDirectory)
        Chromium.configure(dir!!)
        val page = withTimeout(120_000) { Chromium.open("https://example.com/", hidden = false, origins = setOf("https://example.com")) }
        val frame = JFrame("smoke")
        EventQueue.invokeAndWait {
            frame.contentPane.add(page.browser.uiComponent, BorderLayout.CENTER)
            frame.setSize(800, 600)
            frame.isVisible = true
        }
        try {
            withTimeout(30_000) { while (page.loading.value || page.url.value.isEmpty()) delay(100) }
            val image = withTimeout(20_000) {
                var shot: java.awt.image.BufferedImage? = null
                while (shot == null) {
                    shot = runCatching { page.browser.createScreenshot(false).get(1, TimeUnit.SECONDS) }.getOrNull()
                    if (shot == null) delay(200)
                }
                shot
            }
            val colours = (0 until image.height step 8).flatMap { y -> (0 until image.width step 8).map { x -> image.getRGB(x, y) } }.toSet()
            assertTrue("the page painted ${colours.size} colours", colours.size > 2)

            assertEquals("true", page.eval("""(function(){document.body.innerHTML='<input id=q>';document.getElementById('q').focus();return true;})()"""))
            val view = page.browser.uiComponent
            EventQueue.invokeAndWait { view.requestFocusInWindow() }
            delay(300)
            "aurora".forEach { c ->
                EventQueue.invokeAndWait {
                    val code = KeyEvent.getExtendedKeyCodeForChar(c.code)
                    val now = System.currentTimeMillis()
                    view.dispatchEvent(KeyEvent(view, KeyEvent.KEY_PRESSED, now, 0, code, c))
                    view.dispatchEvent(KeyEvent(view, KeyEvent.KEY_TYPED, now, 0, KeyEvent.VK_UNDEFINED, c))
                    view.dispatchEvent(KeyEvent(view, KeyEvent.KEY_RELEASED, now, 0, code, c))
                }
                delay(40)
            }
            delay(500)
            assertEquals("\"aurora\"", page.eval("document.getElementById('q').value"))

            // a click in the middle of a full-page button must reach the page
            page.eval("""(function(){document.body.style.margin='0';document.body.innerHTML='<button id=b style="position:fixed;left:0;top:0;width:100vw;height:100vh">go</button>';window.clicks=0;document.getElementById('b').onclick=function(){window.clicks++;};return true;})()""")
            delay(300)
            EventQueue.invokeAndWait {
                val x = view.width / 2
                val y = view.height / 2
                val now = System.currentTimeMillis()
                val down = java.awt.event.InputEvent.BUTTON1_DOWN_MASK
                view.dispatchEvent(java.awt.event.MouseEvent(view, java.awt.event.MouseEvent.MOUSE_MOVED, now, 0, x, y, 0, false))
                view.dispatchEvent(java.awt.event.MouseEvent(view, java.awt.event.MouseEvent.MOUSE_PRESSED, now, down, x, y, 1, false, java.awt.event.MouseEvent.BUTTON1))
                view.dispatchEvent(java.awt.event.MouseEvent(view, java.awt.event.MouseEvent.MOUSE_RELEASED, now + 50, 0, x, y, 1, false, java.awt.event.MouseEvent.BUTTON1))
                view.dispatchEvent(java.awt.event.MouseEvent(view, java.awt.event.MouseEvent.MOUSE_CLICKED, now + 50, 0, x, y, 1, false, java.awt.event.MouseEvent.BUTTON1))
            }
            delay(500)
            assertEquals("1", page.eval("window.clicks"))
            javax.imageio.ImageIO.write(page.browser.createScreenshot(false).get(10, TimeUnit.SECONDS), "png", File(System.getProperty("java.io.tmpdir"), "aurora-chromium-smoke.png"))
        } finally {
            EventQueue.invokeAndWait { page.close(); frame.dispose() }
        }
    }
}
