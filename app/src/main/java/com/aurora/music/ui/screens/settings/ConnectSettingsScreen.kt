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
import com.aurora.music.playback.network.endpoint.ConnectSwitch
import com.aurora.music.playback.network.endpoint.EndpointTls
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ConnectSettingsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    val manager = container.networkOutput
    val state by manager.state.collectAsStateWithLifecycle()
    val switch by container.settingsStore.connectSwitch.collectAsStateWithLifecycle(initialValue = ConnectSwitch.PAUSE)
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
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
        SettingsTopBar(appString(R.string.connect_settings_title), onBack)
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
        ) {
            item {
                SettingsSectionTitle(appString(R.string.connect_guide_title))
                SettingsGroup { ConnectGuide() }
            }
            item {
                SettingsSectionTitle(appString(R.string.text_this_device_fa5a6d))
                SettingsGroup {
                    RemoteControlSwitch(manager)
                    if (state.receiverEnabled) {
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.receiverAddress?.let { endpoint ->
                                Text(appString(R.string.connect_this_key, EndpointTls.shortKey(endpoint.fingerprint)), style = MaterialTheme.typography.titleSmall)
                                Text(appString(R.string.connect_reachable_at), style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                state.receiverAddresses.ifEmpty { listOf(endpoint.address) }.forEach {
                                    Text(it.removePrefix("https://"), style = MaterialTheme.typography.bodyMedium)
                                }
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
            item {
                SettingsSectionTitle(appString(R.string.connect_devices))
                SettingsGroup {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        com.aurora.music.ui.screens.player.ConnectDevicesSection(onDismiss = {})
                        TextButton(onClick = manager::scan, enabled = !state.scanning, modifier = Modifier.padding(start = 8.dp)) {
                            Text(appString(R.string.text_search_bce064))
                        }
                    }
                    SegmentedRow(appString(R.string.connect_switch_title), listOf(appString(R.string.connect_switch_keep),
                        appString(R.string.connect_switch_pause), appString(R.string.connect_switch_move)), switch) {
                        scope.launch { container.settingsStore.setConnectSwitch(it) }
                    }
                    state.paired.forEach { peer ->
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(peer.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { manager.forget(peer.id) }) { Text(appString(R.string.text_forget_03d5d8)) }
                        }
                    }
                }
            }
            item {
                SettingsSectionTitle(appString(R.string.connect_add_by_address))
                SettingsGroup {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(host, { host = it.take(256) }, label = { Text(appString(R.string.connect_add_by_address)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Text(appString(R.string.connect_address_help), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.probed?.known == true) Text(appString(R.string.connect_already_paired), style = MaterialTheme.typography.bodySmall)
                        state.error?.let { message ->
                            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        Button(onClick = { manager.probe(host) }, enabled = host.isNotBlank() && !state.preparing) { Text(appString(R.string.connect_continue)) }
                    }
                }
            }
            item {
                SettingsSectionTitle(appString(R.string.text_pair_an_aurora_player_de78d4))
                SettingsGroup {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
        }
    }
    state.probed?.takeIf { !it.known }?.let { probed ->
        com.aurora.music.ui.screens.player.ConnectPairDialog(probed.address.removePrefix("https://"), EndpointTls.shortKey(probed.fingerprint),
            onPair = manager::pairProbed, onDismiss = manager::dismissProbe)
    }
}

// the same receiver switch sits here and on network audio, both reading the one live state
@Composable
internal fun RemoteControlSwitch(manager: NetworkOutputManager) {
    val state by manager.state.collectAsStateWithLifecycle()
    SettingsSwitchRow(title = appString(R.string.text_allow_remote_control_0f0d85),
        subtitle = appString(if (state.receiverEnabled) R.string.connect_host_on else R.string.connect_host_off),
        checked = state.receiverEnabled, onCheckedChange = manager::enableReceiver)
}

@Composable
private fun ConnectGuide() {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(R.string.connect_guide_host, R.string.connect_guide_pick, R.string.connect_guide_network).forEachIndexed { i, step ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${i + 1}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Text(appString(step), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(appString(R.string.connect_guide_sources), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
