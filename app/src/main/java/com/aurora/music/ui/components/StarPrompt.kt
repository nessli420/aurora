package com.aurora.music.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.window.DialogProperties
import com.aurora.music.R
import com.aurora.music.data.updates.GitHubRelease
import com.aurora.music.localization.appString
import com.aurora.music.ui.theme.TextButton

// the links leave it open so both can be visited; only close or back dismisses it
@Composable
fun StarPrompt(onClose: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onClose, properties = DialogProperties(dismissOnClickOutside = false),
        text = { Text(appString(R.string.star_prompt_body)) },
        confirmButton = {
            TextButton(onClick = { runCatching { uriHandler.openUri(GitHubRelease.REPOSITORY_URL) } }) { Text(appString(R.string.star_prompt_github)) }
            TextButton(onClick = { runCatching { uriHandler.openUri(GitHubRelease.DISCORD_URL) } }) { Text(appString(R.string.star_prompt_discord)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(appString(R.string.text_close_bbfa77)) } },
    )
}
