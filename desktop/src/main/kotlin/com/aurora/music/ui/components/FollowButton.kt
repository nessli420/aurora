package com.aurora.music.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aurora.music.R
import com.aurora.music.data.FollowPlace
import com.aurora.music.data.Follows
import com.aurora.music.localization.appString

// an artist page's follow: service names where the follow lives, null keeps it in aurora, unknown while offline
@Immutable
data class FollowUi(val followed: Boolean, val local: Boolean, val service: String?, val known: Boolean)

@Composable
fun rememberFollowUi(follows: Follows, id: String): FollowUi {
    val ids by follows.ids.collectAsState()
    val local by follows.localIds.collectAsState()
    val followed = id in ids
    val place by produceState<FollowPlace?>(null, id, followed) { value = follows.place(id) }
    return FollowUi(followed, id in local, place?.service, place != null)
}

fun followLabel(ui: FollowUi): String = when {
    ui.followed && ui.local -> appString(R.string.following_in_aurora)
    !ui.known -> appString(if (ui.followed) R.string.following else R.string.follow)
    ui.service == null -> appString(if (ui.followed) R.string.following_in_aurora else R.string.follow_in_aurora)
    else -> appString(if (ui.followed) R.string.following_on else R.string.follow_on, ui.service)
}

@Composable
fun FollowButton(ui: FollowUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        Icon(if (ui.followed) Icons.Filled.Check else Icons.Filled.PersonAdd, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(followLabel(ui), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    val padding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
    if (ui.followed) com.aurora.music.ui.theme.FilledTonalButton(onClick, modifier, contentPadding = padding, content = content)
    else com.aurora.music.ui.theme.OutlinedButton(onClick, modifier, contentPadding = padding, content = content)
}
