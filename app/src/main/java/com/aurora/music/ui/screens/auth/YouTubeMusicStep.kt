package com.aurora.music.ui.screens.auth

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.aurora.music.data.remote.YouTubeMusicClient
import com.aurora.music.data.remote.YouTubeMusicLoginRecovery
import com.aurora.music.data.remote.YouTubeMusicWebSession
import com.aurora.music.data.remote.string
import com.aurora.music.viewmodel.AuthUiState
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

@Composable
fun YouTubeMusicStep(state: AuthUiState, onConnect: (String) -> Unit) {
    var browserOpen by remember { mutableStateOf(false) }
    var detailsExpanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("More music. Same Aurora.", style = MaterialTheme.typography.titleMedium)
                Text("Keep your playlists and likes close. Explore recommendations alongside your local and server libraries.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Shape your sound with Aurora’s EQ and audio effects.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("Sign in on Google’s page, then choose your YouTube Music profile. No developer setup is needed.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { browserOpen = true }, enabled = !state.loading,
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) {
            Text("Sign in with Google", style = MaterialTheme.typography.titleSmall)
        }
        TextButton(onClick = { detailsExpanded = !detailsExpanded }) { Text(if (detailsExpanded) "Hide connection details" else "Connection details") }
        androidx.compose.animation.AnimatedVisibility(detailsExpanded) {
            Text("Aurora uses YouTube Music’s unofficial web interface and stores your session encrypted on this device. Streams are lossy; uploaded and account-restricted tracks may not play.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (browserOpen) YouTubeMusicBrowser(state, { browserOpen = false }, onConnect)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun YouTubeMusicBrowser(state: AuthUiState, onClose: () -> Unit, onConnect: (String) -> Unit) {
    var browser by remember { mutableStateOf<WebView?>(null) }
    var pageUrl by remember { mutableStateOf(YouTubeMusicWebSession.LOGIN_URL) }
    var reading by remember { mutableStateOf(false) }
    var pageLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val connect by rememberUpdatedState(onConnect)
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(browser, lifecycleOwner) {
        val view = browser ?: return@LaunchedEffect
        val recovery = YouTubeMusicLoginRecovery()
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            view.onResume()
            while (true) {
                delay(750)
                val cookies = CookieManager.getInstance()
                recovery.destination(view.url, cookies.getCookie("https://accounts.google.com").orEmpty(),
                    cookies.getCookie(YouTubeMusicClient.ORIGIN).orEmpty(), android.os.SystemClock.elapsedRealtime())?.let {
                    view.stopLoading()
                    view.loadUrl(it)
                }
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val previous = browser
            browser = null
            previous?.apply { stopLoading(); clearHistory(); clearCache(true); destroy() }
            clearGoogleSignInCookies()
        }
    }
    Dialog(onDismissRequest = { if (!state.loading) onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false,
            decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onClose, enabled = !state.loading) { Text("Close") }
                    TextButton(onClick = { browser?.reload() }, enabled = !state.loading && !reading) { Text("Reload") }
                }
                Text(if (YouTubeMusicWebSession.isMusicPage(pageUrl)) "Choose your profile, then connect this account."
                    else "Complete Google verification. If the page stops responding, tap Continue to YouTube Music.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                Text(runCatching { java.net.URI(pageUrl).host }.getOrNull().orEmpty(),
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                if (pageLoading || reading || state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                (error ?: state.error)?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
                Button(onClick = {
                    val view = browser ?: return@Button
                    if (!YouTubeMusicWebSession.isMusicPage(view.url)) {
                        error = null
                        view.stopLoading()
                        view.loadUrl(YouTubeMusicWebSession.LOGIN_URL)
                        return@Button
                    }
                    reading = true
                    error = null
                    scope.launch {
                        try {
                            val auth = withTimeoutOrNull(12_000) {
                                var captured: YouTubeMusicWebSession? = null
                                while (captured == null) {
                                    captured = readYouTubeSession(view)
                                    if (captured == null) delay(500)
                                }
                                captured
                            }
                            if (auth == null) error = "Finish signing in and wait for your YouTube Music library, then try again."
                            else connect(auth.encode())
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { error = "Could not read the YouTube Music session. Reload and try again." }
                        finally { reading = false }
                    }
                }, enabled = !state.loading && !reading && browser != null,
                    modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(if (state.loading || reading) "Connecting…"
                        else if (YouTubeMusicWebSession.isMusicPage(pageUrl)) "Connect this account"
                        else "Continue to YouTube Music")
                }
                AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { context ->
                    WebView(context).apply {
                        browser = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        CookieManager.getInstance().setAcceptCookie(true)
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(message: android.webkit.ConsoleMessage?) = true
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                // Google may use regional hosts during the account handoff.
                                if (request.url.scheme == "https") return false
                                if (request.isForMainFrame) error = "This link cannot open here. Finish verification using another method, then tap Continue to YouTube Music."
                                return true
                            }
                            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                                pageLoading = true
                                pageUrl = url.orEmpty()
                                error = null
                            }
                            override fun onPageFinished(view: WebView, url: String?) {
                                pageLoading = false
                                pageUrl = view.url.orEmpty()
                            }
                            override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: android.webkit.WebResourceError) {
                                if (request.isForMainFrame) { pageLoading = false; error = "The sign-in page could not load. Check your connection and reload." }
                            }
                        }
                        // Keep the real WebView user agent, as in Metrolist's sign-in flow.
                        clearGoogleSignInCookies { if (browser === this) loadUrl(YouTubeMusicWebSession.LOGIN_URL) }
                    }
                })
            }
        }
    }
}

