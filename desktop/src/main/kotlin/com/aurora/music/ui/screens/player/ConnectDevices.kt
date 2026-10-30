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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aurora.music.R
import com.aurora.music.desktop.connect.LocalConnect
import com.aurora.music.desktop.connect.problem
import com.aurora.music.localization.appString
import com.aurora.music.playback.network.endpoint.ConnectCommand
import com.aurora.music.playback.network.endpoint.DiscoveredRenderer
import com.aurora.music.ui.theme.themedCorner

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectDevicesSection(onDismiss: () -> Unit) {
    val connect = LocalConnect.current ?: return
    val state by connect.state.collectAsState()
    val active by connect.active.collectAsState()
    val remote = active as? com.aurora.music.desktop.connect.RemotePlayerController
    val status = remote?.session?.status?.collectAsState()?.value
    var pairing by remember { mutableStateOf<DiscoveredRenderer?>(null) }
    LaunchedEffect(Unit) { connect.scan() }
    val unpaired = state.discovered.filter { found -> found.connect && state.paired.none { it.id == found.id } }
    val colors = MaterialTheme.colorScheme
    Text(appString(R.string.connect_section), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp))
    DeviceRow(Icons.Filled.Computer, appString(R.string.connect_this_device), null, state.controlling == null) { connect.control(null); onDismiss() }
    state.paired.forEach { peer ->
        val selected = state.controlling?.id == peer.id
        DeviceRow(Icons.Filled.Devices, peer.name, if (selected) status?.current?.title else null, selected) { connect.control(peer); onDismiss() }
    }
    unpaired.forEach { found -> DeviceRow(Icons.Filled.Add, found.name, appString(R.string.connect_pair_title, found.name), false) { pairing = found } }
    if (state.scanning && unpaired.isEmpty() && state.paired.isEmpty()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(appString(R.string.text_searching_1a6a5b), style = MaterialTheme.typography.bodySmall)
        }
    } else if (unpaired.isEmpty() && state.paired.isEmpty()) {
        Text(appString(R.string.connect_none_found), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
    (remote?.session?.error?.collectAsState()?.value?.let { remote.session.problem() } ?: state.error)?.let {
        Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = colors.error)
    }
    if (status != null && status.presets.isNotEmpty()) {
        Text(appString(R.string.connect_preset), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 6.dp))
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            status.presets.forEach { preset ->
                Box(Modifier.clip(RoundedCornerShape(50)).background(colors.surfaceContainerHigh)
                    .clickable { connect.command(ConnectCommand.Preset(preset.id)) }.pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(preset.name, style = MaterialTheme.typography.labelLarge, color = colors.onSurface)
                }
            }
        }
    }
    if (remote != null) Text(appString(R.string.connect_sources_note), Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = colors.outlineVariant.copy(alpha = 0.5f))
    pairing?.let { found -> ConnectPairDialog(found.name, null, onPair = { code -> connect.pairDiscovered(found, code); pairing = null }, onDismiss = { pairing = null }) }
}

@Composable
private fun DeviceRow(icon: ImageVector, label: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(themedCorner(14.dp))).clickable(role = Role.RadioButton, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(if (selected) colors.primary else colors.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (selected) colors.onPrimary else colors.onSurface, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) colors.primary else colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!detail.isNullOrBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) Icon(Icons.Filled.Check, null, tint = colors.primary)
    }
}

@Composable
fun ConnectPairDialog(name: String, key: String?, onPair: (String) -> Unit, onDismiss: () -> Unit) {
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
                OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text(appString(R.string.text_pairing_code_309e30)) }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onPair(code) }, enabled = code.length == 6) { Text(appString(R.string.text_pair_2537f8)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(appString(R.string.text_dismiss_70afe9)) } },
    )
}
