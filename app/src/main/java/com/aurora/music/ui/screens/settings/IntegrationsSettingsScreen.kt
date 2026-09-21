package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

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

    SettingsPage(appString(R.string.text_integrations_a7881c), contentPadding, onBack) {
        item { SettingsSectionTitle(appString(R.string.text_listening_sharing_ecf6a1)) }
        item {
            SettingsGroup {
                IntegrationRow(Icons.Filled.Headset, "Last.fm", appString(R.string.text_scrobble_your_listening_history_e444aa), connectionLabel(lastfm.sessionKey, lastfm.enabled, lastfm.username), onOpenLastfm)
                SettingsRowDivider()
                IntegrationRow(Icons.Filled.Album, "ListenBrainz", appString(R.string.text_send_listens_to_your_open_music_profile_5a7b48), connectionLabel(listenBrainz.token, listenBrainz.enabled, listenBrainz.username), onOpenListenBrainz)
                SettingsRowDivider()
                IntegrationRow(Icons.Filled.Forum, "Discord", appString(R.string.text_share_your_current_track_and_artwork_11d20a), connectionLabel(discord.token, discord.enabled, discord.username), onOpenDiscord)
            }
        }
        item { SettingsSectionTitle(appString(R.string.text_lyrics_metadata_eb9a3c)) }
        item {
            SettingsGroup {
                IntegrationRow(Icons.Filled.Lyrics, "LRCLIB", appString(R.string.text_synced_lyrics_when_your_server_has_none_b4054e), if (lrclib) appString(R.string.text_on_e0049a) else appString(R.string.text_off_e3de5a), onOpenLyrics)
                SettingsRowDivider()
                IntegrationRow(Icons.Filled.Person, appString(R.string.text_artist_information_d5c3fc), appString(R.string.text_musicbrainz_and_wikipedia_biographies_73227e), if (artistInfo) appString(R.string.text_on_e0049a) else appString(R.string.text_off_e3de5a), onOpenArtistInfo)
                SettingsRowDivider()
                IntegrationRow(Icons.Filled.Fingerprint, "AcoustID", appString(R.string.text_identify_tracks_from_their_audio_51a42b), if (acoustId.isBlank()) appString(R.string.text_not_set_93039e) else appString(R.string.text_ready_20c7c5), onOpenAcoustId)
            }
        }
        item { ArtworkLookupSettings() }
        item {
            Text(
                appString(R.string.text_connections_are_optional_aurora_s_own_library_playback_history_an_d7c6c7),
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
    token.isBlank() -> appString(R.string.text_not_connected_8b02f3)
    !enabled -> appString(R.string.text_paused_c7dfb6)
    username.isNotBlank() -> username
    else -> appString(R.string.text_connected_c2f9b7)
}

@Composable
fun LyricsIntegrationScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val container = appContainer()
    val enabled by container.settingsStore.lrclibEnabled.collectAsStateWithLifecycle(initialValue = true)
    val scope = rememberCoroutineScope()
    SettingsPage(appString(R.string.text_lrclib_lyrics_d22392), contentPadding, onBack) {
        item { SettingsGroup { SettingsSwitchRow(Icons.Filled.Lyrics, appString(R.string.text_use_lrclib_1d1cb5), appString(R.string.text_fetch_lyrics_from_the_public_lrclib_service_36057a), enabled) { scope.launch { container.settingsStore.setLrclibEnabled(it) } } } }
        item { PrivacyNote(appString(R.string.text_track_title_artist_album_and_duration_may_be_sent_to_lrclib_to_fi_c268c3)) }
    }
}

@Composable
fun ArtistInfoIntegrationScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val container = appContainer()
    val enabled by container.settingsStore.artistEnrichment.collectAsStateWithLifecycle(initialValue = true)
    val scope = rememberCoroutineScope()
    SettingsPage(appString(R.string.text_artist_information_d5c3fc), contentPadding, onBack) {
        item { SettingsGroup { SettingsSwitchRow(Icons.Filled.Person, appString(R.string.text_artist_information_d5c3fc), appString(R.string.text_look_up_biographies_and_images_9931d4), enabled) { scope.launch { container.settingsStore.setArtistEnrichment(it) } } } }
        item { PrivacyNote(appString(R.string.text_artist_names_are_sent_to_the_metadata_providers_only_when_aurora_2f1daa)) }
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
                    label = { Text(appString(R.string.text_application_api_key_f3749c)) },
                    supportingText = { Text(if (saved.isBlank()) appString(R.string.text_required_for_audio_identification_e2b915) else appString(R.string.text_key_saved_163134)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
                SettingsRowDivider()
                SettingsNavRow(Icons.AutoMirrored.Filled.OpenInNew, appString(R.string.text_create_a_free_acoustid_key_928b23), appString(R.string.text_opens_acoustid_org_in_your_browser_6e6b1f)) { openUrl(ctx, "https://acoustid.org/new-application") }
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
        item { SettingsSectionTitle(appString(R.string.text_account_85dfa3)) }
        item { SettingsGroup { LastfmAccountControls(acct, scope, pendingToken, busy, status, { pendingToken = it }, { busy = it }, { status = it }) } }
        item { SettingsSectionTitle(appString(R.string.text_artist_replacement_rules_ff8fa3)) }
        item { Text(appString(R.string.text_replace_an_exact_artist_credit_before_last_fm_receives_it_matchin_99a847), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
        item {
            SettingsGroup {
                if (rules.isEmpty()) SettingsNavRow(Icons.Filled.SwapHoriz, appString(R.string.text_no_replacement_rules_b1f9fb), appString(R.string.text_example_ye_kanye_west_ded92b), value = appString(R.string.text_add_61cc55)) { addingRule = true }
                else {
                    rules.forEachIndexed { index, rule ->
                        SettingsNavRow(Icons.Filled.SwapHoriz, rule.sourceArtist.orEmpty(), appString(R.string.text_scrobble_as_29e5f2, (rule.replacementArtist.orEmpty()))) { editingRule = index }
                        if (index != rules.lastIndex) SettingsRowDivider()
                    }
                    SettingsRowDivider()
                    SettingsNavRow(Icons.Filled.Add, appString(R.string.text_add_artist_replacement_1379e2), appString(R.string.text_use_the_replacement_only_for_an_exact_artist_match_da4cc7)) { addingRule = true }
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
        SettingsSwitchRow(Icons.Filled.Headset, appString(R.string.text_scrobbling_772be3), appString(R.string.text_connected_as_fc3300, (acct.username)), acct.enabled) { scope.launch { container.settingsStore.setLastfmEnabled(it) } }
        SettingsRowDivider()
        SettingsNavRow(Icons.Filled.LinkOff, appString(R.string.text_disconnect_last_fm_e2c81c), appString(R.string.text_replacement_rules_will_be_kept_6ce6ce), value = appString(R.string.text_remove_e96390)) { scope.launch { container.lastfm.disconnect() } }
        return
    }
    val keys by container.settingsStore.lastfmKeys.collectAsStateWithLifecycle(initialValue = "" to "")
    var apiKey by remember(keys.first) { mutableStateOf(keys.first) }
    var secret by remember(keys.second) { mutableStateOf(keys.second) }
    OutlinedTextField(apiKey, { apiKey = it; scope.launch { container.settingsStore.setLastfmKeys(it, secret) } }, label = { Text(appString(R.string.text_api_key_cf678c)) }, singleLine = true, modifier = fieldModifier())
    OutlinedTextField(secret, { secret = it; scope.launch { container.settingsStore.setLastfmKeys(apiKey, it) } }, label = { Text(appString(R.string.text_shared_secret_4e19fd)) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = fieldModifier())
    if (pendingToken == null) {
        SettingsNavRow(Icons.Filled.Headset, appString(R.string.text_connect_last_fm_406205), status ?: appString(R.string.text_create_an_api_account_then_link_your_profile_5a93ca), if (busy) "…" else appString(R.string.text_connect_b65463)) {
            if (busy || !container.lastfm.configured) return@SettingsNavRow
            scope.launch {
                onBusy(true); onStatus(null)
                val token = container.lastfm.beginLink()
                onBusy(false)
                if (token == null) onStatus(appString(R.string.text_couldn_t_reach_last_fm_check_your_keys_and_connection_e65155))
                else { onPending(token); openUrl(ctx, container.lastfm.authorizeUrl(token)) }
            }
        }
    } else {
        SettingsNavRow(Icons.Filled.Check, appString(R.string.text_finish_linking_54297c), status ?: appString(R.string.text_allow_access_in_the_browser_then_return_here_4c48b2), if (busy) "…" else appString(R.string.text_done_e9b450)) {
            if (busy) return@SettingsNavRow
            scope.launch {
                onBusy(true); onStatus(null)
                val ok = container.lastfm.finishLink(pendingToken)
                onBusy(false)
                if (ok) onPending(null) else onStatus(appString(R.string.text_authorization_is_not_ready_allow_access_then_try_again_99dd5c))
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
                    SettingsSwitchRow(Icons.Filled.Album, appString(R.string.text_scrobbling_772be3), appString(R.string.text_connected_as_fc3300, (acct.username)), acct.enabled) { scope.launch { container.settingsStore.setListenBrainzEnabled(it) } }
                    SettingsRowDivider()
                    SettingsNavRow(Icons.Filled.LinkOff, appString(R.string.text_disconnect_listenbrainz_2a6c2c), value = appString(R.string.text_remove_e96390)) { scope.launch { container.listenBrainz.disconnect() } }
                } else {
                    OutlinedTextField(token, { token = it; status = null }, label = { Text(appString(R.string.text_user_token_a21883)) }, supportingText = { Text(status ?: appString(R.string.text_copy_this_from_your_listenbrainz_profile_458b1a)) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = fieldModifier())
                    SettingsNavRow(Icons.Filled.Link, appString(R.string.text_connect_listenbrainz_fd826c), value = if (busy) "…" else appString(R.string.text_connect_b65463)) {
                        if (busy || token.isBlank()) return@SettingsNavRow
                        scope.launch { busy = true; val ok = container.listenBrainz.connect(token); busy = false; status = if (ok) null else appString(R.string.text_that_token_could_not_be_verified_check_it_and_try_again_6668f1) }
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
        item { SettingsSectionTitle(appString(R.string.text_presence_89a8a3)) }
        item {
            SettingsGroup {
                if (acct.token.isBlank()) SettingsNavRow(Icons.Filled.Forum, appString(R.string.text_connect_discord_8794ce), appString(R.string.text_authorize_your_discord_account_a1f52f), appString(R.string.text_connect_b65463), onOpenDiscordLogin)
                else {
                    SettingsSwitchRow(Icons.Filled.Forum, appString(R.string.text_discord_presence_81fe83), if (acct.username.isBlank()) appString(R.string.text_connected_c2f9b7) else appString(R.string.text_connected_as_fc3300, (acct.username)), acct.enabled) { scope.launch { container.settingsStore.setDiscordEnabled(it) } }
                    SettingsRowDivider()
                    SettingsSwitchRow(Icons.Filled.Album, appString(R.string.text_show_album_b04b79), appString(R.string.text_include_the_album_and_artwork_caption_1d812f), acct.showAlbum) { scope.launch { container.settingsStore.setDiscordShowAlbum(it) } }
                    SegmentedRow(appString(R.string.text_activity_name_09adc3), listOf(appString(R.string.text_aurora_eeee9b), appString(R.string.text_artist_6c3f3d), appString(R.string.text_song_bd1189)), listOf("aurora", "artist", "song").indexOf(acct.activityName).coerceAtLeast(0)) { scope.launch { container.settingsStore.setDiscordActivityName(listOf("aurora", "artist", "song")[it]) } }
                }
            }
        }
        if (acct.token.isNotBlank()) {
            item { SettingsSectionTitle(appString(R.string.text_artwork_bfbeaf)) }
            item {
                SettingsGroup {
                    var appId by remember(acct.appId) { mutableStateOf(acct.appId) }
                    var imgur by remember(acct.imgurClientId) { mutableStateOf(acct.imgurClientId) }
                    OutlinedTextField(appId, { appId = it; scope.launch { container.settingsStore.setDiscordAppId(it.trim()) } }, label = { Text(appString(R.string.text_discord_application_id_2ac458)) }, supportingText = { Text(appString(R.string.text_enables_cover_artwork_40a2d2)) }, singleLine = true, modifier = fieldModifier())
                    OutlinedTextField(imgur, { imgur = it; scope.launch { container.settingsStore.setDiscordImgur(it.trim()) } }, label = { Text(appString(R.string.text_imgur_client_id_f5e499)) }, supportingText = { Text(appString(R.string.text_optional_publishes_local_and_private_server_covers_563e93)) }, singleLine = true, modifier = fieldModifier())
                }
            }
            item { SettingsSectionTitle(appString(R.string.text_account_85dfa3)) }
            item { SettingsGroup { SettingsNavRow(Icons.Filled.LinkOff, appString(R.string.text_disconnect_discord_0939de), value = appString(R.string.text_remove_e96390)) { scope.launch { container.settingsStore.clearDiscord() } } } }
        }
    }
}

@Composable
private fun DiscordPreview(acct: DiscordAccount) {
    val activity = when (acct.activityName) { "artist" -> appString(R.string.text_listening_to_aurora_24a92c); "song" -> appString(R.string.text_listening_to_midnight_drive_35328b); else -> appString(R.string.text_aurora_eeee9b) }
    Surface(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = .18f)), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Forum, null, tint = MaterialTheme.colorScheme.primary) }
                Spacer(Modifier.width(12.dp))
                Column { Text(appString(R.string.text_discord_preview_401388), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold); Text(activity, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(78.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Album, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.onPrimary) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Midnight Drive", fontWeight = FontWeight.Bold)
                    Text(appString(R.string.text_aurora_eeee9b), style = MaterialTheme.typography.bodyMedium)
                    if (acct.showAlbum) Text("Afterglow", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { .43f }, modifier = Modifier.fillMaxWidth().height(3.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("1:42", style = MaterialTheme.typography.labelSmall); Text("3:58", style = MaterialTheme.typography.labelSmall) }
                }
            }
            if (acct.appId.isBlank()) Text(appString(R.string.text_add_an_application_id_to_show_real_cover_artwork_faca69), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ArtistRuleDialog(initial: ScrobbleArtistRule?, onDismiss: () -> Unit, onSave: (ScrobbleArtistRule) -> Unit, onDelete: (() -> Unit)?) {
    var source by remember(initial) { mutableStateOf(initial?.sourceArtist.orEmpty()) }
    var replacement by remember(initial) { mutableStateOf(initial?.replacementArtist.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) appString(R.string.text_add_artist_replacement_1379e2) else appString(R.string.text_edit_artist_replacement_2b228e)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(source, { source = it.take(120) }, label = { Text(appString(R.string.text_artist_from_your_music_10b0e0)) }, placeholder = { Text("Ye") }, singleLine = true)
                OutlinedTextField(replacement, { replacement = it.take(120) }, label = { Text(appString(R.string.text_scrobble_as_f81cff)) }, placeholder = { Text("Kanye West") }, singleLine = true)
                Text(appString(R.string.text_only_an_exact_artist_match_is_replaced_capitalization_does_not_ma_0fa2cb), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(ScrobbleArtistRule(source.trim(), replacement.trim())) }, enabled = source.isNotBlank() && replacement.isNotBlank()) { Text(appString(R.string.text_save_efc007)) } },
        dismissButton = { Row { if (onDelete != null) TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(appString(R.string.text_remove_e96390)) }; TextButton(onClick = onDismiss) { Text(appString(R.string.text_cancel_77dfd2)) } } },
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
