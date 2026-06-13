package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.DataSaverOn
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.aurora.music.data.AlarmPrefs
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.PlaybackPrefs
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val BITRATES = listOf(0, 128, 192, 256, 320)
private val BITRATE_LABELS = listOf("Lossless", "128", "192", "256", "320")

@Composable
fun PlaybackSettingsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as AuroraApplication).container }
    val store = container.settingsStore
    val isLocal = container.isLocal
    val prefs by store.playbackPrefs.collectAsStateWithLifecycle(initialValue = PlaybackPrefs())
    val dataSaver by store.dataSaver.collectAsStateWithLifecycle(initialValue = false)
    val alarm by store.alarmPrefs.collectAsStateWithLifecycle(initialValue = AlarmPrefs())
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar("Playback & quality", onBack)
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {

            // Streaming quality only applies to server backends — local files play as-is.
            if (!isLocal) {
                item { SettingsSectionTitle("Streaming quality") }
                item {
                    val sel = BITRATES.indexOf(prefs.streamWifi).coerceAtLeast(0)
                    SegmentedRow("On Wi-Fi", BITRATE_LABELS, sel) { i -> scope.launch { store.setStreamWifi(BITRATES[i]) } }
                }
                item {
                    val sel = BITRATES.indexOf(prefs.streamCellular).coerceAtLeast(0)
                    SegmentedRow("On cellular", BITRATE_LABELS, sel) { i -> scope.launch { store.setStreamCellular(BITRATES[i]) } }
                }
                item {
                    SettingsGroup {
                        SettingsSwitchRow(Icons.Filled.DataSaverOn, "Data saver", "Cap streaming to ~96 kbps on mobile data", dataSaver) { v -> scope.launch { store.setDataSaver(v) } }
                    }
                }
            }

            item { SettingsSectionTitle("Output") }
            item {
                SettingsGroup {
                    SettingsSwitchRow(Icons.Filled.HighQuality, "Hi-res / bit-perfect output", "32-bit float for DACs · disables speed/pitch on hi-res · restart to apply", prefs.preferHighRes) { v ->
                        scope.launch { store.setPreferHighRes(v) }
                    }
                    SettingsRowDivider()
                    SettingsSwitchRow(
                        Icons.Filled.Usb,
                        "USB DAC bit-perfect (experimental)",
                        "Drive a USB DAC directly, bypassing Android audio & DSP · plug DAC, restart to apply",
                        prefs.bitPerfectUsb,
                    ) { v ->
                        scope.launch { store.setBitPerfectUsb(v) }
                        // Request USB access up front when enabling (if a DAC is plugged in) so the driver
                        // can claim it without a manifest attach-handler prompting on every plug.
                        if (v) runCatching {
                            val dev = com.decent.usbaudio.UsbAudioDevice.getInstance(context)
                            dev.findUsbAudioDevice()?.let { if (!dev.hasPermission(it)) dev.requestPermission(it) {} }
                        }
                    }
                }
            }
            item { SignalPathCard() }

            item { SettingsSectionTitle("Playback") }
            item {
                SettingsSliderRow(
                    "Crossfade",
                    if (prefs.crossfadeSec == 0) "Off" else "${prefs.crossfadeSec}s",
                    prefs.crossfadeSec.toFloat(), 0f..12f, steps = 11,
                ) { v -> scope.launch { store.setCrossfade(v.roundToInt()) } }
            }
            item {
                SettingsGroup {
                    SettingsSwitchRow(Icons.Filled.Audiotrack, "Gapless playback", "Play tracks back-to-back with no gap", prefs.gapless) { v -> scope.launch { store.setGapless(v) } }
                    SettingsRowDivider()
                    SettingsSwitchRow(Icons.Filled.GraphicEq, "Skip silences", "Cut silent sections within tracks", prefs.skipSilence) { v -> scope.launch { store.setSkipSilence(v) } }
                    SettingsRowDivider()
                    SettingsSwitchRow(Icons.Filled.Headphones, "Mono audio", "Combine left & right into a single channel", prefs.monoAudio) { v -> scope.launch { store.setMono(v) } }
                }
            }

            item { SettingsSectionTitle("Default speed") }
            item {
                SettingsSliderRow("Playback speed", "${"%.2f".format(prefs.defaultSpeed)}x", prefs.defaultSpeed, 0.5f..2.0f, steps = 5) { v ->
                    scope.launch { store.setDefaultSpeed(v) }
                }
            }

            item { SettingsSectionTitle("Wake-up alarm") }
            item {
                SettingsGroup {
                    SettingsSwitchRow(
                        Icons.Filled.Alarm,
                        "Wake-to-music alarm",
                        "Fade in your liked music daily at ${formatTime(alarm.hour, alarm.minute)}",
                        alarm.enabled,
                    ) { v -> scope.launch { store.setAlarm(v, alarm.hour, alarm.minute) } }
                    SettingsRowDivider()
                    SettingsNavRow(
                        Icons.Filled.Alarm, "Alarm time", value = formatTime(alarm.hour, alarm.minute),
                    ) {
                        android.app.TimePickerDialog(
                            context, { _, h, m -> scope.launch { store.setAlarm(alarm.enabled, h, m) } },
                            alarm.hour, alarm.minute, false,
                        ).show()
                    }
                }
            }
        }
    }
}

/** 24h hour/minute → a 12h clock label like "7:05 AM". */
private fun formatTime(hour: Int, minute: Int): String {
    val h12 = when { hour % 12 == 0 -> 12; else -> hour % 12 }
    val ampm = if (hour < 12) "AM" else "PM"
    return "%d:%02d %s".format(h12, minute, ampm)
}

/** Live signal-path / bit-perfect indicator — the format actually playing and where it's going. */
@Composable
private fun SignalPathCard() {
    val ctx = LocalContext.current
    val container = remember { (ctx.applicationContext as AuroraApplication).container }
    val sp by container.signalPath.collectAsStateWithLifecycle()
    if (!sp.active) return
    val good = sp.bitPerfect
    val accent = if (good) Color(0xFF28D572) else MaterialTheme.colorScheme.onSurfaceVariant
    val parts = buildList {
        if (sp.codec.isNotBlank()) add(sp.codec)
        if (sp.sampleRateHz > 0) add("%.1f kHz".format(sp.sampleRateHz / 1000f))
        if (sp.bitDepth > 0) add("${sp.bitDepth}-bit")
        if (sp.channels == 2) add("stereo") else if (sp.channels == 1) add("mono")
    }.joinToString(" · ")
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (good) Icons.Filled.Verified else Icons.Filled.GraphicEq, null, tint = accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (good) "Bit-perfect" else "Signal path", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = accent)
        }
        Spacer(Modifier.height(6.dp))
        Text("$parts  →  ${sp.output}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        if (sp.note.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(sp.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
