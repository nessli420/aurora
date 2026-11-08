package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.ui.theme.paperClip
import com.aurora.music.R

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.*
import com.aurora.music.ui.theme.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.data.*
import com.aurora.music.data.remote.LastfmException
import com.aurora.music.desktop.ui.LocalDesktopContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun IntegrationsSettingsScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenLyrics: () -> Unit,
    onOpenLastfm: () -> Unit,
    onOpenListenBrainz: () -> Unit,
    onOpenArtistInfo: () -> Unit,
    onOpenDiscord: () -> Unit = {},
    onOpenAcoustId: () -> Unit = {},
) {
    val store = LocalDesktopContainer.current.settingsStore
    val acoustId by store.acoustIdKey.collectAsStateWithLifecycle(initialValue = "")
    val discord by store.discord.collectAsStateWithLifecycle(initialValue = DiscordAccount())
    val discordUser by LocalDesktopContainer.current.discordUser.collectAsStateWithLifecycle()
    val lrclib by store.lrclibEnabled.collectAsStateWithLifecycle(initialValue = true)
    val artistInfo by store.artistEnrichment.collectAsStateWithLifecycle(initialValue = true)
    val lastfm by store.lastfm.collectAsStateWithLifecycle(initialValue = LastfmAccount())
    val listenBrainz by store.listenBrainz.collectAsStateWithLifecycle(initialValue = ListenBrainzAccount())

    SettingsPage(appString(R.string.text_integrations_a7881c), contentPadding, onBack) {
        item { SettingsSectionTitle(appString(R.string.text_listening_sharing_ecf6a1)) }
        item {
            SettingsGroup {
                IntegrationRow(Icons.Filled.Headset, "Last.fm", appString(R.string.text_scrobble_your_listening_history_e444aa), connectionLabel(lastfm.sessionKey, lastfm.enabled, lastfm.username), onOpenLastfm)
                SettingsRowDivider()
                IntegrationRow(Icons.Filled.Album, "ListenBrainz", appString(R.string.text_send_listens_to_your_open_music_profile_5a7b48), connectionLabel(listenBrainz.token, listenBrainz.enabled, listenBrainz.username), onOpenListenBrainz)
                SettingsRowDivider()
                IntegrationRow(Icons.Filled.Forum, "Discord", appString(R.string.text_share_your_current_track_and_artwork_11d20a), discordLabel(discord, discordUser), onOpenDiscord)
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
        item { ArtworkLookupGroup() }
        item { AnimatedCoverSettings() }
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
private fun AnimatedCoverSettings() {
    val container = LocalDesktopContainer.current
    val covers = container.animatedCovers
    val config by covers.config.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val pickTitle = appString(R.string.animated_covers_folder)
    Column {
        SettingsSectionTitle(appString(R.string.animated_covers_title))
        SettingsGroup {
            SettingsSwitchRow(Icons.Outlined.Animation, appString(R.string.animated_covers_toggle),
                appString(R.string.animated_covers_toggle_desc), config.enabled) {
                scope.launch { container.settingsStore.setAnimatedCoversEnabled(it) }
            }
            SettingsRowDivider()
            SettingsNavRow(Icons.Outlined.FolderOpen, appString(R.string.animated_covers_folder),
                value = if (config.folder.isBlank()) appString(R.string.animated_covers_folder_none) else com.aurora.music.data.AnimatedCovers.label(config.folder)) {
                val folder = com.aurora.music.desktop.ui.FilePickers.pickFolder(pickTitle, config.folder.takeIf { it.isNotBlank() }?.let { java.io.File(it) })
                if (folder != null) scope.launch { covers.setFolder(folder) }
            }
            SettingsRowDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(appString(R.string.animated_covers_help), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (config.folder.isNotBlank()) com.aurora.music.ui.theme.TextButton(onClick = { scope.launch { covers.setFolder(null) } }) {
                    Text(appString(R.string.animated_covers_remove))
                }
            }
        }
    }
}

@Composable
private fun ArtworkLookupGroup() {
    val container = LocalDesktopContainer.current
    val enabled by container.settingsStore.artworkLookupEnabled.collectAsStateWithLifecycle(initialValue = true)
    val bytes by container.artworkRepository.bytes.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var clearing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    Column {
        SettingsSectionTitle(appString(R.string.artwork_title))
        SettingsGroup {
            SettingsSwitchRow(Icons.Outlined.Image, appString(R.string.artwork_lookup),
                appString(R.string.artwork_description), enabled) {
                scope.launch { container.settingsStore.setArtworkLookupEnabled(it) }
            }
            SettingsRowDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(appString(R.string.artwork_provider), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(appString(R.string.artwork_cache_usage, bytes / (1024.0 * 1024)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp))
                TextButton(enabled = !clearing, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand), onClick = {
                    clearing = true
                    failed = false
                    scope.launch {
                        failed = runCatching {
                            container.artworkRepository.clear()
                            container.imageLoader.memoryCache?.clear()
                        }.isFailure
                        clearing = false
                    }
                }) { Text(appString(R.string.artwork_clear)) }
                if (failed) Text(appString(R.string.artwork_clear_failed), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun IntegrationRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, status: String, onClick: () -> Unit) {
    SettingsNavRow(icon, title, subtitle, status, onClick = onClick)
}

private fun discordLabel(acct: DiscordAccount, localUser: String?): String {
    val name = localUser ?: acct.username.takeIf { acct.token.isNotBlank() }
    return when {
        !acct.enabled -> appString(R.string.text_off_e3de5a)
        name == null -> appString(R.string.discord_not_running_desktop)
        else -> name.ifBlank { appString(R.string.text_connected_c2f9b7) }
    }
}

private fun discordStatus(acct: DiscordAccount, localUser: String?): String {
    val name = localUser ?: acct.username.takeIf { acct.token.isNotBlank() }
    return when {
        !acct.enabled -> appString(R.string.text_share_your_current_track_and_artwork_11d20a)
        name == null -> appString(R.string.discord_not_running_desktop)
        name.isBlank() -> appString(R.string.text_connected_c2f9b7)
        else -> appString(R.string.text_connected_as_fc3300, name)
    }
}

private fun connectionLabel(token: String, enabled: Boolean, username: String): String = when {
    token.isBlank() -> appString(R.string.text_not_connected_8b02f3)
    !enabled -> appString(R.string.text_paused_c7dfb6)
    username.isNotBlank() -> username
    else -> appString(R.string.text_connected_c2f9b7)
}

@Composable
fun LyricsIntegrationScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val container = LocalDesktopContainer.current
    val enabled by container.settingsStore.lrclibEnabled.collectAsStateWithLifecycle(initialValue = true)
    val scope = rememberCoroutineScope()
    SettingsPage(appString(R.string.text_lrclib_lyrics_d22392), contentPadding, onBack) {
        item { SettingsGroup { SettingsSwitchRow(Icons.Filled.Lyrics, appString(R.string.text_use_lrclib_1d1cb5), appString(R.string.text_fetch_lyrics_from_the_public_lrclib_service_36057a), enabled) { scope.launch { container.settingsStore.setLrclibEnabled(it) } } } }
        item { PrivacyNote(appString(R.string.text_track_title_artist_album_and_duration_may_be_sent_to_lrclib_to_fi_c268c3)) }
    }
}

@Composable
fun ArtistInfoIntegrationScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val container = LocalDesktopContainer.current
    val enabled by container.settingsStore.artistEnrichment.collectAsStateWithLifecycle(initialValue = true)
    val scope = rememberCoroutineScope()
    SettingsPage(appString(R.string.text_artist_information_d5c3fc), contentPadding, onBack) {
        item { SettingsGroup { SettingsSwitchRow(Icons.Filled.Person, appString(R.string.text_artist_information_d5c3fc), appString(R.string.text_look_up_biographies_and_images_9931d4), enabled) { scope.launch { container.settingsStore.setArtistEnrichment(it) } } } }
        item { PrivacyNote(appString(R.string.text_artist_names_are_sent_to_the_metadata_providers_only_when_aurora_2f1daa)) }
    }
}

@Composable
fun LastfmIntegrationScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val store = LocalDesktopContainer.current.settingsStore
    val acct by store.lastfm.collectAsStateWithLifecycle(initialValue = LastfmAccount())
    val rules by store.lastfmArtistRules.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var editingRule by remember { mutableStateOf<Int?>(null) }
    var addingRule by remember { mutableStateOf(false) }

    SettingsPage("Last.fm", contentPadding, onBack) {
        item { SettingsSectionTitle(appString(R.string.text_account_85dfa3)) }
        item { SettingsGroup { LastfmAccountControls(acct, scope, busy, status, { busy = it }, { status = it }) } }
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
    busy: Boolean,
    status: String?,
    onBusy: (Boolean) -> Unit,
    onStatus: (String?) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val container = LocalDesktopContainer.current
    if (acct.sessionKey.isNotBlank()) {
        SettingsSwitchRow(Icons.Filled.Headset, appString(R.string.text_scrobbling_772be3), appString(R.string.text_connected_as_fc3300, (acct.username)), acct.enabled) { scope.launch { container.settingsStore.setLastfmEnabled(it) } }
        SettingsRowDivider()
        SettingsNavRow(Icons.Filled.LinkOff, appString(R.string.text_disconnect_last_fm_e2c81c), appString(R.string.text_replacement_rules_will_be_kept_6ce6ce), value = appString(R.string.text_remove_e96390)) { scope.launch { container.lastfm.disconnect() } }
        return
    }
    val keys by container.settingsStore.lastfmKeys.collectAsStateWithLifecycle(initialValue = "" to "")
    val pendingToken by container.settingsStore.lastfmPendingToken.collectAsStateWithLifecycle(initialValue = "")
    var draft by remember { mutableStateOf<Pair<String, String>?>(null) }
    DebouncedSave(draft) { (key, secret) -> container.settingsStore.setLastfmKeys(key, secret) }
    val (apiKey, secret) = draft ?: keys
    OutlinedTextField(apiKey, { draft = it to secret }, label = { Text(appString(R.string.text_api_key_cf678c)) }, singleLine = true, modifier = fieldModifier())
    OutlinedTextField(secret, { draft = apiKey to it }, label = { Text(appString(R.string.text_shared_secret_4e19fd)) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = fieldModifier())
    if (pendingToken.isBlank()) {
        SettingsNavRow(Icons.Filled.Headset, appString(R.string.text_connect_last_fm_406205), status ?: appString(R.string.text_create_an_api_account_then_link_your_profile_5a93ca), if (busy) "…" else appString(R.string.text_connect_b65463)) {
            if (busy) return@SettingsNavRow
            if (apiKey.isBlank() || secret.isBlank()) { onStatus(appString(R.string.text_enter_your_api_key_and_shared_secret_first_651d89)); return@SettingsNavRow }
            scope.launch {
                onBusy(true); onStatus(null)
                container.settingsStore.setLastfmKeys(apiKey, secret)
                val url = container.lastfm.beginLink()
                onBusy(false)
                url.onSuccess { runCatching { uriHandler.openUri(it) } }.onFailure { onStatus(lastfmError(it)) }
            }
        }
    } else {
        SettingsNavRow(Icons.Filled.Check, appString(R.string.text_finish_linking_54297c), status ?: appString(R.string.text_allow_access_in_the_browser_then_return_here_4c48b2), if (busy) "…" else appString(R.string.text_done_e9b450)) {
            if (busy) return@SettingsNavRow
            scope.launch {
                onBusy(true); onStatus(null)
                val result = container.lastfm.finishLink()
                onBusy(false)
                result.onFailure {
                    onStatus(lastfmError(it))
                    // not approved yet, show the approval page again in case the tab was closed
                    if ((it as? LastfmException)?.code == 14) container.lastfm.pendingAuthorizeUrl()?.let { url -> runCatching { uriHandler.openUri(url) } }
                }
            }
        }
    }
}

private fun lastfmError(e: Throwable): String = when (val code = (e as? LastfmException)?.code) {
    null -> appString(R.string.text_couldn_t_reach_last_fm_check_your_keys_and_connection_e65155)
    13 -> appString(R.string.text_last_fm_rejected_the_shared_secret_copy_it_again_from_your_api_ac_1061e1)
    10, 26 -> appString(R.string.text_last_fm_rejected_the_api_key_check_it_on_your_api_account_page_f1c437)
    14 -> appString(R.string.text_authorization_is_not_ready_allow_access_then_try_again_99dd5c)
    4, 15 -> appString(R.string.text_this_link_has_expired_connect_again_to_get_a_new_one_8e6cd7)
    else -> appString(R.string.text_last_fm_error_54ddf2, code, e.message.orEmpty())
}

@Composable
fun ListenBrainzIntegrationScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val container = LocalDesktopContainer.current
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
fun AcoustIdIntegrationScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val uri = LocalUriHandler.current
    val container = LocalDesktopContainer.current
    val saved by container.settingsStore.acoustIdKey.collectAsStateWithLifecycle(initialValue = "")
    var key by remember { mutableStateOf<String?>(null) }
    DebouncedSave(key) { container.settingsStore.setAcoustIdKey(it.trim()) }
    SettingsPage("AcoustID", contentPadding, onBack) {
        item {
            SettingsGroup {
                OutlinedTextField(
                    value = key ?: saved,
                    onValueChange = { key = it },
                    label = { Text(appString(R.string.text_application_api_key_f3749c)) },
                    supportingText = { Text(if (saved.isBlank()) appString(R.string.text_required_for_audio_identification_e2b915) else appString(R.string.text_key_saved_163134)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
                SettingsRowDivider()
                SettingsNavRow(Icons.AutoMirrored.Filled.OpenInNew, appString(R.string.text_create_a_free_acoustid_key_928b23), appString(R.string.text_opens_acoustid_org_in_your_browser_6e6b1f)) { runCatching { uri.openUri("https://acoustid.org/new-application") } }
            }
        }
    }
}

@Composable
fun DiscordIntegrationScreen(contentPadding: PaddingValues, onBack: () -> Unit, onOpenDiscordLogin: () -> Unit) {
    val container = LocalDesktopContainer.current
    val acct by container.settingsStore.discord.collectAsStateWithLifecycle(initialValue = DiscordAccount())
    val localUser by container.discordUser.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    SettingsPage("Discord", contentPadding, onBack) {
        item { DiscordPreview(acct) }
        item { SettingsSectionTitle(appString(R.string.text_presence_89a8a3)) }
        item {
            SettingsGroup {
                SettingsSwitchRow(Icons.Filled.Forum, appString(R.string.text_discord_presence_81fe83), discordStatus(acct, localUser), acct.enabled) { scope.launch { container.settingsStore.setDiscordEnabled(it) } }
                SettingsRowDivider()
                SettingsSwitchRow(Icons.Filled.Album, appString(R.string.text_show_album_b04b79), appString(R.string.text_include_the_album_and_artwork_caption_1d812f), acct.showAlbum) { scope.launch { container.settingsStore.setDiscordShowAlbum(it) } }
                SegmentedRow(appString(R.string.text_activity_name_09adc3), listOf(appString(R.string.text_aurora_eeee9b), appString(R.string.text_artist_6c3f3d), appString(R.string.text_song_bd1189)), listOf("aurora", "artist", "song").indexOf(acct.activityName).coerceAtLeast(0)) { scope.launch { container.settingsStore.setDiscordActivityName(listOf("aurora", "artist", "song")[it]) } }
            }
        }
        item { SettingsSectionTitle(appString(R.string.text_artwork_bfbeaf)) }
        item {
            SettingsGroup {
                var imgur by remember { mutableStateOf<String?>(null) }
                DebouncedSave(imgur) { container.settingsStore.setDiscordImgur(it.trim()) }
                OutlinedTextField(imgur ?: acct.imgurClientId, { imgur = it }, label = { Text(appString(R.string.text_imgur_client_id_f5e499)) }, supportingText = { Text(appString(R.string.text_optional_publishes_local_and_private_server_covers_563e93)) }, singleLine = true, modifier = fieldModifier())
            }
        }
        item { SettingsSectionTitle(appString(R.string.discord_without_app_desktop)) }
        item {
            SettingsGroup {
                if (acct.token.isBlank()) SettingsNavRow(Icons.Filled.Forum, appString(R.string.text_connect_discord_8794ce), appString(R.string.discord_without_app_summary_desktop), appString(R.string.text_connect_b65463), onClick = onOpenDiscordLogin)
                else {
                    var appId by remember { mutableStateOf<String?>(null) }
                    DebouncedSave(appId) { container.settingsStore.setDiscordAppId(it.trim()) }
                    OutlinedTextField(appId ?: acct.appId, { appId = it }, label = { Text(appString(R.string.text_discord_application_id_2ac458)) }, supportingText = { Text(appString(R.string.text_enables_cover_artwork_40a2d2)) }, singleLine = true, modifier = fieldModifier())
                    SettingsNavRow(Icons.Filled.LinkOff, appString(R.string.text_disconnect_discord_0939de), value = appString(R.string.text_remove_e96390)) { scope.launch { container.settingsStore.clearDiscord() } }
                }
            }
        }
    }
}

@Composable
private fun DiscordPreview(acct: DiscordAccount) {
    val activity = when (acct.activityName) { "artist" -> appString(R.string.text_listening_to_aurora_24a92c); "song" -> appString(R.string.text_listening_to_midnight_drive_35328b); else -> appString(R.string.text_aurora_eeee9b) }
    Surface(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = com.aurora.music.ui.theme.paperSquare(RoundedCornerShape(20.dp))) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).paperClip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = .18f)), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Forum, null, tint = MaterialTheme.colorScheme.primary) }
                Spacer(Modifier.width(12.dp))
                Column { Text(appString(R.string.text_discord_preview_401388), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold); Text(activity, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(78.dp).paperClip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Album, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.onPrimary) }
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
            if (acct.token.isNotBlank() && acct.appId.isBlank()) Text(appString(R.string.text_add_an_application_id_to_show_real_cover_artwork_faca69), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ArtistRuleDialog(initial: ScrobbleArtistRule?, onDismiss: () -> Unit, onSave: (ScrobbleArtistRule) -> Unit, onDelete: (() -> Unit)?) {
    var source by remember(initial) { mutableStateOf(initial?.sourceArtist.orEmpty()) }
    var replacement by remember(initial) { mutableStateOf(initial?.replacementArtist.orEmpty()) }
    AlertDialog(containerColor = com.aurora.music.ui.theme.solidColors().surfaceContainerHigh, 
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
        SettingsList(contentPadding, content = content)
    }
}

// typed text persists after a 500 ms pause or when the field leaves instead of on every keystroke
@Composable
private fun <T : Any> DebouncedSave(draft: T?, persist: suspend (T) -> Unit) {
    val latest by rememberUpdatedState(draft)
    val write by rememberUpdatedState(persist)
    LaunchedEffect(Unit) {
        var pending: T? = null
        try {
            snapshotFlow { latest }.filterNotNull().collectLatest { value ->
                pending = value
                delay(500)
                write(value)
                pending = null
            }
        } finally {
            pending?.let { value -> withContext(NonCancellable) { write(value) } }
        }
    }
}

private fun fieldModifier() = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp)
