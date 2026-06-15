package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.DiscordAccount
import com.aurora.music.data.LastfmAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun IntegrationsSettingsScreen(contentPadding: PaddingValues, onBack: () -> Unit, onOpenDiscordLogin: () -> Unit) {
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    val lrclib by container.settingsStore.lrclibEnabled.collectAsStateWithLifecycle(initialValue = true)
    val artistEnrichment by container.settingsStore.artistEnrichment.collectAsStateWithLifecycle(initialValue = true)
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar("Integrations", onBack)
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {
            item { SettingsSectionTitle("Lyrics") }
            item {
                SettingsGroup {
                    SettingsSwitchRow(Icons.Filled.Lyrics, "LRCLIB lyrics", "Fetch synced lyrics when your server has none", lrclib) { v ->
                        scope.launch { container.settingsStore.setLrclibEnabled(v) }
                    }
                }
            }
            item { SettingsSectionTitle("Scrobbling & presence") }
            item { SettingsGroup { LastfmRow(scope) } }
            item { SettingsGroup { ListenBrainzRow(scope) } }
            item { SettingsGroup { DiscordRow(scope, onOpenDiscordLogin) } }
            item { SettingsSectionTitle("Metadata") }
            item {
                SettingsGroup {
                    SettingsSwitchRow(Icons.Filled.Person, "Artist info", "Show bios & images from MusicBrainz/Wikipedia on artist pages", artistEnrichment) { v ->
                        scope.launch { container.settingsStore.setArtistEnrichment(v) }
                    }
                }
            }
            item { SettingsGroup { AcoustIdRow(scope) } }
        }
    }
}

