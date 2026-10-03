package com.aurora.music.ui.screens.player

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aurora.music.desktop.natives.AudioDevice
import com.aurora.music.desktop.natives.DeviceKind
import com.aurora.music.desktop.platform.HostPlatform
import kotlin.math.roundToInt

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun OutputDeviceSheet(
    devices: List<AudioDevice>,
    currentId: String?,
    exclusive: Boolean,
    exclusiveAvailable: Boolean,
    volume: Float,
    onSelect: (String?) -> Unit,
    onExclusiveChange: (Boolean) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val systemDefault = devices.firstOrNull { it.isDefault }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.surface) {
        Column(Modifier.fillMaxWidth().heightIn(max = 650.dp).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp).padding(bottom = 28.dp)) {
            Text(appString(R.string.text_play_on_4bd6fc), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
            OutputRow(Icons.Filled.SettingsSuggest, appString(R.string.language_system), systemDefault?.name, currentId == null) { onSelect(null) }
            devices.forEach { d ->
                OutputRow(deviceIcon(d.kind), d.name, if (d.isDefault) appString(R.string.text_default_808d7d) else null, d.id == currentId) { onSelect(d.id) }
            }
            if (currentId != null && devices.none { it.id == currentId }) {
                OutputRow(Icons.Filled.LinkOff, appString(R.string.text_selected_device_disconnected_c2d1b1), systemDefault?.name, selected = true) {}
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = colors.outlineVariant.copy(alpha = 0.5f))
            if (exclusiveAvailable) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .clickable(role = Role.Switch) { onExclusiveChange(!exclusive) }.pointerHoverIcon(PointerIcon.Hand)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(colors.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.HighQuality, null, tint = if (exclusive) colors.primary else colors.onSurface, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(appString(R.string.text_exclusive_mode_01d9b2), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, color = colors.onSurface)
                        Text(appString(if (HostPlatform.isWindows) R.string.text_bit_perfect_output_that_bypasses_the_windows_mixer_eff21d
                            else R.string.text_bit_perfect_output_that_bypasses_the_sound_server_d2dcd9), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    Switch(
                        checked = exclusive,
                        onCheckedChange = onExclusiveChange,
                        colors = SwitchDefaults.colors(checkedThumbColor = colors.onPrimary, checkedTrackColor = colors.primary),
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 20.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggleMute, modifier = Modifier.size(40.dp)) {
                    Icon(volumeIcon(volume), appString(R.string.text_mute_0f0973), tint = colors.onSurface, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(14.dp))
                Slider(
                    value = volume.coerceIn(0f, 1f),
                    onValueChange = onVolumeChange,
                    colors = SliderDefaults.colors(thumbColor = colors.primary, activeTrackColor = colors.primary),
                    modifier = Modifier.weight(1f).semantics { contentDescription = appString(R.string.text_volume_3b18e8) },
                )
                Text("${(volume * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant,
                    modifier = Modifier.widthIn(min = 48.dp).padding(start = 12.dp))
            }
        }
    }
}

@Composable
private fun OutputRow(icon: ImageVector, label: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(role = Role.RadioButton, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(if (selected) colors.primary else colors.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (selected) colors.onPrimary else colors.onSurface, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) colors.primary else colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) Icon(Icons.Filled.Check, null, tint = colors.primary)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SleepTimerSheet(
    currentMinutes: Int,
    endOfTrack: Boolean,
    onSelect: (Int) -> Unit,
    onEndOfTrack: () -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(0 to appString(R.string.text_off_e3de5a), 5 to appString(R.string.text_5_min_b45ebd), 15 to appString(R.string.text_15_min_1028d6), 30 to appString(R.string.text_30_min_d3ddf7), 45 to appString(R.string.text_45_min_983be7), 60 to appString(R.string.text_1_hour_f030c3))
    val status = when {
        endOfTrack -> appString(R.string.text_pausing_at_the_end_of_this_track_d79c45)
        currentMinutes > 0 -> appString(R.string.text_pausing_in_min_fades_out_741f18, (currentMinutes))
        else -> appString(R.string.text_pause_playback_after_a_set_time_ef485a)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
            Text(appString(R.string.text_sleep_timer_e90613), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, modifier = Modifier.padding(bottom = 4.dp))
            Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                options.forEach { (min, label) ->
                    val selected = !endOfTrack && min == currentMinutes
                    Box(
                        Modifier.clip(RoundedCornerShape(50))
                            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { onSelect(min); onDismiss() }.pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                    }
                }
                Box(
                    Modifier.clip(RoundedCornerShape(50))
                        .background(if (endOfTrack) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable { onEndOfTrack(); onDismiss() }.pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Text(appString(R.string.text_end_of_track_9923e9), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = if (endOfTrack) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

internal fun volumeIcon(volume: Float): ImageVector = when {
    volume <= 0f -> Icons.AutoMirrored.Filled.VolumeOff
    volume < 0.5f -> Icons.AutoMirrored.Filled.VolumeDown
    else -> Icons.AutoMirrored.Filled.VolumeUp
}

private fun deviceIcon(kind: DeviceKind): ImageVector = when (kind) {
    DeviceKind.HEADPHONES, DeviceKind.HEADSET, DeviceKind.HANDSET -> Icons.Filled.Headphones
    DeviceKind.DIGITAL_DISPLAY -> Icons.Filled.Tv
    DeviceKind.LINE_LEVEL, DeviceKind.SPDIF, DeviceKind.DIGITAL_PASSTHROUGH -> Icons.Filled.Cable
    DeviceKind.REMOTE -> Icons.Filled.DesktopWindows
    DeviceKind.MICROPHONE -> Icons.Filled.Mic
    DeviceKind.SPEAKERS, DeviceKind.UNKNOWN -> Icons.Filled.Speaker
}
