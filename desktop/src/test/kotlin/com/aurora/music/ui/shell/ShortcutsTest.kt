package com.aurora.music.ui.shell

import androidx.compose.ui.input.key.KeyEvent
import com.aurora.music.desktop.ui.Shortcut
import com.aurora.music.desktop.ui.shortcut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.awt.Component
import java.awt.event.InputEvent
import java.awt.event.KeyEvent as AwtKeyEvent

class ShortcutsTest {
    private val source = object : Component() {}

    private fun pressed(code: Int, modifiers: Int = 0) =
        KeyEvent(AwtKeyEvent(source, AwtKeyEvent.KEY_PRESSED, 0, modifiers, code, AwtKeyEvent.CHAR_UNDEFINED, AwtKeyEvent.KEY_LOCATION_STANDARD))

    private fun released(code: Int, modifiers: Int = 0) =
        KeyEvent(AwtKeyEvent(source, AwtKeyEvent.KEY_RELEASED, 0, modifiers, code, AwtKeyEvent.CHAR_UNDEFINED, AwtKeyEvent.KEY_LOCATION_STANDARD))

    private fun typed(char: Char, modifiers: Int = 0) =
        KeyEvent(AwtKeyEvent(source, AwtKeyEvent.KEY_TYPED, 0, modifiers, AwtKeyEvent.VK_UNDEFINED, char, AwtKeyEvent.KEY_LOCATION_UNKNOWN))

    @Test fun mapsTheDesktopShortcuts() {
        val ctrl = InputEvent.CTRL_DOWN_MASK
        assertEquals(Shortcut.PLAY_PAUSE, typed(' ').shortcut())
        assertEquals(Shortcut.PREVIOUS, pressed(AwtKeyEvent.VK_LEFT, ctrl).shortcut())
        assertEquals(Shortcut.NEXT, pressed(AwtKeyEvent.VK_RIGHT, ctrl).shortcut())
        assertEquals(Shortcut.SEARCH, pressed(AwtKeyEvent.VK_F, ctrl).shortcut())
        assertEquals(Shortcut.LIKE, pressed(AwtKeyEvent.VK_L, ctrl).shortcut())
        assertEquals(Shortcut.QUEUE, pressed(AwtKeyEvent.VK_Q, ctrl).shortcut())
        assertEquals(Shortcut.FULLSCREEN, pressed(AwtKeyEvent.VK_F11).shortcut())
        assertEquals(Shortcut.BACK, pressed(AwtKeyEvent.VK_ESCAPE).shortcut())
        assertEquals(Shortcut.BACK, pressed(AwtKeyEvent.VK_LEFT, InputEvent.ALT_DOWN_MASK).shortcut())
    }

    @Test fun ignoresEverythingElse() {
        val ctrl = InputEvent.CTRL_DOWN_MASK
        assertNull(pressed(AwtKeyEvent.VK_SPACE).shortcut())
        assertNull(typed('a').shortcut())
        assertNull(typed(' ', ctrl).shortcut())
        assertNull(pressed(AwtKeyEvent.VK_LEFT).shortcut())
        assertNull(pressed(AwtKeyEvent.VK_F).shortcut())
        assertNull(pressed(AwtKeyEvent.VK_F, ctrl or InputEvent.SHIFT_DOWN_MASK).shortcut())
        assertNull(pressed(AwtKeyEvent.VK_LEFT, ctrl or InputEvent.ALT_DOWN_MASK).shortcut())
        assertNull(released(AwtKeyEvent.VK_F, ctrl).shortcut())
        assertNull(released(AwtKeyEvent.VK_ESCAPE).shortcut())
    }
}
