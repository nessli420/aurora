package com.aurora.music.util

import kotlin.math.roundToInt

// the slot a queue row dragged by offset px lands in, kept within first..last
fun queueDropTarget(from: Int, offset: Float, rowPx: Float, first: Int, last: Int): Int =
    (from + (offset / rowPx).roundToInt()).coerceIn(first, last)

// px to scroll this frame while the finger sits within edge px of the list's top or bottom, faster deeper in
fun edgeScrollStep(y: Float, height: Float, edge: Float): Float {
    val depth = when {
        y < edge -> y - edge
        y > height - edge -> y - (height - edge)
        else -> return 0f
    }
    return depth.coerceIn(-edge, edge) / 8f
}
