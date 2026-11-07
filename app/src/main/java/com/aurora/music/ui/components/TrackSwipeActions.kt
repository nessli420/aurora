package com.aurora.music.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueuePlayNext
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import com.aurora.music.AuroraApplication
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aurora.music.R
import com.aurora.music.localization.appString
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

internal enum class TrackSwipeAction { NONE, PLAY_NEXT, QUEUE, LIKE, PLAYLISTS }

internal fun trackSwipeAction(offset: Float, shortDistance: Float, longDistance: Float, canLike: Boolean = true): TrackSwipeAction = when {
    abs(offset) < shortDistance -> TrackSwipeAction.NONE
    offset >= longDistance && canLike -> TrackSwipeAction.LIKE
    offset <= -longDistance -> TrackSwipeAction.PLAYLISTS
    offset > 0 -> TrackSwipeAction.PLAY_NEXT
    else -> TrackSwipeAction.QUEUE
}

@Composable
internal fun TrackSwipeActions(
    modifier: Modifier,
    shape: Shape,
    liked: Boolean,
    enabled: Boolean,
    itemKey: Any?,
    onPlayNext: (() -> Unit)?,
    onQueue: (() -> Unit)?,
    onLike: (() -> Unit)?,
    onPlaylists: () -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    // lazy slots keep the row when the song under it changes, so only the swipe state is per song
    var width by remember(itemKey) { mutableIntStateOf(0) }
    var offset by remember(itemKey) { mutableFloatStateOf(0f) }
    var returning by remember(itemKey) { mutableStateOf(false) }
    val currentKey by rememberUpdatedState(itemKey)
    val shortDistance = minOf(with(density) { 72.dp.toPx() }, width * .22f).coerceAtLeast(1f)
    val longDistance = minOf(with(density) { 180.dp.toPx() }, width * .55f).coerceAtLeast(shortDistance + 1f)
    val selected = trackSwipeAction(offset, shortDistance, longDistance, onLike != null)
    val commit by rememberUpdatedState<(TrackSwipeAction) -> Unit> { action ->
        when (action) {
            TrackSwipeAction.PLAY_NEXT -> onPlayNext?.invoke()
            TrackSwipeAction.QUEUE -> onQueue?.invoke()
            TrackSwipeAction.LIKE -> onLike?.invoke()
            TrackSwipeAction.PLAYLISTS -> onPlaylists()
            TrackSwipeAction.NONE -> Unit
        }
    }
    val action = if (selected != TrackSwipeAction.NONE) selected
        else if (offset >= 0) TrackSwipeAction.PLAY_NEXT else TrackSwipeAction.QUEUE
    val colors = MaterialTheme.colorScheme

    // the width is only captured once a drag starts so rows don't compose twice on first layout
    Box(modifier.fillMaxWidth().clip(shape).pointerInput(enabled, itemKey) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (returning) return@awaitEachGesture
            val rowWidth = size.width
            val shortDistance = minOf(72.dp.toPx(), rowWidth * .22f).coerceAtLeast(1f)
            val longDistance = minOf(180.dp.toPx(), rowWidth * .55f).coerceAtLeast(shortDistance + 1f)
            var dragging = false
            var released = false
            var lastAction = TrackSwipeAction.NONE
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        if (dragging) change.consume()
                        released = true
                        break
                    }
                    val delta = change.position - down.position
                    if (!dragging) {
                        if (abs(delta.y) > viewConfiguration.touchSlop && abs(delta.y) > abs(delta.x)) break
                        if (abs(delta.x) <= viewConfiguration.touchSlop) continue
                        dragging = true
                        width = rowWidth
                    }
                    change.consume()
                    offset = (delta.x - sign(delta.x) * viewConfiguration.touchSlop).coerceIn(-rowWidth * .8f, rowWidth * .8f)
                    val next = trackSwipeAction(offset, shortDistance, longDistance, onLike != null)
                    if (next != lastAction && next != TrackSwipeAction.NONE) {
                        container.haptic()
                    }
                    lastAction = next
                }
            } finally {
                if (dragging) {
                    returning = true
                    val chosen = if (released) trackSwipeAction(offset, shortDistance, longDistance, onLike != null) else TrackSwipeAction.NONE
                    scope.launch {
                        try {
                            // Return the row before opening a modal or removing a liked-list item.
                            Animatable(offset).animateTo(0f, spring(dampingRatio = .9f, stiffness = 550f)) { offset = value }
                            // the list moved under the spring, so the action would hit another song
                            if (chosen != TrackSwipeAction.NONE && itemKey == currentKey) commit(chosen)
                        } finally { offset = 0f; returning = false }
                    }
                }
            }
        }
    }) {
        if (abs(offset) > .5f) {
            val background by animateColorAsState(if (action == TrackSwipeAction.LIKE || action == TrackSwipeAction.PLAYLISTS)
                colors.primaryContainer else colors.secondaryContainer, label = "swipeActionColor")
            val foreground = if (action == TrackSwipeAction.LIKE || action == TrackSwipeAction.PLAYLISTS)
                colors.onPrimaryContainer else colors.onSecondaryContainer
            Box(Modifier.matchParentSize().background(background),
                contentAlignment = if (offset > 0) Alignment.CenterStart else Alignment.CenterEnd) {
                AnimatedContent(action, label = "trackSwipeAction") { visibleAction ->
                    val label = when (visibleAction) {
                        TrackSwipeAction.LIKE -> appString(if (liked) R.string.text_remove_from_liked_9d1568 else R.string.text_add_to_liked_b99f26)
                        TrackSwipeAction.PLAYLISTS -> appString(R.string.track_swipe_playlists)
                        TrackSwipeAction.QUEUE -> appString(R.string.text_add_to_queue_69b498)
                        else -> appString(R.string.text_play_next_40d33c)
                    }
                    val icon = when (visibleAction) {
                        TrackSwipeAction.LIKE -> if (liked) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder
                        TrackSwipeAction.PLAYLISTS -> Icons.AutoMirrored.Filled.PlaylistAdd
                        TrackSwipeAction.QUEUE -> Icons.AutoMirrored.Filled.QueueMusic
                        else -> Icons.Default.QueuePlayNext
                    }
                    Row(Modifier.widthIn(max = 170.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, null, Modifier.size(23.dp), tint = foreground)
                        Spacer(Modifier.width(8.dp))
                        Text(label, color = foreground, style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Box(Modifier.graphicsLayer { translationX = offset }
            .then(if (abs(offset) > .5f) Modifier.background(colors.background) else Modifier)) { content() }
    }
}
