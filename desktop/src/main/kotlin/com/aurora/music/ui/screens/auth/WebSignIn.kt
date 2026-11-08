package com.aurora.music.ui.screens.auth

import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import com.aurora.music.ui.theme.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.aurora.music.ui.theme.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.aurora.music.ui.theme.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aurora.music.data.SpotifyWebBackend
import com.aurora.music.data.SpotifyWebProfile
import com.aurora.music.data.remote.SoundCloudWebSession
import com.aurora.music.data.remote.YouTubeMusicClient
import com.aurora.music.data.remote.YouTubeMusicLoginRecovery
import com.aurora.music.data.remote.YouTubeMusicWebSession
import com.aurora.music.desktop.web.Chromium
import com.aurora.music.desktop.web.SoundCloudWebWriter
import com.aurora.music.desktop.web.SpotifyOperations
import com.aurora.music.desktop.web.SpotifyPage
import com.aurora.music.desktop.web.SpotifyWebPlayer
import com.aurora.music.desktop.web.WebPage
import com.aurora.music.viewmodel.AuthUiState
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private val GOOGLE_HOSTS = listOf("accounts.google.com", "google.com", "music.youtube.com", "www.youtube.com", "youtube.com")
private val SOUNDCLOUD_HOSTS = listOf("soundcloud.com", "m.soundcloud.com", "secure.soundcloud.com", "api-auth.soundcloud.com")

// the window every embedded sign-in uses; chromium downloads on first use, so its progress shows here.
// a plain swing dialog hosts the browser: compose's own windows keep reclaiming the keyboard from it
@Composable
private fun BrowserWindow(
    title: String,
    page: WebPage?,
    loading: Boolean,
    error: String?,
    onClose: () -> Unit,
    closeEnabled: Boolean = true,
    onReload: (() -> Unit)? = null,
    header: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val shapes = MaterialTheme.shapes
    val chrome = rememberUpdatedState(BrowserChrome(page, loading, error, onClose, closeEnabled, onReload, header))
    val dialog = remember {
        val owner = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
        javax.swing.JDialog(owner, title, java.awt.Dialog.ModalityType.MODELESS)
    }
    val center = remember { javax.swing.JPanel(java.awt.BorderLayout()) }
    DisposableEffect(Unit) {
        fun themed(content: @Composable () -> Unit): @Composable () -> Unit = {
            MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes) { content() }
        }
        val top = androidx.compose.ui.awt.ComposePanel()
        top.preferredSize = java.awt.Dimension(560, 120)
        top.setContent(themed {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).onSizeChanged { size ->
                        val height = size.height + 1
                        if (top.preferredSize?.height != height) {
                            top.preferredSize = java.awt.Dimension(top.width.coerceAtLeast(1), height)
                            dialog.contentPane.revalidate()
                        }
                    }) { chrome.value.Header(this) }
                }
            }
        })
        val status = androidx.compose.ui.awt.ComposePanel()
        status.setContent(themed {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ChromiumStatus(Chromium.status.collectAsState().value) }
            }
        })
        center.add(status, java.awt.BorderLayout.CENTER)
        dialog.apply {
            defaultCloseOperation = javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE
            addWindowListener(object : java.awt.event.WindowAdapter() {
                override fun windowClosing(e: java.awt.event.WindowEvent) {
                    if (chrome.value.closeEnabled) chrome.value.onClose()
                }
            })
            contentPane.layout = java.awt.BorderLayout()
            contentPane.add(top, java.awt.BorderLayout.NORTH)
            contentPane.add(center, java.awt.BorderLayout.CENTER)
            setSize(560, 820)
            setLocationRelativeTo(owner)
            isVisible = true
        }
        onDispose { dialog.dispose() }
    }
    LaunchedEffect(page) {
        val browser = page?.browser ?: return@LaunchedEffect
        center.removeAll()
        center.add(browser.uiComponent, java.awt.BorderLayout.CENTER)
        center.revalidate()
        center.repaint()
    }
    val popup by (page?.popup ?: remember { kotlinx.coroutines.flow.MutableStateFlow<WebPage?>(null) }).collectAsState()
    popup?.let { opened -> key(opened) { PopupWindow(title, opened) } }
}

