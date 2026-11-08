package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
import com.aurora.music.ui.theme.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

// grabs token from a fresh iframe localStorage discord scrubs it from the top window but not an iframe
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DiscordLoginScreen(contentPadding: PaddingValues, onBack: () -> Unit, onToken: (String) -> Unit) {
    var manual by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        SettingsTopBar(appString(R.string.text_connect_discord_8794ce), onBack)
        Text(
            appString(R.string.text_log_in_to_discord_below_aurora_reads_your_token_locally_to_set_ri_8ae4f4),
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
                label = { Text(appString(R.string.text_paste_token_3c9cd3)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { if (manual.isNotBlank()) onToken(manual.trim()) }) { Text(appString(R.string.text_link_d05170)) }
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { ctx ->
                WebView(ctx).apply {
                    // wrap_content makes webview resolve 100vh to 0 so discord's whole app collapses to nothing
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    // the real chrome version minus the webview markers so discord serves its normal mobile site
                    settings.userAgentString = settings.userAgentString.replace("; wv", "").replace(Regex(" Version/[\\d.]+"), "")
                    CookieManager.getInstance().setAcceptCookie(true)
                    var captured = false
                    fun capture(token: String) {
                        if (captured) return
                        captured = true
                        // aurora keeps its own copy so the web session's stored token is not left behind
                        WebStorage.getInstance().deleteOrigin(DISCORD_ORIGIN)
                        onToken(token)
                    }
                    // discord navigates inside the page after login so poll storage instead of waiting for a page load
                    val poll = object : Runnable {
                        override fun run() {
                            if (captured || !isAttachedToWindow) return
                            if (url?.startsWith(DISCORD_ORIGIN) == true) readToken(this@apply, ::capture)
                            postDelayed(this, 1500)
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            view.removeCallbacks(poll)
                            view.post(poll)
                        }

                        // signed-in api calls carry the token too in case discord stops keeping it in storage
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val url = request.url
                            if (!captured && url.host == "discord.com" && url.path.orEmpty().startsWith("/api/")) {
                                request.requestHeaders.entries.firstOrNull { it.key.equals("authorization", true) }
                                    ?.value?.trim()?.takeIf { it.isNotBlank() && !it.startsWith("Bot ") }
                                    ?.let { token -> view.post { capture(token) } }
                            }
                            return null
                        }
                    }
                    loadUrl("$DISCORD_ORIGIN/login")
                }
            },
            onRelease = { it.destroy() },
        )
    }
}

private const val DISCORD_ORIGIN = "https://discord.com"

private const val TOKEN_JS =
    "(function(){try{var i=document.createElement('iframe');document.body.appendChild(i);" +
        "var t=i.contentWindow.localStorage.getItem('token');i.remove();return t;}catch(e){return null;}})()"

private fun readToken(view: WebView, onToken: (String) -> Unit) {
    view.evaluateJavascript(TOKEN_JS) { result ->
        result?.trim()
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?.replace("\\\"", "")?.replace("\"", "")
            ?.takeIf { it.isNotBlank() }
            ?.let(onToken)
    }
}
