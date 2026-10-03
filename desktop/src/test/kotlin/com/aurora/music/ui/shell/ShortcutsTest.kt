package com.aurora.music.ui.shell

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import com.aurora.music.desktop.ui.Shortcut
import com.aurora.music.desktop.ui.claimsSpace
import com.aurora.music.desktop.ui.shortcut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(InternalComposeUiApi::class)
class ShortcutsTest {
    private fun down(key: Key, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false) =
        KeyEvent(key, KeyEventType.KeyDown, isCtrlPressed = ctrl, isAltPressed = alt, isShiftPressed = shift)

    private fun typed(char: Char, ctrl: Boolean = false) =
        KeyEvent(Key.Unknown, KeyEventType.Unknown, codePoint = char.code, isCtrlPressed = ctrl)

    @Test fun mapsTheDesktopShortcuts() {
        assertEquals(Shortcut.PLAY_PAUSE, typed(' ').shortcut())
        assertEquals(Shortcut.PREVIOUS, down(Key.DirectionLeft, ctrl = true).shortcut())
        assertEquals(Shortcut.NEXT, down(Key.DirectionRight, ctrl = true).shortcut())
        assertEquals(Shortcut.SEARCH, down(Key.F, ctrl = true).shortcut())
        assertEquals(Shortcut.LIKE, down(Key.L, ctrl = true).shortcut())
        assertEquals(Shortcut.QUEUE, down(Key.Q, ctrl = true).shortcut())
        assertEquals(Shortcut.FULLSCREEN, down(Key.F11).shortcut())
        assertEquals(Shortcut.BACK, down(Key.Escape).shortcut())
        assertEquals(Shortcut.BACK, down(Key.DirectionLeft, alt = true).shortcut())
    }

    @Test fun ignoresEverythingElse() {
        assertNull(down(Key.Spacebar).shortcut())
        assertNull(typed('a').shortcut())
        assertNull(typed(' ', ctrl = true).shortcut())
        assertNull(down(Key.DirectionLeft).shortcut())
        assertNull(down(Key.F).shortcut())
        assertNull(down(Key.F, ctrl = true, shift = true).shortcut())
        assertNull(down(Key.DirectionLeft, ctrl = true, alt = true).shortcut())
        assertNull(KeyEvent(Key.F, KeyEventType.KeyUp, isCtrlPressed = true).shortcut())
        assertNull(KeyEvent(Key.Escape, KeyEventType.KeyUp).shortcut())
    }

    @Test fun theWindowKeepsSpaceKeyPressesFromFocusedControls() {
        assertTrue(down(Key.Spacebar).claimsSpace())
        assertTrue(KeyEvent(Key.Spacebar, KeyEventType.KeyUp).claimsSpace())
        assertTrue(down(Key.Spacebar, shift = true).claimsSpace())
        assertFalse(typed(' ').claimsSpace())
        assertFalse(down(Key.Spacebar, ctrl = true).claimsSpace())
        assertFalse(down(Key.Spacebar, alt = true).claimsSpace())
        assertFalse(down(Key.Enter).claimsSpace())
    }
}
