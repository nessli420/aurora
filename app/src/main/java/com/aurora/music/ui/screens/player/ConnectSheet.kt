package com.aurora.music.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.R
import com.aurora.music.localization.appString
import com.aurora.music.playback.network.problem
import com.aurora.music.playback.network.endpoint.ConnectCommand
import com.aurora.music.playback.network.endpoint.ConnectStatus
import com.aurora.music.playback.network.endpoint.DiscoveredRenderer
import kotlinx.coroutines.flow.MutableStateFlow

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConnectDevicesSection(onDismiss: () -> Unit) {
    val manager = (LocalContext.current.applicationContext as AuroraApplication).container.networkOutput
    val state by manager.state.collectAsStateWithLifecycle()
    val session by manager.session.collectAsStateWithLifecycle()
    val remote by remember(session) { session?.status ?: MutableStateFlow<ConnectStatus?>(null) }.collectAsStateWithLifecycle()
    var pairing by remember { mutableStateOf<DiscoveredRenderer?>(null) }
    LaunchedEffect(Unit) { manager.scan() }
    val own = state.receiverAddress?.fingerprint?.take(32)
    val unpaired = state.discovered.filter { found -> found.connect && found.id != own && state.paired.none { it.id == found.id } }
    Text(appString(R.string.connect_section), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp))
    ConnectRow(Icons.Filled.PhoneAndroid, appString(R.string.connect_this_device), null, state.controlling == null) {
        manager.control(null); onDismiss()
    }
    state.paired.forEach { peer ->
        val selected = state.controlling?.id == peer.id
        ConnectRow(Icons.Filled.Devices, peer.name, if (selected) remote?.current?.title else null, selected) {
            manager.control(peer); onDismiss()
        }
    }
    unpaired.forEach { found -> ConnectRow(Icons.Filled.Add, found.name, appString(R.string.connect_pair_title, found.name), false) { pairing = found } }
    if (state.scanning && unpaired.isEmpty() && state.paired.isEmpty()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(appString(R.string.text_searching_1a6a5b), style = MaterialTheme.typography.bodySmall)
        }
    } else if (unpaired.isEmpty() && state.paired.isEmpty()) {
        Text(appString(R.string.connect_none_found), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    (session?.error?.collectAsStateWithLifecycle()?.value?.let { session?.problem() } ?: state.error)?.let {
        Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    val current = remote
    if (state.controlling != null && current != null) {
        if (current.outputs.size > 1) ConnectChoices(appString(R.string.connect_output), current.outputs.map { it.id to it.name }, current.output) {
            manager.command(ConnectCommand.Output(it))
        }
        if (current.presets.isNotEmpty()) ConnectChoices(appString(R.string.connect_preset), current.presets.map { it.id to it.name }, null) {
            manager.command(ConnectCommand.Preset(it))
        }
        Text(appString(R.string.connect_sources_note), Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    pairing?.let { found -> ConnectPairDialog(found.name, onPair = { code -> manager.pairDiscovered(found, code); pairing = null }, onDismiss = { pairing = null }) }
}

@Composable
private fun ConnectRow(icon: ImageVector, label: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        }
        if (selected) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectChoices(title: String, options: List<Pair<String, String>>, selected: String?, onSelect: (String) -> Unit) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 6.dp))
    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (id, name) ->
            val on = id == selected
            Box(Modifier.clip(RoundedCornerShape(50)).background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable { onSelect(id) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(name, style = MaterialTheme.typography.labelLarge, color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
internal fun ConnectPairDialog(name: String, key: String? = null, onPair: (String) -> Unit, onDismiss: () -> Unit) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(appString(R.string.connect_pair_title, name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                key?.let {
                    Text(appString(R.string.connect_device_key, it), style = MaterialTheme.typography.titleMedium)
                    Text(appString(R.string.connect_device_key_help), style = MaterialTheme.typography.bodySmall)
                }
                Text(appString(R.string.connect_enter_code, name), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text(appString(R.string.text_pairing_code_309e30)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onPair(code) }, enabled = code.length == 6) { Text(appString(R.string.text_pair_2537f8)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(appString(R.string.text_dismiss_70afe9)) } },
    )
}
