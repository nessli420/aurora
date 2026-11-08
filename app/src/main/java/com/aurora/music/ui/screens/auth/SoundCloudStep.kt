package com.aurora.music.ui.screens.auth

import com.aurora.music.localization.appString
import com.aurora.music.R

import android.annotation.SuppressLint
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import kotlinx.coroutines.delay

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

@SuppressLint("SetJavaScriptEnabled")
private fun WebView.secure(popups: Boolean) {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    // google sign-in opens in a window of its own
    settings.setSupportMultipleWindows(popups)
    settings.javaScriptCanOpenWindowsAutomatically = popups
    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
}

@Composable
private fun SoundCloudBrowser(state: AuthUiState, onClose: () -> Unit, onConnect: (String) -> Unit) {
    var browser by remember { mutableStateOf<WebView?>(null) }
    var popup by remember { mutableStateOf<WebView?>(null) }
    var pageLoading by remember { mutableStateOf(true) }
    var cleared by remember { mutableStateOf(false) }
    var sentToken by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val connect by rememberUpdatedState(onConnect)
    val connecting by rememberUpdatedState(state.loading)
    LaunchedEffect(browser, cleared) {
        val view = browser ?: return@LaunchedEffect
        if (!cleared) return@LaunchedEffect
        // the mobile site keeps its login in its own host cookies, so read whichever site signed in
        fun signedIn() = SoundCloudWebSession.ORIGINS.map { SoundCloudWebSession(CookieManager.getInstance().getCookie(it).orEmpty(), view.settings.userAgentString, it) }
            .firstOrNull { it.token.isNotBlank() }
        while (true) {
            delay(750)
            val token = signedIn()?.token
            if (token == null || token == sentToken || connecting) continue
            // the refresh cookie lands with the token; give it a moment before reading the jar
            delay(1_000)
            val session = signedIn() ?: continue
            sentToken = session.token
            connect(session.encode())
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            listOfNotNull(popup, browser).forEach { it.stopLoading(); it.destroy() }
            popup = null
            browser = null
        }
    }
    Dialog(onDismissRequest = { if (!state.loading) onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { popup?.let { it.destroy(); popup = null } ?: onClose() }, enabled = !state.loading) { Text(appString(R.string.text_close_bbfa77)) }
                    TextButton(onClick = { sentToken = ""; browser?.reload() }, enabled = !state.loading) { Text(appString(R.string.text_reload_cce715)) }
                }
                Text(appString(R.string.soundcloud_waiting), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                if (pageLoading || state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                (error ?: state.error)?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                        WebView(context).apply {
                            browser = this
                            secure(popups = true)
                            webChromeClient = object : WebChromeClient() {
                                override fun onConsoleMessage(message: android.webkit.ConsoleMessage?) = true
                                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                                    val window = WebView(view.context).apply {
                                        secure(popups = false)
                                        webViewClient = WebViewClient()
                                        webChromeClient = object : WebChromeClient() {
                                            override fun onCloseWindow(window: WebView) { if (popup === window) { popup = null; window.destroy() } }
                                        }
                                    }
                                    popup?.destroy()
                                    popup = window
                                    (resultMsg.obj as WebView.WebViewTransport).webView = window
                                    resultMsg.sendToTarget()
                                    return true
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = request.url.scheme != "https"
                                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) { pageLoading = true; error = null }
                                override fun onPageFinished(view: WebView, url: String?) { pageLoading = false }
                                override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: android.webkit.WebResourceError) {
                                    if (request.isForMainFrame) { pageLoading = false; error = appString(R.string.soundcloud_page_failed) }
                                }
                            }
                            // start signed out so a different account can be added
                            clearSoundCloudCookies { if (browser === this) { cleared = true; loadUrl(SoundCloudWebSession.LOGIN_URL) } }
                        }
                    })
                    popup?.let { window -> AndroidView(modifier = Modifier.fillMaxSize(), factory = { window }) }
                }
            }
        }
    }
}

/** Leave other embedded sign-ins (such as Google for YouTube Music) alone. */
private fun clearSoundCloudCookies(onCleared: () -> Unit) {
    val cookies = CookieManager.getInstance()
    val operations = listOf("soundcloud.com", "m.soundcloud.com", "secure.soundcloud.com", "api-auth.soundcloud.com").flatMap { host ->
        cookies.getCookie("https://$host").orEmpty().split(';').mapNotNull { part ->
            part.substringBefore('=').trim().takeIf { it.isNotEmpty() }
        }.flatMap { name ->
            val expired = "$name=; Path=/; Max-Age=0; Secure"
            listOf("https://$host" to expired, "https://$host" to "$expired; Domain=.soundcloud.com")
        }
    }
    if (operations.isEmpty()) { onCleared(); return }
    var pending = operations.size
    operations.forEach { (url, value) -> cookies.setCookie(url, value) { if (--pending == 0) { cookies.flush(); onCleared() } } }
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
