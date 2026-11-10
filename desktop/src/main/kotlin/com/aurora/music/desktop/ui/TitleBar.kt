package com.aurora.music.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import com.aurora.music.R
import com.aurora.music.desktop.linux.X11MoveResize
import com.aurora.music.desktop.platform.CaptionFrame
import com.aurora.music.desktop.resources.AuroraLogo
import com.aurora.music.localization.appString
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Frame
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window
import kotlin.math.roundToInt

private val BarHeight = 36.dp
private val ButtonWidth = 46.dp
private val CloseRed = Color(0xFFC42B1C)

// macos: the native title bar is 28pt tall and its traffic lights take the first 78pt
internal val MacBarHeight = 28.dp
internal val MacLightsWidth = 78.dp

private enum class Glyph { MINIMIZE, MAXIMIZE, RESTORE, CLOSE }

// caption: windows keeps its native frame and handles the drag area and maximize button itself; null means compose moves the window
@Composable
fun TitleBar(window: ComposeWindow, state: WindowState, caption: CaptionFrame?, onClose: () -> Unit) {
    val focused = LocalWindowInfo.current.isWindowFocused
    val ink = MaterialTheme.colorScheme.onBackground.copy(alpha = if (focused) 0.9f else 0.45f)
    val maximized = state.placement == WindowPlacement.Maximized
    val toggle = { state.placement = if (maximized) WindowPlacement.Floating else WindowPlacement.Maximized }
    val button = with(LocalDensity.current) { ButtonWidth.roundToPx() }
    if (caption != null) DisposableEffect(caption) { onDispose { caption.bar = CaptionFrame.Bar() } }
    Row(
        Modifier.fillMaxWidth().height(BarHeight).then(
            if (caption == null) Modifier else Modifier.onGloballyPositioned { bar ->
                val right = bar.boundsInWindow().right.roundToInt()
                caption.bar = CaptionFrame.Bar(bar.size.height, right - 3 * button until right, right - 2 * button until right - button)
            },
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).fillMaxHeight().then(
                if (caption != null) Modifier
                else Modifier.windowDrag(window, X11MoveResize.MOVE).pointerInput(Unit) { detectTapGestures(onDoubleTap = { toggle() }) },
            ).padding(start = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(rememberVectorPainter(AuroraLogo), null, tint = MaterialTheme.colorScheme.primary.copy(alpha = if (focused) 1f else 0.5f), modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(9.dp))
            Text("Aurora", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = ink)
        }
        CaptionButton(Glyph.MINIMIZE, appString(R.string.window_minimize), ink) { state.isMinimized = true }
        CaptionButton(
            if (maximized) Glyph.RESTORE else Glyph.MAXIMIZE,
            appString(if (maximized) R.string.window_restore else R.string.window_maximize), ink,
            nativeHover = caption?.maximizeHovered == true, nativePress = caption?.maximizePressed == true, onClick = toggle,
        )
        CaptionButton(Glyph.CLOSE, appString(R.string.text_close_bbfa77), ink, onClick = onClose)
    }
}

// macos: the system's own traffic lights sit on the left of this bar (MacTitleBar.install), so it only adds the logo and name
@Composable
fun MacTitleBar(window: ComposeWindow, state: WindowState) {
    val toggle = { state.placement = if (state.placement == WindowPlacement.Maximized) WindowPlacement.Floating else WindowPlacement.Maximized }
    // the transparent native bar normally takes the drag and the double click itself; these only act if it lets them through
    MacTitleBarContent(Modifier.windowDrag(window, X11MoveResize.MOVE).pointerInput(Unit) { detectTapGestures(onDoubleTap = { toggle() }) })
}

@Composable
internal fun MacTitleBarContent(modifier: Modifier = Modifier) {
    val focused = LocalWindowInfo.current.isWindowFocused
    Row(modifier.fillMaxWidth().height(MacBarHeight).padding(start = MacLightsWidth), verticalAlignment = Alignment.CenterVertically) {
        Icon(rememberVectorPainter(AuroraLogo), null, tint = MaterialTheme.colorScheme.primary.copy(alpha = if (focused) 1f else 0.5f), modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text("Aurora", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = if (focused) 0.9f else 0.45f))
    }
}

@Composable
private fun CaptionButton(glyph: Glyph, label: String, ink: Color, nativeHover: Boolean = false, nativePress: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val active = hovered || nativeHover
    val down = pressed || nativePress
    val close = glyph == Glyph.CLOSE
    val fill by animateColorAsState(
        when {
            close && active -> CloseRed.copy(alpha = if (down) 0.85f else 1f)
            active -> MaterialTheme.colorScheme.onBackground.copy(alpha = if (down) 0.05f else 0.09f)
            else -> Color.Transparent
        },
        tween(if (active) 90 else 160),
    )
    val tint = if (close && active) Color.White else ink
    Box(
        Modifier.width(ButtonWidth).fillMaxHeight().background(fill)
            .focusProperties { canFocus = false }
            .semantics { contentDescription = label }
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(10.dp)) { drawGlyph(glyph, tint) }
    }
}

