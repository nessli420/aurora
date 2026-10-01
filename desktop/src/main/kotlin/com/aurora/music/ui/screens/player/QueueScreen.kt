package com.aurora.music.ui.screens.player

import com.aurora.music.localization.appPlural

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueuePlayNext
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.unit.round
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.aurora.music.model.Song
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.Eyebrow
import com.aurora.music.ui.components.formatTime
import java.awt.Cursor
import kotlin.math.roundToInt
import com.aurora.music.model.accent

@Composable
fun QueueContent(
    queue: List<Song>,
    currentIndex: Int,
    isPlaying: Boolean,
    onJump: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    editable: Boolean,
    modifier: Modifier = Modifier,
) {
    val current = queue.getOrNull(currentIndex)
    val startIdx = (currentIndex + 1).coerceAtLeast(0)
    val upcoming = (startIdx until queue.size).toList()
    val played = (currentIndex - 1 downTo 0).toList()
    val accent = MaterialTheme.colorScheme.primary
    val rowHeight = 56.dp
    val rowPx = with(LocalDensity.current) { rowHeight.toPx() }

    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var showHistory by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    Column(modifier) {
        if (current != null) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(current.artworkUrl, accent, Modifier.size(64.dp), corner = 14.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Eyebrow(appString(R.string.text_now_playing_586fa7), accent)
                    Spacer(Modifier.height(2.dp))
                    Text(current.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(current.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Filled.GraphicEq, null, tint = accent, modifier = Modifier.size(26.dp))
            }
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (played.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .clickable { showHistory = !showHistory }.pointerHoverIcon(PointerIcon.Hand)
                            .padding(vertical = 8.dp, horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            appString(R.string.text_previously_played_bf618e, (played.size)),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            if (showHistory) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp),
                        )
                    }
                }
                if (showHistory) {
                    items(played.size) { hi ->
                        val i = played[hi]
                        QueueTrackRow(
                            song = queue[i], index = null, rowHeight = rowHeight,
                            dimmed = true, onClick = { onJump(i) }, onRemove = null, dragHandle = null,
                        )
                    }
                }
            }

            item {
                Text(
                    if (upcoming.isEmpty()) appString(R.string.text_nothing_up_next_295357) else appString(R.string.queue_track_count, appPlural(R.plurals.track_count, (upcoming.size))),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            items(upcoming.size) { vi ->
                val i = upcoming[vi]
                val dragging = i == dragIndex
                QueueTrackRow(
                    song = queue[i],
                    index = vi + 1,
                    rowHeight = rowHeight,
                    dragging = dragging,
                    dragOffset = if (dragging) dragOffset else 0f,
                    onClick = { onJump(i) },
                    onRemove = if (editable) ({ onRemove(i) }) else null,
                    onPlayNext = if (editable && i != startIdx) ({ onMove(i, startIdx) }) else null,
                    // key on i/startIdx so gesture re-captures fresh indices when current advances or rows shift
                    dragHandle = if (!editable) null else Modifier.pointerInput(queue.size, i, startIdx) {
                        detectDragGestures(
                            onDragStart = { dragIndex = i; dragOffset = 0f },
                            onDragEnd = {
                                val target = (dragIndex + (dragOffset / rowPx).roundToInt()).coerceIn(startIdx, queue.size - 1)
                                if (target != dragIndex && dragIndex >= 0) onMove(dragIndex, target)
                                dragIndex = -1; dragOffset = 0f
                            },
                            onDragCancel = { dragIndex = -1; dragOffset = 0f },
                            onDrag = { change, amount -> change.consume(); dragOffset += amount.y },
                        )
                    },
                )
            }
        }
    }
}

