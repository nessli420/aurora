package com.aurora.music.ui.screens.player

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import com.aurora.music.data.CommentFeed
import com.aurora.music.data.MergeFeatures
import com.aurora.music.data.ServerType
import com.aurora.music.data.commentSourceName
import com.aurora.music.data.compactCount
import com.aurora.music.data.mayHaveComments
import com.aurora.music.data.offeredCommentSources
import com.aurora.music.data.shownCommentSource
import com.aurora.music.desktop.ui.LocalDesktopContainer
import com.aurora.music.model.Song
import com.aurora.music.model.TrackComment
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.EmptyState
import com.aurora.music.ui.components.LoadingState
import com.aurora.music.ui.components.formatTime
import com.aurora.music.ui.theme.Motion
import com.aurora.music.ui.theme.Space
import kotlinx.coroutines.launch

@Stable
class TrackComments {
    var sources by mutableStateOf<List<ServerType>>(emptyList())
        internal set
    var feeds by mutableStateOf<Map<ServerType, CommentFeed>>(emptyMap())
        internal set
    var failed by mutableStateOf(false)
        internal set
    var needsVerify by mutableStateOf(false)
        internal set
    var available by mutableStateOf(false)
        internal set
    var likeFailed by mutableStateOf(false)
        internal set
    // the comment services this account can post to and like on, only ever signed in
    var postable by mutableStateOf<Set<ServerType>>(emptySet())
        internal set
    var likeable by mutableStateOf<Set<ServerType>>(emptySet())
        internal set
    var enabled by mutableStateOf(true)
        internal set
    // the timed ones along the waveform
    val list: List<TrackComment> get() = sources.firstOrNull { it.timedComments }?.let { feeds[it]?.comments }.orEmpty()
    val supported: Boolean get() = mayHaveComments(sources, feeds, postable)
    internal var post: suspend (ServerType, String, Long) -> Unit = { _, _, _ -> }
    internal var like: (ServerType, String, Boolean) -> Unit = { _, _, _ -> }
    internal var loadMore: (ServerType) -> Unit = {}
    var setEnabled: (Boolean) -> Unit = {}
        internal set
}

// the comments sheet's last pick, kept while the app runs
private var lastCommentPick by mutableStateOf<ServerType?>(null)