private suspend fun readYouTubeSession(view: WebView): YouTubeMusicWebSession? {
    if (!YouTubeMusicWebSession.isMusicPage(view.url)) return null
    val result = withTimeoutOrNull(2_000) {
        suspendCancellableCoroutine<String> { continuation ->
            // Origin is checked inside the page as well as before and after evaluation.
            view.evaluateJavascript("""(function() {
                if (location.origin !== 'https://music.youtube.com') return null;
                function config(key) {
                    return (window.ytcfg && window.ytcfg.get && window.ytcfg.get(key)) ||
                        (window.yt && window.yt.config_ && window.yt.config_[key]) || '';
                }
                return {visitorData: config('VISITOR_DATA'), dataSyncId: config('DATASYNC_ID'),
                    authUser: String(config('SESSION_INDEX') || 0), clientVersion: config('INNERTUBE_CLIENT_VERSION')};
            })()""") { value -> if (continuation.isActive) continuation.resume(value) }
        }
    } ?: return null
    if (!YouTubeMusicWebSession.isMusicPage(view.url)) return null
    return runCatching {
        val config = JsonParser.parseString(result).asJsonObject
        YouTubeMusicWebSession(CookieManager.getInstance().getCookie(YouTubeMusicClient.ORIGIN).orEmpty(),
            config.string("visitorData"), config.string("dataSyncId").substringBefore("||"),
            config.string("authUser"), config.string("clientVersion"), view.settings.userAgentString).validate()
    }.getOrNull()
}

/** Leave other embedded sign-ins (such as Discord) alone. */
private fun clearGoogleSignInCookies(onCleared: () -> Unit = {}) {
    val cookies = CookieManager.getInstance()
    val operations = buildList {
        listOf("accounts.google.com", "google.com", "music.youtube.com", "www.youtube.com", "youtube.com").forEach { host ->
            cookies.getCookie("https://$host").orEmpty().split(';').forEach { part ->
                val name = part.substringBefore('=').trim()
                if (name.isNotEmpty()) {
                    val expired = "$name=; Path=/; Max-Age=0; Secure"
                    add("https://$host" to expired)
                    add("https://$host" to "$expired; Domain=.$host")
                }
            }
        }
    }
    if (operations.isEmpty()) { onCleared(); return }
    var pending = operations.size
    operations.forEach { (url, value) ->
        cookies.setCookie(url, value) { if (--pending == 0) { cookies.flush(); onCleared() } }
    }
}
