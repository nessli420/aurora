package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.desktop.natives.AudioDevices
import com.aurora.music.desktop.natives.DeviceKind
import com.aurora.music.desktop.natives.MixFormat
import com.aurora.music.desktop.platform.DesktopSettings
import com.aurora.music.desktop.ui.LocalDesktopContainer
import com.aurora.music.desktop.ui.LocalPlayer
import com.aurora.music.playback.engine.OutputRatePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

internal val OUTPUT_SUMMARY: String get() = appString(R.string.text_device_exclusive_mode_and_buffer_f2337c)

private val BUFFERS = listOf(50, 100, 200, 400, 800, 1600)

@Composable
fun AudioOutputSettingsScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenSignalPath: () -> Unit,
) {
    val container = LocalDesktopContainer.current
    val player = LocalPlayer.current
    val store = container.settingsStore
    val devices by player.outputs.collectAsStateWithLifecycle()
    val preferred by player.preferredOutput.collectAsStateWithLifecycle()
    val exclusive by player.exclusiveOutput.collectAsStateWithLifecycle()
    val bufferMs by container.desktopSettings.outputBufferMs.collectAsStateWithLifecycle(initialValue = DesktopSettings.DEFAULT_BUFFER_MS)
    val ratePolicy by store.outputRatePolicy.collectAsStateWithLifecycle(initialValue = OutputRatePolicy())
    val scope = rememberCoroutineScope()
    val systemDefault = devices.firstOrNull { it.isDefault }
    val activeId = preferred?.takeIf { id -> devices.any { it.id == id } } ?: systemDefault?.id
    val mix by produceState<MixFormat?>(null, activeId) {
        value = withContext(Dispatchers.IO) { runCatching { AudioDevices.mixFormat(activeId) }.getOrNull() }
    }

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar(SettingsDestinations.output.label, onBack)
        SettingsList(contentPadding) {
            item { SettingsSectionTitle(appString(R.string.text_output_device_709178)) }
            item {
                SettingsGroup {
                    Column(Modifier.selectableGroup()) {
                        DeviceRow(Icons.Filled.SettingsSuggest, appString(R.string.language_system), systemDefault?.name, preferred == null) {
                            player.setPreferredDevice(null)
                        }
                        devices.forEach { device ->
                            SettingsRowDivider()
                            DeviceRow(outputIcon(device.kind), device.name, if (device.isDefault) appString(R.string.text_default_808d7d) else null,
                                device.id == preferred) { player.setPreferredDevice(device.id) }
                        }
                        if (preferred != null && devices.none { it.id == preferred }) {
                            SettingsRowDivider()
                            DeviceRow(Icons.Filled.LinkOff, appString(R.string.text_selected_device_disconnected_c2d1b1), systemDefault?.name, true) {}
                        }
                    }
                }
            }
            item { Note(appString(R.string.text_system_default_follows_the_windows_output_device_even_when_it_cha_5703e0)) }

            item { SettingsSectionTitle(appString(R.string.text_output_mode_ba6e71)) }
            item {
                SettingsGroup {
                    SettingsSwitchRow(Icons.Filled.HighQuality, appString(R.string.text_exclusive_mode_01d9b2),
                        appString(R.string.text_bit_perfect_output_that_bypasses_the_windows_mixer_eff21d), exclusive) {
                        player.setExclusiveOutput(it)
                    }
                }
            }
            item {
                Note(if (exclusive) appString(R.string.text_aurora_takes_sole_control_of_the_device_and_sends_samples_at_the_cfbce6)
                else appString(R.string.text_shared_mode_mixes_aurora_with_other_apps_through_the_windows_audi_a3c865))
            }
            item {
                SettingsGroup {
                    SegmentedRow(appString(R.string.text_output_buffer_61b372), BUFFERS.map { appString(R.string.text_ms_1191ce, it) }, BUFFERS.indices.minBy { abs(BUFFERS[it] - bufferMs) }) { i ->
                        scope.launch { container.desktopSettings.setOutputBufferMs(BUFFERS[i]) }
                    }
                }
            }
            item { Note(appString(R.string.text_larger_buffers_ride_out_heavy_system_load_smaller_ones_react_fast_e4d5f8)) }
            item { OutputRateSettings(ratePolicy) { change -> scope.launch { store.updateOutputRatePolicy(change) } } }
            item { Note(appString(R.string.text_the_sample_rate_policy_and_dither_apply_in_exclusive_mode_shared_caca2d)) }

            item { SettingsSectionTitle(appString(R.string.text_windows_capabilities_0f1fa0)) }
            item {
                SettingsGroup {
                    Text(mix?.describe() ?: appString(R.string.text_output_unknown_ef4fdb), Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            item { SettingsSectionTitle(appString(R.string.text_inspect_playback_94b689)) }
            item {
                SettingsGroup {
                    SettingsDestinationRow(Icons.Filled.Route, SettingsDestinations.signalPath, onClick = onOpenSignalPath)
                }
            }
        }
    }
}

private fun MixFormat.describe(): String = listOf(
    appString(R.string.text_shared_mode_mix_format_4a5bc8),
    appString(R.string.text_khz_dd177d, sampleRate / 1000.0),
    appString(if (isFloat) R.string.text_bit_float_012895 else R.string.text_bit_integer_72520f, validBits),
    appString(R.string.text_ch_282ab4, channels),
).joinToString(" · ")

@Composable
private fun Note(text: String) {
    Text(text, Modifier.padding(horizontal = 20.dp, vertical = 10.dp), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DeviceRow(icon: ImageVector, title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth()
            .then(if (selected) Modifier.background(colors.primary.copy(alpha = 0.14f)) else Modifier)
            .pointerHoverIcon(PointerIcon.Hand)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(if (selected) colors.primary else colors.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = if (selected) colors.onPrimary else colors.onSurface, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        RadioButton(selected = selected, onClick = null)
    }
}

private fun outputIcon(kind: DeviceKind): ImageVector = when (kind) {
    DeviceKind.HEADPHONES, DeviceKind.HEADSET, DeviceKind.HANDSET -> Icons.Filled.Headphones
    DeviceKind.DIGITAL_DISPLAY -> Icons.Filled.Tv
    DeviceKind.LINE_LEVEL, DeviceKind.SPDIF, DeviceKind.DIGITAL_PASSTHROUGH -> Icons.Filled.Cable
    DeviceKind.REMOTE -> Icons.Filled.DesktopWindows
    DeviceKind.MICROPHONE -> Icons.Filled.Mic
    DeviceKind.SPEAKERS, DeviceKind.UNKNOWN -> Icons.Filled.Speaker
}
