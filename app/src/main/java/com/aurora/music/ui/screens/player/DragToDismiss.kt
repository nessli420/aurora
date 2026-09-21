package com.aurora.music.ui.screens.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DragToDismiss(
    onDismiss: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val headerHeight = WindowInsets.statusBarsIgnoringVisibility.getTop(density) + with(density) { 88.dp.toPx() }
    val minimumFlingDistance = with(density) { 40.dp.toPx() }
    val flingVelocity = with(density) { 1100.dp.toPx() }
    val reverseVelocity = with(density) { 600.dp.toPx() }
    val dismiss by rememberUpdatedState(onDismiss)
    val scope = rememberCoroutineScope()
    var offset by remember { mutableFloatStateOf(0f) }
    var height by remember { mutableIntStateOf(0) }
    var completed by remember { mutableStateOf(false) }
    var settling by remember { mutableStateOf<Job?>(null) }
    val corners = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

    fun settle(velocity: Float, allowDismiss: Boolean) {
        val close = allowDismiss && height > 0 && (
            (offset > height * .22f && velocity > -reverseVelocity) ||
                (offset > minimumFlingDistance && velocity > flingVelocity)
            )
        settling?.cancel()
        settling = scope.launch {
            Animatable(offset).animateTo(
                targetValue = if (close) height.toFloat() else 0f,
                animationSpec = spring(dampingRatio = 1f, stiffness = 400f),
                initialVelocity = velocity,
            ) { offset = value.coerceIn(0f, height.toFloat()) }
            if (close) {
                completed = true
                dismiss()
            }
        }
    }

    // Gesture coordinates stay on this stationary container while only the surface moves.
    Box(Modifier.fillMaxSize().onSizeChanged { height = it.height }.then(
        if (enabled && !completed) Modifier.pointerInput(headerHeight) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (down.position.y < offset || down.position.y > offset + headerHeight) return@awaitEachGesture
                settling?.cancel()
                val startOffset = offset
                val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
                var dragging = false
                var released = false
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        tracker.addPosition(change.uptimeMillis, change.position)
                        if (!change.pressed) {
                            if (dragging) change.consume()
                            released = true
                            break
                        }
                        val delta = change.position - down.position
                        if (!dragging) {
                            if ((abs(delta.x) > viewConfiguration.touchSlop && abs(delta.x) > abs(delta.y)) ||
                                delta.y < -viewConfiguration.touchSlop) break
                            if (delta.y <= viewConfiguration.touchSlop) continue
                            dragging = true
                        }
                        change.consume()
                        offset = (startOffset + delta.y - viewConfiguration.touchSlop).coerceIn(0f, height.toFloat())
                    }
                } finally {
                    if (dragging || startOffset > 0f) settle(
                        velocity = if (released && dragging) tracker.calculateVelocity().y else 0f,
                        allowDismiss = released && dragging,
                    )
                }
            }
        } else Modifier,
    )) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            translationY = offset
            shape = corners
            clip = offset > 0f
            shadowElevation = if (offset > 0f) 18.dp.toPx() else 0f
        }) { content() }
    }
}
