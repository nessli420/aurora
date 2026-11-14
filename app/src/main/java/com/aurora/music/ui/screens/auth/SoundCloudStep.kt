package com.aurora.music.ui.screens.auth

import com.aurora.music.localization.appString
import com.aurora.music.R

import android.view.ViewGroup
import android.webkit.CookieManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import com.aurora.music.ui.theme.TextButton
import com.aurora.music.ui.theme.Button
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aurora.music.data.remote.SoundCloudWebSession
import com.aurora.music.viewmodel.AuthUiState

@Composable
fun SoundCloudStep(state: AuthUiState, onConnect: (String) -> Unit, onGuest: () -> Unit) {
    var browserOpen by remember { mutableStateOf(false) }
    var detailsExpanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(appString(R.string.soundcloud_sign_in_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { browserOpen = true }, enabled = !state.loading,
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = com.aurora.music.ui.theme.paperSquare(RoundedCornerShape(16.dp))) {
            Text(appString(R.string.soundcloud_sign_in), style = MaterialTheme.typography.titleSmall)
        }
        GuestOption(!state.loading, onGuest)
        TextButton(onClick = { detailsExpanded = !detailsExpanded }) { Text(if (detailsExpanded) appString(R.string.text_hide_connection_details_64711a) else appString(R.string.text_connection_details_ab30a5)) }
        androidx.compose.animation.AnimatedVisibility(detailsExpanded) {
            Text(appString(R.string.soundcloud_details), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (browserOpen) SoundCloudBrowser(state, { browserOpen = false }, onConnect)
}

@Composable
private fun SoundCloudBrowser(state: AuthUiState, onClose: () -> Unit, onConnect: (String) -> Unit) =
    WebSignInDialog(state, SoundCloudWebSession.LOGIN_URL, listOf("soundcloud.com", "m.soundcloud.com", "secure.soundcloud.com", "api-auth.soundcloud.com"),
        ".soundcloud.com", appString(R.string.soundcloud_waiting), onClose, onConnect) { agent ->
        // the mobile site keeps its login in its own host cookies, so read whichever site signed in
        SoundCloudWebSession.ORIGINS.map { SoundCloudWebSession(CookieManager.getInstance().getCookie(it).orEmpty(), agent, it) }
            .firstOrNull { it.token.isNotBlank() }?.let { it.token to it.encode() }
    }

/** SoundCloud asks for a captcha before some changes; the user solves it once in the page that sends them. */
@Composable
fun SoundCloudCaptchaDialog(writer: com.aurora.music.data.SoundCloudWebWriter) {
    val page by writer.captcha.collectAsState()
    val view = page ?: return
    Dialog(onDismissRequest = writer::cancelCaptcha,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = writer::cancelCaptcha) { Text(appString(R.string.text_close_bbfa77)) }
                }
                Text(appString(R.string.soundcloud_captcha_hint), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { (view.parent as? ViewGroup)?.removeView(view); view },
                    onRelease = { (it.parent as? ViewGroup)?.removeView(it) })
            }
        }
    }
}
