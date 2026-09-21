package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.playback.network.NetworkOutputManager
import com.aurora.music.playback.network.NetworkTarget
import kotlinx.coroutines.delay

@Composable
fun NetworkOutputScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val manager = (LocalContext.current.applicationContext as AuroraApplication).container.networkOutput
    val state by manager.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var address by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.pairing) {
        while (state.pairing != null && System.currentTimeMillis() < state.pairing!!.expiresAtMs) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
        now = System.currentTimeMillis()
    }
    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar(appString(R.string.text_network_playback_5d5a8a), onBack)
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
        ) {
            item { NetworkOutputControls(manager, showForget = true) }
            item {
                SettingsSectionTitle(appString(R.string.text_pair_an_aurora_player_de78d4))
                SettingsGroup {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        state.discovered.filter { found -> state.paired.none { it.id == found.id } }.forEach { found ->
                            Text("${found.name} · ${found.address}", style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedTextField(
                            address, { address = it.take(512) }, label = { Text(appString(R.string.text_pairing_address_0a7563)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                        )
                        OutlinedTextField(
                            code, { code = it.take(32) }, label = { Text(appString(R.string.text_pairing_code_309e30)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                        )
                        Text(appString(R.string.text_copy_the_full_address_from_the_other_device_825ae9), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { manager.pair(address.trim(), code.trim()) },
                            enabled = address.isNotBlank() && code.isNotBlank() && !state.preparing) { Text(appString(R.string.text_pair_2537f8)) }
                    }
                }
            }
            item {
                SettingsSectionTitle(appString(R.string.text_this_device_fa5a6d))
                SettingsGroup {
                    SettingsSwitchRow(title = appString(R.string.text_allow_remote_control_0f0d85), subtitle = appString(R.string.text_paired_devices_can_control_playback_86f484),
                        checked = state.receiverEnabled, onCheckedChange = manager::enableReceiver)
                    if (state.receiverEnabled) {
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.receiverAddress?.let { endpoint ->
                                Text(endpoint.address, style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = { clipboard.setText(AnnotatedString(endpoint.pairingAddress)) }) {
                                    Text(appString(R.string.text_copy_pairing_address_fc506d))
                                }
                            } ?: Text(appString(R.string.text_connecting_to_the_local_network_4df7c7), style = MaterialTheme.typography.bodySmall)
                            val pairing = state.pairing
                            if (pairing != null && pairing.expiresAtMs > now) {
                                Text(pairing.code, style = MaterialTheme.typography.headlineSmall)
                                Text(appString(R.string.text_expires_in_s_829189, (((pairing.expiresAtMs - now + 999) / 1_000))),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = { clipboard.setText(AnnotatedString(pairing.code)) }) { Text(appString(R.string.text_copy_code_6c1069)) }
                            }
                            TextButton(onClick = manager::beginPairing, enabled = state.receiverAddress != null) {
                                Text(if (pairing == null || pairing.expiresAtMs <= now) appString(R.string.text_show_pairing_code_921392) else appString(R.string.text_new_code_6499c7))
                            }
                        }
                    }
                    state.controllers.forEach { controller ->
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(controller.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { manager.revokeController(controller.id) }) { Text(appString(R.string.text_revoke_0be720)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NetworkOutputControls(
    manager: NetworkOutputManager,
    showForget: Boolean = false,
    onConnected: () -> Unit = {},
) {
    val state by manager.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth()) {
        SettingsSectionTitle("Chromecast")
        SettingsGroup {
            SegmentedRow(appString(R.string.text_audio_acdac2), listOf(appString(R.string.text_direct_bc8152), appString(R.string.text_processed_8291b2)), if (state.processedCast) 1 else 0) {
                manager.setProcessedCast(it == 1)
            }
            Text(if (state.processedCast) appString(R.string.text_applies_aurora_s_effects_before_sending_audio_a462ad) else appString(R.string.text_the_receiver_plays_the_source_aurora_s_effects_are_bypassed_e273f5),
                Modifier.padding(start = 20.dp, end = 20.dp, bottom = 14.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.receiverName != null || state.preparing || state.error != null) {
            SettingsSectionTitle(appString(R.string.text_playback_c8e308))
            SettingsGroup {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.receiverName?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                    if (state.preparing) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(appString(R.string.text_preparing_audio_63cde6), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (state.detail.isNotBlank()) Text(state.detail, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.error?.let { message ->
                        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = manager::dismissError) { Text(appString(R.string.text_dismiss_70afe9)) }
                    }
                    if (state.receiverName != null) TextButton(onClick = manager::disconnect) { Text(appString(R.string.text_disconnect_ed28e0)) }
                }
            }
        }
        SettingsSectionTitle(appString(R.string.text_dlna_receivers_61e7ce))
        SettingsGroup {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (state.scanning) appString(R.string.text_searching_1a6a5b) else if (state.dlna.isEmpty()) appString(R.string.text_no_receivers_found_ec71c6) else appString(R.string.text_local_network_0a1fc0),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = manager::scan, enabled = !state.scanning) { Text(appString(R.string.text_search_bce064)) }
            }
            state.dlna.forEach { renderer ->
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(renderer.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(enabled = !state.preparing, onClick = { manager.connect(NetworkTarget.Dlna(renderer)); onConnected() }) { Text(appString(R.string.text_play_here_49df29)) }
                }
            }
        }
        if (state.paired.isNotEmpty()) {
            SettingsSectionTitle(appString(R.string.text_aurora_players_373351))
            SettingsGroup {
                Text(appString(R.string.text_copies_the_queue_before_playback_up_to_100_tracks_and_512_mb_aae54a),
                    Modifier.padding(horizontal = 20.dp, vertical = 14.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.paired.forEach { renderer ->
                    Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
                        Text(renderer.name, style = MaterialTheme.typography.titleSmall)
                        Row {
                            TextButton(enabled = !state.preparing, onClick = { manager.connect(NetworkTarget.Aurora(renderer)); onConnected() }) { Text(appString(R.string.text_play_here_49df29)) }
                            if (showForget) TextButton(onClick = { manager.forget(renderer.id) }) { Text(appString(R.string.text_forget_03d5d8)) }
                        }
                    }
                }
            }
        }
        Text(appString(R.string.text_processed_playback_uses_the_current_effects_when_each_track_start_23b137),
            Modifier.padding(horizontal = 20.dp, vertical = 14.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
