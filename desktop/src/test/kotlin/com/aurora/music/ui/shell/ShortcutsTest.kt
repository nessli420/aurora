package com.aurora.music.ui.shell

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import com.aurora.music.desktop.platform.HostPlatform
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
    // ctrl stands for the platform's command key: control on windows and linux, command on macos
    private val mac = HostPlatform.isMac

    private fun down(key: Key, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false, control: Boolean = false) =
        KeyEvent(key, KeyEventType.KeyDown, isCtrlPressed = (ctrl && !mac) || control, isMetaPressed = ctrl && mac, isAltPressed = alt, isShiftPressed = shift)

    private fun typed(char: Char, ctrl: Boolean = false) =
        KeyEvent(Key.Unknown, KeyEventType.Unknown, codePoint = char.code, isCtrlPressed = ctrl && !mac, isMetaPressed = ctrl && mac)

    @Test fun mapsTheDesktopShortcuts() {
        assertEquals(Shortcut.PLAY_PAUSE, typed(' ').shortcut())
        assertEquals(Shortcut.PREVIOUS, down(Key.DirectionLeft, ctrl = true).shortcut())
        assertEquals(Shortcut.NEXT, down(Key.DirectionRight, ctrl = true).shortcut())
        assertEquals(Shortcut.SEARCH, down(Key.F, ctrl = true).shortcut())
        assertEquals(Shortcut.LIKE, down(Key.L, ctrl = true).shortcut())
        // command+q stays the mac's quit
        assertEquals(Shortcut.QUEUE.takeIf { !mac }, down(Key.Q, ctrl = true).shortcut())
        assertEquals(Shortcut.FULLSCREEN, down(Key.F11).shortcut())
        assertEquals(Shortcut.BACK, down(Key.Escape).shortcut())
        if (!mac) assertEquals(Shortcut.BACK, down(Key.DirectionLeft, alt = true).shortcut())
    }

    @Test fun mapsTheMacShortcuts() {
        if (!mac) return
        assertEquals(Shortcut.FULLSCREEN, down(Key.F, ctrl = true, control = true).shortcut())
        assertEquals(Shortcut.BACK, down(Key.LeftBracket, ctrl = true).shortcut())
        assertNull(down(Key.DirectionLeft, control = true).shortcut())
        assertNull(down(Key.Q, ctrl = true).shortcut())
    }

    @Test fun mapsSpotifysShortcuts() {
        assertEquals(Shortcut.SEEK_BACK, down(Key.DirectionLeft, shift = true).shortcut())
        assertEquals(Shortcut.SEEK_FORWARD, down(Key.DirectionRight, shift = true).shortcut())
        assertEquals(Shortcut.VOLUME_UP, down(Key.DirectionUp, ctrl = true).shortcut())
        assertEquals(Shortcut.VOLUME_DOWN, down(Key.DirectionDown, ctrl = true).shortcut())
        assertEquals(Shortcut.MUTE, down(Key.DirectionDown, ctrl = true, shift = true).shortcut())
        assertEquals(Shortcut.SHUFFLE, down(Key.S, ctrl = true).shortcut())
        assertEquals(Shortcut.REPEAT, down(Key.R, ctrl = true).shortcut())
        assertEquals(Shortcut.SEARCH, down(Key.K, ctrl = true).shortcut())
        assertEquals(Shortcut.LIKE, down(Key.B, alt = true, shift = true).shortcut())
        assertEquals(Shortcut.QUEUE, down(Key.Q, alt = true, shift = true).shortcut())
        assertEquals(Shortcut.SETTINGS, down(Key.Comma, ctrl = true).shortcut())
        assertEquals(Shortcut.SHORTCUTS, down(Key.Slash, ctrl = true).shortcut())
        assertEquals(Shortcut.SHORTCUTS, down(Key.Slash, ctrl = true, shift = true).shortcut())
    }

    @Test fun everyShortcutIsListed() {
        assertEquals(Shortcut.entries.toSet(), com.aurora.music.desktop.ui.ShortcutKeys.keys)
    }

    @Test fun ignoresEverythingElse() {
        assertNull(down(Key.Spacebar).shortcut())
        assertNull(typed('a').shortcut())
        assertNull(typed(' ', ctrl = true).shortcut())
        assertNull(down(Key.DirectionLeft).shortcut())
        assertNull(down(Key.F).shortcut())
        assertNull(down(Key.F, ctrl = true, shift = true).shortcut())
        assertNull(down(Key.DirectionLeft, ctrl = true, alt = true).shortcut())
        assertNull(down(Key.DirectionUp, shift = true).shortcut())
        assertNull(down(Key.S).shortcut())
        assertNull(down(Key.B, alt = true).shortcut())
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
