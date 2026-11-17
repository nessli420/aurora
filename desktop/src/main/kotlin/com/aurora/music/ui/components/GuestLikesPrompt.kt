package com.aurora.music.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import com.aurora.music.ui.theme.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.DialogProperties
import com.aurora.music.R
import com.aurora.music.data.GuestLikes
import com.aurora.music.data.ServerType
import com.aurora.music.data.Session
import com.aurora.music.localization.appPlural
import com.aurora.music.localization.appString
import kotlinx.coroutines.launch

// once an account takes a guest's place, the songs liked as that guest are offered to it; not now asks again next launch
@Composable
fun GuestLikesPrompt(sessions: List<Session>, guestLikes: GuestLikes, move: suspend (Session) -> Int, held: (Session) -> Int,
    onMoved: () -> Unit, notify: (String) -> Unit, canTake: suspend (Session) -> Boolean = { true }) {
    val revision by guestLikes.revision.collectAsState()
    var later by remember { mutableStateOf(emptySet<ServerType>()) }
    val scope = rememberCoroutineScope()
    // an account that can't keep likes (apple music without a subscription) is passed over, asked once per launch since every like bumps the revision
    val answers = remember { mutableMapOf<Session, Boolean>() }
    val waiting = androidx.compose.runtime.produceState<Pair<Session, Int>?>(null, sessions, revision, later) {
        value = guestLikes.waitingFor(sessions.filter { it.type !in later }) { s -> answers.getOrPut(s) { canTake(s) } }
    }.value ?: return
    val (session, count) = waiting
    val service = session.typeLabel
    // an account kept playlists or album, artist and playlist likes here, so the offer counts items rather than songs
    val library = guestLikes.playlists(session.type).isNotEmpty() || guestLikes.likedCollections(session.type).isNotEmpty()
    AlertDialog(containerColor = com.aurora.music.ui.theme.solidColors().surfaceContainerHigh, 
        // only the buttons close it: a stray click outside used to drop the offer until the next launch
        onDismissRequest = {}, properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(if (library) appString(R.string.guest_library_title, service) else appString(R.string.guest_likes_title, service), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = { Text(if (library) appString(R.string.guest_library_body, appPlural(R.plurals.item_count, count), service)
            else appString(R.string.guest_likes_body, appPlural(R.plurals.track_count, count), service)) },
        confirmButton = {
            TextButton(onClick = {
                // whatever doesn't make it waits for the next launch rather than asking again straight away
                later = later + session.type
                scope.launch {
                    val moved = move(session)
                    val left = guestLikes.pending(session.type)
                    onMoved()
                    val queued = held(session)
                    notify(when {
                        left > 0 -> appString(R.string.guest_likes_left, appPlural(if (library) R.plurals.item_count else R.plurals.track_count, left))
                        // the account has them, but the service still wants its human check before it lists them
                        queued > 0 -> appString(R.string.guest_likes_held, appPlural(R.plurals.track_count, queued), service)
                        library -> appString(R.string.guest_library_added, service)
                        else -> appString(R.string.guest_likes_added, appPlural(R.plurals.track_count, moved), service)
                    })
                }
            }) { Text(appString(R.string.guest_likes_add)) }
        },
        dismissButton = { TextButton(onClick = { later = later + session.type }) { Text(appString(R.string.guest_likes_later)) } })
}
