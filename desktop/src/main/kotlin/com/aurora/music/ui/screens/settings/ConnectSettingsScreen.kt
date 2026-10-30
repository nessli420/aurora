package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aurora.music.R
import com.aurora.music.desktop.connect.LocalConnect
import com.aurora.music.desktop.ui.LocalDesktopContainer
import com.aurora.music.localization.appString
import com.aurora.music.playback.network.endpoint.ConnectSwitch
import com.aurora.music.playback.network.endpoint.EndpointTls
import com.aurora.music.ui.screens.player.ConnectDevicesSection
import com.aurora.music.ui.screens.player.ConnectPairDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ConnectSettingsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val connect = LocalConnect.current ?: return
    val store = LocalDesktopContainer.current.settingsStore
    val state by connect.state.collectAsState()
    val switch by store.connectSwitch.collectAsState(ConnectSwitch.PAUSE)
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
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
        SettingsList(contentPadding) {
            item { SettingsSectionTitle(appString(R.string.connect_guide_title)) }
            item { SettingsGroup { ConnectGuide() } }
            item { SettingsSectionTitle(appString(R.string.text_this_device_fa5a6d)) }
            item {
                SettingsGroup {
                    SettingsSwitchRow(Icons.Filled.Devices, appString(R.string.text_allow_remote_control_0f0d85),
                        appString(if (state.enabled) R.string.connect_host_on else R.string.connect_host_off), state.enabled, connect::setEnabled)
                    state.address?.let { address ->
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(appString(R.string.connect_this_key, EndpointTls.shortKey(address.fingerprint)), style = MaterialTheme.typography.titleSmall)
                            Text(appString(R.string.connect_reachable_at), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            SelectionContainer {
                                Column { state.addresses.ifEmpty { listOf(address.address) }.forEach { Text(it.removePrefix("https://"), style = MaterialTheme.typography.bodyMedium) } }
                            }
                            val pairing = state.pairing?.takeIf { it.expiresAtMs > now }
                            if (pairing != null) {
                                Text(pairing.code, style = MaterialTheme.typography.headlineSmall)
                                Text(appString(R.string.text_expires_in_s_829189, (pairing.expiresAtMs - now + 999) / 1_000),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = connect::beginPairing) {
                                Text(appString(if (pairing == null) R.string.text_show_pairing_code_921392 else R.string.text_new_code_6499c7))
                            }
                        }
                    }
                    state.controllers.forEach { controller ->
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(controller.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { connect.revokeController(controller.id) }) { Text(appString(R.string.text_revoke_0be720)) }
                        }
                    }
                }
            }
            item { SettingsSectionTitle(appString(R.string.connect_devices)) }
            item {
                SettingsGroup {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        ConnectDevicesSection(onDismiss = {})
                        TextButton(onClick = connect::scan, enabled = !state.scanning, modifier = Modifier.padding(start = 8.dp)) {
                            Text(appString(R.string.text_search_bce064))
                        }
                    }
                    SegmentedRow(appString(R.string.connect_switch_title), listOf(appString(R.string.connect_switch_keep),
                        appString(R.string.connect_switch_pause), appString(R.string.connect_switch_move)), switch) {
                        scope.launch { store.setConnectSwitch(it) }
                    }
                    state.paired.forEach { peer ->
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(peer.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { connect.forget(peer.id) }) { Text(appString(R.string.text_forget_03d5d8)) }
                        }
                    }
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(host, { host = it.take(256) }, label = { Text(appString(R.string.connect_add_by_address)) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Text(appString(R.string.connect_address_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.probed?.known == true) Text(appString(R.string.connect_already_paired), style = MaterialTheme.typography.bodySmall)
                        state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        Button(onClick = { connect.probe(host) }, enabled = host.isNotBlank() && !state.busy) { Text(appString(R.string.connect_continue)) }
                    }
                }
            }
        }
    }
    state.probed?.takeIf { !it.known }?.let { probed ->
        ConnectPairDialog(probed.address.removePrefix("https://"), EndpointTls.shortKey(probed.fingerprint), connect::pairProbed, connect::dismissError)
    }
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
