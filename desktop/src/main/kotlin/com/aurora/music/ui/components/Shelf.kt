package com.aurora.music.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aurora.music.R
import com.aurora.music.localization.appString
import com.aurora.music.ui.layout.PageMetrics
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.roundToInt

fun shelfColumns(available: Dp, minItemWidth: Dp = PageMetrics.ShelfMinItemWidth, spacing: Dp = PageMetrics.ShelfSpacing): Int =
    floor((available + spacing) / (minItemWidth + spacing)).toInt().coerceAtLeast(2)

fun shelfItemWidth(available: Dp, minItemWidth: Dp = PageMetrics.ShelfMinItemWidth, spacing: Dp = PageMetrics.ShelfSpacing): Dp {
    val columns = shelfColumns(available, minItemWidth, spacing)
    return (available - spacing * (columns - 1)) / columns
}

@Composable
fun <T> AdaptiveShelf(
    items: List<T>,
    modifier: Modifier = Modifier,
    minItemWidth: Dp = PageMetrics.ShelfMinItemWidth,
    spacing: Dp = PageMetrics.ShelfSpacing,
    bleed: Dp = 0.dp,
    artHeight: Dp? = null,
    key: ((T) -> Any)? = null,
    state: LazyListState = rememberLazyListState(),
    itemContent: @Composable (item: T, itemWidth: Dp) -> Unit,
) = AdaptiveShelf(
    itemCount = items.size,
    modifier = modifier,
    minItemWidth = minItemWidth,
    spacing = spacing,
    bleed = bleed,
    artHeight = artHeight,
    key = key?.let { k -> { i: Int -> k(items[i]) } },
    state = state,
) { i, width -> itemContent(items[i], width) }

@Composable
fun AdaptiveShelf(
    itemCount: Int,
    modifier: Modifier = Modifier,
    minItemWidth: Dp = PageMetrics.ShelfMinItemWidth,
    spacing: Dp = PageMetrics.ShelfSpacing,
    bleed: Dp = 0.dp,
    artHeight: Dp? = null,
    key: ((index: Int) -> Any)? = null,
    state: LazyListState = rememberLazyListState(),
    itemContent: @Composable (index: Int, itemWidth: Dp) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth().hoverable(interaction)) {
        val available = if (constraints.hasBoundedWidth) maxWidth else minItemWidth * 4 + spacing * 3
        val columns = shelfColumns(available, minItemWidth, spacing)
        val itemWidth = shelfItemWidth(available, minItemWidth, spacing)
        val fade = if (bleed > 0.dp) bleed else 24.dp
        val startFade by animateFloatAsState(if (state.canScrollBackward) 1f else 0f, label = "shelfStartFade")
        val endFade by animateFloatAsState(if (state.canScrollForward) 1f else 0f, label = "shelfEndFade")
        fun page(direction: Int) = scope.launch {
            val step = with(density) { (itemWidth + spacing).toPx() }
            val position = state.firstVisibleItemIndex * step + state.firstVisibleItemScrollOffset
            val target = ((position / step).roundToInt() + direction * columns).coerceIn(0, (itemCount - 1).coerceAtLeast(0))
            state.animateScrollToItem(target)
        }
        LazyRow(
            state = state,
            modifier = Modifier
                .layout { measurable, constraints ->
                    val extra = bleed.roundToPx()
                    val width = if (constraints.hasBoundedWidth) constraints.maxWidth else available.roundToPx()
                    val placeable = measurable.measure(constraints.copy(minWidth = width + extra * 2, maxWidth = width + extra * 2))
                    layout(width, placeable.height) { placeable.place(-extra, 0) }
                }
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val edge = fade.toPx()
                    if (startFade > 0f) drawRect(
                        Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 1f - startFade), Color.Black), startX = 0f, endX = edge),
                        size = Size(edge, size.height),
                        blendMode = BlendMode.DstIn,
                    )
                    if (endFade > 0f) drawRect(
                        Brush.horizontalGradient(listOf(Color.Black, Color.Black.copy(alpha = 1f - endFade)), startX = size.width - edge, endX = size.width),
                        topLeft = Offset(size.width - edge, 0f),
                        size = Size(edge, size.height),
                        blendMode = BlendMode.DstIn,
                    )
                },
            contentPadding = PaddingValues(horizontal = bleed),
            horizontalArrangement = Arrangement.spacedBy(spacing),
        ) {
            items(itemCount, key = key) { i -> Box(Modifier.width(itemWidth)) { itemContent(i, itemWidth) } }
        }
        val chevronTop = ((artHeight ?: itemWidth) / 2 - 18.dp).coerceAtLeast(0.dp)
        AnimatedVisibility(
            visible = hovered && state.canScrollBackward,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = chevronTop),
        ) {
            ShelfChevron(Icons.AutoMirrored.Filled.KeyboardArrowLeft, appString(R.string.text_previous_50f942)) { page(-1) }
        }
        AnimatedVisibility(
            visible = hovered && state.canScrollForward,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 8.dp, top = chevronTop),
        ) {
            ShelfChevron(Icons.AutoMirrored.Filled.KeyboardArrowRight, appString(R.string.text_next_bc9819)) { page(1) }
        }
    }
}

@Composable
private fun ShelfChevron(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f))
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), CircleShape)
            .clickable(onClickLabel = label, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
    }
}
