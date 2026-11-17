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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import com.aurora.music.ui.theme.Motion
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import com.aurora.music.ui.theme.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aurora.music.viewmodel.AuthUiState
import kotlinx.coroutines.delay

@SuppressLint("SetJavaScriptEnabled")
internal fun WebView.secure(popups: Boolean) {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    // google and apple sign-in open in a window of their own
    settings.setSupportMultipleWindows(popups)
    settings.javaScriptCanOpenWindowsAutomatically = popups
    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
}

/** A service's own sign-in page, full screen; [read] gives the login once it's there, keyed by a value that changes with it. */
@Composable
internal fun WebSignInDialog(
    state: AuthUiState,
    start: String,
    hosts: List<String>,
    domain: String,
    waiting: String,
    onClose: () -> Unit,
    onConnect: (String) -> Unit,
    read: (userAgent: String) -> Pair<String, String>?,
) {
    var browser by remember { mutableStateOf<WebView?>(null) }
    var popup by remember { mutableStateOf<WebView?>(null) }
    var pageLoading by remember { mutableStateOf(true) }
    var cleared by remember { mutableStateOf(false) }
    var sentKey by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val connect by rememberUpdatedState(onConnect)
    val connecting by rememberUpdatedState(state.loading)
    val signedIn by rememberUpdatedState(read)
    LaunchedEffect(browser, cleared) {
        val view = browser ?: return@LaunchedEffect
        if (!cleared) return@LaunchedEffect
        val agent = view.settings.userAgentString
        while (true) {
            delay(750)
            val key = signedIn(agent)?.first
            if (key == null || key == sentKey || connecting) continue
            // cookies that come with the login land a moment later; give them time before reading the jar
            delay(1_000)
            val (latest, login) = signedIn(agent) ?: continue
            sentKey = latest
            connect(login)
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
                    TextButton(onClick = { sentKey = ""; browser?.reload() }, enabled = !state.loading) { Text(appString(R.string.text_reload_cce715)) }
                }
                Text(waiting, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                // the bar keeps its slot so the page below never resizes
                Box(Modifier.fillMaxWidth().height(4.dp)) {
                    androidx.compose.animation.AnimatedVisibility(pageLoading || state.loading, enter = fadeIn(Motion.enter()), exit = fadeOut(Motion.exit())) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
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
                            clearCookies(hosts, domain) { if (browser === this) { cleared = true; loadUrl(start) } }
                        }
                    })
                    popup?.let { window -> AndroidView(modifier = Modifier.fillMaxSize(), factory = { window }) }
                }
            }
        }
    }
}

/** Leave other embedded sign-ins (such as Google for YouTube Music) alone. */
private fun clearCookies(hosts: List<String>, domain: String, onCleared: () -> Unit) {
    val cookies = CookieManager.getInstance()
    val operations = hosts.flatMap { host ->
        cookies.getCookie("https://$host").orEmpty().split(';').mapNotNull { part ->
            part.substringBefore('=').trim().takeIf { it.isNotEmpty() }
        }.flatMap { name ->
            val expired = "$name=; Path=/; Max-Age=0; Secure"
            listOf("https://$host" to expired, "https://$host" to "$expired; Domain=$domain")
        }
    }
    if (operations.isEmpty()) { onCleared(); return }
    var pending = operations.size
    operations.forEach { (url, value) -> cookies.setCookie(url, value) { if (--pending == 0) { cookies.flush(); onCleared() } } }
}
