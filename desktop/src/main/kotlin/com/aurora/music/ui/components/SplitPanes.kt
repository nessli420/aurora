package com.aurora.music.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aurora.music.R
import com.aurora.music.localization.appString
import com.aurora.music.ui.layout.TabletMetrics
import java.awt.Cursor

@Stable
class PaneWidth internal constructor(saved: Float?) {
    private var custom by mutableStateOf(saved)
    private var default = 0.dp
    private var min = 0.dp
    private var max = Dp.Infinity

    val value: Float? get() = custom

    fun resolve(default: Dp, min: Dp, max: Dp): Dp {
        this.default = default
        this.min = min
        this.max = max.coerceAtLeast(min)
        return current()
    }

    fun drag(delta: Dp) {
        custom = (current() + delta).coerceIn(min, max).value
    }

    fun reset() {
        custom = null
    }

    private fun current(): Dp = (custom?.dp ?: default).coerceIn(min, max)
}

@Composable
fun rememberPaneWidth(saved: Float?): PaneWidth = remember(saved) { PaneWidth(saved) }

@Composable
fun PaneDivider(pane: PaneWidth, onSave: (Float?) -> Unit, modifier: Modifier = Modifier, fromEnd: Boolean = false) {
    PaneDivider(
        onDrag = { pane.drag(if (fromEnd) -it else it) },
        onDragEnd = { onSave(pane.value) },
        modifier = modifier,
        onReset = { pane.reset(); onSave(null) },
    )
}

@Composable
fun PaneDivider(
    onDrag: (Dp) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
    onReset: (() -> Unit)? = null,
    width: Dp = TabletMetrics.DividerWidth,
) {
    var dragging by remember { mutableStateOf(false) }
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onDragEnd)
    val reset by rememberUpdatedState(onReset)
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val alpha by animateFloatAsState(if (dragging) 0.5f else if (hovered) 0.32f else 0.16f, tween(160), label = "dividerAlpha")
    val length by animateDpAsState(if (dragging) 64.dp else 36.dp, tween(160), label = "dividerLength")
    val description = appString(R.string.tablet_resize_panels)
    Box(
        modifier.width(width).fillMaxHeight()
            .hoverable(hover)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false; end() },
                    onDragCancel = { dragging = false; end() },
                ) { change, amount -> change.consume(); drag(amount.toDp()) }
            }
            .then(if (onReset != null) Modifier.pointerInput(Unit) { detectTapGestures(onDoubleTap = { reset?.invoke() }) } else Modifier)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(3.dp).height(length).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FullPageButton(onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    val label = appString(R.string.text_open_in_full_page_0ec923)
    TooltipArea(tooltip = { HoverTooltip(label) }, modifier = modifier, delayMillis = 400) {
        Icon(
            Icons.Outlined.OpenInFull, label, tint = tint,
            modifier = Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand).padding(14.dp),
        )
    }
}

@Composable
fun HoverTooltip(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.inverseOnSurface,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.inverseSurface).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