@Composable
private fun AcoustIdRow(scope: CoroutineScope) {
    val ctx = LocalContext.current
    val container = (ctx.applicationContext as AuroraApplication).container
    val saved by container.settingsStore.acoustIdKey.collectAsStateWithLifecycle(initialValue = "")
    var key by remember(saved) { mutableStateOf(saved) }
    SettingsNavRow(
        Icons.Filled.Fingerprint, "AcoustID",
        subtitle = if (saved.isNotBlank()) "Key set — Auto-identify enabled in tag editor" else "Add your free key from acoustid.org/new-application",
        value = "",
    ) {
        runCatching {
            ctx.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://acoustid.org/new-application"))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
    OutlinedTextField(
        value = key,
        onValueChange = { key = it; scope.launch { container.settingsStore.setAcoustIdKey(it.trim()) } },
        label = { Text("AcoustID API key") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
private fun LastfmRow(scope: CoroutineScope) {
    val ctx = LocalContext.current
    val container = (ctx.applicationContext as AuroraApplication).container
    val acct by container.settingsStore.lastfm.collectAsStateWithLifecycle(initialValue = LastfmAccount())
    var pendingToken by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    when {
        acct.sessionKey.isNotBlank() -> {
            SettingsSwitchRow(Icons.Filled.Headset, "Last.fm", "Scrobbling as ${acct.username}", acct.enabled) { v ->
                scope.launch { container.settingsStore.setLastfmEnabled(v) }
            }
            SettingsRowDivider()
            SettingsNavRow(Icons.Filled.LinkOff, "Disconnect Last.fm", value = "Remove") {
                scope.launch { container.lastfm.disconnect() }
            }
        }
        pendingToken != null -> {
            SettingsNavRow(
                Icons.Filled.Headset, "Finish linking Last.fm",
                subtitle = status ?: "Tap once you've pressed “Allow access” in the browser",
                value = if (busy) "…" else "Done",
            ) {
                if (busy) return@SettingsNavRow
                scope.launch {
                    busy = true; status = null
                    val ok = container.lastfm.finishLink(pendingToken!!)
                    busy = false
                    if (ok) pendingToken = null else status = "Not authorized yet — allow access, then tap again"
                }
            }
        }
        else -> {
            // user supplies own last.fm credentials none shipped
            val keys by container.settingsStore.lastfmKeys.collectAsStateWithLifecycle(initialValue = "" to "")
            var apiKey by remember(keys.first) { mutableStateOf(keys.first) }
            var secret by remember(keys.second) { mutableStateOf(keys.second) }
            OutlinedTextField(
                value = apiKey, onValueChange = { apiKey = it; scope.launch { container.settingsStore.setLastfmKeys(it, secret) } },
                label = { Text("Last.fm API key") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
            )
            OutlinedTextField(
                value = secret, onValueChange = { secret = it; scope.launch { container.settingsStore.setLastfmKeys(apiKey, it) } },
                label = { Text("Last.fm shared secret") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
            )
            SettingsNavRow(
                Icons.Filled.Headset, "Connect Last.fm",
                subtitle = status ?: if (container.lastfm.configured) "Scrobble your plays to Last.fm" else "Create a free API account at last.fm/api, then paste the key + secret above",
                value = if (busy) "…" else "Connect",
            ) {
                if (busy || !container.lastfm.configured) return@SettingsNavRow
                scope.launch {
                    busy = true; status = null
                    val token = container.lastfm.beginLink()
                    busy = false
                    if (token == null) { status = "Couldn't reach Last.fm"; return@launch }
                    pendingToken = token
                    runCatching {
                        ctx.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(container.lastfm.authorizeUrl(token)))
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ListenBrainzRow(scope: CoroutineScope) {
    val ctx = LocalContext.current
    val container = (ctx.applicationContext as AuroraApplication).container
    val acct by container.settingsStore.listenBrainz.collectAsStateWithLifecycle(initialValue = com.aurora.music.data.ListenBrainzAccount())
    if (acct.token.isNotBlank()) {
        SettingsSwitchRow(Icons.Filled.Album, "ListenBrainz", "Scrobbling as ${acct.username}", acct.enabled) { v ->
            scope.launch { container.settingsStore.setListenBrainzEnabled(v) }
        }
        SettingsRowDivider()
        SettingsNavRow(Icons.Filled.LinkOff, "Disconnect ListenBrainz", value = "Remove") {
            scope.launch { container.listenBrainz.disconnect() }
        }
    } else {
        var token by remember { mutableStateOf("") }
        var status by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = token,
            onValueChange = { token = it; status = null },
            label = { Text("ListenBrainz user token") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        )
        SettingsNavRow(
            Icons.Filled.Album, "Connect ListenBrainz",
            subtitle = status ?: "Paste your token from listenbrainz.org/profile",
            value = if (busy) "…" else "Connect",
        ) {
            if (busy || token.isBlank()) return@SettingsNavRow
            scope.launch {
                busy = true; status = null
                val ok = container.listenBrainz.connect(token)
                busy = false
                status = if (ok) null else "Invalid token — check and try again"
            }
        }
    }
}

@Composable
private fun DiscordRow(scope: CoroutineScope, onConnect: () -> Unit) {
    val ctx = LocalContext.current
    val container = (ctx.applicationContext as AuroraApplication).container
    val acct by container.settingsStore.discord.collectAsStateWithLifecycle(initialValue = DiscordAccount())

    if (acct.token.isNotBlank()) {
        SettingsSwitchRow(
            Icons.Filled.Forum, "Discord presence",
            if (acct.username.isNotBlank()) "Connected as ${acct.username}" else "Connected",
            acct.enabled,
        ) { v -> scope.launch { container.settingsStore.setDiscordEnabled(v) } }
        var appId by remember { mutableStateOf(acct.appId) }
        OutlinedTextField(
            value = appId,
            onValueChange = { appId = it; scope.launch { container.settingsStore.setDiscordAppId(it.trim()) } },
            label = { Text("Discord application ID — for album art (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        )
        var imgur by remember { mutableStateOf(acct.imgurClientId) }
        OutlinedTextField(
            value = imgur,
            onValueChange = { imgur = it; scope.launch { container.settingsStore.setDiscordImgur(it.trim()) } },
            label = { Text("Imgur client ID — for album art (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        )
        SettingsRowDivider()
        SettingsNavRow(Icons.Filled.LinkOff, "Disconnect Discord", value = "Remove") {
            scope.launch { container.settingsStore.clearDiscord() }
        }
    } else {
        SettingsNavRow(Icons.Filled.Forum, "Connect Discord", "Show your now playing in Discord (create a free app at discord.com/developers for album art)", value = "Connect", onClick = onConnect)
    }
}
