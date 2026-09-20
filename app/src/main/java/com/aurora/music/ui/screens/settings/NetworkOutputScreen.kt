package com.aurora.music.ui.screens.settings

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
        SettingsTopBar("Network playback", onBack)
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
        ) {
            item { NetworkOutputControls(manager, showForget = true) }
            item {
                SettingsSectionTitle("Pair an Aurora player")
                SettingsGroup {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        state.discovered.filter { found -> state.paired.none { it.id == found.id } }.forEach { found ->
                            Text("${found.name} · ${found.address}", style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedTextField(
                            address, { address = it.take(512) }, label = { Text("Pairing address") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                        )
                        OutlinedTextField(
                            code, { code = it.take(32) }, label = { Text("Pairing code") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                        )
                        Text("Copy the full address from the other device.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { manager.pair(address.trim(), code.trim()) },
                            enabled = address.isNotBlank() && code.isNotBlank() && !state.preparing) { Text("Pair") }
                    }
                }
            }
            item {
                SettingsSectionTitle("This device")
                SettingsGroup {
                    SettingsSwitchRow(title = "Allow remote control", subtitle = "Paired devices can control playback.",
                        checked = state.receiverEnabled, onCheckedChange = manager::enableReceiver)
                    if (state.receiverEnabled) {
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.receiverAddress?.let { endpoint ->
                                Text(endpoint.address, style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = { clipboard.setText(AnnotatedString(endpoint.pairingAddress)) }) {
                                    Text("Copy pairing address")
                                }
                            } ?: Text("Connecting to the local network…", style = MaterialTheme.typography.bodySmall)
                            val pairing = state.pairing
                            if (pairing != null && pairing.expiresAtMs > now) {
                                Text(pairing.code, style = MaterialTheme.typography.headlineSmall)
                                Text("Expires in ${((pairing.expiresAtMs - now + 999) / 1_000)} s.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = { clipboard.setText(AnnotatedString(pairing.code)) }) { Text("Copy code") }
                            }
                            TextButton(onClick = manager::beginPairing, enabled = state.receiverAddress != null) {
                                Text(if (pairing == null || pairing.expiresAtMs <= now) "Show pairing code" else "New code")
                            }
                        }
                    }
                    state.controllers.forEach { controller ->
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(controller.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { manager.revokeController(controller.id) }) { Text("Revoke") }
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
            SegmentedRow("Audio", listOf("Direct", "Processed"), if (state.processedCast) 1 else 0) {
                manager.setProcessedCast(it == 1)
            }
            Text(if (state.processedCast) "Applies Aurora's effects before sending audio." else "The receiver plays the source. Aurora's effects are bypassed.",
                Modifier.padding(start = 20.dp, end = 20.dp, bottom = 14.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.receiverName != null || state.preparing || state.error != null) {
            SettingsSectionTitle("Playback")
            SettingsGroup {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.receiverName?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                    if (state.preparing) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("Preparing audio…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (state.detail.isNotBlank()) Text(state.detail, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.error?.let { message ->
                        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = manager::dismissError) { Text("Dismiss") }
                    }
                    if (state.receiverName != null) TextButton(onClick = manager::disconnect) { Text("Disconnect") }
                }
            }
        }
        SettingsSectionTitle("DLNA receivers")
        SettingsGroup {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (state.scanning) "Searching…" else if (state.dlna.isEmpty()) "No receivers found." else "Local network",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = manager::scan, enabled = !state.scanning) { Text("Search") }
            }
            state.dlna.forEach { renderer ->
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(renderer.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(enabled = !state.preparing, onClick = { manager.connect(NetworkTarget.Dlna(renderer)); onConnected() }) { Text("Play here") }
                }
            }
        }
        if (state.paired.isNotEmpty()) {
            SettingsSectionTitle("Aurora players")
            SettingsGroup {
                Text("Copies the queue before playback. Up to 100 tracks and 512 MB.",
                    Modifier.padding(horizontal = 20.dp, vertical = 14.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.paired.forEach { renderer ->
                    Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
                        Text(renderer.name, style = MaterialTheme.typography.titleSmall)
                        Row {
                            TextButton(enabled = !state.preparing, onClick = { manager.connect(NetworkTarget.Aurora(renderer)); onConnected() }) { Text("Play here") }
                            if (showForget) TextButton(onClick = { manager.forget(renderer.id) }) { Text("Forget") }
                        }
                    }
                }
            }
        }
        Text("Processed playback uses the current effects when each track starts. Speed changes and crossfade are unavailable.",
            Modifier.padding(horizontal = 20.dp, vertical = 14.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
