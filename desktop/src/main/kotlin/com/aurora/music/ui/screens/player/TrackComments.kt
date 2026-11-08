package com.aurora.music.ui.screens.player

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import com.aurora.music.ui.theme.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.data.MergeFeatures
import com.aurora.music.desktop.ui.LocalDesktopContainer
import com.aurora.music.model.Song
import com.aurora.music.model.TrackComment
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.formatTime
import kotlinx.coroutines.launch

@Stable
class TrackComments {
    var supported by mutableStateOf(false)
        internal set
    var list by mutableStateOf<List<TrackComment>>(emptyList())
        internal set
    var failed by mutableStateOf(false)
        internal set
    var needsVerify by mutableStateOf(false)
        internal set
    var available by mutableStateOf(false)
        internal set
    var canPost by mutableStateOf(false)
        internal set
    var enabled by mutableStateOf(true)
        internal set
    internal var post: suspend (String, Long) -> Unit = { _, _ -> }
    var setEnabled: (Boolean) -> Unit = {}
        internal set
}

// comments load once per track; they're offered only once a soundcloud track for the song turned up
@Composable
fun rememberTrackComments(song: Song): TrackComments {
    val container = LocalDesktopContainer.current
    val features by container.settingsStore.mergeFeatures.collectAsStateWithLifecycle(initialValue = MergeFeatures())
    val state = remember(song.id) { TrackComments() }
    val repository = container.repository
    val scope = rememberCoroutineScope()
    LaunchedEffect(song.id, features) {
        state.supported = false
        state.list = emptyList()
        val type = repository.sourceType(song)
        state.available = song.id.isNotEmpty() && repository.supportsComments(song) && type?.name !in features.commentsOff
        state.canPost = repository.canPostComments(song)
        state.enabled = features.comments
        if (!state.available || !features.commentsFor(type)) return@LaunchedEffect
        val loaded = runCatching { repository.comments(song) }.getOrNull() ?: return@LaunchedEffect
        state.list = loaded.sortedBy { it.timestampMs }
        state.supported = true
    }
    state.setEnabled = { on -> scope.launch { container.settingsStore.setMergeFeatures(features.copy(comments = on)) } }
    // posted at the moment the track is at, the way soundcloud pins a comment
    state.post = { body, atMs ->
        val result = runCatching { repository.postComment(song, body, atMs) }
        val posted = result.getOrNull()
        state.failed = posted == null
        state.needsVerify = result.exceptionOrNull() is com.aurora.music.data.remote.SoundCloudCheckException
        if (posted != null) state.list = (state.list + posted).sortedBy { it.timestampMs }
    }
    return state
}

// soundcloud-style: the comment just passed pops up, and every commenter sits along the timeline
@Composable
fun CommentTrack(comments: List<TrackComment>, durationSec: Int, positionSec: Float, accent: Color, onOpen: (String) -> Unit) {
    if (comments.isEmpty() || durationSec <= 0) return
    val passing = comments.lastOrNull { it.timestampMs / 1000f in (positionSec - 4f)..positionSec }
    Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.BottomStart) {
        Crossfade(passing, label = "comment") { comment ->
            if (comment != null) Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clip(MaterialTheme.shapes.small).clickable { onOpen(comment.id) }.pointerHoverIcon(PointerIcon.Hand)
                    .padding(bottom = 2.dp, end = 8.dp)) {
                Artwork(comment.avatarUrl, accent, Modifier.size(24.dp), corner = 12.dp)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(comment.user, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(comment.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    val current by rememberUpdatedState(comments)
    BoxWithConstraints(Modifier.fillMaxWidth().height(20.dp).pointerHoverIcon(PointerIcon.Hand).pointerInput(durationSec) {
        // the avatars are small, so a click anywhere on the strip opens the comment nearest to it
        detectTapGestures { tap ->
            val atMs = tap.x / size.width * durationSec * 1000f
            current.minByOrNull { kotlin.math.abs(it.timestampMs - atMs) }?.let { onOpen(it.id) }
        }
    }, contentAlignment = Alignment.CenterStart) {
        // one avatar per small slice of the timeline, so a busy track stays readable
        val slices = (maxWidth / 14.dp).toInt().coerceAtLeast(1)
        val shown = remember(comments, slices, durationSec) {
            comments.distinctBy { (it.timestampMs / 1000f / durationSec * slices).toInt().coerceIn(0, slices - 1) }
        }
        shown.forEach { comment ->
            val at = (comment.timestampMs / 1000f / durationSec).coerceIn(0f, 1f)
            Artwork(comment.avatarUrl, accent, Modifier.offset(x = (maxWidth - 14.dp) * at).size(14.dp), corner = 7.dp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsSheet(comments: TrackComments, accent: Color, positionSec: Float, durationSec: Int, focusId: String?,
    onSeek: (Float) -> Unit, onDismiss: () -> Unit) {
    var draft by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    LaunchedEffect(focusId, comments.list.size) {
        val index = comments.list.indexOfFirst { it.id == focusId }
        if (index >= 0) list.scrollToItem(index)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = com.aurora.music.ui.theme.solidColors().surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.8f).padding(horizontal = 24.dp)) {
            Text(appString(R.string.player_comments), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list) {
                if (comments.list.isEmpty()) item {
                    Text(appString(R.string.comments_empty), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
                }
                items(comments.list.size, key = { comments.list[it].id }) { index ->
                    val comment = comments.list[index]
                    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                        .background(if (comment.id == focusId) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else Color.Transparent)
                        .clickable { if (durationSec > 0) onSeek((comment.timestampMs / 1000f / durationSec).coerceIn(0f, 1f)) }
                        .pointerHoverIcon(PointerIcon.Hand).padding(8.dp)) {
                        Artwork(comment.avatarUrl, accent, Modifier.size(32.dp), corner = 16.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(comment.user, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Spacer(Modifier.width(6.dp))
                                Text(formatTime((comment.timestampMs / 1000).toInt()), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                            Text(comment.body, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            if (comments.failed) Text(appString(if (comments.needsVerify) R.string.comment_needs_verify else R.string.comment_failed), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            if (comments.canPost) Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(draft, { draft = it }, Modifier.weight(1f), singleLine = true,
                    placeholder = { Text(appString(R.string.comment_hint, formatTime(positionSec.toInt()))) })
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    val body = draft
                    draft = ""
                    scope.launch { comments.post(body, (positionSec * 1000).toLong()) }
                }, enabled = draft.isNotBlank(), modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) { Text(appString(R.string.comment_post)) }
            }
        }
    }
}
