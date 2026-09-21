package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aurora.music.data.PlaybackPrefs
import com.aurora.music.data.UsbFallbackPolicy
import com.aurora.music.data.UsbOutputMode
import com.aurora.music.data.UsbDsdMode

@Composable
fun UsbOutputSettings(
    prefs: PlaybackPrefs,
    onEnabled: (Boolean) -> Unit,
    onMode: (UsbOutputMode) -> Unit,
    onFallback: (UsbFallbackPolicy) -> Unit,
    onDsdMode: (UsbDsdMode) -> Unit,
    onExperimentalDsd: (Boolean) -> Unit,
) {
    SettingsGroup {
        SettingsSwitchRow(Icons.Filled.Usb, appString(R.string.text_usb_dac_output_c6adc2), appString(R.string.text_restart_aurora_to_apply_usb_settings_2757a4),
            prefs.bitPerfectUsb, onEnabled)
        if (prefs.bitPerfectUsb) {
            SettingsRowDivider()
            SegmentedRow(appString(R.string.text_usb_mode_cce5b7), listOf(appString(R.string.text_direct_bc8152), appString(R.string.text_processed_8291b2)), prefs.usbOutputMode.ordinal) {
                onMode(UsbOutputMode.entries[it])
            }
            Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                Text(if (prefs.usbOutputMode == UsbOutputMode.DIRECT)
                    appString(R.string.text_app_dsp_and_software_volume_are_bypassed_use_the_dac_s_volume_con_9fb536)
                else appString(R.string.text_apply_app_processing_and_software_volume_crossfade_speed_pitch_an_1dad19),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SegmentedRow(appString(R.string.text_if_usb_is_unavailable_7d21fd), listOf(appString(R.string.text_pause_781961), appString(R.string.text_android_output_0a3516)), prefs.usbFallbackPolicy.ordinal) {
                onFallback(UsbFallbackPolicy.entries[it])
            }
            if (prefs.usbOutputMode == UsbOutputMode.DIRECT) {
                SegmentedRow(appString(R.string.text_dsd_files_d4d480), listOf("PCM", "DoP", appString(R.string.text_native_4fc6e7)), (prefs.usbDsdMode ?: UsbDsdMode.PCM).ordinal) {
                    onDsdMode(UsbDsdMode.entries[it])
                }
                Text(appString(R.string.text_raw_dsd_requires_a_supported_dac_and_stops_if_unavailable_c1bf89),
                    Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (prefs.usbDsdMode != null && prefs.usbDsdMode != UsbDsdMode.PCM) {
                    SettingsSwitchRow(Icons.Filled.Usb, appString(R.string.text_experimental_dsd_f91f90),
                        appString(R.string.text_other_dacs_and_rates_up_to_dsd1024_untested_hardware_support_disc_800807),
                        prefs.usbDsdExperimental == true, onExperimentalDsd)
                }
            }
            if (prefs.usbFallbackPolicy == UsbFallbackPolicy.ANDROID) {
                Text(appString(R.string.text_playback_may_continue_through_the_speaker_7f06f2), Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
