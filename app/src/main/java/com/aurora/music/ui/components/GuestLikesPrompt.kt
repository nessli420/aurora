package com.aurora.music.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.aurora.music.R
import com.aurora.music.data.GuestLikes
import com.aurora.music.data.ServerType
import com.aurora.music.data.Session
import com.aurora.music.localization.appPlural
import com.aurora.music.localization.appString
import kotlinx.coroutines.launch

// once an account takes a guest's place, the songs liked as that guest are offered to it; not now asks again next launch
@Composable
fun GuestLikesPrompt(sessions: List<Session>, guestLikes: GuestLikes, move: suspend (Session) -> Int, onMoved: () -> Unit, notify: (String) -> Unit) {
    val revision by guestLikes.revision.collectAsState()
    var later by remember { mutableStateOf(emptySet<ServerType>()) }
    val scope = rememberCoroutineScope()
    val waiting = remember(sessions, revision, later) { guestLikes.waitingFor(sessions.filter { it.type !in later }) } ?: return
    val (session, count) = waiting
    val service = session.typeLabel
    AlertDialog(onDismissRequest = { later = later + session.type },
        title = { Text(appString(R.string.guest_likes_title, service)) },
        text = { Text(appString(R.string.guest_likes_body, appPlural(R.plurals.track_count, count), service)) },
        confirmButton = {
            TextButton(onClick = {
                // whatever doesn't make it waits for the next launch rather than asking again straight away
                later = later + session.type
                scope.launch {
                    val moved = move(session)
                    val left = guestLikes.count(session.type)
                    onMoved()
                    notify(if (left > 0) appString(R.string.guest_likes_left, appPlural(R.plurals.track_count, left))
                        else appString(R.string.guest_likes_added, appPlural(R.plurals.track_count, moved), service))
                }
            }) { Text(appString(R.string.guest_likes_add)) }
        },
        dismissButton = { TextButton(onClick = { later = later + session.type }) { Text(appString(R.string.guest_likes_later)) } })
}
