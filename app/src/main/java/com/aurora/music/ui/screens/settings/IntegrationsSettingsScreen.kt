package com.aurora.music.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun IntegrationsSettingsScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenLyrics: () -> Unit,
    onOpenLastfm: () -> Unit,
    onOpenListenBrainz: () -> Unit,
    onOpenDiscord: () -> Unit,
    onOpenArtistInfo: () -> Unit,
    onOpenAcoustId: () -> Unit,
) {
    val store = appContainer().settingsStore
    val lrclib by store.lrclibEnabled.collectAsStateWithLifecycle(initialValue = true)
    val artistInfo by store.artistEnrichment.collectAsStateWithLifecycle(initialValue = true)
    val acoustId by store.acoustIdKey.collectAsStateWithLifecycle(initialValue = "")
    val lastfm by store.lastfm.collectAsStateWithLifecycle(initialValue = LastfmAccount())
    val listenBrainz by store.listenBrainz.collectAsStateWithLifecycle(initialValue = ListenBrainzAccount())
    val discord by store.discord.collectAsStateWithLifecycle(initialValue = DiscordAccount())

    SettingsPage("Integrations", contentPadding, onBack) {
        item { SettingsSectionTitle("Listening & sharing") }
        item {
            SettingsGroup {
                IntegrationRow(Icons.Filled.Headset, "Last.fm", "Scrobble your listening history", connectionLabel(lastfm.sessionKey, lastfm.enabled, lastfm.username), onOpenLastfm)
                SettingsRowDivider()
                IntegrationRow(Icons.Filled.Album, "ListenBrainz", "Send listens to your open music profile", connectionLabel(listenBrainz.token, listenBrainz.enabled, listenBrainz.username), onOpenListenBrainz)
                SettingsRowDivider()
                IntegrationRow(Icons.Filled.Forum, "Discord", "Share your current track and artwork", connectionLabel(discord.token, discord.enabled, discord.username), onOpenDiscord)
            }
        }
        item { SettingsSectionTitle("Lyrics & metadata") }
        item {
            SettingsGroup {
                IntegrationRow(Icons.Filled.Lyrics, "LRCLIB", "Synced lyrics when your server has none", if (lrclib) "On" else "Off", onOpenLyrics)
                SettingsRowDivider()
                IntegrationRow(Icons.Filled.Person, "Artist information", "MusicBrainz and Wikipedia biographies", if (artistInfo) "On" else "Off", onOpenArtistInfo)
                SettingsRowDivider()
                IntegrationRow(Icons.Filled.Fingerprint, "AcoustID", "Identify tracks from their audio", if (acoustId.isBlank()) "Not set" else "Ready", onOpenAcoustId)
            }
        }
        item {
            Text(
                "Connections are optional. Aurora’s own library, playback history and recaps work without them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            )
        }
    }
}

@Composable
private fun IntegrationRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, status: String, onClick: () -> Unit) {
    SettingsNavRow(icon, title, subtitle, status, onClick)
}

private fun connectionLabel(token: String, enabled: Boolean, username: String): String = when {
    token.isBlank() -> "Not connected"
    !enabled -> "Paused"
    username.isNotBlank() -> username
    else -> "Connected"
}

@Composable
fun LyricsIntegrationScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val container = appContainer()
    val enabled by container.settingsStore.lrclibEnabled.collectAsStateWithLifecycle(initialValue = true)
    val scope = rememberCoroutineScope()
    SettingsPage("LRCLIB lyrics", contentPadding, onBack) {
        item { SettingsGroup { SettingsSwitchRow(Icons.Filled.Lyrics, "Use LRCLIB", "Fetch lyrics from the public LRCLIB service", enabled) { scope.launch { container.settingsStore.setLrclibEnabled(it) } } } }
        item { PrivacyNote("Track title, artist, album and duration may be sent to LRCLIB to find a match.") }
    }
}

@Composable
fun ArtistInfoIntegrationScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val container = appContainer()
    val enabled by container.settingsStore.artistEnrichment.collectAsStateWithLifecycle(initialValue = true)
    val scope = rememberCoroutineScope()
    SettingsPage("Artist information", contentPadding, onBack) {
        item { SettingsGroup { SettingsSwitchRow(Icons.Filled.Person, "Artist information", "Look up biographies and images", enabled) { scope.launch { container.settingsStore.setArtistEnrichment(it) } } } }
        item { PrivacyNote("Artist names are sent to the metadata providers only when Aurora needs the extra information.") }
    }
}