// "continue with google" and the like open a window of their own; it lives until it closes itself or the user does
@Composable
private fun PopupWindow(title: String, page: WebPage) {
    val loading by page.loading.collectAsState()
    BrowserWindow(title = title, page = page, loading = loading, error = null, onClose = page::close)
}

private class BrowserChrome(
    val page: WebPage?,
    val loading: Boolean,
    val error: String?,
    val onClose: () -> Unit,
    val closeEnabled: Boolean,
    val onReload: (() -> Unit)?,
    val header: @Composable ColumnScope.() -> Unit,
) {
    @Composable
    fun Header(scope: ColumnScope) = with(scope) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onClose, enabled = closeEnabled) { Text(appString(R.string.text_close_bbfa77)) }
            if (onReload != null) TextButton(onClick = onReload, enabled = page != null && closeEnabled) { Text(appString(R.string.text_reload_cce715)) }
        }
        header()
        if (loading || page == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun ChromiumStatus(status: Chromium.Status) {
    Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when (status) {
            is Chromium.Status.Failed -> Text(appString(R.string.chromium_failed, status.message), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium)
            is Chromium.Status.Preparing -> {
                if (status.downloading && status.fraction >= 0f) LinearProgressIndicator(progress = { status.fraction }, modifier = Modifier.width(260.dp))
                else CircularProgressIndicator()
                Text(appString(if (status.downloading) R.string.chromium_downloading else R.string.chromium_preparing),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> CircularProgressIndicator()
        }
    }
}

// opens a page once chromium is ready and closes it with the window; a shown browser only exists natively
// once its window does, so it starts on its first address instead of being sent there before it's shown
@Composable
private fun rememberPage(origins: Set<String>, prepare: suspend () -> Unit, start: String, onOpened: (WebPage) -> Unit = {}): WebPage? {
    var page by remember { mutableStateOf<WebPage?>(null) }
    LaunchedEffect(Unit) {
        val opened = runCatching {
            Chromium.client()
            prepare()
            Chromium.open(start, hidden = false, origins = origins)
        }.onFailure { com.aurora.music.util.AppLog.w("WebSignIn", "sign-in page could not open", it) }.getOrNull() ?: return@LaunchedEffect
        onOpened(opened)
        page = opened
    }
    DisposableEffect(Unit) { onDispose { page?.close() } }
    return page
}

@Composable
fun YouTubeMusicStep(state: AuthUiState, onConnect: (String) -> Unit, onGuest: () -> Unit) {
    var browserOpen by remember { mutableStateOf(false) }
    var detailsExpanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(appString(R.string.text_sign_in_on_google_s_page_then_choose_your_youtube_music_profile_n_0b2a03),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { browserOpen = true }, enabled = !state.loading,
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = com.aurora.music.ui.theme.paperSquare(RoundedCornerShape(16.dp))) {
            Text(appString(R.string.text_sign_in_with_google_4a0b72), style = MaterialTheme.typography.titleSmall)
        }
        GuestOption(!state.loading, onGuest)
        TextButton(onClick = { detailsExpanded = !detailsExpanded }) {
            Text(if (detailsExpanded) appString(R.string.text_hide_connection_details_64711a) else appString(R.string.text_connection_details_ab30a5))
        }
        AnimatedVisibility(detailsExpanded) {
            Text(appString(R.string.text_aurora_uses_youtube_music_s_unofficial_web_interface_and_stores_y_28fcb3),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (browserOpen) YouTubeMusicBrowser(state, { browserOpen = false }, onConnect)
}

@Composable
private fun YouTubeMusicBrowser(state: AuthUiState, onClose: () -> Unit, onConnect: (String) -> Unit) {
    val page = rememberPage(setOf(YouTubeMusicClient.ORIGIN), prepare = { Chromium.clearCookies(GOOGLE_HOSTS) }, start = YouTubeMusicWebSession.LOGIN_URL)
    val url by (page?.url ?: remember { kotlinx.coroutines.flow.MutableStateFlow("") }).collectAsState()
    val pageLoading by (page?.loading ?: remember { kotlinx.coroutines.flow.MutableStateFlow(true) }).collectAsState()
    val failed by (page?.failed ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsState()
    var reading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val connect by rememberUpdatedState(onConnect)
    LaunchedEffect(page) {
        val view = page ?: return@LaunchedEffect
        val recovery = YouTubeMusicLoginRecovery()
        val started = System.currentTimeMillis()
        while (true) {
            delay(750)
            recovery.destination(view.url.value, Chromium.cookieHeader("https://accounts.google.com"),
                Chromium.cookieHeader(YouTubeMusicClient.ORIGIN), System.currentTimeMillis() - started)?.let { view.load(it) }
        }
    }
    val musicPage = YouTubeMusicWebSession.isMusicPage(url)
    BrowserWindow(
        title = "YouTube Music", page = page, loading = pageLoading || reading || state.loading,
        error = error ?: state.error ?: if (failed) appString(R.string.text_the_sign_in_page_could_not_load_check_your_connection_and_reload_a45e09) else null,
        onClose = onClose, closeEnabled = !state.loading, onReload = { page?.reload() },
    ) {
        Text(if (musicPage) appString(R.string.text_choose_your_profile_then_connect_this_account_dd6fe5)
            else appString(R.string.text_complete_google_verification_if_the_page_stops_responding_tap_con_a561de),
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
        Button(onClick = {
            val view = page ?: return@Button
            if (!YouTubeMusicWebSession.isMusicPage(view.url.value)) {
                error = null
                view.load(YouTubeMusicWebSession.LOGIN_URL)
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
                    if (auth == null) error = appString(R.string.text_finish_signing_in_and_wait_for_your_youtube_music_library_then_tr_c7f0dd)
                    else connect(auth.encode())
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    error = appString(R.string.text_could_not_read_the_youtube_music_session_reload_and_try_again_49d60c)
                } finally {
                    reading = false
                }
            }
        }, enabled = !state.loading && !reading && page != null, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(if (state.loading || reading) appString(R.string.text_connecting_fd3e79)
                else if (musicPage) appString(R.string.text_connect_this_account_fd5e5e)
                else appString(R.string.text_continue_to_youtube_music_7f9fcb))
        }
    }
}

private suspend fun readYouTubeSession(view: WebPage): YouTubeMusicWebSession? {
    if (!YouTubeMusicWebSession.isMusicPage(view.url.value)) return null
    val result = view.eval("""(function() {
        if (location.origin !== 'https://music.youtube.com') return null;
        function config(key) {
            return (window.ytcfg && window.ytcfg.get && window.ytcfg.get(key)) ||
                (window.yt && window.yt.config_ && window.yt.config_[key]) || '';
        }
        return {visitorData: config('VISITOR_DATA'), dataSyncId: config('DATASYNC_ID'),
            pageId: config('DELEGATED_SESSION_ID'), authUser: String(config('SESSION_INDEX') || 0),
            clientVersion: config('INNERTUBE_CLIENT_VERSION'), userAgent: navigator.userAgent};
    })()""") ?: return null
    if (!YouTubeMusicWebSession.isMusicPage(view.url.value)) return null
    return runCatching {
        val config = JsonParser.parseString(result).asJsonObject
        fun string(name: String) = config.get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        YouTubeMusicWebSession(Chromium.cookieHeader(YouTubeMusicClient.ORIGIN), string("visitorData"),
            string("dataSyncId").substringBefore("||"), string("authUser"), string("clientVersion"), string("userAgent"),
            string("pageId")).validate()
    }.getOrNull()
}

@Composable
fun SpotifyWebStep(state: AuthUiState, operations: SpotifyOperations, onConnect: (cookie: String, profile: SpotifyWebProfile?) -> Unit) {
    var browserOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(appString(R.string.spotify_web_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { browserOpen = true }, enabled = !state.loading,
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = com.aurora.music.ui.theme.paperSquare(RoundedCornerShape(16.dp))) {
            Text(appString(R.string.spotify_web_login), style = MaterialTheme.typography.titleSmall)
        }
        Text(appString(R.string.spotify_web_login_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (browserOpen) SpotifyLoginBrowser(state, operations, onClose = { browserOpen = false }) { cookie, profile ->
        browserOpen = false
        onConnect(cookie, profile)
    }
}

@Composable
private fun SpotifyLoginBrowser(state: AuthUiState, operations: SpotifyOperations, onClose: () -> Unit, onCaptured: (String, SpotifyWebProfile?) -> Unit) {
    val scope = rememberCoroutineScope()
    var reading by remember { mutableStateOf(false) }
    val captured by rememberUpdatedState(onCaptured)
    // the profile comes from the signed-in page itself since the public api refuses web player tokens
    val page = rememberPage(setOf(SpotifyWebPlayer.ORIGIN), prepare = { SpotifyWebPlayer.clearCookies() }, start = SpotifyWebPlayer.LOGIN_URL) { opened ->
        val spotify = SpotifyPage(opened, operations)
        var done = false
        spotify.onSignedIn = {
            if (!done) {
                done = true
                reading = true
                scope.launch {
                    val profile = runCatching { SpotifyWebBackend.profile(spotify) }.getOrNull()
                    captured(SpotifyWebPlayer.sessionCookie(), profile)
                }
            }
        }
    }
    val pageLoading by (page?.loading ?: remember { kotlinx.coroutines.flow.MutableStateFlow(true) }).collectAsState()
    val failed by (page?.failed ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsState()
    BrowserWindow(
        title = "Spotify", page = page, loading = pageLoading || reading || state.loading,
        error = state.error ?: if (failed) appString(R.string.text_the_sign_in_page_could_not_load_check_your_connection_and_reload_a45e09) else null,
        onClose = onClose, onReload = { page?.reload() },
    ) {
        Text(appString(R.string.spotify_web_browser_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
    }
}

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
        TextButton(onClick = { detailsExpanded = !detailsExpanded }) {
            Text(if (detailsExpanded) appString(R.string.text_hide_connection_details_64711a) else appString(R.string.text_connection_details_ab30a5))
        }
        AnimatedVisibility(detailsExpanded) {
            Text(appString(R.string.soundcloud_details), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (browserOpen) SoundCloudBrowserWindow(state, { browserOpen = false }, onConnect)
}

@Composable
private fun GuestOption(enabled: Boolean, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onClick, enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = com.aurora.music.ui.theme.paperSquare(RoundedCornerShape(16.dp))) {
            Text(appString(R.string.guest_continue), style = MaterialTheme.typography.titleSmall)
        }
        Text(appString(R.string.guest_detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SoundCloudBrowserWindow(state: AuthUiState, onClose: () -> Unit, onConnect: (String) -> Unit) {
    val page = rememberPage(SoundCloudWebSession.ORIGINS.toSet(), prepare = { Chromium.clearCookies(SOUNDCLOUD_HOSTS) }, start = SoundCloudWebSession.LOGIN_URL)
    val pageLoading by (page?.loading ?: remember { kotlinx.coroutines.flow.MutableStateFlow(true) }).collectAsState()
    val failed by (page?.failed ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsState()
    val connect by rememberUpdatedState(onConnect)
    val connecting by rememberUpdatedState(state.loading)
    LaunchedEffect(page) {
        val view = page ?: return@LaunchedEffect
        var sent = ""
        // the mobile site keeps its login in its own host cookies, so read whichever site signed in
        suspend fun signedIn(agent: String) = SoundCloudWebSession.ORIGINS.map { SoundCloudWebSession(Chromium.cookieHeader(it), agent, it) }
            .firstOrNull { it.token.isNotBlank() }
        while (true) {
            delay(750)
            val agent = runCatching { JsonParser.parseString(view.eval("navigator.userAgent") ?: "null").asString }.getOrNull().orEmpty()
            val token = signedIn(agent)?.token
            if (token == null || token == sent || connecting) continue
            // the refresh cookie lands with the token; give it a moment before reading the jar
            delay(1_000)
            val session = signedIn(agent) ?: continue
            sent = session.token
            connect(session.encode())
        }
    }
    BrowserWindow(
        title = "SoundCloud", page = page, loading = pageLoading || state.loading,
        error = state.error ?: if (failed) appString(R.string.soundcloud_page_failed) else null,
        onClose = onClose, closeEnabled = !state.loading, onReload = { page?.reload() },
    ) {
        Text(appString(R.string.soundcloud_waiting), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    }
}

/** SoundCloud asks for a captcha before some changes; the user solves it once in a window of its own. */
@Composable
fun SoundCloudCaptchaWindow(writer: SoundCloudWebWriter) {
    val request by writer.captcha.collectAsState()
    val pending = request ?: return
    val page = rememberPage(setOf(pending.origin), prepare = {}, start = "${pending.origin}/") { opened ->
        opened.startScript = opened.bridgeScript(SoundCloudWebWriter.BRIDGE)
        opened.onMessage = { bridge, data -> if (bridge == SoundCloudWebWriter.BRIDGE && "\"captcha\"" in data) writer.captchaSolved(true) }
    }
    val pageLoading by (page?.loading ?: remember { kotlinx.coroutines.flow.MutableStateFlow(true) }).collectAsState()
    LaunchedEffect(page, pageLoading) {
        if (page != null && !pageLoading) page.run(SoundCloudWebWriter.captchaScript(pending.url))
    }
    BrowserWindow(title = "SoundCloud", page = page, loading = pageLoading, error = null, onClose = { writer.captchaSolved(false) }) {
        Text(appString(R.string.soundcloud_captcha_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    }
}

private val DISCORD_HOSTS = listOf("discord.com", "www.discord.com")
private const val DISCORD_ORIGIN = "https://discord.com"

// discord scrubs its token from the top window once the app loads, but a fresh iframe's storage still has it
private const val DISCORD_TOKEN_JS = "(function(){try{var i=document.createElement('iframe');document.body.appendChild(i);" +
    "var t=i.contentWindow.localStorage.getItem('token');i.remove();return t;}catch(e){return null;}})()"

/** Discord's own login page; the token it stores is read locally and its web session is cleared straight after. */
@Composable
fun DiscordLoginWindow(onClose: () -> Unit, onToken: (String) -> Unit) {
    val page = rememberPage(setOf(DISCORD_ORIGIN), prepare = { Chromium.clearCookies(DISCORD_HOSTS) }, start = "$DISCORD_ORIGIN/login")
    val pageLoading by (page?.loading ?: remember { kotlinx.coroutines.flow.MutableStateFlow(true) }).collectAsState()
    val capture by rememberUpdatedState(onToken)
    var manual by remember { mutableStateOf("") }
    LaunchedEffect(page) {
        val view = page ?: return@LaunchedEffect
        while (true) {
            delay(1_500)
            if (view.origin != DISCORD_ORIGIN) continue
            val token = runCatching { JsonParser.parseString(view.eval(DISCORD_TOKEN_JS) ?: "null") }.getOrNull()
                ?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotBlank() } ?: continue
            runCatching { view.eval("(function(){ localStorage.clear(); sessionStorage.clear(); return true; })()") }
            Chromium.clearCookies(DISCORD_HOSTS)
            capture(token)
            break
        }
    }
    BrowserWindow(title = "Discord", page = page, loading = pageLoading, error = null, onClose = onClose) {
        Text(appString(R.string.text_log_in_to_discord_below_aurora_reads_your_token_locally_to_set_ri_8ae4f4), style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.OutlinedTextField(manual, { manual = it }, label = { Text(appString(R.string.text_paste_token_3c9cd3)) },
                singleLine = true, modifier = Modifier.weight(1f))
            TextButton(onClick = { if (manual.isNotBlank()) capture(manual.trim()) }) { Text(appString(R.string.text_link_d05170)) }
        }
    }
}
