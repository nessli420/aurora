package com.aurora.music.desktop.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackGestureDispatcher
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
import com.aurora.music.desktop.platform.HostPlatform

enum class Shortcut {
    PLAY_PAUSE, PREVIOUS, NEXT, SEEK_BACK, SEEK_FORWARD, VOLUME_UP, VOLUME_DOWN, MUTE, SHUFFLE, REPEAT,
    LIKE, QUEUE, SEARCH, SETTINGS, FULLSCREEN, BACK, SHORTCUTS,
}

// macos binds the same shortcuts to command, and keeps command+q for quit
private val mac = HostPlatform.isMac

// the keys shown in the shortcut list, laid out the way spotify's desktop app binds them
val ShortcutKeys: Map<Shortcut, List<String>> = if (mac) linkedMapOf(
    Shortcut.PLAY_PAUSE to listOf("Space"),
    Shortcut.NEXT to listOf("⌘ →"),
    Shortcut.PREVIOUS to listOf("⌘ ←"),
    Shortcut.SEEK_FORWARD to listOf("⇧ →"),
    Shortcut.SEEK_BACK to listOf("⇧ ←"),
    Shortcut.VOLUME_UP to listOf("⌘ ↑"),
    Shortcut.VOLUME_DOWN to listOf("⌘ ↓"),
    Shortcut.MUTE to listOf("⌘ ⇧ ↓"),
    Shortcut.SHUFFLE to listOf("⌘ S"),
    Shortcut.REPEAT to listOf("⌘ R"),
    Shortcut.LIKE to listOf("⌥ ⇧ B", "⌘ L"),
    Shortcut.QUEUE to listOf("⌥ ⇧ Q"),
    Shortcut.SEARCH to listOf("⌘ K", "⌘ F"),
    Shortcut.SETTINGS to listOf("⌘ ,"),
    Shortcut.FULLSCREEN to listOf("⌃ ⌘ F", "F11"),
    Shortcut.BACK to listOf("⌘ [", "Esc"),
    Shortcut.SHORTCUTS to listOf("⌘ /"),
) else linkedMapOf(
    Shortcut.PLAY_PAUSE to listOf("Space"),
    Shortcut.NEXT to listOf("Ctrl + →"),
    Shortcut.PREVIOUS to listOf("Ctrl + ←"),
    Shortcut.SEEK_FORWARD to listOf("Shift + →"),
    Shortcut.SEEK_BACK to listOf("Shift + ←"),
    Shortcut.VOLUME_UP to listOf("Ctrl + ↑"),
    Shortcut.VOLUME_DOWN to listOf("Ctrl + ↓"),
    Shortcut.MUTE to listOf("Ctrl + Shift + ↓"),
    Shortcut.SHUFFLE to listOf("Ctrl + S"),
    Shortcut.REPEAT to listOf("Ctrl + R"),
    Shortcut.LIKE to listOf("Alt + Shift + B", "Ctrl + L"),
    Shortcut.QUEUE to listOf("Alt + Shift + Q", "Ctrl + Q"),
    Shortcut.SEARCH to listOf("Ctrl + K", "Ctrl + F"),
    Shortcut.SETTINGS to listOf("Ctrl + ,"),
    Shortcut.FULLSCREEN to listOf("F11"),
    Shortcut.BACK to listOf("Alt + ←", "Esc"),
    Shortcut.SHORTCUTS to listOf("Ctrl + /"),
)

// a focused button would also click on space, so the window keeps the key presses and lets only the typed space through
fun KeyEvent.claimsSpace(): Boolean =
    key == Key.Spacebar && type != KeyEventType.Unknown && !isCtrlPressed && !isAltPressed && !isMetaPressed

fun KeyEvent.shortcut(): Shortcut? = if (mac) macShortcut() else pcShortcut()

