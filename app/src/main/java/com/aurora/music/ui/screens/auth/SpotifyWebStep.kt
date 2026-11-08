package com.aurora.music.ui.screens.auth

import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import com.aurora.music.ui.theme.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.aurora.music.ui.theme.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aurora.music.AuroraApplication
import com.aurora.music.R
import com.aurora.music.data.SpotifyPage
import com.aurora.music.data.SpotifyWebBackend
import com.aurora.music.data.SpotifyWebPlayer
import com.aurora.music.data.SpotifyWebProfile
import com.aurora.music.localization.appString
import com.aurora.music.viewmodel.AuthUiState
import kotlinx.coroutines.launch

// flip to bring back the bring-your-own developer app oauth sign-in
internal const val SPOTIFY_OAUTH_VISIBLE = false

@Composable
internal fun SpotifyWebStep(state: AuthUiState, onConnect: (cookie: String, profile: SpotifyWebProfile?) -> Unit, oauth: @Composable () -> Unit) {
    var browserOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(appString(R.string.spotify_web_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { browserOpen = true }, enabled = !state.loading,
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = com.aurora.music.ui.theme.paperSquare(RoundedCornerShape(16.dp))) {
            Text(appString(R.string.spotify_web_login), style = MaterialTheme.typography.titleSmall)
        }
        Text(appString(R.string.spotify_web_login_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (SPOTIFY_OAUTH_VISIBLE) oauth()
    }
    if (browserOpen) SpotifyLoginBrowser(onClose = { browserOpen = false }) { cookie, profile ->
        browserOpen = false
        onConnect(cookie, profile)
    }
}

@Composable
private fun SpotifyLoginBrowser(onClose: () -> Unit, onCaptured: (String, SpotifyWebProfile?) -> Unit) {
    val operations = (LocalContext.current.applicationContext as AuroraApplication).container.spotifyWeb.operations
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf<SpotifyPage?>(null) }
    var pageLoading by remember { mutableStateOf(true) }
    var reading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val captured by rememberUpdatedState(onCaptured)
    DisposableEffect(Unit) {
        onDispose {
            page?.close()
            page = null
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false,
        dismissOnClickOutside = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onClose) { Text(appString(R.string.text_close_bbfa77)) }
                    TextButton(onClick = { error = null; page?.view?.reload() }) { Text(appString(R.string.text_reload_cce715)) }
                }
                Text(appString(R.string.spotify_web_browser_hint), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp))
                if (pageLoading || reading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
                AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { context ->
                    WebView(context).apply {
                        // wrap_content would make the page resolve 100vh to 0 and render nothing
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        SpotifyWebPlayer.configure(this)
                        val created = SpotifyPage(this, operations)
                        page = created
                        var done = false
                        // the profile comes from the signed-in page itself since the public api refuses web player tokens
                        created.onSignedIn = {
                            if (!done) {
                                done = true
                                reading = true
                                scope.launch {
                                    val profile = runCatching { SpotifyWebBackend.profile(created) }.getOrNull()
                                    captured(SpotifyWebPlayer.sessionCookie(), profile)
                                }
                            }
                        }
                        val hooked = created.install()
                        if (!hooked) error = appString(R.string.spotify_web_unsupported)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                request.url.scheme != "https"
                            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) { pageLoading = true }
                            override fun onPageFinished(view: WebView, url: String?) { pageLoading = false }
                            override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
                                if (request.isForMainFrame) {
                                    pageLoading = false
                                    error = appString(R.string.text_the_sign_in_page_could_not_load_check_your_connection_and_reload_a45e09)
                                }
                            }
                        }
                        // always start signed out so adding a second account never reuses the first one's browser login
                        SpotifyWebPlayer.clearCookies()
                        if (hooked) loadUrl(SpotifyWebPlayer.LOGIN_URL)
                    }
                })
            }
        }
    }
}