@Composable
fun AcoustIdIntegrationScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val container = appContainer()
    val saved by container.settingsStore.acoustIdKey.collectAsStateWithLifecycle(initialValue = "")
    var key by remember(saved) { mutableStateOf(saved) }
    val scope = rememberCoroutineScope()
    SettingsPage("AcoustID", contentPadding, onBack) {
        item {
            SettingsGroup {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it; scope.launch { container.settingsStore.setAcoustIdKey(it.trim()) } },
                    label = { Text("Application API key") },
                    supportingText = { Text(if (saved.isBlank()) "Required for audio identification" else "Key saved") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
                SettingsRowDivider()
                SettingsNavRow(Icons.AutoMirrored.Filled.OpenInNew, "Create a free AcoustID key", "Opens acoustid.org in your browser") { openUrl(ctx, "https://acoustid.org/new-application") }
            }
        }
    }
}

@Composable
fun LastfmIntegrationScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val container = appContainer()
    val store = container.settingsStore
    val acct by store.lastfm.collectAsStateWithLifecycle(initialValue = LastfmAccount())
    val rules by store.lastfmArtistRules.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var pendingToken by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var editingRule by remember { mutableStateOf<Int?>(null) }
    var addingRule by remember { mutableStateOf(false) }

    SettingsPage("Last.fm", contentPadding, onBack) {
        item { SettingsSectionTitle("Account") }
        item { SettingsGroup { LastfmAccountControls(acct, scope, pendingToken, busy, status, { pendingToken = it }, { busy = it }, { status = it }) } }
        item { SettingsSectionTitle("Artist replacement rules") }
        item { Text("Replace an exact artist credit before Last.fm receives it. Matching ignores capitalization.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
        item {
            SettingsGroup {
                if (rules.isEmpty()) SettingsNavRow(Icons.Filled.SwapHoriz, "No replacement rules", "Example: Ye → Kanye West", value = "Add") { addingRule = true }
                else {
                    rules.forEachIndexed { index, rule ->
                        SettingsNavRow(Icons.Filled.SwapHoriz, rule.sourceArtist.orEmpty(), "Scrobble as ${rule.replacementArtist.orEmpty()}") { editingRule = index }
                        if (index != rules.lastIndex) SettingsRowDivider()
                    }
                    SettingsRowDivider()
                    SettingsNavRow(Icons.Filled.Add, "Add artist replacement", "Use the replacement only for an exact artist match") { addingRule = true }
                }
            }
        }
    }
    val target = editingRule
    if (addingRule || target != null) ArtistRuleDialog(
        initial = target?.let(rules::getOrNull),
        onDismiss = { addingRule = false; editingRule = null },
        onSave = { rule ->
            val updated = rules.toMutableList()
            if (target == null) updated += rule else if (target in updated.indices) updated[target] = rule
            scope.launch { store.setLastfmArtistRules(updated) }
            addingRule = false
            editingRule = null
        },
        onDelete = target?.let { index -> { scope.launch { store.setLastfmArtistRules(rules.filterIndexed { i, _ -> i != index }) }; editingRule = null } },
    )
}

@Composable
private fun LastfmAccountControls(
    acct: LastfmAccount,
    scope: CoroutineScope,
    pendingToken: String?,
    busy: Boolean,
    status: String?,
    onPending: (String?) -> Unit,
    onBusy: (Boolean) -> Unit,
    onStatus: (String?) -> Unit,
) {
    val ctx = LocalContext.current
    val container = appContainer()
    if (acct.sessionKey.isNotBlank()) {
        SettingsSwitchRow(Icons.Filled.Headset, "Scrobbling", "Connected as ${acct.username}", acct.enabled) { scope.launch { container.settingsStore.setLastfmEnabled(it) } }
        SettingsRowDivider()
        SettingsNavRow(Icons.Filled.LinkOff, "Disconnect Last.fm", "Replacement rules will be kept", value = "Remove") { scope.launch { container.lastfm.disconnect() } }
        return
    }
    val keys by container.settingsStore.lastfmKeys.collectAsStateWithLifecycle(initialValue = "" to "")
    var apiKey by remember(keys.first) { mutableStateOf(keys.first) }
    var secret by remember(keys.second) { mutableStateOf(keys.second) }
    OutlinedTextField(apiKey, { apiKey = it; scope.launch { container.settingsStore.setLastfmKeys(it, secret) } }, label = { Text("API key") }, singleLine = true, modifier = fieldModifier())
    OutlinedTextField(secret, { secret = it; scope.launch { container.settingsStore.setLastfmKeys(apiKey, it) } }, label = { Text("Shared secret") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = fieldModifier())
    if (pendingToken == null) {
        SettingsNavRow(Icons.Filled.Headset, "Connect Last.fm", status ?: "Create an API account, then link your profile", if (busy) "…" else "Connect") {
            if (busy || !container.lastfm.configured) return@SettingsNavRow
            scope.launch {
                onBusy(true); onStatus(null)
                val token = container.lastfm.beginLink()
                onBusy(false)
                if (token == null) onStatus("Couldn’t reach Last.fm. Check your keys and connection.")
                else { onPending(token); openUrl(ctx, container.lastfm.authorizeUrl(token)) }
            }
        }
    } else {
        SettingsNavRow(Icons.Filled.Check, "Finish linking", status ?: "Allow access in the browser, then return here", if (busy) "…" else "Done") {
            if (busy) return@SettingsNavRow
            scope.launch {
                onBusy(true); onStatus(null)
                val ok = container.lastfm.finishLink(pendingToken)
                onBusy(false)
                if (ok) onPending(null) else onStatus("Authorization is not ready. Allow access, then try again.")
            }
        }
    }
}

@Composable
fun ListenBrainzIntegrationScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val container = appContainer()
    val acct by container.settingsStore.listenBrainz.collectAsStateWithLifecycle(initialValue = ListenBrainzAccount())
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    SettingsPage("ListenBrainz", contentPadding, onBack) {
        item {
            SettingsGroup {
                if (acct.token.isNotBlank()) {
                    SettingsSwitchRow(Icons.Filled.Album, "Scrobbling", "Connected as ${acct.username}", acct.enabled) { scope.launch { container.settingsStore.setListenBrainzEnabled(it) } }
                    SettingsRowDivider()
                    SettingsNavRow(Icons.Filled.LinkOff, "Disconnect ListenBrainz", value = "Remove") { scope.launch { container.listenBrainz.disconnect() } }
                } else {
                    OutlinedTextField(token, { token = it; status = null }, label = { Text("User token") }, supportingText = { Text(status ?: "Copy this from your ListenBrainz profile") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = fieldModifier())
                    SettingsNavRow(Icons.Filled.Link, "Connect ListenBrainz", value = if (busy) "…" else "Connect") {
                        if (busy || token.isBlank()) return@SettingsNavRow
                        scope.launch { busy = true; val ok = container.listenBrainz.connect(token); busy = false; status = if (ok) null else "That token could not be verified. Check it and try again." }
                    }
                }
            }
        }
    }
}

@Composable
fun DiscordIntegrationScreen(contentPadding: PaddingValues, onBack: () -> Unit, onOpenDiscordLogin: () -> Unit) {
    val container = appContainer()
    val acct by container.settingsStore.discord.collectAsStateWithLifecycle(initialValue = DiscordAccount())
    val scope = rememberCoroutineScope()
    SettingsPage("Discord", contentPadding, onBack) {
        item { DiscordPreview(acct) }
        item { SettingsSectionTitle("Presence") }
        item {
            SettingsGroup {
                if (acct.token.isBlank()) SettingsNavRow(Icons.Filled.Forum, "Connect Discord", "Authorize your Discord account", "Connect", onOpenDiscordLogin)
                else {
                    SettingsSwitchRow(Icons.Filled.Forum, "Discord presence", if (acct.username.isBlank()) "Connected" else "Connected as ${acct.username}", acct.enabled) { scope.launch { container.settingsStore.setDiscordEnabled(it) } }
                    SettingsRowDivider()
                    SettingsSwitchRow(Icons.Filled.Album, "Show album", "Include the album and artwork caption", acct.showAlbum) { scope.launch { container.settingsStore.setDiscordShowAlbum(it) } }
                    SegmentedRow("Activity name", listOf("Aurora", "Artist", "Song"), listOf("aurora", "artist", "song").indexOf(acct.activityName).coerceAtLeast(0)) { scope.launch { container.settingsStore.setDiscordActivityName(listOf("aurora", "artist", "song")[it]) } }
                }
            }
        }
        if (acct.token.isNotBlank()) {
            item { SettingsSectionTitle("Artwork") }
            item {
                SettingsGroup {
                    var appId by remember(acct.appId) { mutableStateOf(acct.appId) }
                    var imgur by remember(acct.imgurClientId) { mutableStateOf(acct.imgurClientId) }
                    OutlinedTextField(appId, { appId = it; scope.launch { container.settingsStore.setDiscordAppId(it.trim()) } }, label = { Text("Discord application ID") }, supportingText = { Text("Enables cover artwork") }, singleLine = true, modifier = fieldModifier())
                    OutlinedTextField(imgur, { imgur = it; scope.launch { container.settingsStore.setDiscordImgur(it.trim()) } }, label = { Text("Imgur client ID") }, supportingText = { Text("Optional: publishes local and private-server covers") }, singleLine = true, modifier = fieldModifier())
                }
            }
            item { SettingsSectionTitle("Account") }
            item { SettingsGroup { SettingsNavRow(Icons.Filled.LinkOff, "Disconnect Discord", value = "Remove") { scope.launch { container.settingsStore.clearDiscord() } } } }
        }
    }
}

@Composable
private fun DiscordPreview(acct: DiscordAccount) {
    val activity = when (acct.activityName) { "artist" -> "Listening to Aurora"; "song" -> "Listening to Midnight Drive"; else -> "Aurora" }
    Surface(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = .18f)), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Forum, null, tint = MaterialTheme.colorScheme.primary) }
                Spacer(Modifier.width(12.dp))
                Column { Text("DISCORD PREVIEW", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold); Text(activity, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(78.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Album, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.onPrimary) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Midnight Drive", fontWeight = FontWeight.Bold)
                    Text("Aurora", style = MaterialTheme.typography.bodyMedium)
                    if (acct.showAlbum) Text("Afterglow", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { .43f }, modifier = Modifier.fillMaxWidth().height(3.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("1:42", style = MaterialTheme.typography.labelSmall); Text("3:58", style = MaterialTheme.typography.labelSmall) }
                }
            }
            if (acct.appId.isBlank()) Text("Add an application ID to show real cover artwork.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ArtistRuleDialog(initial: ScrobbleArtistRule?, onDismiss: () -> Unit, onSave: (ScrobbleArtistRule) -> Unit, onDelete: (() -> Unit)?) {
    var source by remember(initial) { mutableStateOf(initial?.sourceArtist.orEmpty()) }
    var replacement by remember(initial) { mutableStateOf(initial?.replacementArtist.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add artist replacement" else "Edit artist replacement") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(source, { source = it.take(120) }, label = { Text("Artist from your music") }, placeholder = { Text("Ye") }, singleLine = true)
                OutlinedTextField(replacement, { replacement = it.take(120) }, label = { Text("Scrobble as") }, placeholder = { Text("Kanye West") }, singleLine = true)
                Text("Only an exact artist match is replaced. Capitalization does not matter.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(ScrobbleArtistRule(source.trim(), replacement.trim())) }, enabled = source.isNotBlank() && replacement.isNotBlank()) { Text("Save") } },
        dismissButton = { Row { if (onDelete != null) TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Remove") }; TextButton(onClick = onDismiss) { Text("Cancel") } } },
    )
}

@Composable
private fun PrivacyNote(text: String) { Text(text, Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

@Composable
private fun SettingsPage(title: String, contentPadding: PaddingValues, onBack: () -> Unit, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar(title, onBack)
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp), content = content)
    }
}

@Composable
private fun appContainer() = (LocalContext.current.applicationContext as AuroraApplication).container

private fun fieldModifier() = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp)

private fun openUrl(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