private fun KeyEvent.macShortcut(): Shortcut? {
    if (type == KeyEventType.Unknown) return Shortcut.PLAY_PAUSE.takeIf { utf16CodePoint == ' '.code && !isMetaPressed && !isCtrlPressed && !isAltPressed }
    if (type != KeyEventType.KeyDown) return null
    val command = isMetaPressed
    return when {
        key == Key.F && command && isCtrlPressed && !isAltPressed && !isShiftPressed -> Shortcut.FULLSCREEN
        isCtrlPressed -> null
        key == Key.F11 && !command && !isAltPressed && !isShiftPressed -> Shortcut.FULLSCREEN
        key == Key.Escape && !command && !isAltPressed && !isShiftPressed -> Shortcut.BACK
        key == Key.LeftBracket && command && !isAltPressed && !isShiftPressed -> Shortcut.BACK
        isAltPressed && isShiftPressed && !command -> when (key) {
            Key.B -> Shortcut.LIKE
            Key.Q -> Shortcut.QUEUE
            else -> null
        }
        isShiftPressed && !command && !isAltPressed -> when (key) {
            Key.DirectionLeft -> Shortcut.SEEK_BACK
            Key.DirectionRight -> Shortcut.SEEK_FORWARD
            else -> null
        }
        command && isShiftPressed && !isAltPressed -> when (key) {
            Key.DirectionDown -> Shortcut.MUTE
            Key.Slash -> Shortcut.SHORTCUTS
            else -> null
        }
        !command || isAltPressed -> null
        else -> when (key) {
            Key.DirectionLeft -> Shortcut.PREVIOUS
            Key.DirectionRight -> Shortcut.NEXT
            Key.DirectionUp -> Shortcut.VOLUME_UP
            Key.DirectionDown -> Shortcut.VOLUME_DOWN
            Key.S -> Shortcut.SHUFFLE
            Key.R -> Shortcut.REPEAT
            Key.F, Key.K -> Shortcut.SEARCH
            Key.L -> Shortcut.LIKE
            Key.Comma -> Shortcut.SETTINGS
            Key.Slash -> Shortcut.SHORTCUTS
            else -> null
        }
    }
}

private fun KeyEvent.pcShortcut(): Shortcut? {
    if (isMetaPressed) return null
    // text fields consume typed characters so an unconsumed typed space means nothing editable has focus
    if (type == KeyEventType.Unknown) return Shortcut.PLAY_PAUSE.takeIf { utf16CodePoint == ' '.code && !isCtrlPressed && !isAltPressed }
    if (type != KeyEventType.KeyDown) return null
    return when {
        key == Key.F11 && !isCtrlPressed && !isAltPressed && !isShiftPressed -> Shortcut.FULLSCREEN
        key == Key.Escape && !isCtrlPressed && !isAltPressed && !isShiftPressed -> Shortcut.BACK
        key == Key.DirectionLeft && isAltPressed && !isCtrlPressed && !isShiftPressed -> Shortcut.BACK
        isAltPressed && isShiftPressed && !isCtrlPressed -> when (key) {
            Key.B -> Shortcut.LIKE
            Key.Q -> Shortcut.QUEUE
            else -> null
        }
        isShiftPressed && !isCtrlPressed && !isAltPressed -> when (key) {
            Key.DirectionLeft -> Shortcut.SEEK_BACK
            Key.DirectionRight -> Shortcut.SEEK_FORWARD
            else -> null
        }
        // ctrl+/ comes as ctrl+shift+/ on layouts where the question mark shares the key
        isCtrlPressed && isShiftPressed && !isAltPressed -> when (key) {
            Key.DirectionDown -> Shortcut.MUTE
            Key.Slash -> Shortcut.SHORTCUTS
            else -> null
        }
        !isCtrlPressed || isAltPressed -> null
        else -> when (key) {
            Key.DirectionLeft -> Shortcut.PREVIOUS
            Key.DirectionRight -> Shortcut.NEXT
            Key.DirectionUp -> Shortcut.VOLUME_UP
            Key.DirectionDown -> Shortcut.VOLUME_DOWN
            Key.S -> Shortcut.SHUFFLE
            Key.R -> Shortcut.REPEAT
            Key.F, Key.K -> Shortcut.SEARCH
            Key.L -> Shortcut.LIKE
            Key.Q -> Shortcut.QUEUE
            Key.Comma -> Shortcut.SETTINGS
            Key.Slash -> Shortcut.SHORTCUTS
            else -> null
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
class BackDispatcher : BackGestureDispatcher() {
    fun back(): Boolean {
        val listener = activeListener ?: return false
        listener.onStarted()
        listener.onCompleted()
        return true
    }
}
