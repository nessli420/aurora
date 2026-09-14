package com.aurora.music.ui.screens.settings

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.PlaybackPrefs
import com.aurora.music.ui.screens.player.OutputDeviceSheet
import kotlinx.coroutines.launch

@Composable
fun AudioOutputSettingsScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenSignalPath: () -> Unit,
) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as AuroraApplication).container }
    val store = container.settingsStore
    val prefs by store.playbackPrefs.collectAsStateWithLifecycle(initialValue = PlaybackPrefs())
    val preferredId by container.preferredAudioDeviceId.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showDevices by remember { mutableStateOf(false) }
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var devices by remember(audioManager) { mutableStateOf(audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()) }
    DisposableEffect(audioManager) {
        val callback = object : AudioDeviceCallback() {
            private fun refresh() { devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
        }
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        onDispose { audioManager.unregisterAudioDeviceCallback(callback) }
    }
    val preferredLabel = if (preferredId == 0) "Automatic" else devices.firstOrNull { it.id == preferredId }?.let {
        if (it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) "Phone speaker" else it.productName.toString().ifBlank { "Connected device" }
    } ?: "Selected device disconnected"

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar(SettingsDestinations.output.label, onBack)
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {
            item { SettingsSectionTitle("Routing") }
            item {
                SettingsGroup {
                    SettingsNavRow(Icons.Filled.Devices, "Preferred output device", preferredLabel) { showDevices = true }
                    SettingsRowDivider()
                    SettingsSwitchRow(
                        Icons.Filled.Devices, "Independent output",
                        "Do not request audio focus. Other apps can keep playing; Aurora will not pause for calls.",
                        prefs.independentOutput,
                    ) { value -> scope.launch { store.setIndependentOutput(value) } }
                }
            }
            item {
                Text("The preferred device applies to Android audio in this app session. Android may choose another route; inspect Signal Path for the available route evidence.",
                    Modifier.padding(horizontal = 20.dp, vertical = 10.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { SettingsSectionTitle("Output mode") }
            item {
                SettingsGroup {
                    SettingsSwitchRow(
                        Icons.Filled.HighQuality, "Prefer hi-res Android output",
                        "Prefer 32-bit float for supported sources. Custom DSP and Android routing affect the active path. Restart Aurora to apply.",
                        prefs.preferHighRes,
                    ) { value -> scope.launch { store.setPreferHighRes(value) } }
                    SettingsRowDivider()
                    SettingsSwitchRow(
                        Icons.Filled.Usb, "Direct USB output (experimental)",
                        "Drive a USB DAC through Aurora's USB driver. Processing depends on the playback path; check Signal Path. Connect the DAC and restart Aurora to apply.",
                        prefs.bitPerfectUsb,
                    ) { value ->
                        scope.launch { store.setBitPerfectUsb(value) }
                        if (value) runCatching {
                            val usb = com.decent.usbaudio.UsbAudioDevice.getInstance(context)
                            usb.findUsbAudioDevice()?.let { if (!usb.hasPermission(it)) usb.requestPermission(it) {} }
                        }
                    }
                }
            }
            item { SettingsSectionTitle("Inspect playback") }
            item {
                SettingsGroup {
                    SettingsDestinationRow(Icons.Filled.Route, SettingsDestinations.signalPath, onClick = onOpenSignalPath)
                }
            }
        }
    }
    if (showDevices) {
        OutputDeviceSheet(
            currentId = preferredId,
            onSelect = { container.preferredAudioDeviceId.value = it },
            onDismiss = { showDevices = false },
        )
    }
}
