package com.aurora.music.ui.screens.auth

import com.aurora.music.localization.appString
import com.aurora.music.R

import android.webkit.CookieManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import com.aurora.music.ui.theme.TextButton
import com.aurora.music.ui.theme.Button
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aurora.music.data.remote.AppleMusicClient
import com.aurora.music.data.remote.AppleMusicWebSession
import com.aurora.music.viewmodel.AuthUiState

@Composable
fun AppleMusicStep(state: AuthUiState, onConnect: (String) -> Unit, onGuest: () -> Unit) {
    var browserOpen by remember { mutableStateOf(false) }
    var detailsExpanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(appString(R.string.apple_music_sign_in_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { browserOpen = true }, enabled = !state.loading,
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = com.aurora.music.ui.theme.paperSquare(RoundedCornerShape(16.dp))) {
            Text(appString(R.string.apple_music_sign_in), style = MaterialTheme.typography.titleSmall)
        }
        GuestOption(!state.loading, onGuest)
        TextButton(onClick = { detailsExpanded = !detailsExpanded }) { Text(if (detailsExpanded) appString(R.string.text_hide_connection_details_64711a) else appString(R.string.text_connection_details_ab30a5)) }
        androidx.compose.animation.AnimatedVisibility(detailsExpanded) {
            Text(appString(R.string.apple_music_details), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (browserOpen) WebSignInDialog(state, AppleMusicWebSession.LOGIN_URL, listOf("music.apple.com", "authorize.music.apple.com", "idmsa.apple.com", "apple.com"),
        ".apple.com", appString(R.string.apple_music_waiting), { browserOpen = false }, onConnect) {
        AppleMusicWebSession.fromCookies(CookieManager.getInstance().getCookie(AppleMusicClient.WEB).orEmpty())?.let { it.userToken to it.encode() }
    }
}
