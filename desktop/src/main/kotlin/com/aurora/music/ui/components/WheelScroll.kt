package com.aurora.music.ui.components

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.dp

private val WheelStep = 64.dp

fun Modifier.wheelScrollsHorizontally(state: LazyListState): Modifier = wheelScrollsHorizontally(state as ScrollableState)

@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.wheelScrollsHorizontally(state: ScrollableState): Modifier = onPointerEvent(PointerEventType.Scroll) { event ->
    val change = event.changes.firstOrNull() ?: return@onPointerEvent
    val delta = change.scrollDelta
    if (change.isConsumed || delta.x != 0f || delta.y == 0f) return@onPointerEvent
    if (state.dispatchRawDelta(delta.y * WheelStep.toPx()) != 0f) change.consume()
}