// timed comments load with the track for the waveform; the others wait for the comments sheet
@Composable
fun rememberTrackComments(song: Song): TrackComments {
    val container = LocalDesktopContainer.current
    val features by container.settingsStore.mergeFeatures.collectAsStateWithLifecycle(initialValue = MergeFeatures())
    val state = remember(song.id) { TrackComments() }
    val repository = container.repository
    val scope = rememberCoroutineScope()
    state.loadMore = { source ->
        val feed = state.feeds[source] ?: CommentFeed()
        if (source in state.sources && !feed.loading && feed.hasMore) {
            state.feeds += source to feed.copy(loading = true)
            scope.launch {
                val page = runCatching { repository.comments(song, source, feed.next) }.getOrNull()
                if (source in state.sources) state.feeds += source to feed.plus(page)
            }
        }
    }
    LaunchedEffect(song.id, features) {
        state.feeds = emptyMap()
        val type = repository.sourceType(song)
        val all = if (song.id.isEmpty()) emptyList() else repository.commentSources(song)
        state.sources = all.filter { features.commentsFrom(it, type) }
        state.available = all.any { it.timedComments } && type?.name !in features.commentsOff
        state.postable = state.sources.filter { repository.canPostComments(song, it) }.toSet()
        state.likeable = state.sources.filter { repository.canLikeComments(song, it) }.toSet()
        state.enabled = features.comments
        state.sources.filter { it.timedComments }.forEach(state.loadMore)
    }
    state.setEnabled = { on -> scope.launch { container.settingsStore.setMergeFeatures(features.copy(comments = on)) } }
    // a timed comment is pinned to the moment the track is at; an untimed one goes on top
    state.post = { source, body, atMs ->
        val result = runCatching { repository.postComment(song, source, body, if (source.timedComments) atMs else -1L) }
        val posted = result.getOrNull()
        state.failed = posted == null
        state.needsVerify = result.exceptionOrNull() is com.aurora.music.data.remote.SoundCloudCheckException
        if (posted != null) {
            val feed = state.feeds[source] ?: CommentFeed(loaded = true)
            val list = if (source.timedComments) (feed.comments + posted).sortedBy { it.timestampMs } else listOf(posted) + feed.comments
            state.feeds += source to feed.copy(comments = list, missing = false)
        }
    }
    // shows at once and goes back if the service refuses it
    state.like = { source, id, liked ->
        fun mark(on: Boolean) = state.feeds[source]?.let { feed ->
            state.feeds += source to feed.copy(comments = feed.comments.map {
                if (it.id != id || it.liked == on) it else it.copy(liked = on, likes = (it.likes + if (on) 1 else -1).coerceAtLeast(0))
            })
        }
        mark(liked)
        state.likeFailed = false
        scope.launch {
            if (runCatching { repository.likeComment(song, source, id, liked) }.getOrDefault(false)) return@launch
            mark(!liked)
            state.likeFailed = true
        }
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
    onSeek: (Float) -> Unit, onDismiss: () -> Unit, onPick: () -> Unit = {}) {
    var draft by remember { mutableStateOf("") }
    var pickedHere by remember(comments) { mutableStateOf(false) }
    val postable = comments.postable
    // a comment opened from the waveform shows among the timed ones
    val shown = comments.sources.firstOrNull { focusId != null && it.timedComments }
        ?: shownCommentSource(comments.sources, comments.feeds, lastCommentPick, pickedHere, postable)
    val offered = offeredCommentSources(comments.sources, comments.feeds, shown, postable)
    val feed = comments.feeds[shown] ?: CommentFeed()
    val rows = feed.comments
    val list = remember(shown) { LazyListState() }
    // an untimed post lands on top, above where the list is anchored
    var postedOnTop by remember { mutableStateOf(false) }
    LaunchedEffect(rows.firstOrNull()?.id) { if (postedOnTop) { postedOnTop = false; list.animateScrollToItem(0) } }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // each service's first page loads as the sheet opens, so the switch offers only those with comments
    LaunchedEffect(comments, comments.sources) { comments.sources.filter { comments.feeds[it] == null }.forEach(comments.loadMore) }
    LaunchedEffect(focusId, rows.size) {
        val index = rows.indexOfFirst { it.id == focusId }
        if (index >= 0) list.scrollToItem(index)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = com.aurora.music.ui.theme.solidColors().surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.8f).padding(horizontal = 24.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(appString(R.string.player_comments), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                AnimatedVisibility(offered.size > 1, enter = fadeIn(Motion.enter()), exit = fadeOut(Motion.exit())) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        offered.forEach { source ->
                            FilterChip(source == shown, { pickedHere = true; lastCommentPick = source; onPick() },
                                label = { Text(commentSourceName(source)) }, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand))
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list) {
                items(rows.size, key = { rows[it].id }) { index ->
                    val comment = rows[index]
                    CommentRow(comment, comment.id == focusId, accent,
                        if (comment.timestampMs >= 0 && durationSec > 0) ({ onSeek((comment.timestampMs / 1000f / durationSec).coerceIn(0f, 1f)) }) else null,
                        if (shown != null && shown in comments.likeable) ({ on -> comments.like(shown, comment.id, on) }) else null)
                }
                // the next page loads as this row scrolls near; unkeyed, so the list keeps its place when a page lands above it
                if (feed.hasMore) item {
                    LaunchedEffect(shown, feed.next, feed.loaded) { shown?.let(comments.loadMore) }
                    if (rows.isEmpty()) LoadingState()
                    else Box(Modifier.fillMaxWidth().padding(Space.m), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                } else if (rows.isEmpty()) item {
                    EmptyState(Icons.Filled.ChatBubbleOutline, appString(if (shown in postable) R.string.comments_empty else R.string.comments_none))
                }
            }
            // the last message stays up while the line folds away
            val problem = if (comments.likeFailed) R.string.comment_like_failed
                else if (!comments.failed) null else if (comments.needsVerify) R.string.comment_needs_verify else R.string.comment_failed
            val lastProblem = remember { intArrayOf(R.string.comment_failed) }
            problem?.let { lastProblem[0] = it }
            AnimatedVisibility(problem != null, enter = fadeIn(Motion.enter()) + expandVertically(Motion.enter()), exit = fadeOut(Motion.exit()) + shrinkVertically(Motion.exit())) {
                Text(appString(problem ?: lastProblem[0]), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
            if (shown != null && shown in postable) Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(draft, { draft = it }, Modifier.weight(1f), singleLine = true,
                    placeholder = { Text(if (shown.timedComments) appString(R.string.comment_hint, formatTime(positionSec.toInt())) else appString(R.string.comment_hint_untimed)) })
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    val body = draft
                    draft = ""
                    postedOnTop = !shown.timedComments
                    scope.launch { comments.post(shown, body, (positionSec * 1000).toLong()) }
                }, enabled = draft.isNotBlank(), modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) { Text(appString(R.string.comment_post)) }
            }
        }
    }
}

// timed comments seek to their moment; the others show their age and likes
@Composable
private fun CommentRow(comment: TrackComment, focused: Boolean, accent: Color, onClick: (() -> Unit)?, onLike: ((Boolean) -> Unit)?) {
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
        .background(if (focused) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else Color.Transparent)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand) else Modifier).padding(8.dp)) {
        Artwork(comment.avatarUrl, accent, Modifier.size(32.dp), corner = 16.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(comment.user, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(6.dp))
                if (comment.timestampMs >= 0) Text(formatTime((comment.timestampMs / 1000).toInt()), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
                else Text(comment.age, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Text(comment.body, style = MaterialTheme.typography.bodyMedium)
            val likeTint = if (comment.liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            // signed in, the count turns into the like toggle
            if (onLike != null || comment.likes > 0) Row(Modifier.padding(top = Space.xs)
                .then(if (onLike == null) Modifier else Modifier.offset(x = (-8).dp).heightIn(min = 32.dp).clip(CircleShape)
                    .toggleable(comment.liked, role = Role.Checkbox, onValueChange = onLike).pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 8.dp)),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(if (comment.liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                    if (onLike != null) appString(R.string.text_like_c7e02c) else null, Modifier.size(if (onLike != null) 16.dp else 14.dp), tint = likeTint)
                if (comment.likes > 0) {
                    Spacer(Modifier.width(Space.xs))
                    Text(compactCount(comment.likes), style = MaterialTheme.typography.labelSmall, color = likeTint)
                }
            }
        }
    }
}
