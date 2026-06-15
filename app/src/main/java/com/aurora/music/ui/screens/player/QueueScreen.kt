package com.aurora.music.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import com.aurora.music.util.rememberDominantColor
import kotlin.math.roundToInt

@Composable
fun QueueScreen(
    queue: List<Song>,
    currentIndex: Int,
    isPlaying: Boolean,
    onJump: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onClear: () -> Unit,
    onSaveAsPlaylist: (String) -> Unit,
    onClose: () -> Unit,
) {
    val current = queue.getOrNull(currentIndex)
    val startIdx = (currentIndex + 1).coerceAtLeast(0)
    val upcoming = (startIdx until queue.size).toList()
    val played = (currentIndex - 1 downTo 0).toList()
    val accent by rememberDominantColor(current?.artworkUrl ?: "", MaterialTheme.colorScheme.primary)
    val rowHeight = 64.dp
    val rowPx = with(LocalDensity.current) { rowHeight.toPx() }

    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var showHistory by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .background(
                    Brush.verticalGradient(
                        listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = 0.14f), MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.background)
                    )
                )
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Column(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(horizontal = 16.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.KeyboardArrowDown, "Close", modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClose).padding(6.dp))
                    Spacer(Modifier.weight(1f))
                    Text("Queue", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                    Spacer(Modifier.weight(1f))
                    Icon(
                        Icons.Filled.PlaylistAdd, "Save queue as playlist",
                        tint = if (queue.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f) else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(40.dp).clip(CircleShape)
                            .clickable(enabled = queue.isNotEmpty()) { showSaveDialog = true }.padding(8.dp),
                    )
                    Icon(
                        Icons.Filled.DeleteSweep, "Clear queue",
                        tint = if (upcoming.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f) else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(40.dp).clip(CircleShape)
                            .clickable(enabled = upcoming.isNotEmpty(), onClick = onClear).padding(8.dp),
                    )
                }

                if (current != null) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(current.artworkUrl, accent, Modifier.size(64.dp), corner = 14.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Eyebrow("NOW PLAYING", accent)
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
                                    .clickable { showHistory = !showHistory }.padding(vertical = 8.dp, horizontal = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "PREVIOUSLY PLAYED  •  ${played.size}",
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
                            if (upcoming.isEmpty()) "NOTHING UP NEXT" else "UP NEXT  •  ${upcoming.size} tracks",
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
                            onRemove = { onRemove(i) },
                            // key on i/startIdx so gesture re-captures fresh indices when current advances or rows shift
                            dragHandle = Modifier.pointerInput(queue.size, i, startIdx) {
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
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                translationY = dragOffset
                if (dragging) { shadowElevation = 16f; scaleX = 1.02f; scaleY = 1.02f }
            }
            .clip(RoundedCornerShape(14.dp))
            .background(if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(26.dp), contentAlignment = Alignment.Center) {
            if (index != null) Text("$index", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Artwork(song.artworkUrl, song.accent, Modifier.size(44.dp), corner = 10.dp)
        Spacer(Modifier.width(12.dp))
        val alpha = if (dimmed) 0.6f else 1f
        Column(Modifier.weight(1f)) {
            Text(
                song.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(song.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(formatTime(song.durationSec), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
        if (onRemove != null) {
            Icon(
                Icons.Filled.Close, "Remove",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onRemove).padding(7.dp),
            )
        }
        if (dragHandle != null) {
            Icon(
                Icons.Filled.DragHandle, "Reorder",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(34.dp).padding(6.dp).then(dragHandle),
            )
        }
    }
}

@Composable
private fun SaveQueueDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save queue as playlist", fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Playlist name") }, singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onSave(name.trim()) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
