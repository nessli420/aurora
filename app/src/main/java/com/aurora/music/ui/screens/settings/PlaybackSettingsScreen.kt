package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.DataSaverOn
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.PlaybackPrefs
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val BITRATES = listOf(0, 128, 192, 256, 320)
private val BITRATE_LABELS = listOf("Lossless", "128", "192", "256", "320")

@Composable
fun PlaybackSettingsScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenOutput: () -> Unit,
    onOpenLoudness: () -> Unit,
    onOpenEq: () -> Unit,
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as AuroraApplication).container }
    val store = container.settingsStore
    val isLocal = container.isLocal
    val prefs by store.playbackPrefs.collectAsStateWithLifecycle(initialValue = PlaybackPrefs())
    val dataSaver by store.dataSaver.collectAsStateWithLifecycle(initialValue = false)
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar("Playback & quality", onBack)
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {

            // streaming quality only applies to server backends
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

            item { SettingsSectionTitle("Playback") }
            item {
                SettingsSliderRow(
                    "Crossfade",
                    if (prefs.crossfadeSec == 0) "Off" else "${prefs.crossfadeSec}s",
                    prefs.crossfadeSec.toFloat(), 0f..30f, steps = 29,
                ) { v -> scope.launch { store.setCrossfade(v.roundToInt()) } }
                if (prefs.crossfadeSec > 0) {
                    val curves = listOf("SMOOTH", "LINEAR", "POWER")
                    SegmentedRow("Fade curve", listOf("Smooth", "Linear", "Equal power"), curves.indexOf(prefs.crossfadeCurve).coerceAtLeast(0)) {
                        index -> scope.launch { store.setCrossfadeCurve(curves[index]) }
                    }
                    SettingsSwitchRow(title = "Overlap headroom", subtitle = "Balance loud tracks during overlap to prevent volume peaks", checked = prefs.crossfadeHeadroom) {
                        value -> scope.launch { store.setCrossfadeHeadroom(value) }
                    }
                    Text(if (prefs.bitPerfectUsb) "Exclusive USB blends compatible local FLAC files. Other formats keep normal track transitions; mixing modifies samples during overlap."
                        else "The next track prepares in advance. Fades pause with playback and wait for buffering. Equal power can be louder with headroom off.",
                        Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                SettingsGroup {
                    SettingsSwitchRow(Icons.Filled.Audiotrack, "Gapless playback", "Play tracks back-to-back with no gap", prefs.gapless) { v -> scope.launch { store.setGapless(v) } }
                    SettingsRowDivider()
                    SettingsSwitchRow(Icons.Filled.GraphicEq, "Skip silences", "Cut silent sections within tracks", prefs.skipSilence) { v -> scope.launch { store.setSkipSilence(v) } }
                }
            }

            item { SettingsSectionTitle("Default speed") }
            item {
                SettingsSliderRow("Playback speed", "${"%.2f".format(prefs.defaultSpeed)}x", prefs.defaultSpeed, 0.5f..2.0f, steps = 5) { v ->
                    scope.launch { store.setDefaultSpeed(v) }
                }
            }

            item { SettingsSectionTitle("Related settings") }
            item {
                SettingsGroup {
                    SettingsDestinationRow(Icons.Filled.Devices, SettingsDestinations.output, onClick = onOpenOutput)
                    SettingsRowDivider()
                    SettingsDestinationRow(Icons.Filled.VolumeUp, SettingsDestinations.loudness, onClick = onOpenLoudness)
                    SettingsRowDivider()
                    SettingsDestinationRow(Icons.Filled.Tune, SettingsDestinations.equalizer, "Mono audio, channel processing and tone", onClick = onOpenEq)
                }
            }
        }
    }
}
