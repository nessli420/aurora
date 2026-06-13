package com.aurora.music.ui.screens.settings

import android.annotation.SuppressLint
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Links a Discord account for Rich Presence. Logs in via the WebView and grabs the token from
 * localStorage using the fresh-iframe trick (Discord scrubs it from the top window but not an
 * iframe; same approach Kizzy uses). Fallback: paste a token directly, since Discord's web app
 * sometimes won't render in an Android WebView. Calls [onToken] once.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DiscordLoginScreen(contentPadding: PaddingValues, onBack: () -> Unit, onToken: (String) -> Unit) {
    var manual by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        SettingsTopBar("Connect Discord", onBack)
        Text(
            "Log in to Discord below — Aurora reads your token locally to set Rich Presence (never sent anywhere but Discord). If the page stays blank, paste a token instead.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = manual,
                onValueChange = { manual = it },
                label = { Text("Paste token") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { if (manual.isNotBlank()) onToken(manual.trim()) }) { Text("Link") }
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { ctx ->
                WebView(ctx).apply {
                    // Discord's SPA often renders blank in a hardware-accelerated WebView; software
                    // layer forces it to actually paint.
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.userAgentString =
                        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                            Log.d(TAG, "console: ${m.message()} @${m.sourceId()}:${m.lineNumber()}")
                            return true
                        }
                    }
                    var captured = false
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            Log.d(TAG, "finished $url")
                            if (captured) return
                            if (url != null && (url.contains("/channels") || url.contains("/app"))) {
                                tryGrabToken(view, attemptsLeft = 8) { token ->
                                    if (!captured) { captured = true; onToken(token) }
                                }
                            }
                        }
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            Log.d(TAG, "error ${error.errorCode} ${error.description} url=${request.url} main=${request.isForMainFrame}")
                        }
                    }
                    loadUrl("https://discord.com/login")
                }
            },
        )
    }
}

private const val TAG = "DiscordLogin"

private const val TOKEN_JS =
    "(function(){try{var i=document.createElement('iframe');document.body.appendChild(i);" +
        "var t=i.contentWindow.localStorage.getItem('token');i.remove();return t;}catch(e){return null;}})()"

private fun tryGrabToken(view: WebView, attemptsLeft: Int, onToken: (String) -> Unit) {
    view.evaluateJavascript(TOKEN_JS) { result ->
        val token = result?.trim()
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?.replace("\\\"", "")?.replace("\"", "")
        if (!token.isNullOrBlank()) {
            onToken(token)
        } else if (attemptsLeft > 0) {
            view.postDelayed({ tryGrabToken(view, attemptsLeft - 1, onToken) }, 1500)
        }
    }
}
