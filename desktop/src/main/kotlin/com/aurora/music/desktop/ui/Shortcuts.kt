package com.aurora.music.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.DesktopBackGestureDispatcher
import androidx.compose.ui.backhandler.LocalBackGestureDispatcher
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import java.awt.Component
import java.awt.event.KeyEvent as AwtKeyEvent

enum class Shortcut { PLAY_PAUSE, PREVIOUS, NEXT, SEARCH, LIKE, QUEUE, FULLSCREEN, BACK }

fun KeyEvent.shortcut(): Shortcut? {
    if (isMetaPressed) return null
    // text fields consume typed characters so an unconsumed typed space means nothing editable has focus
    if (type == KeyEventType.Unknown) return Shortcut.PLAY_PAUSE.takeIf { utf16CodePoint == ' '.code && !isCtrlPressed && !isAltPressed }
    if (type != KeyEventType.KeyDown) return null
    return when {
        key == Key.F11 && !isCtrlPressed && !isAltPressed && !isShiftPressed -> Shortcut.FULLSCREEN
        key == Key.DirectionLeft && isAltPressed && !isCtrlPressed && !isShiftPressed -> Shortcut.BACK
        !isCtrlPressed || isAltPressed || isShiftPressed -> null
        else -> when (key) {
            Key.DirectionLeft -> Shortcut.PREVIOUS
            Key.DirectionRight -> Shortcut.NEXT
            Key.F -> Shortcut.SEARCH
            Key.L -> Shortcut.LIKE
            Key.Q -> Shortcut.QUEUE
            else -> null
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun rememberBackDispatch(): () -> Boolean {
    val dispatcher = LocalBackGestureDispatcher.current as? DesktopBackGestureDispatcher
    return remember(dispatcher) { { dispatcher?.onKeyEvent(escape()) == true } }
}

private val keySource = object : Component() {}

private fun escape() = KeyEvent(
    AwtKeyEvent(keySource, AwtKeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, AwtKeyEvent.VK_ESCAPE, AwtKeyEvent.CHAR_UNDEFINED, AwtKeyEvent.KEY_LOCATION_STANDARD),
)