@Composable
fun QueueActions(queue: List<Song>, currentIndex: Int, editable: Boolean, onClear: () -> Unit, onSaveAsPlaylist: (String) -> Unit) {
    var showSaveDialog by remember { mutableStateOf(false) }
    val hasUpcoming = currentIndex + 1 < queue.size
    val colors = MaterialTheme.colorScheme
    androidx.compose.material3.IconButton(onClick = { showSaveDialog = true }, enabled = queue.isNotEmpty()) {
        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, appString(R.string.text_save_queue_as_playlist_7f09d8),
            tint = if (queue.isEmpty()) colors.onSurfaceVariant.copy(alpha = 0.35f) else colors.onSurfaceVariant, modifier = Modifier.size(22.dp))
    }
    androidx.compose.material3.IconButton(onClick = onClear, enabled = hasUpcoming && editable) {
        Icon(Icons.Filled.DeleteSweep, appString(R.string.text_clear_queue_984301),
            tint = if (!hasUpcoming || !editable) colors.onSurfaceVariant.copy(alpha = 0.35f) else colors.onSurfaceVariant, modifier = Modifier.size(22.dp))
    }
    if (showSaveDialog) {
        SaveQueueDialog(
            onSave = { name -> onSaveAsPlaylist(name); showSaveDialog = false },
            onDismiss = { showSaveDialog = false },
        )
    }
}

// index null = history row no number/drag, dragHandle null = no reorder handle
@Composable
private fun QueueTrackRow(
    song: Song,
    index: Int?,
    rowHeight: androidx.compose.ui.unit.Dp,
    dragging: Boolean = false,
    dragOffset: Float = 0f,
    dimmed: Boolean = false,
    onClick: () -> Unit,
    onRemove: (() -> Unit)?,
    dragHandle: Modifier?,
    onPlayNext: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var contextAt by remember(song.id) { mutableStateOf<Offset?>(null) }
    val actions = hovered || dragging || contextAt != null
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .fillMaxWidth()
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                translationY = dragOffset
                if (dragging) { shadowElevation = 16f; scaleX = 1.02f; scaleY = 1.02f }
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                            event.changes.forEach { it.consume() }
                            contextAt = event.changes.first().position
                        }
                    }
                }
            },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(rowHeight)
                .clip(RoundedCornerShape(12.dp))
                .background(if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                if (index != null) Text("$index", style = MaterialTheme.typography.labelMedium, color = muted)
            }
            Spacer(Modifier.width(8.dp))
            Artwork(song.artworkUrl, song.accent, Modifier.size(40.dp), corner = 8.dp)
            Spacer(Modifier.width(12.dp))
            val alpha = if (dimmed) 0.6f else 1f
            Column(Modifier.weight(1f)) {
                Text(
                    song.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(song.artist, style = MaterialTheme.typography.bodySmall, color = muted.copy(alpha = alpha), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(formatTime(song.durationSec), style = MaterialTheme.typography.labelSmall, color = muted, modifier = Modifier.padding(start = 8.dp))
            if (actions && onRemove != null) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Filled.Close, appString(R.string.text_remove_e96390),
                    tint = muted,
                    modifier = Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onRemove).pointerHoverIcon(PointerIcon.Hand).padding(7.dp),
                )
            }
            if (actions && dragHandle != null) {
                Icon(
                    Icons.Filled.DragHandle, appString(R.string.text_reorder_33d997),
                    tint = muted,
                    modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR))).then(dragHandle).padding(6.dp),
                )
            }
        }
        contextAt?.let { at ->
            Box(Modifier.offset { at.round() }) {
                DropdownMenu(expanded = true, onDismissRequest = { contextAt = null }) {
                    DropdownMenuItem(
                        text = { Text(appString(R.string.text_play_5d12bd)) },
                        onClick = { contextAt = null; onClick() },
                        leadingIcon = { Icon(Icons.Filled.PlayArrow, null) },
                    )
                    if (onPlayNext != null) DropdownMenuItem(
                        text = { Text(appString(R.string.text_play_next_40d33c)) },
                        onClick = { contextAt = null; onPlayNext() },
                        leadingIcon = { Icon(Icons.Filled.QueuePlayNext, null) },
                    )
                    if (onRemove != null) DropdownMenuItem(
                        text = { Text(appString(R.string.text_remove_e96390)) },
                        onClick = { contextAt = null; onRemove() },
                        leadingIcon = { Icon(Icons.Filled.Close, null) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SaveQueueDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(appString(R.string.text_save_queue_as_playlist_7f09d8), fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text(appString(R.string.text_playlist_name_544f75)) }, singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onSave(name.trim()) }, enabled = name.isNotBlank()) { Text(appString(R.string.text_save_efc007)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(appString(R.string.text_cancel_77dfd2)) } },
    )
}