private fun DrawScope.drawGlyph(glyph: Glyph, color: Color) {
    val stroke = 1.dp.toPx()
    val s = size.width
    val half = stroke / 2
    when (glyph) {
        Glyph.MINIMIZE -> drawLine(color, Offset(0f, s / 2), Offset(s, s / 2), stroke)
        Glyph.MAXIMIZE -> drawRect(color, Offset(half, half), Size(s - stroke, s - stroke), style = Stroke(stroke))
        Glyph.RESTORE -> {
            val back = s * 0.22f
            drawRect(color, Offset(half, back + half), Size(s - back - stroke, s - back - stroke), style = Stroke(stroke))
            drawLine(color, Offset(back, half), Offset(s - half, half), stroke)
            drawLine(color, Offset(s - half, half), Offset(s - half, s - back), stroke)
        }
        Glyph.CLOSE -> {
            drawLine(color, Offset(0f, 0f), Offset(s, s), stroke)
            drawLine(color, Offset(s, 0f), Offset(0f, s), stroke)
        }
    }
}

// linux: an undecorated window has no frame to grab, so its edges resize it
@Composable
fun WindowResizeEdges(window: ComposeWindow) {
    val edge = 5.dp
    val corner = 10.dp
    Box(Modifier.fillMaxSize()) {
        ResizeEdge(window, X11MoveResize.TOP, Cursor.N_RESIZE_CURSOR, Modifier.align(Alignment.TopCenter).fillMaxWidth().height(edge))
        ResizeEdge(window, X11MoveResize.BOTTOM, Cursor.S_RESIZE_CURSOR, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(edge))
        ResizeEdge(window, X11MoveResize.LEFT, Cursor.W_RESIZE_CURSOR, Modifier.align(Alignment.CenterStart).fillMaxHeight().width(edge))
        ResizeEdge(window, X11MoveResize.RIGHT, Cursor.E_RESIZE_CURSOR, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(edge))
        ResizeEdge(window, X11MoveResize.TOP_LEFT, Cursor.NW_RESIZE_CURSOR, Modifier.align(Alignment.TopStart).size(corner))
        ResizeEdge(window, X11MoveResize.TOP_RIGHT, Cursor.NE_RESIZE_CURSOR, Modifier.align(Alignment.TopEnd).size(corner))
        ResizeEdge(window, X11MoveResize.BOTTOM_LEFT, Cursor.SW_RESIZE_CURSOR, Modifier.align(Alignment.BottomStart).size(corner))
        ResizeEdge(window, X11MoveResize.BOTTOM_RIGHT, Cursor.SE_RESIZE_CURSOR, Modifier.align(Alignment.BottomEnd).size(corner))
    }
}

@Composable
private fun ResizeEdge(window: ComposeWindow, direction: Int, cursor: Int, modifier: Modifier) {
    Box(modifier.pointerHoverIcon(PointerIcon(Cursor(cursor))).windowDrag(window, direction))
}

private fun Modifier.windowDrag(window: Window, direction: Int): Modifier = pointerInput(window, direction) {
    var follow: ((Point) -> Unit)? = null
    detectDragGestures(
        onDragStart = { follow = if (X11MoveResize.begin(window, direction)) null else followPointer(window, direction) },
        onDragEnd = { follow = null },
        onDragCancel = { follow = null },
    ) { change, _ ->
        change.consume()
        follow?.invoke(MouseInfo.getPointerInfo().location)
    }
}

// fallback when the window manager won't take the drag: move or resize from the pointer's travel
private fun followPointer(window: Window, direction: Int): ((Point) -> Unit)? {
    if (window is Frame && window.extendedState and Frame.MAXIMIZED_BOTH != 0) return null
    val start = MouseInfo.getPointerInfo().location
    val from = window.bounds
    val min = window.minimumSize
    return { at -> window.bounds = dragBounds(from, min, direction, at.x - start.x, at.y - start.y) }
}

internal fun dragBounds(from: Rectangle, min: Dimension, direction: Int, dx: Int, dy: Int): Rectangle {
    if (direction == X11MoveResize.MOVE) return Rectangle(from.x + dx, from.y + dy, from.width, from.height)
    val left = if (direction in LEFT_EDGES) minOf(from.x + dx, from.x + from.width - min.width) else from.x
    val top = if (direction in TOP_EDGES) minOf(from.y + dy, from.y + from.height - min.height) else from.y
    val right = if (direction in RIGHT_EDGES) maxOf(from.x + from.width + dx, left + min.width) else from.x + from.width
    val bottom = if (direction in BOTTOM_EDGES) maxOf(from.y + from.height + dy, top + min.height) else from.y + from.height
    return Rectangle(left, top, right - left, bottom - top)
}

private val LEFT_EDGES = setOf(X11MoveResize.TOP_LEFT, X11MoveResize.LEFT, X11MoveResize.BOTTOM_LEFT)
private val TOP_EDGES = setOf(X11MoveResize.TOP_LEFT, X11MoveResize.TOP, X11MoveResize.TOP_RIGHT)
private val RIGHT_EDGES = setOf(X11MoveResize.TOP_RIGHT, X11MoveResize.RIGHT, X11MoveResize.BOTTOM_RIGHT)
private val BOTTOM_EDGES = setOf(X11MoveResize.BOTTOM_LEFT, X11MoveResize.BOTTOM, X11MoveResize.BOTTOM_RIGHT)
