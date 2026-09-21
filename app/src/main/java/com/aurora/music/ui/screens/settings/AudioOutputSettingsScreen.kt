package com.aurora.music.ui.screens.settings

import com.aurora.music.localization.appString
import com.aurora.music.R

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
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
import com.aurora.music.data.AndroidMixerCapability
import com.aurora.music.data.AndroidOutputCapabilities
import com.aurora.music.data.routes.ProcessingRouteKind
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
    val ratePolicy by store.outputRatePolicy.collectAsStateWithLifecycle(initialValue = com.aurora.music.playback.engine.OutputRatePolicy())
    val preferredId by container.preferredAudioDeviceId.collectAsStateWithLifecycle()
    val observation by store.processingRoutes.observations.collectAsStateWithLifecycle()
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
    val preferredLabel = if (preferredId == 0) appString(R.string.text_automatic_ac9041) else devices.firstOrNull { it.id == preferredId }?.let {
        if (it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) appString(R.string.text_phone_speaker_ae9329) else it.productName.toString().ifBlank { appString(R.string.text_connected_device_6ed592) }
    } ?: appString(R.string.text_selected_device_disconnected_c2d1b1)
    val capabilities = remember(observation, devices) {
        val route = observation.route
        val active = devices.firstOrNull { route.kind == ProcessingRouteKind.ANDROID && it.id == route.androidDeviceId }
        if (active == null) AndroidOutputCapabilities() else {
            val mixer = if (Build.VERSION.SDK_INT >= 34) runCatching {
                val modes = audioManager.getSupportedMixerAttributes(active)
                when {
                    modes.any { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT } -> AndroidMixerCapability.BIT_PERFECT
                    modes.isNotEmpty() -> AndroidMixerCapability.STANDARD
                    else -> AndroidMixerCapability.NOT_REPORTED
                }
            }.getOrDefault(AndroidMixerCapability.NOT_REPORTED) else AndroidMixerCapability.NOT_REPORTED
            AndroidOutputCapabilities(route.category?.label ?: appString(R.string.text_android_output_0a3516), runCatching { active.sampleRates.toList() }.getOrNull(), mixer)
        }
    }

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar(SettingsDestinations.output.label, onBack)
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {
            item { SettingsSectionTitle(appString(R.string.text_routing_7d15dd)) }
            item {
                SettingsGroup {
                    SettingsNavRow(Icons.Filled.Devices, appString(R.string.text_preferred_output_device_7cecb0), preferredLabel) { showDevices = true }
                    SettingsRowDivider()
                    SettingsSwitchRow(
                        Icons.Filled.Devices, appString(R.string.text_independent_output_8bb01a),
                        appString(R.string.text_allow_other_apps_to_play_audio_calls_will_not_pause_aurora_1aac7c),
                        prefs.independentOutput,
                    ) { value -> scope.launch { store.setIndependentOutput(value) } }
                }
            }
            item {
                Text(appString(R.string.text_applies_to_this_session_check_signal_path_for_the_active_route_81fa8c),
                    Modifier.padding(horizontal = 20.dp, vertical = 10.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { SettingsSectionTitle(appString(R.string.text_output_mode_ba6e71)) }
            item { OutputRateSettings(ratePolicy) { next -> scope.launch { store.setOutputRatePolicy(next) } } }
            item {
                SettingsGroup {
                    SettingsSwitchRow(
                        Icons.Filled.HighQuality, appString(R.string.text_prefer_hi_res_android_output_e20fb4),
                        appString(R.string.text_use_float32_when_supported_restart_aurora_to_apply_79edcd),
                        prefs.preferHighRes,
                    ) { value -> scope.launch { store.setPreferHighRes(value) } }
                }
            }
            item { SettingsSectionTitle(appString(R.string.text_usb_dac_948bb8)) }
            item {
                UsbOutputSettings(prefs,
                    onEnabled = { value ->
                        scope.launch { store.setBitPerfectUsb(value) }
                        if (value) runCatching {
                            val usb = com.decent.usbaudio.UsbAudioDevice.getInstance(context)
                            usb.findUsbAudioDevice()?.let { if (!usb.hasPermission(it)) usb.requestPermission(it) {} }
                        }
                    },
                    onMode = { value -> scope.launch { store.setUsbOutputMode(value) } },
                    onFallback = { value -> scope.launch { store.setUsbFallbackPolicy(value) } },
                    onDsdMode = { value -> scope.launch { store.setUsbDsdMode(value) } },
                    onExperimentalDsd = { value -> scope.launch { store.setUsbDsdExperimental(value) } },
                )
            }
            item { SettingsSectionTitle(appString(R.string.text_android_capabilities_5df566)) }
            item {
                SettingsGroup {
                    Text(capabilities.describe(), Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    if (showDevices) {
        OutputDeviceSheet(
            currentId = preferredId,
            onSelect = { container.preferredAudioDeviceId.value = it },
            onDismiss = { showDevices = false },
        )
    }
}
