package com.aurora.music.ui.screens.settings

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

@Composable
fun UsbOutputSettings(
    prefs: PlaybackPrefs,
    onEnabled: (Boolean) -> Unit,
    onMode: (UsbOutputMode) -> Unit,
    onFallback: (UsbFallbackPolicy) -> Unit,
) {
    SettingsGroup {
        SettingsSwitchRow(Icons.Filled.Usb, "USB DAC output", "Restart Aurora to apply USB settings.",
            prefs.bitPerfectUsb, onEnabled)
        if (prefs.bitPerfectUsb) {
            SettingsRowDivider()
            SegmentedRow("USB mode", listOf("Direct", "Processed"), prefs.usbOutputMode.ordinal) {
                onMode(UsbOutputMode.entries[it])
            }
            Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                Text(if (prefs.usbOutputMode == UsbOutputMode.DIRECT)
                    "App DSP and software volume are bypassed. Use the DAC's volume control."
                else "Apply app processing and software volume. Crossfade, speed/pitch and silence skipping are unavailable.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SegmentedRow("If USB is unavailable", listOf("Pause", "Android output"), prefs.usbFallbackPolicy.ordinal) {
                onFallback(UsbFallbackPolicy.entries[it])
            }
            if (prefs.usbFallbackPolicy == UsbFallbackPolicy.ANDROID) {
                Text("Playback may continue through the speaker.", Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
