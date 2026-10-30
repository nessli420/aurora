package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.playback.network.NetworkOutputManager
import com.aurora.music.playback.network.NetworkTarget

@Composable
fun NetworkOutputScreen(contentPadding: PaddingValues, onBack: () -> Unit, onOpenConnect: () -> Unit) {
    val manager = (LocalContext.current.applicationContext as AuroraApplication).container.networkOutput
    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar(appString(R.string.text_network_playback_5d5a8a), onBack)
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
        ) {
            item { NetworkOutputControls(manager, showForget = true) }
            item {
                SettingsSectionTitle(appString(R.string.text_this_device_fa5a6d))
                SettingsGroup {
                    RemoteControlSwitch(manager)
                    SettingsRowDivider()
                    SettingsDestinationRow(Icons.Filled.Devices, SettingsDestinations.connect, appString(R.string.connect_settings_link), onClick = onOpenConnect)
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
