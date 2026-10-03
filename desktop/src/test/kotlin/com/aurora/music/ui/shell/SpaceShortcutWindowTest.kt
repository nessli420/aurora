package com.aurora.music.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.aurora.music.desktop.ui.Shortcut
import com.aurora.music.desktop.ui.claimsSpace
import com.aurora.music.desktop.ui.shortcut
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.util.Collections
import javax.swing.SwingUtilities

@OptIn(ExperimentalComposeUiApi::class)
class SpaceShortcutWindowTest {
    private class Probe(val focusText: Boolean) {
        val shortcuts: MutableList<Shortcut> = Collections.synchronizedList(mutableListOf())
        val clicks: MutableList<Unit> = Collections.synchronizedList(mutableListOf())
        @Volatile var text = ""
        lateinit var window: ComposeWindow

        val surface: Component get() = descendants(window).last { it.isFocusable }

        private fun descendants(c: Component): List<Component> =
            listOf(c) + ((c as? Container)?.components?.flatMap(::descendants) ?: emptyList())
    }

    private fun withWindow(focusText: Boolean, block: Probe.() -> Unit) {
        assumeFalse(GraphicsEnvironment.isHeadless())
        val probe = Probe(focusText)
        SwingUtilities.invokeAndWait {
            probe.window = ComposeWindow().apply {
                setContent(
                    onPreviewKeyEvent = { it.claimsSpace() },
                    onKeyEvent = { event -> event.shortcut()?.let(probe.shortcuts::add) != null },
                ) {
                    val focus = remember { FocusRequester() }
                    var value by remember { mutableStateOf("") }
                    Column {
                        Box(Modifier.size(80.dp).clickable { probe.clicks += Unit })
                        BasicTextField(value, { value = it; probe.text = it }, Modifier.size(200.dp, 40.dp).focusRequester(focus))
                    }
                    LaunchedEffect(Unit) { if (probe.focusText) focus.requestFocus() }
                }
                size = Dimension(320, 240)
                isVisible = true
            }
        }
        try {
            settle()
            probe.block()
        } finally {
            SwingUtilities.invokeAndWait { probe.window.dispose() }
        }
    }

    private fun settle() = Thread.sleep(800)

    private fun Probe.edt(block: Component.() -> Unit) {
        SwingUtilities.invokeAndWait { surface.block() }
        settle()
    }

    private fun Component.clickAt(x: Int, y: Int) {
        val now = System.currentTimeMillis()
        listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED).forEach { id ->
            val mask = if (id == MouseEvent.MOUSE_PRESSED) MouseEvent.BUTTON1_DOWN_MASK else 0
            dispatchEvent(MouseEvent(this, id, now, mask, x, y, 1, false, MouseEvent.BUTTON1))
        }
    }

    private fun Component.pressSpace() {
        val now = System.currentTimeMillis()
        dispatchEvent(KeyEvent(this, KeyEvent.KEY_PRESSED, now, 0, KeyEvent.VK_SPACE, ' '))
        dispatchEvent(KeyEvent(this, KeyEvent.KEY_TYPED, now, 0, KeyEvent.VK_UNDEFINED, ' '))
        dispatchEvent(KeyEvent(this, KeyEvent.KEY_RELEASED, now, 0, KeyEvent.VK_SPACE, ' '))
    }

    @Test fun spaceTogglesPlaybackOnceAfterAButtonWasClicked() = withWindow(focusText = false) {
        edt { clickAt(30, 30) }
        assertEquals(1, clicks.size)
        edt { pressSpace() }
        assertEquals(listOf(Shortcut.PLAY_PAUSE), shortcuts.toList())
        assertEquals(1, clicks.size)
    }

    @Test fun spaceTypesIntoAFocusedTextField() = withWindow(focusText = true) {
        edt { pressSpace() }
        assertEquals(" ", text)
        assertEquals(emptyList<Shortcut>(), shortcuts.toList())
        assertEquals(0, clicks.size)
    }
}
