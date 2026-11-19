package com.aurora.music.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.DialogProperties
import com.aurora.music.R
import com.aurora.music.data.FollowedArtist
import com.aurora.music.data.Session
import com.aurora.music.data.followsToMove
import com.aurora.music.data.mergeNamespace
import com.aurora.music.localization.appPlural
import com.aurora.music.localization.appString
import com.aurora.music.ui.theme.TextButton
import kotlinx.coroutines.launch

// follows aurora kept for an account are offered to it once its service can keep them; not now asks again next launch
@Composable
fun FollowsPrompt(sessions: List<Session>, kept: List<FollowedArtist>, keepsFollows: suspend (Session) -> Boolean,
    move: suspend (Session, List<FollowedArtist>) -> Int, onMoved: () -> Unit, notify: (String) -> Unit) {
    var later by remember { mutableStateOf(emptySet<String>()) }
    val scope = rememberCoroutineScope()
    // an account that can't keep follows (apple music without a subscription) is asked once per launch
    val answers = remember { mutableMapOf<Session, Boolean>() }
    val waiting = produceState<Pair<Session, List<FollowedArtist>>?>(null, sessions, kept, later) {
        value = followsToMove(sessions.filter { mergeNamespace(it) !in later }, kept) { s -> answers.getOrPut(s) { keepsFollows(s) } }
    }.value ?: return
    val (session, artists) = waiting
    val service = session.typeLabel
    AlertDialog(
        onDismissRequest = {}, properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(appString(R.string.follows_move_title, service)) },
        text = { Text(appString(R.string.follows_move_body, appPlural(R.plurals.artist_count, artists.size), service)) },
        confirmButton = {
            TextButton(onClick = {
                later = later + mergeNamespace(session)
                scope.launch {
                    val moved = move(session, artists)
                    onMoved()
                    val left = artists.size - moved
                    notify(if (left > 0) appString(R.string.follows_move_left, appPlural(R.plurals.artist_count, left))
                        else appString(R.string.follows_moved, appPlural(R.plurals.artist_count, moved), service))
                }
            }) { Text(appString(R.string.follows_move_confirm)) }
        },
        dismissButton = { TextButton(onClick = { later = later + mergeNamespace(session) }) { Text(appString(R.string.guest_likes_later)) } })
}
